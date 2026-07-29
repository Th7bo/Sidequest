package dev.th7bo.sidequest.ui.state

/**
 * The reactive graph: dependency tracking, invalidation and batched notification.
 *
 * Pull-based with version cut-off. A write marks dependents *stale* but recomputes
 * nothing; a derivation recomputes only when it is actually read and one of the
 * sources it read last time has genuinely changed. A frame that touches no state
 * therefore does zero recomputation, which is the property the idle-frame budget
 * depends on.
 */
internal object ReactiveGraph {

    /** Distinguishes "no value yet" from a legitimately null value. */
    object Unset

    private var versionCounter: Long = 0

    /** Innermost-last stack of derivations currently being computed. */
    val evaluationStack: ArrayList<DerivedState<*>> = ArrayList()

    private var untrackedDepth: Int = 0
    private var batchDepth: Int = 0

    /** Nodes touched since the last flush, in the order they were invalidated. */
    private val pending: LinkedHashSet<StateNode<*>> = LinkedHashSet()

    fun nextVersion(): Long = ++versionCounter

    /** Records that the derivation currently being computed read [node]. */
    fun recordRead(node: StateNode<*>) {
        if (untrackedDepth > 0) return
        val consumer = evaluationStack.lastOrNull() ?: return
        if (consumer === node) return
        consumer.addDependency(node)
    }

    /** Marks [source] and everything downstream of it stale, then notifies listeners. */
    fun invalidate(source: StateNode<*>) {
        markDirty(source)
        if (batchDepth == 0) flush()
    }

    private fun markDirty(node: StateNode<*>) {
        pending.add(node)
        if (node.dependents.isEmpty()) return
        // Copy: a dependent's own invalidation may not mutate this set, but a listener
        // added during a previous flush wave can, and the cost is negligible here.
        for (dependent in node.dependents.toList()) {
            if (dependent.markStale()) markDirty(dependent)
        }
    }

    fun <R> batch(block: () -> R): R {
        UiThread.check()
        batchDepth++
        try {
            return block()
        } finally {
            batchDepth--
            if (batchDepth == 0) flush()
        }
    }

    fun <R> untracked(block: () -> R): R {
        untrackedDepth++
        try {
            return block()
        } finally {
            untrackedDepth--
        }
    }

    private fun flush() {
        if (pending.isEmpty()) return
        var wave = 0
        while (pending.isNotEmpty()) {
            check(wave++ < MAX_NOTIFICATION_WAVES) {
                "Reactive notifications did not settle after $MAX_NOTIFICATION_WAVES waves; " +
                    "a listener is very likely writing state that re-triggers itself"
            }
            val current = pending.toList()
            pending.clear()
            for (node in current) node.notifyListeners()
        }
    }

    /**
     * Test hook. Clears graph-wide bookkeeping so one test cannot leak pending
     * notifications into the next.
     */
    fun reset() {
        evaluationStack.clear()
        pending.clear()
        untrackedDepth = 0
        batchDepth = 0
    }

    private const val MAX_NOTIFICATION_WAVES = 100
}
