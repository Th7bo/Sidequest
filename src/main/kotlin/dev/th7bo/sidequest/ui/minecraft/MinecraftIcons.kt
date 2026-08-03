package dev.th7bo.sidequest.ui.minecraft

import dev.th7bo.sidequest.ui.core.icon.IconRegistry
import dev.th7bo.sidequest.ui.extension.RegistrationScope
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.Icon

/**
 * Icons drawn from Minecraft's own item textures.
 *
 * The mod's own glyphs are flat monochrome shapes, which look like a settings screen from somewhere else. A
 * diamond, a bell and an ender eye are what this game's interface is made of, and using them is most of what
 * makes an added screen feel like part of it rather than a window bolted on.
 *
 * **No new rendering was needed for this.** The framework's texture path already resolves a [UiId] to
 * `assets/<namespace>/textures/<path>.png`, so `minecraft:item.diamond` finds
 * `assets/minecraft/textures/item/diamond.png` — vanilla's own file, at its own resolution, from whatever
 * resource pack the player has on. A texture pack that restyles diamonds restyles this screen too, which is
 * exactly the "home feeling" and is a property of using their files rather than copying them.
 *
 * They also draw **untinted**, because the blit takes no colour. That is right here: an item texture recoloured
 * to the interface accent looks like a rendering bug rather than a choice.
 *
 * Every id below is checked against the game jar rather than remembered. A missing texture draws the
 * registry's hollow-square placeholder, which is deliberately unlike any real icon — so a wrong path shows up
 * as obviously wrong rather than as a slightly odd picture.
 */
public object MinecraftIcons {

    private fun item(name: String): Icon = Icon(UiId.of("minecraft", "item.$name"))

    // -- the config's categories --------------------------------------------

    /** General. A comparator is the game's own "settings" object. */
    public val settings: Icon = item("comparator")

    /** Features. */
    public val features: Icon = item("redstone")

    /** Network. An ender eye points somewhere far away, which is what a backend is. */
    public val network: Icon = item("ender_eye")

    /** Advanced and diagnostics. */
    public val tools: Icon = item("stick")

    // -- sections ------------------------------------------------------------

    public val appearance: Icon = item("painting")

    /**
     * Serious mode. A barrier is the clearest "stop" the game has.
     *
     * `item/barrier`, not `block/barrier`: the block has no texture of its own, which is the sort of thing
     * that is obvious once checked against the jar and easy to get wrong from memory.
     */
    public val quiet: Icon = item("barrier")

    public val notifications: Icon = item("bell")

    public val sound: Icon = item("music_disc_cat")

    /** Cinematics. */
    public val cinematics: Icon = item("spyglass")

    /** Rare drops. */
    public val rareDrop: Icon = item("diamond")

    public val cosmetics: Icon = item("totem_of_undying")

    /**
     * Playtime.
     *
     * `clock_00` rather than `clock`, because **there is no `item/clock.png`** — the clock is animated and
     * ships as sixteen numbered frames. Referencing the obvious name draws the missing-texture chequerboard,
     * which is only obvious after checking the jar. Frame zero is the face at noon.
     */
    public val playtime: Icon = item("clock_00")

    /** The title screen's nebula. A nether star is the closest the game has to one. */
    public val titleScreen: Icon = item("nether_star")

    /** SkyBlock levels, which are drawn on a nametag. */
    public val levels: Icon = item("name_tag")

    /** The Garden. */
    public val garden: Icon = item("wheat")

    /** Waypoints. A filled map, because an empty one is a blank sheet. */
    public val waypoints: Icon = item("filled_map")

    /** Every icon here, for the registration below and for a gallery. */
    public val all: List<Icon> = listOf(
        settings, features, network, tools,
        appearance, quiet, notifications, sound, cinematics, rareDrop, cosmetics,
        playtime, titleScreen, levels, garden, waypoints,
    )
}

/**
 * Registers Minecraft's textures under their own ids.
 *
 * Owned by [scope], like the standard set, so a host that wants different art disposes this and registers its
 * own against the same ids.
 */
public fun IconRegistry.registerMinecraftIcons(scope: RegistrationScope) {
    for (icon in MinecraftIcons.all) {
        registerTexture(scope, icon.id, dev.th7bo.sidequest.ui.rendering.TextureRef(icon.id))
    }
}
