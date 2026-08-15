package dev.th7bo.sidequest.platform.minecraft

import net.dimaskama.mcef.api.MCEFApi
import net.dimaskama.mcef.api.MCEFBrowser

/**
 * The only file in the mod that names MCEF's classes.
 *
 * Split from [EmbeddedBrowsers] deliberately: reaching any member of this object loads it, and loading it
 * links against `net.dimaskama.mcef.*`. On a client without the browser mod that link fails, so **every path
 * here must be behind [EmbeddedBrowsers.isInstalled]**, and keeping the check and the types in separate
 * files is what makes that a rule the compiler cannot help anybody break by accident.
 */
internal object Mcef {

    /** Triggers the asynchronous download-and-unpack. Idempotent; MCEF returns the same handle. */
    fun begin() {
        MCEFApi.initialize()
    }

    /** MCEF's own progress, translated into vocabulary that does not mention MCEF. */
    fun startup(): EmbeddedBrowsers.Startup {
        val initialization = MCEFApi.initialize()
        val future = initialization.future
        if (future.isCompletedExceptionally) {
            // Asked without blocking. `join` on a failed future throws, which is the answer we want, but
            // `join` on an *unfinished* one would block the client thread for the length of a download.
            val reason = runCatching { future.join() }.exceptionOrNull()?.message ?: "initialisation failed"
            return EmbeddedBrowsers.Startup.Failed(reason)
        }
        if (initialization.isDone) return EmbeddedBrowsers.Startup.Ready

        val percent = initialization.percentage
        return EmbeddedBrowsers.Startup.Working(
            stage = initialization.stage.readable(),
            // MCEF reports -1 for a stage it cannot measure, which is a real answer and not a percentage.
            percent = percent.takeIf { it >= 0f },
        )
    }

    /**
     * A browser showing [url], or null if one could not be made.
     *
     * Opaque, transparent-background off: the page is drawn over the game and a transparent browser would
     * let the world show through the stats.
     */
    fun create(url: String, width: Int, height: Int): MCEFBrowser? = runCatching {
        MCEFApi.getInstance().createBrowser(url, false).also {
            // MCEF's own documentation is explicit that this must happen immediately after creation.
            it.resize(width.coerceAtLeast(1), height.coerceAtLeast(1))
        }
    }.getOrNull()

    /** MCEF's stage names are enum constants; these are what a person reads on a loading screen. */
    private fun MCEFApi.Initialization.Stage.readable(): String = when (this) {
        MCEFApi.Initialization.Stage.NOT_STARTED -> "Starting"
        MCEFApi.Initialization.Stage.DOWNLOADING -> "Downloading Chromium"
        MCEFApi.Initialization.Stage.EXTRACTING -> "Extracting"
        MCEFApi.Initialization.Stage.INSTALL -> "Installing"
        MCEFApi.Initialization.Stage.INITIALIZING -> "Starting Chromium"
        MCEFApi.Initialization.Stage.DONE -> "Ready"
    }
}
