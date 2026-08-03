package dev.th7bo.sidequest.platform.minecraft

import dev.th7bo.sidequest.platform.text.ClickAction
import dev.th7bo.sidequest.platform.text.ClickActionType
import dev.th7bo.sidequest.platform.text.SqStyle
import dev.th7bo.sidequest.platform.text.SqText
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor

/**
 * Translation between [SqText] and Minecraft components.
 *
 * Both directions matter. Outward so a feature can build a message without importing a
 * game class; inward so the chat parser sees structure — the command behind a party
 * invite, the lore behind a hover — rather than the flattened string that throws away
 * exactly what makes matching reliable.
 */
fun SqText.toMinecraft(): Component {
    val component: MutableComponent = Component.literal(content).setStyle(style.toMinecraft(clickAction))
    for (child in children) component.append(child.toMinecraft())
    return component
}

private fun SqStyle.toMinecraft(click: ClickAction?): Style {
    var style = Style.EMPTY
        .withBold(bold)
        .withItalic(italic)
        .withUnderlined(underlined)
        .withStrikethrough(strikethrough)
        .withObfuscated(obfuscated)

    color?.let { style = style.withColor(TextColor.fromRgb(it and 0xFFFFFF)) }

    // Only the click actions the game still accepts from a client-built component are
    // set. The rest survive the round trip as data without being made live, which is
    // what a parser needs and what a chat message has no business doing.
    click?.let { action ->
        when (action.type) {
            ClickActionType.RUN_COMMAND ->
                style = style.withClickEvent(ClickEvent.RunCommand(action.value))
            ClickActionType.SUGGEST_COMMAND ->
                style = style.withClickEvent(ClickEvent.SuggestCommand(action.value))
            ClickActionType.COPY_TO_CLIPBOARD ->
                style = style.withClickEvent(ClickEvent.CopyToClipboard(action.value))
            ClickActionType.OPEN_URL, ClickActionType.CHANGE_PAGE, ClickActionType.OTHER -> Unit
        }
    }
    return style
}

/**
 * Reads a component into [SqText].
 *
 * Siblings become children and the component's own content becomes this node's, which
 * preserves the run boundaries. Flattening here would be convenient and would throw away
 * the styling the parsers key off — a rarity colour is often the only thing separating a
 * drop message from an ordinary one.
 */
fun Component.toSq(): SqText = SqText(
    content = plainContent(),
    style = style.toSq(),
    children = siblings.map { it.toSq() },
    clickAction = style.clickEvent?.toSq(),
)

/**
 * Renders a component back into legacy `§` codes.
 *
 * The chat parsers match against this, and it has to be the game doing the colour lookup
 * rather than the platform: which `§` code a given colour renders as is Minecraft's table,
 * and a copy of it in `platform-api` would be a copy that goes stale. The platform gets a
 * string and never learns where the codes came from.
 *
 * A colour with no legacy equivalent — Hypixel does send RGB text — is emitted as nothing
 * rather than as an approximation. A pattern keying off a specific code would be wrong
 * either way, and a made-up code is the one that looks right in a log.
 */
fun Component.toLegacyFormatting(): String = buildString {
    for (part in flatten()) {
        val style = part.style
        style.color?.let { colour ->
            LEGACY_COLOURS[colour.value]?.let { append('§').append(it) }
        }
        if (style.isBold) append("§l")
        if (style.isItalic) append("§o")
        if (style.isUnderlined) append("§n")
        if (style.isStrikethrough) append("§m")
        if (style.isObfuscated) append("§k")
        append(part.plainContent())
    }
}

/** This component and its siblings, depth first, each carrying its own style. */
internal fun Component.flatten(): List<Component> =
    listOf(this) + siblings.flatMap { it.flatten() }

/**
 * The styled runs this component is made of, in reading order.
 *
 * The same walk [flatten] does, named for what callers outside this file want it for: anything rebuilding a
 * component while changing one piece of it needs the runs, not the flattened string.
 */
internal fun Component.runs(): List<Component> = flatten()

/**
 * This component's own text, without its siblings' contributions.
 *
 * `string` includes the siblings, so the run's own text is what is left once they are taken
 * off the end. Keeping the run boundaries is the point of both conversions: flattening would
 * throw away the styling that separates a drop message from somebody quoting one.
 */
internal fun Component.plainContent(): String {
    val full = string
    val childText = siblings.joinToString("") { it.string }
    return if (childText.isNotEmpty() && full.endsWith(childText)) full.dropLast(childText.length) else full
}

/**
 * The sixteen legacy colour codes, which are a property of the `§` format itself.
 *
 * What each one *means* is not assumed — the game is asked, below.
 */
private const val LEGACY_COLOUR_CODES = "0123456789abcdef"

/**
 * RGB to legacy code, resolved through the game.
 *
 * Both halves come from Minecraft: [ChatFormatting.getByCode] says what a code is and
 * [TextColor.fromLegacyFormat] says what colour it renders as. Nothing here is a copied
 * table, so a colour Mojang adjusts adjusts here with it — and the two supported versions
 * disagree about the rest of `ChatFormatting`'s surface, which is reason enough to depend on
 * as little of it as possible.
 */
private val LEGACY_COLOURS: Map<Int, Char> = buildMap {
    for (code in LEGACY_COLOUR_CODES) {
        val formatting = ChatFormatting.getByCode(code) ?: continue
        val colour = TextColor.fromLegacyFormat(formatting) ?: continue
        putIfAbsent(colour.value, code)
    }
}

private fun Style.toSq(): SqStyle = SqStyle(
    color = color?.value,
    bold = isBold,
    italic = isItalic,
    underlined = isUnderlined,
    strikethrough = isStrikethrough,
    obfuscated = isObfuscated,
)

private fun ClickEvent.toSq(): ClickAction = when (this) {
    is ClickEvent.RunCommand -> ClickAction(ClickActionType.RUN_COMMAND, command())
    is ClickEvent.SuggestCommand -> ClickAction(ClickActionType.SUGGEST_COMMAND, command())
    is ClickEvent.CopyToClipboard -> ClickAction(ClickActionType.COPY_TO_CLIPBOARD, value())
    is ClickEvent.OpenUrl -> ClickAction(ClickActionType.OPEN_URL, uri().toString())
    else -> ClickAction(ClickActionType.OTHER, toString())
}
