package dev.th7bo.sidequest.ui.minecraft.lifecycle

import dev.th7bo.sidequest.Sidequest
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManager

/**
 * Drops cached text layouts when the client's resources reload.
 *
 * A resource pack can replace the font, which changes every glyph width. Without this,
 * a screen open across a reload keeps the measurements it took for the old font, and
 * every label stays laid out to the wrong width until something else invalidates it.
 *
 * Synchronous and client-only: clearing a cache is trivial work, and doing it on the
 * client thread means the next frame already sees the new metrics.
 */
public object FontReloadListener : SimpleSynchronousResourceReloadListener {

    private val listeners = ArrayList<() -> Unit>()

    private val identifier: Identifier =
        Identifier.fromNamespaceAndPath(Sidequest.MOD_ID, "font_cache")

    override fun getFabricId(): Identifier = identifier

    /**
     * Registers [action], invoked on the client thread after every resource reload.
     *
     * The returned handle removes it again — a screen registers when shown and closes
     * the handle when removed, so a closed screen's caches are not kept alive by this
     * long-lived object.
     */
    public fun onReload(action: () -> Unit): AutoCloseable {
        listeners.add(action)
        return AutoCloseable { listeners.remove(action) }
    }

    override fun onResourceManagerReload(resourceManager: ResourceManager) {
        // Snapshot: a listener may unregister itself while reacting.
        for (listener in listeners.toList()) {
            runCatching(listener).onFailure {
                Sidequest.logger.error("A font-reload listener failed", it)
            }
        }
    }

    /** Hooks into the client resource pipeline. Called once at startup. */
    public fun register() {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(this)
    }
}
