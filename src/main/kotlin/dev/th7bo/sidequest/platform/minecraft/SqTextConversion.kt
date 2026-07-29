package dev.th7bo.sidequest.platform.minecraft

import dev.th7bo.sidequest.platform.text.ClickAction
import dev.th7bo.sidequest.platform.text.ClickActionType
import dev.th7bo.sidequest.platform.text.SqStyle
import dev.th7bo.sidequest.platform.text.SqText
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
    content = this.string.let { full ->
        // `string` includes siblings; the run's own text is what is left once the
        // children's contributions are removed.
        val childText = siblings.joinToString("") { it.string }
        if (childText.isNotEmpty() && full.endsWith(childText)) full.dropLast(childText.length) else full
    },
    style = style.toSq(),
    children = siblings.map { it.toSq() },
    clickAction = style.clickEvent?.toSq(),
)

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
