package dev.th7bo.sidequest.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull
import java.util.Locale
import kotlin.math.floor
import kotlin.math.pow

/**
 * The Heart of the Mountain and Heart of the Forest, as the meowdding repository describes them.
 *
 * **Why this repository and not NotEnoughUpdates.** Hypixel revamped the Heart of the Forest — an eighth
 * tier and seven new perks — and that repository had not caught up. This one had, which is why the profile
 * viewer built on it draws the tree correctly. It is also the better description: a perk here carries the
 * currency it costs, an explicit cost formula, and its reward as a formula rather than a pre-rendered
 * string, so the tooltip can be built for the level a player actually has.
 *
 * Every formula is ordinary infix arithmetic — `floor((level + 1)^3.2)` — which is a smaller thing to read
 * than the parenthesised dialect the other repository writes.
 */
internal object SkillTreeRepo {

    const val BASE_URL: String = "https://repo.owdding.me"

    /** Where each tree lives, and the name Hypixel keys its nodes under. */
    enum class Tree(val id: String, val displayName: String, val path: String, val core: String) {
        MINING("mining", "Heart of the Mountain", "mining/hotm.json", "core_of_the_mountain"),
        FORAGING("foraging", "Heart of the Forest", "foraging/hotf.json", "center_of_the_forest"),
    }

    /** What a node costs to take one level further. */
    data class Cost(val currency: String, val kind: String, val amount: Long?)

    data class Node(
        val id: String,
        val name: String,
        val kind: String,
        val column: Int,
        val row: Int,
        val maxLevel: Int,
        /** `reward`, or the named formulas a tooltip interpolates by name. Unevaluated. */
        val rewards: Map<String, String>,
        /** Hypixel colour codes already; the repository writes tags and they are converted on read. */
        val tooltip: List<String>,
        val cost: Cost?,
        val costFormula: String?,
        /** For a core, the rewards unlocked at each of its levels, in order. */
        val levels: List<List<String>>,
    )

    class Layout(val nodes: List<Node>, val columns: Int, val rows: Int) {
        val byId: Map<String, Node> = nodes.associateBy { it.id }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Reads one tree.
     *
     * The two columns to the left of the grid are furniture — a tier label and a spacer — and are dropped:
     * they describe the menu's chrome rather than anything a player has. Everything else keeps the
     * repository's own coordinates, which run left to right and top to bottom exactly as the menu does.
     */
    fun parse(body: String): Layout? {
        val root = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull() ?: return null
        val nodes = root.mapNotNull { raw ->
            val entry = raw as? JsonObject ?: return@mapNotNull null
            val kind = entry.text("type")?.uppercase() ?: return@mapNotNull null
            if (kind == "TIER" || kind == "SPACER") return@mapNotNull null
            val id = entry.text("id") ?: return@mapNotNull null
            val location = (entry["location"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
            val column = location?.getOrNull(0) ?: return@mapNotNull null
            val row = location.getOrNull(1) ?: return@mapNotNull null
            if (column < 0) return@mapNotNull null

            val levels = (entry["level"] as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .map { level -> level["reward"].textLines().map(::toLegacy) }

            Node(
                id = id,
                name = entry.text("name") ?: id.words(),
                kind = kind,
                column = column,
                row = row,
                maxLevel = entry.number("max_level")?.toInt() ?: levels.size.takeIf { it > 0 } ?: 1,
                rewards = rewardFormulas(entry["reward_formula"]),
                tooltip = entry["tooltip"].textLines().map(::toLegacy),
                cost = (entry["cost"] as? JsonObject)?.let {
                    Cost(
                        currency = it.text("type").orEmpty(),
                        kind = it.text("kind").orEmpty(),
                        amount = it.number("amount")?.toLong(),
                    )
                },
                costFormula = entry.text("cost_formula"),
                levels = levels,
            )
        }
        if (nodes.isEmpty()) return null
        return Layout(nodes, nodes.maxOf { it.column } + 1, nodes.maxOf { it.row } + 1)
    }

    /**
     * A node's description at the level somebody has it.
     *
     * A core is different in shape from everything else: rather than one description with a number in it,
     * it has a list of what each of its levels unlocked, so the lines already taken are the ones shown.
     */
    fun describe(node: Node, variables: Map<String, Double>): List<String> {
        if (node.levels.isNotEmpty()) {
            val level = (variables["level"] ?: 0.0).toInt().coerceAtLeast(0)
            return node.levels.take(level).flatten()
        }
        val values = node.rewards.mapNotNull { (name, formula) ->
            Formula.evaluate(formula, variables)?.let { name to format(it) }
        }.toMap()

        return node.tooltip.mapNotNull { line ->
            var text = line
            for ((name, value) in values) text = text.replace("%$name%", value)
            if (PLACEHOLDER.containsMatchIn(text)) null else text
        }
    }

    /**
     * What the next level costs, worded the way the menu words it.
     *
     * The formula takes the *current* level and yields the price of the one after — the same convention the
     * other public implementation of this uses, and the two formulas agree term for term.
     */
    fun costOf(node: Node, level: Int): String? {
        val cost = node.cost ?: return null
        val amount = cost.amount
            ?: node.costFormula?.let { Formula.evaluate(it, mapOf("level" to level.toDouble())) }?.toLong()
            ?: return null
        if (amount <= 0L) return null
        val unit = when (cost.currency.uppercase()) {
            "POWDER" -> "Powder"
            "WHISPER" -> "Whispers"
            else -> cost.currency.words()
        }
        return "%,d %s %s".format(Locale.ROOT, amount, cost.kind.words(), unit)
    }

    private fun rewardFormulas(value: JsonElement?): Map<String, String> = when (value) {
        is JsonPrimitive -> mapOf("reward" to value.content)
        is JsonObject -> value.mapNotNull { (name, raw) -> (raw as? JsonPrimitive)?.content?.let { name to it } }.toMap()
        else -> emptyMap()
    }

    private fun format(value: Double): String =
        if (value == floor(value) && kotlin.math.abs(value) < 1e15) {
            "%,d".format(Locale.ROOT, value.toLong())
        } else {
            "%.2f".format(Locale.ROOT, value).trimEnd('0').trimEnd('.')
        }

    private fun JsonObject.text(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.content

    private fun JsonObject.number(name: String): Double? =
        (get(name) as? JsonPrimitive)?.let { it.longOrNull?.toDouble() ?: it.content.toDoubleOrNull() }

    /** A field that is sometimes one line and sometimes several. Both spellings appear in the same file. */
    private fun JsonElement?.textLines(): List<String> = when (this) {
        is JsonPrimitive -> listOf(content)
        is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.content }
        else -> emptyList()
    }

    private fun String.words(): String = lowercase().split('_').joinToString(" ") {
        it.replaceFirstChar(Char::uppercase)
    }

    private val PLACEHOLDER = Regex("%[a-zA-Z0-9_]+%")

    // -- colours ---------------------------------------------------------------

    /**
     * The repository writes colours as tags; Minecraft draws them as section codes.
     *
     * A closing tag restores whatever was in force around it rather than resetting outright, because the
     * lines nest — `<gray>Grants </gray><gold>…` reads wrong if the close drops back to no colour at all.
     * Tags this does not know are dropped rather than printed, so an unfamiliar one costs its colour and
     * never leaks angle brackets into a tooltip.
     */
    fun toLegacy(text: String): String {
        val out = StringBuilder()
        val open = ArrayDeque<String>()
        var index = 0
        while (index < text.length) {
            val start = text.indexOf('<', index)
            if (start < 0) { out.append(text, index, text.length); break }
            val end = text.indexOf('>', start + 1)
            if (end < 0) { out.append(text, index, text.length); break }
            out.append(text, index, start)
            val tag = text.substring(start + 1, end).lowercase()
            if (tag.startsWith("/")) {
                open.removeLastOrNull()
                out.append(CODES[open.lastOrNull()] ?: "§r")
            } else {
                CODES[tag]?.let { out.append(it); open.addLast(tag) }
            }
            index = end + 1
        }
        return out.toString()
    }

    /** Whether a tag has a colour code. For the test that checks the files use no others. */
    fun knowsTag(name: String): Boolean = name.removePrefix("/").lowercase().let { it.isEmpty() || it in CODES }

    private val CODES = mapOf(
        "black" to "§0", "dark_blue" to "§1", "dark_green" to "§2", "dark_aqua" to "§3",
        "dark_red" to "§4", "dark_purple" to "§5", "gold" to "§6", "gray" to "§7",
        "dark_gray" to "§8", "blue" to "§9", "green" to "§a", "aqua" to "§b",
        "red" to "§c", "light_purple" to "§d", "yellow" to "§e", "white" to "§f",
        // Two names the files use that Minecraft does not. A Token of the Mountain is dark purple, and
        // `lime` wraps a positive number — the bright green, which the chat palette calls plain green.
        "purple" to "§5",
        "lime" to "§a",
        "obfuscated" to "§k", "bold" to "§l", "strikethrough" to "§m",
        "underlined" to "§n", "italic" to "§o", "reset" to "§r",
    )

    // -- formulas ---------------------------------------------------------------

    /**
     * Ordinary infix arithmetic, and nothing more.
     *
     * Exactly what the two layout files use: the four operators, a power, parentheses, `floor` and `min`.
     * `ceil`, `round`, `max` and `abs` come along because they cost a line each and are the obvious
     * neighbours of the two that are used.
     *
     * **Nothing throws.** An expression this cannot read is null and the caller drops that one line, so a
     * perk written in some form nobody anticipated cannot take a profile lookup down with it.
     */
    internal object Formula {

        fun evaluate(source: String, variables: Map<String, Double>): Double? =
            runCatching { Reader(source, variables).run() }.getOrNull()

        private class Reader(private val source: String, private val variables: Map<String, Double>) {
            private var at = 0

            fun run(): Double? {
                val value = expression() ?: return null
                skipSpace()
                return if (at == source.length) value else null
            }

            private fun expression(): Double? {
                var left = term() ?: return null
                while (true) {
                    skipSpace()
                    val operator = peek() ?: return left
                    if (operator != '+' && operator != '-') return left
                    at++
                    val right = term() ?: return null
                    left = if (operator == '+') left + right else left - right
                }
            }

            private fun term(): Double? {
                var left = power() ?: return null
                while (true) {
                    skipSpace()
                    val operator = peek() ?: return left
                    if (operator != '*' && operator != '/') return left
                    at++
                    val right = power() ?: return null
                    if (operator == '/' && right == 0.0) return null
                    left = if (operator == '*') left * right else left / right
                }
            }

            private fun power(): Double? {
                val base = unary() ?: return null
                skipSpace()
                if (peek() != '^') return base
                at++
                // Right-associative, which is what an exponent means even where the data never nests one.
                val exponent = power() ?: return null
                return base.pow(exponent)
            }

            private fun unary(): Double? {
                skipSpace()
                if (peek() == '-') { at++; return unary()?.let { -it } }
                return primary()
            }

            private fun primary(): Double? {
                skipSpace()
                val character = peek() ?: return null
                if (character == '(') {
                    at++
                    val value = expression() ?: return null
                    skipSpace()
                    if (peek() != ')') return null
                    at++
                    return value
                }
                if (character.isDigit() || character == '.') return number()
                if (character.isLetter() || character == '_') return name()
                return null
            }

            private fun number(): Double? {
                val start = at
                while (at < source.length && (source[at].isDigit() || source[at] == '.')) at++
                return source.substring(start, at).toDoubleOrNull()
            }

            private fun name(): Double? {
                val start = at
                while (at < source.length && (source[at].isLetterOrDigit() || source[at] == '_')) at++
                val word = source.substring(start, at)
                skipSpace()
                if (peek() != '(') return variables[word]
                at++
                val arguments = ArrayList<Double>()
                skipSpace()
                if (peek() == ')') { at++ } else {
                    while (true) {
                        arguments.add(expression() ?: return null)
                        skipSpace()
                        when (peek()) {
                            ',' -> at++
                            ')' -> { at++; break }
                            else -> return null
                        }
                    }
                }
                return call(word, arguments)
            }

            private fun call(name: String, arguments: List<Double>): Double? = when (name) {
                "floor" -> arguments.singleOrNull()?.let(::floor)
                "ceil" -> arguments.singleOrNull()?.let { kotlin.math.ceil(it) }
                "round" -> arguments.singleOrNull()?.let { kotlin.math.round(it) }
                "abs" -> arguments.singleOrNull()?.let { kotlin.math.abs(it) }
                "min" -> if (arguments.size == 2) minOf(arguments[0], arguments[1]) else null
                "max" -> if (arguments.size == 2) maxOf(arguments[0], arguments[1]) else null
                else -> null
            }

            private fun peek(): Char? = source.getOrNull(at)
            private fun skipSpace() { while (at < source.length && source[at].isWhitespace()) at++ }
        }
    }
}
