package dev.th7bo.sidequest.backend

import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round

/**
 * The small expression language NotEnoughUpdates writes its Heart of the Mountain tooltips in.
 *
 * The perk layouts are not tables of numbers. A perk records the *formula* for its effect —
 * `(* level 20)` for Mining Speed, `(if (lt potm 2) "200" "250")` for the ability perks — and its lore
 * carries `{stat}` placeholders that the formula fills in. Reading the layout without evaluating it would
 * leave a tooltip reading "Grants +{stat} Mining Speed", so the evaluator is not a flourish: it is the only
 * way to use the data at all.
 *
 * **Why not copy the numbers instead.** Because they change. Hypixel rebalances perks, and a hand-copied
 * table of stat values is a table that silently starts lying — the exact failure the project has a standing
 * rule against. This reads the live formula, so a rebalance upstream arrives here on its own.
 *
 * The dialect is tiny and entirely determined by what the two layout files actually use: `if`, the
 * comparisons `lt`/`gt`/`=`, the four arithmetic operators, `pow`, `round`, `floor`, two list forms, and
 * `defun` for the prelude each file ships. Nothing here is general — it is exactly enough, and an expression
 * that reaches for anything else evaluates to null rather than guessing.
 *
 * **Nothing throws.** A malformed or unfamiliar expression yields null and the caller drops that one line.
 * One perk gaining a form this does not know must not take a whole profile lookup down with it.
 */
internal object NeuLisp {

    /** What an expression evaluates to. Deliberately four cases; the language has no more. */
    sealed interface Value {
        data class Num(val value: Double) : Value
        data class Text(val value: String) : Value
        data class Flag(val value: Boolean) : Value
        data class Items(val values: List<Value>) : Value
    }

    /**
     * The prelude's functions, ready to evaluate expressions against.
     *
     * Built once per layout file. The prelude is where `npi`, `api` and the arithmetic helpers are defined,
     * and every perk in that file is written assuming they exist.
     */
    class Scope internal constructor(internal val functions: Map<String, Function>) {

        /** Evaluates [source] with [variables] bound. Null when it cannot be read or does not finish. */
        fun evaluate(source: String, variables: Map<String, Double>): Value? {
            val node = parse(source) ?: return null
            return runCatching { eval(node, this, variables.mapValues { Value.Num(it.value) }, 0) }.getOrNull()
        }

        /** [evaluate], rendered the way a tooltip wants it. */
        fun render(source: String, variables: Map<String, Double>): String? =
            evaluate(source, variables)?.let(::render)

        /** True only for an expression that evaluates to a true flag. Used for conditional lore lines. */
        fun holds(source: String, variables: Map<String, Double>): Boolean =
            (evaluate(source, variables) as? Value.Flag)?.value == true
    }

    internal class Function(val parameters: List<String>, val body: Node)

    /** Builds a scope from a file's `prelude` array. An entry that is not a `defun` is ignored. */
    fun scopeOf(prelude: List<String>): Scope {
        val functions = LinkedHashMap<String, Function>()
        for (line in prelude) {
            val node = parse(line) as? Node.Call ?: continue
            val head = (node.items.firstOrNull() as? Node.Atom)?.text
            if (head != "defun" || node.items.size < 4) continue
            val name = (node.items[1] as? Node.Atom)?.text ?: continue
            val parameters = (node.items[2] as? Node.Call)?.items?.mapNotNull { (it as? Node.Atom)?.text }
                ?: continue
            functions[name] = Function(parameters, node.items[3])
        }
        return Scope(functions)
    }

    // -- reading -------------------------------------------------------------

    internal sealed interface Node {
        data class Atom(val text: String) : Node
        data class Str(val text: String) : Node
        data class Call(val items: List<Node>) : Node
    }

    internal fun parse(source: String): Node? {
        val cursor = intArrayOf(0)
        val node = read(source, cursor) ?: return null
        return node
    }

    private fun read(source: String, cursor: IntArray): Node? {
        skipSpace(source, cursor)
        if (cursor[0] >= source.length) return null
        return when (source[cursor[0]]) {
            '(' -> {
                cursor[0]++
                val items = ArrayList<Node>()
                while (true) {
                    skipSpace(source, cursor)
                    if (cursor[0] >= source.length) return null
                    if (source[cursor[0]] == ')') { cursor[0]++; break }
                    items.add(read(source, cursor) ?: return null)
                }
                Node.Call(items)
            }
            ')' -> null
            '"' -> {
                cursor[0]++
                val start = cursor[0]
                while (cursor[0] < source.length && source[cursor[0]] != '"') cursor[0]++
                if (cursor[0] >= source.length) return null
                Node.Str(source.substring(start, cursor[0])).also { cursor[0]++ }
            }
            else -> {
                val start = cursor[0]
                while (cursor[0] < source.length && !source[cursor[0]].isWhitespace() &&
                    source[cursor[0]] != '(' && source[cursor[0]] != ')'
                ) {
                    cursor[0]++
                }
                if (cursor[0] == start) null else Node.Atom(source.substring(start, cursor[0]))
            }
        }
    }

    private fun skipSpace(source: String, cursor: IntArray) {
        while (cursor[0] < source.length && source[cursor[0]].isWhitespace()) cursor[0]++
    }

    // -- evaluating ----------------------------------------------------------

    /**
     * A ceiling on nesting.
     *
     * The layouts nest three or four deep. This exists so a prelude that ever defines a function in terms of
     * itself is a null rather than a stack overflow in the profile route.
     */
    private const val MAX_DEPTH = 64

    private fun eval(node: Node, scope: Scope, variables: Map<String, Value>, depth: Int): Value? {
        if (depth > MAX_DEPTH) return null
        return when (node) {
            is Node.Str -> Value.Text(node.text)
            is Node.Atom -> atom(node.text, variables)
            is Node.Call -> call(node, scope, variables, depth)
        }
    }

    /**
     * An atom is a number, a bound variable, a boolean, or a bare name standing for itself.
     *
     * The last case is the one worth stating: `MITHRIL` and `:COAL` are not undefined variables, they are the
     * language's way of naming a powder and a Minecraft item. Treating an unbound name as text is therefore
     * correct rather than lenient — the layouts rely on it.
     */
    private fun atom(text: String, variables: Map<String, Value>): Value? {
        variables[text]?.let { return it }
        text.toDoubleOrNull()?.let { return Value.Num(it) }
        return when (text) {
            "true" -> Value.Flag(true)
            "false" -> Value.Flag(false)
            else -> Value.Text(text.removePrefix(":"))
        }
    }

    private fun call(node: Node.Call, scope: Scope, variables: Map<String, Value>, depth: Int): Value? {
        val head = (node.items.firstOrNull() as? Node.Atom)?.text ?: return null
        val arguments = node.items.drop(1)

        // `if` is not a function: evaluating both branches would divide by zero in the ones guarding it.
        if (head == "if") {
            if (arguments.size != 3) return null
            val condition = eval(arguments[0], scope, variables, depth + 1) ?: return null
            val taken = if (truth(condition)) arguments[1] else arguments[2]
            return eval(taken, scope, variables, depth + 1)
        }

        val values = arguments.map { eval(it, scope, variables, depth + 1) ?: return null }

        fun number(index: Int): Double? = (values.getOrNull(index) as? Value.Num)?.value

        return when (head) {
            "+" -> fold(values) { a, b -> a + b }
            "-" -> fold(values) { a, b -> a - b }
            "*" -> fold(values) { a, b -> a * b }
            "/" -> {
                val left = number(0) ?: return null
                val right = number(1) ?: return null
                if (right == 0.0) null else Value.Num(left / right)
            }
            "pow" -> Value.Num((number(0) ?: return null).pow(number(1) ?: return null))
            "round" -> Value.Num(round(number(0) ?: return null))
            "floor" -> Value.Num(floor(number(0) ?: return null))
            "lt" -> Value.Flag((number(0) ?: return null) < (number(1) ?: return null))
            "gt" -> Value.Flag((number(0) ?: return null) > (number(1) ?: return null))
            "=" -> Value.Flag(same(values.getOrNull(0), values.getOrNull(1)))
            "list.new" -> Value.Items(values)
            "list.at" -> {
                val items = (values.getOrNull(0) as? Value.Items)?.values ?: return null
                val index = number(1)?.toInt() ?: return null
                items.getOrNull(index)
            }
            else -> {
                val function = scope.functions[head] ?: return null
                if (function.parameters.size != values.size) return null
                eval(function.body, scope, function.parameters.zip(values).toMap(), depth + 1)
            }
        }
    }

    private inline fun fold(values: List<Value>, operation: (Double, Double) -> Double): Value? {
        if (values.isEmpty()) return null
        var total = (values.first() as? Value.Num)?.value ?: return null
        for (value in values.drop(1)) total = operation(total, (value as? Value.Num)?.value ?: return null)
        return Value.Num(total)
    }

    private fun truth(value: Value): Boolean = when (value) {
        is Value.Flag -> value.value
        is Value.Num -> value.value != 0.0
        is Value.Text -> value.value.isNotEmpty()
        is Value.Items -> value.values.isNotEmpty()
    }

    private fun same(left: Value?, right: Value?): Boolean = when {
        left is Value.Num && right is Value.Num -> left.value == right.value
        left == null || right == null -> false
        else -> render(left) == render(right)
    }

    /**
     * A value as a tooltip wants to read it.
     *
     * Whole numbers lose their decimal point, because "Grants +1000.0 Mining Speed" is not what the game
     * says. Fractional ones keep at most two places, which is what `round-decimals` in the prelude is already
     * asking for.
     */
    fun render(value: Value): String = when (value) {
        is Value.Text -> value.value
        is Value.Flag -> value.value.toString()
        is Value.Items -> value.values.joinToString(", ", transform = ::render)
        is Value.Num -> {
            val number = value.value
            if (number == floor(number) && !number.isInfinite() && kotlin.math.abs(number) < 1e15) {
                number.toLong().toString()
            } else {
                String.format(java.util.Locale.ROOT, "%.2f", number).trimEnd('0').trimEnd('.')
            }
        }
    }
}
