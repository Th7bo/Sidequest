package dev.th7bo.sidequest.platform.minecraft

import net.fabricmc.loader.api.FabricLoader

/**
 * Whether this client can show a web page, and how far along it is towards being able to.
 *
 * **Optional.** The browser comes from the `mcef-modern` mod, exactly like the Hypixel Mod API classes come
 * from `hypixel-mod-api` — see [HypixelModApiSource]. Nobody is made to install a couple of hundred
 * megabytes of Chromium to use the waypoints, so everything here degrades to "not available" and the profile
 * viewer falls back to the player's real browser.
 *
 * **No MCEF type appears anywhere in this file's signatures, and that is the whole design.** A class is
 * verified when it is first loaded, and a signature naming an absent class fails that verification — so an
 * innocent-looking `fun browser(): MCEFBrowser?` would turn "MCEF is not installed" from a supported state
 * into a `NoClassDefFoundError` at the moment somebody asks whether it is installed. Everything that
 * actually touches MCEF lives in [Mcef], which is only ever reached through [isInstalled].
 */
object EmbeddedBrowsers {

    /** The mod that provides the browser. */
    const val MOD_ID: String = "mcef-modern"

    /** Whether the browser mod is present. Checked before any of its classes are touched. */
    val isInstalled: Boolean by lazy { FabricLoader.getInstance().isModLoaded(MOD_ID) }

    /**
     * Starts Chromium coming up, if it is not already.
     *
     * Safe to call repeatedly — MCEF's own initialisation is idempotent. Worth calling early, because the
     * first call on a fresh install downloads and unpacks a large native bundle, and doing that when
     * somebody opens a profile means staring at a progress bar instead of at stats.
     */
    fun beginStartup() {
        if (!isInstalled) return
        runCatching { Mcef.begin() }
    }

    /**
     * How far along startup is.
     *
     * Reported in the mod's own vocabulary rather than MCEF's, so that this signature stays free of MCEF
     * types and a caller without the mod gets [Startup.Absent] instead of a linkage error.
     */
    fun startup(): Startup {
        if (!isInstalled) return Startup.Absent
        return runCatching { Mcef.startup() }.getOrElse { Startup.Failed(it.message ?: "unknown") }
    }

    /** Whether a browser can be created right now. */
    val isReady: Boolean get() = startup() is Startup.Ready

    /** What state the embedded browser is in, in terms that do not mention MCEF. */
    sealed interface Startup {

        /** The mod is not installed. The normal state, and not an error. */
        data object Absent : Startup

        /**
         * Chromium is coming up.
         *
         * @param stage what it is doing, in MCEF's own words.
         * @param percent 0..100, or null when the stage has no measurable progress.
         */
        data class Working(val stage: String, val percent: Float?) : Startup

        /** Ready to create browsers. */
        data object Ready : Startup

        /** It tried and could not. */
        data class Failed(val reason: String) : Startup
    }
}
