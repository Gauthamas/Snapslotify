package `in`.gauthama.snapslotify.review

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import `in`.gauthama.snapslotify.DebugActions
import `in`.gauthama.snapslotify.MainActivity
import `in`.gauthama.snapslotify.R
import `in`.gauthama.snapslotify.ShareShortcut
import `in`.gauthama.snapslotify.core.chat.ChatAssist
import `in`.gauthama.snapslotify.core.ocr.OcrJson
import `in`.gauthama.snapslotify.core.ocr.PageTurn
import `in`.gauthama.snapslotify.core.review.CalendarValues
import `in`.gauthama.snapslotify.core.review.Chip
import `in`.gauthama.snapslotify.core.review.EventDraft
import `in`.gauthama.snapslotify.core.review.Field
import `in`.gauthama.snapslotify.core.review.ReviewSession
import `in`.gauthama.snapslotify.databinding.ActivityReviewBinding
import `in`.gauthama.snapslotify.databinding.ItemEventBinding
import `in`.gauthama.snapslotify.stats.TapsSaved
import `in`.gauthama.snapslotify.stats.TypingCounter
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Review screen (spec section 5): the image on top (collapsible, pinch-zoom; tapping a field outlines its source line),
 * one card per event with title, date, start/end time, location and all-day, the source line under each field, candidate
 * chips under date and time, flag sentences, a one-tap date confirmation for flagged dates, Add to calendar and Delete.
 */
class ReviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReviewBinding
    private val viewModel: ReviewViewModel by viewModels()
    private val cards = mutableMapOf<Int, ItemEventBinding>()
    private var ready: ReviewState.Ready? = null
    private var programmatic = false
    /** Key taps per (event id, field) since the field's last commit, from the text before and after each keyboard step. */
    private val counters = mutableMapOf<Pair<Int, String>, TypingCounter>()

    /** Debug builds, asked for by an extra only: every text change of the fields and how it was counted, to a file. */
    private var typingTrace: java.io.File? = null
    private val dateFormat = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")

    /** The activity went fully behind another screen (a calendar editor) since Add all opened its last one. */
    private var leftForEditor = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // recreated (the phone closed the app while an editor was open): coming back means that editor is done
        leftForEditor = savedInstanceState != null
        binding = ActivityReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.moreOptions.setOnClickListener { v ->
            val menu = android.widget.PopupMenu(this, v)
            menu.menu.add(0, MENU_REPORT, 0, R.string.report_problem)
            menu.setOnMenuItemClickListener { item -> if (item.itemId == MENU_REPORT) { openReport(); true } else false }
            menu.show()
        }
        binding.toggleImage.setOnClickListener {
            val show = binding.image.visibility != View.VISIBLE
            binding.image.visibility = if (show) View.VISIBLE else View.GONE
            binding.toggleImage.setText(if (show) R.string.hide_image else R.string.show_image)
        }
        binding.debugRow.visibility = if (DebugActions.AVAILABLE) View.VISIBLE else View.GONE
        DebugActions.addReviewButtons(this, binding.debugButtons, { ready?.ocr }, { ready?.ocrEngine.orEmpty() })
        binding.saveTestCase.setOnClickListener {
            val r = ready ?: return@setOnClickListener
            DebugActions.saveTestCase(this, viewModel.sourceUri(), OcrJson.encode(r.ocr), r.result)?.let(::startActivity)
        }
        binding.copyOcrJson.setOnClickListener {
            val r = ready ?: return@setOnClickListener
            copyOcrJson(OcrJson.encode(r.ocr))
        }
        binding.viewOcrJson.setOnClickListener {
            val r = ready ?: return@setOnClickListener
            showOcrJson(OcrJson.encode(r.ocr))
        }

        binding.tryHarder.setOnClickListener { commitAllText(); viewModel.tryHarder() }
        binding.addAll.setOnClickListener { addAll() }
        binding.addEvent.setOnClickListener { addEvent() }
        binding.retake.setOnClickListener { viewModel.retake() }
        binding.useAnyway.setOnClickListener { viewModel.useAnyway() }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.state.collect(::render) } }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.tryHarder.collect(::renderTryHarder) } }
        // From the share until chips finish or the model times out: a sleeping phone stalls the work, and the code result too.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.screenHold.collect { review ->
                    if (review != null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    viewModel.noteScreenHold(review != null)
                }
            }
        }

        startReview(intent, replace = false)
    }

    private fun copyOcrJson(json: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(getString(R.string.ocr_json_label), json))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(this, R.string.copied_ocr_json, Toast.LENGTH_SHORT).show()
    }

    /** Debug row only: what OCR read, pretty printed. The JSON is on screen and nowhere else; it is never logged. */
    private fun showOcrJson(json: String) {
        val pad = (12 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            this.text = json
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(pad, pad, pad, pad)
            tag = OCR_JSON_VIEW_TAG
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.ocr_json_label)
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.copy) { _, _ -> copyOcrJson(json) }
            .show()
    }

    /** A new share while this screen is on top: it replaces the current review, whatever state that review is in. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        commitAllText()
        ready = null
        counters.clear()
        cards.clear()
        binding.events.removeAllViews()
        binding.noEvent.visibility = View.GONE
        binding.pageNote.visibility = View.GONE
        binding.image.highlight(null)
        binding.image.setImageDrawable(null)
        binding.image.visibility = View.VISIBLE
        binding.toggleImage.setText(R.string.hide_image)
        binding.formScroll.scrollTo(0, 0)
        startReview(intent, replace = true)
    }

    private fun startReview(intent: Intent, replace: Boolean) {
        val tap = intent.getLongExtra(EXTRA_TAP_TIME, 0L)
        // Phone runs only: record the review under a label, and force short model timeouts.
        val label = if (DebugActions.AVAILABLE) intent.getStringExtra(EXTRA_DEBUG_LABEL) else null
        val timeout = if (DebugActions.AVAILABLE && intent.hasExtra(EXTRA_DEBUG_TIMEOUT)) intent.getLongExtra(EXTRA_DEBUG_TIMEOUT, 0L) else null
        val tag = if (DebugActions.AVAILABLE) intent.getStringExtra(EXTRA_DEBUG_SHARE_TAG) else null
        typingTrace = if (DebugActions.AVAILABLE && intent.getBooleanExtra(EXTRA_DEBUG_TYPING_TRACE, false))
            java.io.File(filesDir, "typing_trace.txt").also { it.writeText("") } else null
        if (replace) viewModel.closeCurrentReview()
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            // Share sheet: keep the direct-share shortcut published and ranked (no effect on the review itself)
            ShareShortcut.publish(this)
            ShareShortcut.reportUsed(this)
        }
        when (intent.action) {
            Intent.ACTION_SEND -> viewModel.start(
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri,
                tapTime = tap, runLabel = label, callTimeoutMs = timeout, shareTag = tag,
            )
            // a list share (some galleries send even one image this way): its first image, one review as for SEND;
            // with more than one image the user is told only the first is read
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                val count = maxOf(list?.size ?: 0, intent.clipData?.itemCount ?: 0)
                if (count > 1) Toast.makeText(this, resources.getQuantityString(R.plurals.share_first_image_only, count, count), Toast.LENGTH_LONG).show()
                viewModel.start(
                    list?.firstOrNull() ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri,
                    tapTime = tap, runLabel = label, callTimeoutMs = timeout, shareTag = tag,
                )
            }
            else -> viewModel.start(intent.data, tapTime = tap, runLabel = label, callTimeoutMs = timeout, shareTag = tag)
        }
    }

    override fun onPause() {
        commitAllText()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (viewModel.addAllRunning || viewModel.confirmPending) leftForEditor = true
    }

    override fun onResume() {
        super.onResume()
        // back from one Add all editor: open the next; back from the last editor: the confirmation
        if (viewModel.addAllRunning && leftForEditor) {
            leftForEditor = false
            binding.root.post { addNext() }
        } else if (viewModel.confirmPending && leftForEditor) {
            leftForEditor = false
            binding.root.post { showAdded() }
        }
    }

    // ---- rendering ----

    private fun render(state: ReviewState) {
        binding.progress.visibility = if (state is ReviewState.Loading || state is ReviewState.Idle) View.VISIBLE else View.GONE
        binding.error.visibility = if (state is ReviewState.Error) View.VISIBLE else View.GONE
        binding.formScroll.visibility = if (state is ReviewState.Ready) View.VISIBLE else View.GONE
        binding.blurryPanel.visibility = if (state is ReviewState.Blurry) View.VISIBLE else View.GONE
        when (state) {
            // take the photo again: back to the main screen, which opens the camera
            is ReviewState.Retake -> {
                startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_RETAKE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }
            is ReviewState.Error -> binding.error.setText(when (state.reason) {
                ErrorReason.NO_IMAGE -> R.string.error_no_image
                ErrorReason.UNREADABLE -> R.string.error_unreadable
                ErrorReason.OUT_OF_MEMORY -> R.string.error_out_of_memory
                ErrorReason.OCR_FAILED -> R.string.error_ocr_failed
            })
            is ReviewState.Ready -> {
                showReady(state)
                viewModel.noteCodeShown()
                if (state.session.events.any { it.suggestedTitles.isNotEmpty() || it.suggestedDates.isNotEmpty() || it.suggestedTimes.isNotEmpty() }) viewModel.noteChipsShown()
            }
            else -> Unit
        }
    }

    private fun renderTryHarder(ui: TryHarderUi) {
        binding.tryHarderRow.visibility = if (ui == TryHarderUi.HIDDEN) View.GONE else View.VISIBLE
        // Found or nothing: it ran to the end for this image, so only the status stays
        binding.tryHarder.visibility = if (ui == TryHarderUi.FOUND || ui == TryHarderUi.NOTHING) View.GONE else View.VISIBLE
        binding.tryHarder.setText(if (ui == TryHarderUi.RUNNING) R.string.try_harder_stop else R.string.try_harder)
        val status = when (ui) {
            TryHarderUi.RUNNING -> R.string.try_harder_running
            TryHarderUi.FOUND -> R.string.try_harder_found
            TryHarderUi.NOTHING -> R.string.try_harder_nothing
            TryHarderUi.FAILED -> R.string.try_harder_failed
            TryHarderUi.STOPPED -> R.string.try_harder_stopped
            else -> 0
        }
        binding.tryHarderStatus.visibility = if (status == 0) View.GONE else View.VISIBLE
        if (status != 0) binding.tryHarderStatus.setText(status)
    }

    private fun showReady(r: ReviewState.Ready) {
        val first = ready == null || ready?.bitmap !== r.bitmap
        ready = r
        if (first) binding.image.setImageBitmap(r.bitmap)
        binding.noEvent.visibility = if (r.session.noEventFound) View.VISIBLE else View.GONE
        binding.pageNote.text = r.session.note
        // with no event found, a note only says why (all dates past) or that chips are offered
        binding.pageNote.visibility = if (r.session.note != null) View.VISIBLE else View.GONE
        binding.debugInfo.visibility = if (DebugActions.AVAILABLE) View.VISIBLE else View.GONE
        if (DebugActions.AVAILABLE) {
            binding.debugInfo.text = "image ${r.ocr.imageWidth}x${r.ocr.imageHeight}  decode ${r.decodeMs} ms  ocr ${r.ocr.ocrMs} ms" +
                " (${if (r.cold) "cold" else "warm"})  clean ${r.result.cleanMs} ms  extract ${r.result.extractMs} ms  total ${r.totalMs} ms"
        }
        for (e in r.session.events) {
            val card = cards.getOrPut(e.id) { newCard(e) }
            bind(card, e, r)
        }
        binding.addEvent.visibility = View.VISIBLE
        bindAddAll(r.session)
        bindTapsSaved(r.session)
    }

    /** Add event: an empty event at the end of the list, scrolled into view with its title ready to type. */
    private fun addEvent() {
        commitAllText()
        val e = session()?.addEvent() ?: return
        viewModel.changed()
        binding.events.post {
            val card = cards[e.id] ?: return@post
            binding.formScroll.smoothScrollTo(0, binding.events.top + card.root.top)
            card.title.requestFocus()
        }
    }

    /**
     * The slim strip at the top: what adding the events ready to add would save now, from the fields as they are on
     * screen (recomputed on every change, typing included), and what the events added from this page saved. It keeps
     * its place when empty, so the form never moves.
     */
    private fun bindTapsSaved(s: ReviewSession) {
        val p = viewModel.pageTaps(s, liveFields())
        val page = viewModel.pageAdded
        // the true numbers may be below 0 (an event that needed a lot of fixing): shown floored, or said in words
        val done = TapsSaved.shown(page.saved)
        val rest = TapsSaved.shown(p.wouldSave)
        val text = when {
            s.noEventFound -> null
            p.ready > 0 && page.events > 0 -> if (done == 0 && rest == 0) getString(R.string.taps_strip_none_done)
                else getString(R.string.taps_strip_more, done, rest)
            p.ready > 0 -> if (rest == 0) getString(R.string.taps_strip_none_now) else getString(R.string.taps_strip_now, rest)
            page.events > 0 -> if (done == 0) getString(R.string.taps_strip_none_done) else getString(R.string.taps_strip_done, done)
            else -> null
        }
        binding.tapsSaved.visibility = if (text == null) View.INVISIBLE else View.VISIBLE
        if (typingTrace != null && binding.tapsSaved.text?.toString() != text) trace("strip: $text")
        binding.tapsSaved.text = text
    }

    private fun trace(line: String) { typingTrace?.appendText(line + "\n") }

    /** Text fields changed and not committed yet: what each card's title and location hold now, as a commit would count it. */
    private fun liveFields(): Map<Int, ReviewViewModel.LiveFields> {
        val s = session() ?: return emptyMap()
        val out = mutableMapOf<Int, ReviewViewModel.LiveFields>()
        for ((id, c) in cards) {
            val e = runCatching { s.event(id) }.getOrNull() ?: continue
            val title = c.title.text.toString()
            val location = c.location.text.toString()
            val titleChanged = title != e.title.orEmpty()
            val locationChanged = location != e.location.orEmpty()
            val titleTaps = counters[id to "title"]?.taps ?: 0
            val locationTaps = counters[id to "location"]?.taps ?: 0
            // a field typed in counts its touch and key taps even when its text is back as it was
            val titleTyped = titleChanged || titleTaps > 0
            val locationTyped = locationChanged || locationTaps > 0
            if (!titleTyped && !locationTyped) continue
            out[id] = ReviewViewModel.LiveFields(
                if (titleChanged) title.ifBlank { null } else e.title,
                if (locationChanged) location.ifBlank { null } else e.location,
                (if (titleTyped) 1 else 0) + (if (locationTyped) 1 else 0),
                titleTaps + locationTaps,
                (counters[id to "title"]?.letters ?: 0) + (counters[id to "location"]?.letters ?: 0),
            )
        }
        return out
    }

    /** Back from the calendar editor after Add (after Add all's last one): the confirmation with the page's saving. */
    private fun showAdded() {
        viewModel.confirmPending = false
        val page = viewModel.pageAdded
        if (page.events == 0) return
        val more = session()?.toAdd?.isNotEmpty() ?: true
        addedScreen.launch(`in`.gauthama.snapslotify.stats.AddedActivity.intent(this, page, more))
    }

    /** Done on the confirmation closes the review. */
    private val addedScreen = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) finish()
    }

    /** Add all: only with more than one event, enabled only when every event's own Add is, with the count it would add. */
    private fun bindAddAll(s: ReviewSession) {
        binding.addAll.visibility = if (s.showAddAll) View.VISIBLE else View.GONE
        val n = s.toAdd.size
        val running = viewModel.addAllRunning
        binding.addAll.text = when {
            n == 0 -> getString(R.string.all_added)
            running -> getString(R.string.add_all_next, n)
            else -> getString(R.string.add_all, n)
        }
        binding.addAll.isEnabled = s.canAddAll
        val last = s.lastAddAll?.takeIf { s.showAddAll && !running }
        binding.addAllStatus.visibility = if (last == null && !running) View.GONE else View.VISIBLE
        binding.addAllStatus.text = if (running) getString(R.string.add_all_running) else last?.let { res ->
            if (res.failed.isEmpty()) getString(R.string.add_all_result, res.added, res.tried)
            else getString(R.string.add_all_result_failed, res.added, res.tried,
                res.failed.joinToString(", ") { id -> s.event(id).title ?: getString(R.string.event_header, id + 1) })
        }
    }

    private fun newCard(e: EventDraft): ItemEventBinding {
        val c = ItemEventBinding.inflate(layoutInflater, binding.events, false)
        binding.events.addView(c.root)
        c.root.tag = "event_${e.id}"
        val id = e.id
        watchTyping(c.title, id, "title")
        watchTyping(c.location, id, "location")
        // while the user is in the title field, a model title never replaces what they type (it is offered as a chip)
        c.title.setOnFocusChangeListener { _, has ->
            session()?.event(id)?.titleBeingEdited = has
            if (has) highlight(field = Field.TITLE, id) else commitText(id, "title", c.title)
        }
        c.location.setOnFocusChangeListener { _, has -> if (has) highlight(Field.LOCATION, id) else commitText(id, "location", c.location) }
        c.date.setOnClickListener { highlight(Field.DATE, id); pickDate(id) }
        c.start.setOnClickListener { highlight(Field.TIME, id); pickTime(id, start = true) }
        c.end.setOnClickListener { highlight(Field.TIME, id); pickTime(id, start = false) }
        c.confirmDate.setOnClickListener { session()?.confirmDate(id); viewModel.changed() }
        c.allDay.setOnCheckedChangeListener { _, checked ->
            if (!programmatic && session()?.event(id)?.allDay != checked) { session()?.setAllDay(id, checked); viewModel.changed() }
        }
        c.delete.setOnClickListener { commitAllText(); session()?.delete(id); viewModel.changed() }
        c.addToCalendar.setOnClickListener { addToCalendar(id) }
        // one tap clears a wrong title and opens the keyboard on the empty field; the tap is the field's one edit (the
        // removed text costs no key taps), then only the new letters count
        c.clearTitle.setOnClickListener {
            // the heading being cleared stays offered as a chip: one tap puts it back
            session()?.offerClearedTitle(id, c.title.text.toString())
            programmatic = true
            c.title.setText("")
            programmatic = false
            c.title.requestFocus()
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showSoftInput(c.title, 0)
            session()?.let(::bindTapsSaved)
            c.clearTitle.visibility = View.INVISIBLE
            viewModel.changed()
        }
        c.addLocation.setOnClickListener {
            session()?.showLocationField(id)
            viewModel.changed()
            c.location.visibility = View.VISIBLE
            c.location.requestFocus()
        }
        c.undoTitle.setOnClickListener {
            commitAllText()
            c.title.clearFocus()
            session()?.undoModelTitle(id)
            // as with a title chip: write the field here, bind() leaves a focused field alone
            programmatic = true
            c.title.setText(session()?.event(id)?.title.orEmpty())
            programmatic = false
            viewModel.changed()
        }
        return c
    }

    private fun bind(c: ItemEventBinding, e: EventDraft, r: ReviewState.Ready) {
        programmatic = true
        val lines = r.result.cleaned.kept
        fun source(view: TextView, line: Int?) {
            val text = line?.let { lines.getOrNull(it)?.text }
            view.visibility = if (text == null) View.GONE else View.VISIBLE
            view.text = text?.let { getString(R.string.source_line, it) }
        }
        fun flags(view: TextView, field: Field) {
            val s = e.flags.filter { it.field == field }.joinToString("\n") { it.sentence }
            view.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE
            view.text = s
        }
        c.root.visibility = if (e.deleted) View.GONE else View.VISIBLE
        c.header.text = getString(R.string.event_header, e.id + 1)
        if (!c.title.hasFocus() && c.title.text.toString() != e.title.orEmpty()) c.title.setText(e.title.orEmpty())
        if (!c.location.hasFocus() && c.location.text.toString() != e.location.orEmpty()) c.location.setText(e.location.orEmpty())
        c.location.visibility = if (e.showLocation || c.location.hasFocus()) View.VISIBLE else View.GONE
        c.addLocation.visibility = if (c.location.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        c.titleImproving.visibility = if (e.titleImproving) View.VISIBLE else View.GONE
        c.undoTitle.visibility = if (e.modelTitleApplied && e.codeTitle != null) View.VISIBLE else View.GONE
        if (e.modelTitleApplied) e.codeTitle?.let { c.undoTitle.text = getString(R.string.undo_title, it) }
        c.date.text = e.date?.let { d -> dateFormat.format(d) + (e.endDate?.let { " to " + dateFormat.format(it) } ?: "") }.orEmpty()
        c.date.setBackgroundResource(if (e.dateNeedsConfirm) R.drawable.flagged_field else R.drawable.field_box)
        c.confirmDate.visibility = if (e.dateNeedsConfirm && e.date != null) View.VISIBLE else View.GONE
        c.start.text = e.start?.toString().orEmpty()
        c.end.text = e.end?.toString().orEmpty()
        c.allDay.isChecked = e.allDay
        flags(c.titleFlags, Field.TITLE)
        flags(c.dateFlags, Field.DATE)
        flags(c.timeFlags, Field.TIME)
        source(c.titleSource, e.titleSourceLine)
        source(c.dateSource, e.dateSourceLine)
        source(c.timeSource, e.timeSourceLine)
        source(c.locationSource, e.locationSourceLine)
        chips(c.dateChips, e.dateChips, "date_chip") { chip -> session()?.tapDateChip(e.id, chip); viewModel.changed(); highlightLine(chip.line) }
        chips(c.timeChips, e.timeChips, "time_chip") { chip -> session()?.tapTimeChip(e.id, chip); viewModel.changed(); highlightLine(chip.line) }
        // Model suggestions: only a tap puts one in a field. Text being typed is committed first, so a tap is the user's last word.
        suggestions(c.titleSuggestions, e.suggestedTitles, "title_suggestion") { text ->
            commitAllText()
            c.title.clearFocus()
            session()?.acceptSuggestedTitle(e.id, text)
            // clearFocus can hand focus straight back to this field (the first focusable one), and bind() leaves a focused
            // field alone, so the old text stayed and Add to calendar committed it over the suggestion. Write it here.
            programmatic = true
            c.title.setText(text)
            programmatic = false
            viewModel.changed()
        }
        // The chip says the phrase and the date it means, so the arithmetic can be checked before the tap.
        fun dateLabel(chip: Chip) = "${chip.label} (${dateFormat.format(LocalDate.parse(chip.value))})"
        suggestions(c.dateSuggestions, e.suggestedDates.map(::dateLabel), "date_suggestion") { label ->
            e.suggestedDates.firstOrNull { dateLabel(it) == label }?.let { chip ->
                session()?.acceptSuggestedDate(e.id, chip)
                viewModel.changed()
                highlightLine(chip.line)
            }
        }
        // Try harder's time: the words it read and the time code made of them
        fun timeLabel(chip: Chip) = "${chip.label} (${chip.value}${chip.end?.let { "-$it" } ?: ""})"
        suggestions(c.timeSuggestions, e.suggestedTimes.map(::timeLabel), "time_suggestion") { label ->
            e.suggestedTimes.firstOrNull { timeLabel(it) == label }?.let { chip ->
                session()?.acceptSuggestedTime(e.id, chip)
                viewModel.changed()
                highlightLine(chip.line)
            }
        }
        c.addToCalendar.isEnabled = e.canAddToCalendar
        c.addToCalendar.setText(if (e.saved) R.string.added else R.string.add_to_calendar)
        c.addNote.visibility = View.VISIBLE
        c.addNote.text = when {
            e.saved -> getString(R.string.note_opened_calendar)
            e.addFailed -> getString(R.string.note_add_failed)
            e.date == null -> getString(R.string.note_pick_date)
            e.dateNeedsConfirm -> getString(R.string.note_confirm_date)
            else -> ""
        }
        if (c.addNote.text.isEmpty()) c.addNote.visibility = View.GONE
        programmatic = false
    }

    private fun chips(row: LinearLayout, chips: List<Chip>, tagPrefix: String, onTap: (Chip) -> Unit) {
        if (row.childCount == chips.size && chips.indices.all { (row.getChildAt(it) as Button).text == chips[it].label }) return
        row.removeAllViews()
        chips.forEachIndexed { i, chip ->
            row.addView(Button(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = chip.label
                isAllCaps = false
                tag = "${tagPrefix}_$i"
                setOnClickListener { onTap(chip) }
            })
        }
    }

    private fun suggestions(row: LinearLayout, labels: List<String>, tagPrefix: String, onTap: (String) -> Unit) {
        row.visibility = if (labels.isEmpty()) View.GONE else View.VISIBLE
        val texts = labels.map { getString(R.string.suggested_chip, it) }
        if (row.childCount == texts.size && texts.indices.all { (row.getChildAt(it) as Button).text == texts[it] }) return
        row.removeAllViews()
        labels.forEachIndexed { i, label ->
            row.addView(Button(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = texts[i]
                isAllCaps = false
                tag = "${tagPrefix}_$i"
                setOnClickListener { onTap(label) }
            })
        }
    }

    // ---- editing ----

    private fun session() = ready?.session

    /**
     * Key taps per keyboard step ([TypingCounter]: the field's text diffed once per keyboard batch, so a composing rewrite
     * counts only what changed); the strip follows each step. Text the app sets itself counts nothing.
     */
    private fun watchTyping(view: CountingEditText, id: Int, field: String) {
        val counter = counters.getOrPut(id to field) { TypingCounter(view.text.toString()) }
        if (typingTrace != null) counter.onStep = { st ->
            trace("$field step: '${st.old}' -> '${st.new}'  removed '${st.removed}' inserted '${st.inserted}'  taps ${st.taps} letters ${st.letters}")
        }
        view.onBatch = { begin ->
            if (typingTrace != null) trace("$field batch ${if (begin) "begin" else "end"}")
            if (begin) counter.beginBatch() else { counter.endBatch(view.text.toString()); session()?.let(::bindTapsSaved) }
        }
        view.addTextChangedListener(object : TextWatcher {
            private var replaced = ""
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                replaced = s?.subSequence(start, start + count)?.toString().orEmpty()
            }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (typingTrace == null || programmatic || s == null) return
                val put = s.subSequence(start, start + count).toString()
                trace("$field change at $start: '$replaced' -> '$put'  text now '$s'  old rule taps ${oldRuleTaps(replaced, put)}")
            }
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                if (field == "title") (cards[id]?.clearTitle)?.visibility = if (text.isEmpty()) View.INVISIBLE else View.VISIBLE
                if (programmatic) { counter.setByApp(text); return }
                counter.changed(text)
                session()?.let(::bindTapsSaved)
            }
        })
    }

    /** The trace only: what the previous counting gave for one raw change (prefix-only diff of the replaced segment). */
    private fun oldRuleTaps(old: String, new: String): Int {
        var same = 0
        while (same < old.length && same < new.length && old[same] == new[same]) same++
        val added = new.length - same
        val removed = old.length - same
        return when { removed == 0 -> added; added == 0 -> removed; else -> maxOf(1, added - removed) }
    }

    /** A text field's change counts as one edit when it is committed (focus lost, delete, add, leaving the screen). */
    private fun commitText(id: Int, field: String, view: EditText) {
        val s = session() ?: return
        val e = runCatching { s.event(id) }.getOrNull() ?: return
        val text = view.text.toString()
        val old = if (field == "title") e.title.orEmpty() else e.location.orEmpty()
        val (taps, letters) = counters[id to field]?.take() ?: (0 to 0)
        if (text == old) {
            // typed in and back to where it started: the taps were made, the field is not edited
            if (taps > 0) { s.countRevertedTyping(id, taps, letters); viewModel.changed() }
            return
        }
        if (field == "title") s.editTitle(id, text, taps, letters) else s.editLocation(id, text, taps, letters)
        viewModel.changed()
    }

    private fun commitAllText() {
        for ((id, c) in cards) {
            commitText(id, "title", c.title)
            commitText(id, "location", c.location)
        }
    }

    private fun pickDate(id: Int) {
        val d = session()?.event(id)?.date ?: LocalDate.now()
        DatePickerDialog(this, { _, y, m, day ->
            val picked = LocalDate.of(y, m + 1, day)
            session()?.setDate(id, picked)
            // the day, OK, and the months the picker moved from the date it opened on
            session()?.event(id)?.counts?.let { it.pickerTouches += `in`.gauthama.snapslotify.stats.TapsSaved.formDatePick(d, picked) }
            viewModel.changed()
        },
            d.year, d.monthValue - 1, d.dayOfMonth).show()
    }

    private fun pickTime(id: Int, start: Boolean) {
        val e = session()?.event(id) ?: return
        val t = (if (start) e.start else e.end) ?: e.start ?: LocalTime.of(10, 0)
        val clock24 = android.text.format.DateFormat.is24HourFormat(this)
        TimePickerDialog(this, { _, h, m ->
            val v = LocalTime.of(h, m)
            if (start) session()?.setStartTime(id, v) else session()?.setEndTime(id, v)
            session()?.event(id)?.counts?.let { it.pickerTouches += `in`.gauthama.snapslotify.stats.TapCosts.FORM_TIME_PICKER - 1 - (if (clock24) `in`.gauthama.snapslotify.stats.TapCosts.TIME_AM_PM else 0) }
            viewModel.changed()
        }, t.hour, t.minute, clock24).show()
    }

    private fun addToCalendar(id: Int) {
        commitAllText()
        val s = session() ?: return
        if (!s.event(id).canAddToCalendar) return
        val first = s.events.none { it.saved }
        if (!s.add(id, ::openCalendar)) Toast.makeText(this, R.string.no_calendar_app, Toast.LENGTH_LONG).show()
        else { viewModel.countAdded(listOf(s.event(id)), first); viewModel.confirmPending = true }
        viewModel.changed()
    }

    /**
     * Add all: each event not added yet through the same [openCalendar] as its own Add, one editor at a time, first event
     * first; the next opens when the user comes back from the calendar. One failing does not stop the rest. Tapping it
     * again part way opens the next one at once.
     */
    private fun addAll() {
        commitAllText()
        val s = session() ?: return
        if (viewModel.addAllRunning) { addNext(); return }
        if (!s.canAddAll) return
        // more than one calendar app: ask once, so the run does not ask again for every event
        val apps = calendarApps()
        if (apps.size <= 1) {
            viewModel.startAddAll(s.toAdd.map { it.id }, null)
            addNext()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.add_all_pick_calendar)
            .setAdapter(calendarAppsAdapter(apps)) { _, which ->
                val now = session() ?: return@setAdapter
                if (!now.canAddAll || viewModel.addAllRunning) return@setAdapter
                viewModel.startAddAll(now.toAdd.map { it.id }, apps[which].first)
                addNext()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * The calendar apps that take the insert intent, one per app: package and the name shown. Two apps both called
     * "Calendar" get their maker from the package name: "Calendar (Google)", "Calendar (Xiaomi)".
     */
    private fun calendarApps(): List<Pair<String, String>> {
        val apps = packageManager.queryIntentActivities(CalendarIntent.probe(), 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .distinctBy { it.first }
        val clash = apps.groupingBy { it.second }.eachCount()
        return apps.map { (pkg, label) ->
            val maker = pkg.split('.').let { p -> p.getOrNull(if (p.firstOrNull() in setOf("com", "org", "net", "in")) 1 else 0) }
                ?.replaceFirstChar { it.uppercase() }
            pkg to if ((clash[label] ?: 0) > 1 && maker != null) "$label ($maker)" else label
        }
    }

    /** The app picker's rows: each app's icon before its name. */
    private fun calendarAppsAdapter(apps: List<Pair<String, String>>) =
        object : android.widget.ArrayAdapter<String>(this, android.R.layout.select_dialog_item, apps.map { it.second }) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                val icon = runCatching { packageManager.getApplicationIcon(apps[position].first) }.getOrNull()
                val size = (32 * resources.displayMetrics.density).toInt()
                icon?.setBounds(0, 0, size, size)
                v.setCompoundDrawablesRelative(icon, null, null, null)
                v.compoundDrawablePadding = (16 * resources.displayMetrics.density).toInt()
                return v
            }
        }

    private fun addNext() {
        val s = session() ?: return
        leftForEditor = false
        while (true) {
            val id = viewModel.nextToAdd() ?: break
            if (!s.event(id).canAddToCalendar) continue
            val first = s.events.none { it.saved }
            if (s.add(id, ::openCalendar)) {
                viewModel.countAdded(listOf(s.event(id)), first)
                // the last one: the run ends now, its result shows when the user comes back
                if (viewModel.addAllLeft == 0) { viewModel.finishAddAll(s); viewModel.confirmPending = true }
                viewModel.changed()
                return
            }
        }
        viewModel.finishAddAll(s)
        viewModel.changed()
    }

    /** The calendar app's insert editor for one event; false when no app takes it. Used by Add and Add all alike. */
    private fun openCalendar(values: CalendarValues): Boolean = try {
        startActivity(CalendarIntent.insert(values, pkg = if (viewModel.addAllRunning) viewModel.addAllPackage else null))
        true
    } catch (ex: ActivityNotFoundException) {
        false
    }

    // ---- source-line highlight ----

    private fun highlight(field: Field, id: Int) {
        val e = session()?.events?.firstOrNull { it.id == id } ?: return
        highlightLine(when (field) {
            Field.TITLE -> e.titleSourceLine
            Field.DATE -> e.dateSourceLine
            Field.TIME -> e.timeSourceLine
            Field.LOCATION -> e.locationSourceLine
        })
    }

    private fun highlightLine(line: Int?) {
        val r = ready
        val l = line?.let { r?.result?.cleaned?.kept?.getOrNull(it) }
        // a page photographed turned was read from the image turned upright, but is shown as taken: turn the box back;
        // rows that were levelled (a tilted or curled page, PageBend) are put back where they are in the photo first
        binding.image.highlight(l?.let {
            val (bl, bt, br, bb) = r!!.result.bend?.photographed(it.left, it.top, it.right, it.bottom) ?: intArrayOf(it.left, it.top, it.right, it.bottom)
            val (left, top, right, bottom) = PageTurn.boxInImage(bl, bt, br, bb, r.turn, r.ocr.imageWidth, r.ocr.imageHeight,
                r.bitmap?.width ?: 0, r.bitmap?.height ?: 0)
            RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        })
    }

    /** Report a problem about this page: what the app showed, the screenshot and the text read, held in memory only. */
    private fun openReport() {
        commitAllText()
        val r = ready
        val snapshot = r?.let {
            val shown = it.session.events.filterNot { e -> e.deleted }.map { e ->
                `in`.gauthama.snapslotify.report.ProblemReport.Shown(e.title, e.dateIso, if (e.allDay) "all day" else e.startIso)
            }
            val flags = it.session.events.filterNot { e -> e.deleted }.flatMap { e -> e.flags.map { f -> f.code } }.distinct()
            val page = `in`.gauthama.snapslotify.report.ProblemReport.Page(
                it.result.route?.chosen?.name?.lowercase(), flags, shown, it.ocr.lines.joinToString("\n") { l -> l.text })
            `in`.gauthama.snapslotify.report.ReportSource.Snapshot(page, it.bitmap)
        }
        `in`.gauthama.snapslotify.report.ReportProblemActivity.open(this, snapshot)
    }

    companion object {
        private const val MENU_REPORT = 1
        const val EXTRA_TAP_TIME = "tap_time"
        const val EXTRA_DEBUG_LABEL = "debug_label"
        const val EXTRA_DEBUG_TIMEOUT = "debug_timeout_ms"
        const val EXTRA_DEBUG_SHARE_TAG = "debug_share_tag"
        const val EXTRA_DEBUG_TYPING_TRACE = "debug_typing_trace"
        const val OCR_JSON_VIEW_TAG = "ocr_json_view"
    }
}
