package `in`.gauthama.snapslotify.image

import java.io.File

/**
 * The temporary file a photo from the system camera is written to, in app-private cache/camera/. No Android types, so
 * the rules can be tested without a phone. The file lasts only until the photo is decoded: nothing keeps a copy.
 */
object CameraCapture {

    private const val DIR = "camera"
    private val NAME = Regex("""capture_\d+\.jpg""")
    private const val STALE_MS = 60 * 60 * 1000L

    fun newFile(cacheDir: File, now: Long = System.currentTimeMillis()): File {
        val dir = File(cacheDir, DIR).apply { mkdirs() }
        return File(dir, "capture_$now.jpg").apply { createNewFile() }
    }

    /**
     * The camera came back. Returns the photo to open, or null to stay on the screen with no error, having removed any
     * file: the user cancelled, the camera wrote nothing, or [pending] is null because the app was killed while the
     * camera was open and the file's path did not survive.
     */
    fun finish(success: Boolean, pending: File?): File? {
        if (pending == null) return null
        if (success && pending.isFile && pending.length() > 0) return pending
        pending.delete()
        return null
    }

    /**
     * The capture file behind a URI, or null for anything else. Only this app's provider, this folder and this name
     * pattern match, so an image shared in by another app can never be deleted through the review screen.
     */
    fun fileFor(cacheDir: File, uriAuthority: String?, ownAuthority: String, uriPath: String?): File? {
        if (uriAuthority != ownAuthority || uriPath == null) return null
        val parts = uriPath.trim('/').split('/')
        if (parts.size != 2 || parts[0] != DIR || !NAME.matches(parts[1])) return null
        return File(File(cacheDir, DIR), parts[1])
    }

    /** Removes photos left behind by a process that died before reading them. [keep] may still be in use by the camera. */
    fun deleteStale(cacheDir: File, keep: File?, now: Long = System.currentTimeMillis()) {
        File(cacheDir, DIR).listFiles()?.forEach { f ->
            if (f.path != keep?.path && now - f.lastModified() > STALE_MS) f.delete()
        }
    }
}
