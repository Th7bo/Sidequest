package dev.th7bo.sidequest.ui.components

import dev.th7bo.sidequest.ui.core.icon.IconRegistry
import dev.th7bo.sidequest.ui.extension.RegistrationScope
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.rendering.Icon
import dev.th7bo.sidequest.ui.rendering.TextureRef

/**
 * The icons the standard library ships.
 *
 * Ids only — what they resolve to is the registry's business, so a resource pack or a
 * third-party module can substitute any of them without this file changing.
 *
 * An id like `sidequest:gui.icon.gear` resolves to
 * `assets/sidequest/textures/gui/icon/gear.png`.
 */
public object Icons {

    private fun icon(name: String): Icon = Icon(UiId.of("sidequest", "gui.icon.$name"))

    public val gear: Icon = icon("gear")
    public val sliders: Icon = icon("sliders")
    public val bell: Icon = icon("bell")
    public val monitor: Icon = icon("monitor")
    public val wrench: Icon = icon("wrench")
    public val palette: Icon = icon("palette")
    public val eye: Icon = icon("eye")
    public val search: Icon = icon("search")

    /** Every icon this object exposes, in declaration order. */
    public val all: List<Icon> = listOf(gear, sliders, bell, monitor, wrench, palette, eye, search)
}

/**
 * Registers the standard icon set as textures.
 *
 * Owned by [scope], so a host that wants different art disposes this and registers its
 * own against the same ids.
 */
public fun IconRegistry.registerStandardIcons(scope: RegistrationScope) {
    for (icon in Icons.all) {
        registerTexture(scope, icon.id, TextureRef(icon.id))
    }
}
