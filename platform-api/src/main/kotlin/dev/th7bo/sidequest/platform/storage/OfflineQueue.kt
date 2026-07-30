package dev.th7bo.sidequest.platform.storage

import dev.th7bo.sidequest.platform.id.SqId
import kotlinx.serialization.Serializable

/**
 * Work that has to survive a restart.
 *
 * The queue exists because the interesting moments happen while offline. A rare drop at two in the
 * morning on a flaky connection, an achievement earned while the homelab is rebooting, a debt
 * settled in a lobby where the backend is unreachable — all of them are worth recording and none of
 * them can be sent yet. Holding them in memory means losing them on the next crash, and dropping
 * them means the group's history has holes in exactly the places somebody will look.
 *
 * Append-only and read-in-batches, which is why this is not a [Repository]. A repository is
 * read-modify-write; a queue that rewrote its whole file per append would rewrite a megabyte to add
 * forty bytes, several times a minute, on somebody's SSD.
 *
 * **Delivery is at-least-once, not exactly-once.** [acknowledge] is a separate call, so a crash
 * between sending and acknowledging replays the entry. That is the right trade for this: the
 * receiving end can discard a duplicate by its id, and there is no way to make it exactly-once
 * without a transaction across a network boundary.
 */
public interface OfflineQueue<T : Any> {

    public val id: SqId

    /** Entries waiting to be sent. */
    public suspend fun size(): Int

    /**
     * Appends [entry].
     *
     * Returns the entry as stored, with its id and timestamp. At capacity the *oldest* entry is
     * dropped — see [DEFAULT_CAPACITY] for why that direction.
     */
    public suspend fun enqueue(entry: T): QueuedEntry<T>

    /**
     * The oldest [limit] entries, without removing them.
     *
     * Peek and not poll, because the caller has not sent them yet. Removing on read would lose the
     * batch to any failure between here and the network.
     */
    public suspend fun peek(limit: Int = DEFAULT_BATCH): List<QueuedEntry<T>>

    /** Removes entries by id. Ids that are not present are ignored. */
    public suspend fun acknowledge(ids: Collection<String>)

    /** Empties the queue. For a sign-out, or a user who asks. */
    public suspend fun clear()

    public companion object {
        /**
         * How many entries to keep.
         *
         * At capacity the oldest is dropped rather than the newest rejected. A queue that refuses new
         * entries when full stops recording the present in order to preserve a past nobody has looked
         * at in weeks, and the present is what the player is doing right now.
         */
        public const val DEFAULT_CAPACITY: Int = 500

        /** A sensible batch size for one send. Small enough to retry cheaply. */
        public const val DEFAULT_BATCH: Int = 50
    }
}

/**
 * One entry, with the two pieces of bookkeeping the queue adds.
 *
 * The [id] is what makes at-least-once delivery survivable: the receiver discards a repeat by id, so
 * a replayed batch after a crash is harmless rather than a duplicate record.
 *
 * The [timestampMillis] is when it *happened*, not when it is sent, and that distinction is the
 * whole point of queueing. A drop recorded at 2am and delivered at 9am belongs at 2am in the group's
 * history, and only the client knows that.
 */
@Serializable
public data class QueuedEntry<T : Any>(
    public val id: String,
    public val timestampMillis: Long,
    public val entry: T,
    /** How many times delivery has been attempted. For backing off a poison entry. */
    public val attempts: Int = 0,
)
