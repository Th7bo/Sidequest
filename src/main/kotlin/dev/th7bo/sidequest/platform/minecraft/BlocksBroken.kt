package dev.th7bo.sidequest.platform.minecraft

import java.util.concurrent.atomic.AtomicLong

/**
 * How many blocks the player has broken, ever.
 *
 * A single counter, and deliberately nothing more. The mixin that feeds it runs on the game's own hot path —
 * once per block, which while farming is several times a tick — so it does no work beyond an increment, and
 * every question worth asking about the number is asked elsewhere by whoever polls it.
 *
 * Monotonic and never reset. A consumer wanting "since when" keeps its own mark and subtracts, which is one
 * subtraction against a shared counter that would otherwise need a rule about who is allowed to zero it.
 */
public object BlocksBroken {

    private val count = AtomicLong()

    public val total: Long get() = count.get()

    /** Called from the mixin, once per block actually destroyed. */
    @JvmStatic
    public fun record() {
        count.incrementAndGet()
    }
}
