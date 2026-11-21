package `in`.gauthama.snapslotify.review

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import `in`.gauthama.snapslotify.BuildConfig
import `in`.gauthama.snapslotify.DebugActions
import `in`.gauthama.snapslotify.assist.ChatModels
import `in`.gauthama.snapslotify.assist.VisionModels
import `in`.gauthama.snapslotify.core.chat.ChatAssist
import `in`.gauthama.snapslotify.core.chat.ChatRouter
import `in`.gauthama.snapslotify.core.chat.ChatSuggestions
import `in`.gauthama.snapslotify.core.chat.JobOutcome
import `in`.gauthama.snapslotify.core.chat.Route
import `in`.gauthama.snapslotify.core.chat.RouteDecision
import `in`.gauthama.snapslotify.core.diag.ShareDiag
import `in`.gauthama.snapslotify.core.metrics.Metrics
import `in`.gauthama.snapslotify.diag.ShareTrace
import `in`.gauthama.snapslotify.core.ocr.OcrJson
import `in`.gauthama.snapslotify.core.ocr.OcrQuality
import `in`.gauthama.snapslotify.core.image.Sharpness
import `in`.gauthama.snapslotify.core.ocr.OcrResult
import `in`.gauthama.snapslotify.core.pipeline.CodePipeline
import `in`.gauthama.snapslotify.core.pipeline.PipelineResult
import `in`.gauthama.snapslotify.core.review.ReviewSession
import `in`.gauthama.snapslotify.core.review.TitleRules
import `in`.gauthama.snapslotify.core.vision.TryHarder
import `in`.gauthama.snapslotify.core.vision.TryHarderOutcome
import `in`.gauthama.snapslotify.image.CameraCapture
import `in`.gauthama.snapslotify.image.ImageLoadException
import `in`.gauthama.snapslotify.image.ImageLoader
import `in`.gauthama.snapslotify.image.VisionImage
import `in`.gauthama.snapslotify.metrics.MetricsLog
import `in`.gauthama.snapslotify.ocr.AppOcr
import `in`.gauthama.snapslotify.ocr.UprightRead
import `in`.gauthama.snapslotify.image.sharpness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import `in`.gauthama.snapslotify.stats.LocalStats
import `in`.gauthama.snapslotify.stats.TapsSaved

enum class ErrorReason { NO_IMAGE, UNREADABLE, OUT_OF_MEMORY, OCR_FAILED }

/** The Try harder row: not offered, ready, running, or how the last run ended. */
enum class TryHarderUi { HIDDEN, READY, RUNNING, FOUND, NOTHING, FAILED, STOPPED }

sealed interface ReviewState {
    data object Idle : ReviewState
    data object Loading : ReviewState

    /** The photo looks blurry: waiting for Retake or Use anyway, before any OCR. */
    data object Blurry : ReviewState

    /** The user chose Retake: the screen closes and the camera opens again. */
    data object Retake : ReviewState

    /** [bitmap] is null after process death if the image can no longer be read; the form still works. [version] bumps on every edit. */
    data class Ready(
        val bitmap: Bitmap?, val ocr: OcrResult, val result: PipelineResult, val session: ReviewSession,
        val decodeMs: Long, val totalMs: Long, val cold: Boolean, val version: Int,
        /** The OCR engine that read this image, for the debug tools; empty when not known. */
        val ocrEngine: String = "",
        /** How far the page was turned (clockwise) in the photo when [ocr] was read from it turned upright, else 0. */
        val turn: Int = 0,
    ) : ReviewState

    data class Error(val reason: ErrorReason) : ReviewState
}

/**
 * Review state across rotation (ViewModel) and process death (SavedStateHandle: the image URI, the OCR JSON and the
 * review session JSON; the pipeline is re-run from the OCR, which is deterministic). The bitmap is only in memory.
 * Metrics: one image line when the review is first shown, one line per event when the review screen is closed.
 */
class ReviewViewModel(private val application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {

    private val loader = ImageLoader(application.contentResolver)
    private val _state = MutableStateFlow<ReviewState>(ReviewState.Idle)
    val state: StateFlow<ReviewState> = _state.asStateFlow()
    private val _screenHold = MutableStateFlow<Int?>(null)

    /**
     * Non-null, with the review's number, from the moment a share is received until its chips finish or the model times
     * out, or until the router sends it down the code-only path. The screen stays on for it, because a sleeping phone
     * stalls OCR and the model alike. The number makes a replacing review a new value even if the hold never lapsed.
     */
    val screenHold: StateFlow<Int?> = _screenHold.asStateFlow()
    private val _tryHarder = MutableStateFlow(TryHarderUi.HIDDEN)
    val tryHarder: StateFlow<TryHarderUi> = _tryHarder.asStateFlow()
    private var tryJob: Job? = null
    @Volatile private var tryAssist: TryHarder? = null
    private var holdNoted = false
    private var version = 0
    private var shareId = ""
    private var codeShownNoted = false
    private var chipsShownNoted = false
    private var reviewJob: Job? = null
    private var reviewNumber = 0
    private var ocrEngineName = "mlkit"
    private val blurGate = BlurGate()

    init {
        val ocrJson = saved.get<String>(KEY_OCR)
        val sessionJson = saved.get<String>(KEY_SESSION)
        if (ocrJson != null && sessionJson != null) restore(ocrJson, sessionJson)
    }

    /**
     * Starts once per review (ignored after rotation or restore). [tapTime]: SystemClock time of the tap that opened it.
     * Sequential by design: OCR, then the code result on screen, then (chats only) load and warm up the model, then chips.
     * Warming up alongside OCR slowed OCR and the code result (benchmarks/phone/OVERLAP.md, private).
     */
    fun start(
        uri: Uri?, tapTime: Long, runLabel: String? = null,
        callTimeoutMs: Long? = null, shareTag: String? = null,
    ) {
        if (_state.value != ReviewState.Idle) return
        _state.value = ReviewState.Loading
        val intentAt = SystemClock.elapsedRealtime()
        shareId = ShareTrace.begin(getApplication(), shareTag)
        val share = shareId
        val thisReview = ++reviewNumber
        _screenHold.value = thisReview
        reviewJob = viewModelScope.launch { try {
            val t0 = if (tapTime > 0) tapTime else SystemClock.elapsedRealtime()
            val source = uri
            // A photo from the system camera lives only until it is decoded. Nothing else here is ever deleted.
            val capture = source?.let { CameraCapture.fileFor(application.cacheDir, it.authority, "${application.packageName}.files", it.path) }
            val image = try {
                withContext(Dispatchers.IO) { loader.load(source) }
            } catch (e: ImageLoadException) {
                Log.w(TAG, "image not loaded: ${e.reason}")
                ShareTrace.end(share, ShareDiag.OTHER, "reason" to "image_${e.reason}")
                _state.value = ReviewState.Error(when (e.reason) {
                    ImageLoadException.Reason.NO_IMAGE -> ErrorReason.NO_IMAGE
                    ImageLoadException.Reason.UNREADABLE -> ErrorReason.UNREADABLE
                    ImageLoadException.Reason.OUT_OF_MEMORY -> ErrorReason.OUT_OF_MEMORY
                })
                return@launch
            } finally {
                capture?.delete()
            }
            val sharpness = withContext(Dispatchers.Default) { image.bitmap.sharpness() }
            ShareTrace.event(share, "sharpness", "score" to sharpness)
            ShareTrace.event(share, "ocr_start")
            // Chosen here, per share, off the main thread: a change of engine applies to this share and frees the old one.
            val engine = withContext(Dispatchers.IO) { AppOcr.engine }
            ocrEngineName = engine.name
            saved[KEY_OCR_ENGINE] = engine.name
            // a page photographed sideways or upside down is read once more from the image turned upright
            val read = try {
                UprightRead.read(engine, image.bitmap)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "ocr failed: ${e.javaClass.simpleName}")
                _state.value = ReviewState.Error(ErrorReason.OCR_FAILED)
                ShareTrace.end(share, ShareDiag.OTHER, "reason" to "ocr_failed")
                return@launch
            }
            val ocr = read.ocr
            // a blurry photo that read weakly: Retake or Use anyway (the screen may sleep while asking); a blurry photo
            // that still read well goes on
            val weak = OcrQuality.weak(ocr)
            ShareTrace.event(share, "blur_check", "blurry" to Sharpness.isBlurry(sharpness), "weak" to weak)
            val choice = blurGate.check(sharpness, weak) {
                _screenHold.value = null
                _state.value = ReviewState.Blurry
            }
            if (choice == BlurGate.Choice.RETAKE) {
                ShareTrace.end(share, ShareDiag.OTHER, "reason" to "retake")
                _state.value = ReviewState.Retake
                return@launch
            }
            if (_state.value == ReviewState.Blurry) {
                _state.value = ReviewState.Loading
                _screenHold.value = thisReview
            }
            ShareTrace.event(share, "ocr_end", "ocr_ms" to ocr.ocrMs, "lines" to ocr.lines.size, "engine" to engine.name,
                "turn" to read.turn, "turn_unread" to read.turnUnread, "reread_ms" to read.rereadMs)
            // Counts and confidence only, to see which photos fail before deciding what to build for them.
            val quality = OcrQuality.of(ocr)
            Log.i(TAG, "ocr_quality photo=${image.cameraExif} exif_orientation=${image.exifOrientation} original=${image.originalWidth}x${image.originalHeight} " +
                "used=${ocr.imageWidth}x${ocr.imageHeight} blocks=${quality.blocks} lines=${quality.lines} conf_mean=${quality.meanConfidence} conf_min=${quality.minConfidence} " +
                "turn=${read.turn} turn_unread=${read.turnUnread} reread_ms=${read.rereadMs}")
            ShareTrace.event(share, "ocr_quality", "photo" to image.cameraExif, "exif_orientation" to image.exifOrientation, "original_w" to image.originalWidth,
                "original_h" to image.originalHeight, "used_w" to ocr.imageWidth, "used_h" to ocr.imageHeight, "blocks" to quality.blocks, "lines" to quality.lines,
                "conf_mean" to quality.meanConfidence?.toDouble(), "conf_min" to quality.minConfidence?.toDouble())
            val shotDate = image.shotDate
            saved[KEY_SHOT_DATE] = shotDate?.toString()
            saved[KEY_TURN] = read.turn
            saved[KEY_TURN_UNREAD] = read.turnUnread
            val result = withContext(Dispatchers.Default) { CodePipeline.run(ocr, LocalDate.now(), shotDate, read.turnUnread) }
            val session = ReviewSession.from(result, LocalDate.now())
            val cold = AppOcr.timings.record(ocr.ocrMs)
            val totalMs = SystemClock.elapsedRealtime() - t0
            val imageId = UUID.randomUUID().toString()
            saved[KEY_URI] = source?.takeIf { capture == null }?.toString()  // the camera's file is gone; after process death the form still works
            saved[KEY_OCR] = OcrJson.encode(ocr)
            saved[KEY_IMAGE_ID] = imageId
            saved[KEY_REVIEW_START] = System.currentTimeMillis()
            persist(session)
            val line = Metrics.imageLine(result, ocr, build = "${BuildConfig.VERSION_NAME}-${BuildConfig.BUILD_TYPE}",
                mode = "code:${CodePipeline.VERSION}", totalMs = totalMs, timestamp = OffsetDateTime.now().toString(), imageId = imageId,
                ocrEngine = engine.name)
            withContext(Dispatchers.IO) { MetricsLog.append(getApplication(), listOf(line)) }
            // Counts and timings only; never the recognised text.
            Log.i(TAG, "review ${if (cold) "cold" else "warm"} engine=${engine.name} lines=${ocr.lines.size} events=${result.events.size} " +
                "decode_ms=${image.decodeMs} ocr_ms=${ocr.ocrMs} total_ms=$totalMs")
            _state.value = ReviewState.Ready(image.bitmap, ocr, result, session, image.decodeMs, totalMs, cold, version, engine.name, read.turn)
            val codeAt = SystemClock.elapsedRealtime()
            val route = withContext(Dispatchers.Default) { ChatRouter.classify(ocr) }
            // the model offers a title chip on every page; on a chat it also offers its date chip
            val model = runCatching { ChatModels.get(getApplication()) }.getOrNull()
            val chatPage = route.route == Route.CHAT
            Log.i(TAG, "route ${route.route} score=${route.score} stamped=${route.stampedLines} chrome=${route.chromeLines} " +
                "sender=${route.senderLines} doc=${route.documentWords} model=${model != null}")
            ShareTrace.event(share, "router", "route" to route.route.name, "score" to route.score, "has_model" to (model != null))
            if (model == null) _screenHold.value = null  // code only: nothing more to wait for
            val first = result.events.firstOrNull()?.event
            // a model title must pass TitleRules.modelTitleOk against everything OCR read on the page
            val pageText = ocr.lines.joinToString(" ") { it.text }
            // the events whose title the model works on (a note shows on each): a chat's one event, else a page's first few
            val improving = when {
                model == null -> emptyList()
                chatPage -> if (result.events.size == 1) listOf(0) else emptyList()
                else -> result.events.indices.take(MAX_PAGE_TITLES).toList()
            }.filterNot { result.events[it].event.bill }   // a bill keeps its "Pay ... bill"
            if (improving.isNotEmpty()) { improving.forEach { session.events[it].titleImproving = true }; changed() }
            // Leaving the screen cancels this coroutine; the interrupt stops the native call.
            val suggestions = model?.let { m ->
                val assist = ChatAssist(m, callTimeoutMs, { name, job, outcome -> ShareTrace.event(share, name, "job" to job, "result" to outcome) },
                    onTimeout = { kind -> LocalStats.countTimeout(getApplication(), kind) })
                try {
                    if (chatPage) {
                        runInterruptible(Dispatchers.IO) { assist.suggest(result.cleaned.kept.map { it.text }, first?.title, first?.date, LocalDate.now(), result.chat, shotDate) }
                    } else {
                        // any other page: one title per event, from the lines around its date, each offered on its own event
                        val per = runInterruptible(Dispatchers.IO) {
                            result.events.indices.take(MAX_PAGE_TITLES).map { i -> i to assist.pageTitle(result.titleContext(i)) }
                        }
                        // a title that passes goes into the field unless the user edited it (one tap undoes it)
                        // a late answer never lands on another screenshot's form
                        if (thisReview == reviewNumber) per.forEach { (i, r) -> r.first.firstOrNull()?.let { session.applyModelTitle(i, it, pageText) } }
                        ChatSuggestions(titles = per.flatMap { it.second.first }, title = per.firstOrNull()?.second?.second ?: JobOutcome.SKIPPED,
                            warmUp = if (per.isEmpty()) JobOutcome.SKIPPED else JobOutcome.OK)
                    }
                } finally {
                    improving.forEach { session.events.getOrNull(it)?.titleImproving = false }
                    // A replaced review finishes late; the screen hold now belongs to the review that replaced it.
                    if (thisReview == reviewNumber) _screenHold.value = null
                }
            }
            if (suggestions == null && improving.isNotEmpty()) changed()
            if (suggestions != null && thisReview == reviewNumber) {
                if (chatPage) {
                    // only titles that pass are shown; with one event the first goes into the field, the rest stay chips
                    val valid = suggestions.titles.filter { TitleRules.modelTitleOk(it, pageText) }
                    val applied = result.events.size == 1 && valid.firstOrNull()?.let { session.applyModelTitle(0, it, pageText) } == true
                    session.addSuggestions(if (applied) valid.drop(1) else valid, suggestions.dateChip)
                }
                changed()
                Log.i(TAG, "chips warm=${suggestions.warmUp} date=${suggestions.date} title=${suggestions.title} " +
                    "titles=${suggestions.titles.size} date_chip=${suggestions.dateChip != null} total_ms=${SystemClock.elapsedRealtime() - t0}")
            }
            if (runLabel != null) {
                val doneAt = SystemClock.elapsedRealtime()
                withContext(Dispatchers.IO) {
                    DebugActions.recordReview(getApplication()) {
                        reviewLine(runLabel, model != null, intentAt, codeAt, doneAt, image.decodeMs, ocr, result, route, suggestions)
                    }
                }
            }
            ShareTrace.end(share, ShareDiag.outcome(route.route, model != null, suggestions), "route" to route.route.name,
                "titles" to suggestions?.titles?.size, "date_chip" to suggestions?.let { it.dateChip != null })
            // Offered only now: the code result is on screen and any chat chips are done, so the two models never overlap.
            offerTryHarder()
        } catch (e: CancellationException) {
            ShareTrace.end(share, ShareDiag.OTHER, "reason" to "cancelled")
            throw e
        } finally {
            // Every other way out: an unreadable image, failed OCR, no screenshot.
            if (thisReview == reviewNumber) _screenHold.value = null
        } }
    }

    /**
     * What a card's text fields hold now and the session does not have yet (typing not committed): the field texts, the
     * one edit a commit will count, and the key taps typed since the last commit.
     */
    data class LiveFields(val title: String?, val location: String?, val touches: Int, val typed: Int, val letters: Int)

    /** One event's values for the estimate, the final ones: [live] puts what its fields hold now over the session's. */
    private fun tapsAdded(e: `in`.gauthama.snapslotify.core.review.EventDraft, live: LiveFields? = null): TapsSaved.Added? {
        val d = e.date ?: return null
        return TapsSaved.Added(if (live != null) live.title else e.title, if (live != null) live.location else e.location, d,
            !e.allDay && e.start != null, e.counts.taps + e.counts.pickerTouches + (live?.touches ?: 0), e.counts.typedChars + (live?.typed ?: 0),
            e.counts.typedLetters + (live?.letters ?: 0),
            endSet = !e.allDay && e.start != null && e.end != null &&
                e.end != e.start!!.plusMinutes(`in`.gauthama.snapslotify.stats.TapCosts.DEFAULT_EVENT_MINUTES),
            endDate = e.endDate)
    }

    /**
     * The taps strip for this page, worked out from the session itself (saved for process death, so it is still right
     * when the phone closed the app while the calendar editor was open) and the fields as they are on screen ([live], by
     * event id): what adding the events ready to add would save now, and what the added ones saved.
     */
    fun pageTaps(s: ReviewSession, live: Map<Int, LiveFields> = emptyMap()): TapsSaved.PageLine = TapsSaved.pageLine(
        s.events.filter { it.saved && !it.deleted }.mapNotNull { tapsAdded(it) },
        s.events.filter { it.canAddToCalendar }.mapNotNull { tapsAdded(it, live[it.id]) },
        LocalDate.now(), amPm(),
    )

    /** The phone uses the 12-hour clock: the calendar's time picker by hand then has an AM/PM step. */
    private fun amPm(): Boolean = !android.text.format.DateFormat.is24HourFormat(getApplication())

    /** The events added from this page, by the estimates made at each Add (kept for process death). */
    val pageAdded: TapsSaved.Estimate
        get() = saved.get<IntArray>(KEY_PAGE_TAPS)?.let { TapsSaved.Estimate(it[0], it[1], it[2], it[3], it[4]) } ?: TapsSaved.Estimate(0, 0, 0, 0, 0)

    /** An Add opened the calendar editor: the confirmation shows when the user comes back (Add all: after its last one). */
    var confirmPending: Boolean
        get() = saved.get<Boolean>(KEY_CONFIRM) == true
        set(v) { saved[KEY_CONFIRM] = v }

    /**
     * Events just added (their calendar editor opened): each event's estimate is stored in the local totals; the page's
     * share or open step is counted once, with the page's first added event ([firstOnPage]: nothing added before).
     */
    fun countAdded(events: List<`in`.gauthama.snapslotify.core.review.EventDraft>, firstOnPage: Boolean) {
        val today = LocalDate.now()
        val added = events.mapNotNull(::tapsAdded)
        if (added.isEmpty()) return
        val estimates = added.mapIndexed { i, a -> TapsSaved.estimate(a, today, firstOnPage && i == 0, amPm()) }
        val page = (saved.get<IntArray>(KEY_PAGE_TAPS) ?: IntArray(5)).copyOf()
        for (x in estimates) { page[0] += x.events; page[1] += x.manualTaps; page[2] += x.appTaps; page[3] += x.saved; page[4] += x.charsNotTyped }
        saved[KEY_PAGE_TAPS] = page
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) { estimates.forEach { runCatching { LocalStats.add(app, it) } } }
    }

    /*
     * Add all opens the calendar editors one at a time: a calendar app that opens its editor in its own task (Google
     * Calendar does) keeps only the last of several sent at once. The events still to open and the ones tried are kept
     * in the saved state, so the run carries on after the phone closed the app while an editor was open.
     */

    /** Add all is part way: an editor was opened and more events wait for the user to come back. */
    val addAllRunning: Boolean get() = saved.get<IntArray>(KEY_ADD_QUEUE) != null

    /** Events of the run not opened yet. */
    val addAllLeft: Int get() = saved.get<IntArray>(KEY_ADD_QUEUE)?.size ?: 0

    /** The calendar app the user picked for this Add all run (null: one app only, Android decides). */
    val addAllPackage: String? get() = saved.get<String>(KEY_ADD_PACKAGE)

    fun startAddAll(ids: List<Int>, pkg: String?) {
        saved[KEY_ADD_QUEUE] = ids.toIntArray()
        saved[KEY_ADD_TRIED] = IntArray(0)
        saved[KEY_ADD_PACKAGE] = pkg
    }

    /** The next event to open, recorded as tried; null when the run is over. */
    fun nextToAdd(): Int? {
        val q = saved.get<IntArray>(KEY_ADD_QUEUE) ?: return null
        if (q.isEmpty()) return null
        saved[KEY_ADD_QUEUE] = q.copyOfRange(1, q.size)
        saved[KEY_ADD_TRIED] = (saved.get<IntArray>(KEY_ADD_TRIED) ?: IntArray(0)) + q[0]
        return q[0]
    }

    /** Ends the run: its result for the status line (added means its editor was opened). */
    fun finishAddAll(s: ReviewSession) {
        val tried = saved.get<IntArray>(KEY_ADD_TRIED)?.toList() ?: emptyList()
        saved.remove<Any>(KEY_ADD_QUEUE)
        saved.remove<Any>(KEY_ADD_TRIED)
        saved.remove<Any>(KEY_ADD_PACKAGE)
        if (tried.isEmpty()) return
        val failed = tried.filter { !s.event(it).saved }.sorted()
        s.lastAddAll = `in`.gauthama.snapslotify.core.review.AddAllResult(tried.size - failed.size, tried.size, failed)
    }

    /**
     * Ends the current review so [start] can begin a new one: cancels its work, including chips still computing (the
     * interrupt stops the native call), writes its metrics as closing the screen would, and forgets its saved state.
     */
    fun closeCurrentReview() {
        reviewJob?.cancel()
        reviewJob = null
        tryAssist?.cancel()
        tryJob?.cancel()
        tryJob = null
        _tryHarder.value = TryHarderUi.HIDDEN
        reviewNumber++
        writeEventLines()
        listOf(KEY_URI, KEY_OCR, KEY_SESSION, KEY_IMAGE_ID, KEY_REVIEW_START, KEY_SHOT_DATE, KEY_ADD_QUEUE, KEY_ADD_TRIED, KEY_ADD_PACKAGE, KEY_PAGE_TAPS, KEY_CONFIRM).forEach { saved.remove<Any>(it) }
        if (holdNoted) ShareTrace.event(shareId, "screen_hold_released", "reason" to "replaced")
        holdNoted = false
        _screenHold.value = null
        codeShownNoted = false
        chipsShownNoted = false
        _state.value = ReviewState.Idle
    }

    /** Shows Try harder if the image is in memory, the model is on the phone, and it has not run to the end for this image. */
    private suspend fun offerTryHarder() {
        val r = _state.value as? ReviewState.Ready ?: return
        if (r.bitmap == null || r.session.tryHarderDone || _tryHarder.value != TryHarderUi.HIDDEN) return
        // a poster with one event only: not a programme, calendar grid, list, bill, chat or email
        if (!r.result.tryHarderFits) return
        val has = withContext(Dispatchers.IO) { runCatching { VisionModels.get(getApplication()) }.getOrNull() != null }
        // Still the same review (a new share may have replaced it while the files were checked)
        if (has && (_state.value as? ReviewState.Ready)?.session === r.session) _tryHarder.value = TryHarderUi.READY
    }

    /**
     * The Try harder button: starts a run, or stops the one running. The model reads the image once more; its title, date
     * and time arrive as chips (ReviewSession.addTryHarder) and never fill a field. The screen stays on while it runs.
     */
    /** The blurry-photo prompt: take the photo again. */
    fun retake() = blurGate.retake()

    /** The blurry-photo prompt: read this photo anyway. */
    fun useAnyway() = blurGate.useAnyway()

    fun tryHarder() {
        if (_tryHarder.value == TryHarderUi.RUNNING) {
            tryAssist?.cancel()
            tryJob?.cancel()
            _tryHarder.value = TryHarderUi.STOPPED
            ShareTrace.event(shareId, "try_harder_stopped")
            return
        }
        val r = _state.value as? ReviewState.Ready ?: return
        val bitmap = r.bitmap ?: return
        if (r.session.tryHarderDone) return
        _tryHarder.value = TryHarderUi.RUNNING
        val thisReview = reviewNumber
        val share = shareId
        _screenHold.value = thisReview
        ShareTrace.event(share, "try_harder_start")
        tryJob = viewModelScope.launch {
            try {
                val model = withContext(Dispatchers.IO) { runCatching { VisionModels.get(getApplication()) }.getOrNull() }
                if (model == null) { _tryHarder.value = TryHarderUi.FAILED; return@launch }
                val img = withContext(Dispatchers.Default) { VisionImage.from(bitmap) }
                val assist = TryHarder(model, onTimeout = { kind -> LocalStats.countTimeout(getApplication(), kind) }).also { tryAssist = it }
                val res = runInterruptible(Dispatchers.IO) { assist.run(img.rgb, img.width, img.height, LocalDate.now()) }
                if (thisReview != reviewNumber) return@launch
                val added = when (res.outcome) {
                    TryHarderOutcome.OK -> r.session.addTryHarder(res.title, res.dateChip, res.timeChip)
                    TryHarderOutcome.NOTHING, TryHarderOutcome.INVALID -> r.session.addTryHarder(null, null, null)
                    else -> 0
                }
                _tryHarder.value = when (res.outcome) {
                    TryHarderOutcome.OK, TryHarderOutcome.NOTHING, TryHarderOutcome.INVALID -> if (added > 0) TryHarderUi.FOUND else TryHarderUi.NOTHING
                    TryHarderOutcome.CANCELLED -> TryHarderUi.STOPPED
                    TryHarderOutcome.TIMEOUT, TryHarderOutcome.FAILED -> TryHarderUi.FAILED
                }
                changed()
                // Counts and timings only; never what the model read.
                Log.i(TAG, "try_harder outcome=${res.outcome} image=${img.width}x${img.height} warm_ms=${res.warmUpMs} read_ms=${res.readMs} " +
                    "title=${res.title != null} date=${res.dateChip != null} time=${res.timeChip != null} added=$added")
                ShareTrace.event(share, "try_harder_end", "outcome" to res.outcome.name, "warm_ms" to res.warmUpMs, "read_ms" to res.readMs,
                    "title" to (res.title != null), "date" to (res.dateChip != null), "time" to (res.timeChip != null), "added" to added)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "try_harder failed: ${e.javaClass.simpleName}")
                if (thisReview == reviewNumber) _tryHarder.value = TryHarderUi.FAILED
            } finally {
                tryAssist = null
                if (thisReview == reviewNumber) _screenHold.value = null
            }
        }
    }

    /** Debug share log: the screen really drew the code result. Once per share. */
    fun noteCodeShown() {
        if (!codeShownNoted) { codeShownNoted = true; ShareTrace.event(shareId, "code_shown") }
    }

    /** Debug share log: the screen really drew the chips. Once per share. */
    fun noteChipsShown() {
        if (!chipsShownNoted) { chipsShownNoted = true; ShareTrace.event(shareId, "chips_shown") }
    }

    /** Debug share log: the window flag really changed. */
    fun noteScreenHold(held: Boolean) {
        if (held == holdNoted) return
        holdNoted = held
        ShareTrace.event(shareId, if (held) "screen_hold_taken" else "screen_hold_released")
    }

    /** One line for the debug run file. Holds recognised text, so it is never logged. */
    private fun reviewLine(
        label: String, hasModel: Boolean, intentAt: Long, codeAt: Long, doneAt: Long, decodeMs: Long,
        ocr: OcrResult, result: PipelineResult, route: RouteDecision, s: ChatSuggestions?,
    ) = JSONObject()
        .put("label", label).put("has_model", hasModel)
        .put("process_start", Process.getStartElapsedRealtime()).put("intent_at", intentAt)
        .put("code_at", codeAt).put("done_at", doneAt).put("decode_ms", decodeMs).put("ocr_ms", ocr.ocrMs).put("n_lines", ocr.lines.size)
        .put("route", route.route.name).put("route_score", route.score)
        .put("code_events", JSONArray(result.events.map { e ->
            JSONObject().put("title", e.event.title).put("date", e.event.date).put("time", e.event.time).put("start", e.resolved.start?.toString())
        }))
        .put("kept_lines", JSONArray(result.cleaned.kept.map { it.text }))
        .apply {
            if (s != null) {
                put("warm", s.warmUp.name).put("warm_ms", s.warmUpMs).put("date", s.date.name).put("date_ms", s.dateMs)
                put("title", s.title.name).put("title_ms", s.titleMs).put("raw_date", s.rawDate).put("raw_title", s.rawTitle)
                put("date_phrase", s.datePhrase).put("date_chip", s.dateChip?.value).put("titles", JSONArray(s.titles))
            }
        }

    private fun restore(ocrJson: String, sessionJson: String) {
        val ocr = OcrJson.decode(ocrJson)
        val session = ReviewSession.restore(sessionJson)
        val result = CodePipeline.run(ocr, LocalDate.now(), saved.get<String>(KEY_SHOT_DATE)?.let(LocalDate::parse), saved.get<Boolean>(KEY_TURN_UNREAD) == true)
        _state.value = ReviewState.Ready(null, ocr, result, session, 0, 0, false, version, saved.get<String>(KEY_OCR_ENGINE).orEmpty(),
            saved.get<Int>(KEY_TURN) ?: 0)
        val uri = saved.get<String>(KEY_URI)?.let(Uri::parse) ?: return
        reviewJob = viewModelScope.launch {
            val bmp = runCatching { withContext(Dispatchers.IO) { loader.load(uri).bitmap } }.getOrNull() ?: return@launch
            (_state.value as? ReviewState.Ready)?.let { _state.value = it.copy(bitmap = bmp) }
            offerTryHarder()
        }
    }

    /** Call after every change to the session: saves it for process death and re-renders. */
    fun changed() {
        val s = (_state.value as? ReviewState.Ready) ?: return
        persist(s.session)
        _state.value = s.copy(version = ++version)
    }

    private fun persist(session: ReviewSession) {
        saved[KEY_SESSION] = session.save()
    }

    /** Debug "save as test case" needs the source image and the raw OCR. */
    fun sourceUri(): Uri? = saved.get<String>(KEY_URI)?.let(Uri::parse)

    override fun onCleared() = writeEventLines()

    /** The review screen was closed (not rotated) or replaced: one metrics line per event. */
    private fun writeEventLines() {
        val s = (_state.value as? ReviewState.Ready) ?: return
        val start = saved.get<Long>(KEY_REVIEW_START) ?: return
        val lines = Metrics.eventLines(s.session, saved.get<String>(KEY_IMAGE_ID).orEmpty(), (System.currentTimeMillis() - start) / 1000.0,
            ocrEngine = saved.get<String>(KEY_OCR_ENGINE) ?: ocrEngineName, ocrMs = s.ocr.ocrMs)
        MetricsLog.append(getApplication(), lines)
    }

    private companion object {
        const val TAG = "Snapslotify"
        /** At most this many events of one page get a model title (one model call each). */
        const val MAX_PAGE_TITLES = 3
        const val KEY_URI = "uri"
        const val KEY_SHOT_DATE = "shot_date"
        const val KEY_OCR = "ocr"
        const val KEY_SESSION = "session"
        const val KEY_IMAGE_ID = "image_id"
        const val KEY_REVIEW_START = "review_start"
        const val KEY_OCR_ENGINE = "ocr_engine"
        const val KEY_TURN = "turn"
        const val KEY_TURN_UNREAD = "turn_unread"
        const val KEY_ADD_QUEUE = "add_queue"
        const val KEY_ADD_TRIED = "add_tried"
        const val KEY_ADD_PACKAGE = "add_package"
        const val KEY_PAGE_TAPS = "page_taps"
        const val KEY_CONFIRM = "confirm_pending"
    }
}
