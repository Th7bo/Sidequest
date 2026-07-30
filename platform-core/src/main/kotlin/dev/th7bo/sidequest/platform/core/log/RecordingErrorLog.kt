package dev.th7bo.sidequest.platform.core.log

import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.log.ErrorId
import dev.th7bo.sidequest.platform.log.ErrorLog
import dev.th7bo.sidequest.platform.log.ErrorRecord
import dev.th7bo.sidequest.platform.log.ErrorSummary
import dev.th7bo.sidequest.platform.log.LogCategory
import dev.th7bo.sidequest.platform.log.LogLevel

/**
 * Keeps what went wrong, grouped.
 *
 * The grouping is the point. A backend that is unreachable logs a warning every retry, and a list would be
 * two hundred identical lines that push every other problem out of a bounded buffer — so the *interesting*
 * failure, the one that happened once, is the one that gets lost. Keyed by [ErrorId], the same two hundred
 * are one entry with a count.
 *
 * Bounded by distinct failures rather than by occurrences, for that reason. A session producing more than a
 * few dozen genuinely different failures has larger problems than this buffer.
 *
 * Synchronised because logging happens from the client thread, the scheduler's IO pool and the JDK's
 * WebSocket executor, and a diagnostic that itself throws a `ConcurrentModificationException` is worse than
 * no diagnostic.
 */
public class RecordingErrorLog(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val now: () -> Long = System::currentTimeMillis,
) : ErrorLog {

    /** Access-ordered, so eviction drops whatever has not been seen for longest. */
    private val records = object : LinkedHashMap<ErrorId, ErrorRecord>(INITIAL_CAPACITY, LOAD_FACTOR, true) {}

    private var occurrences = 0
    private var errorOccurrences = 0

    public fun record(
        level: LogLevel,
        category: LogCategory,
        owner: SqId,
        message: String,
        thrown: Throwable?,
    ): ErrorId = synchronized(records) {
        val id = ErrorId.of(owner, message, thrown)
        val moment = now()
        val existing = records[id]

        records[id] = if (existing == null) {
            ErrorRecord(
                id = id,
                category = category,
                owner = owner,
                message = message,
                cause = thrown?.let { "${it::class.simpleName}: ${it.message ?: "no message"}" },
                firstSeenMillis = moment,
                lastSeenMillis = moment,
                count = 1,
            )
        } else {
            // The newest message wins. Two occurrences of one failure differ only in the parts the id
            // generalises away, and the recent one is the one somebody is looking at right now.
            existing.copy(
                message = message,
                cause = thrown?.let { "${it::class.simpleName}: ${it.message ?: "no message"}" } ?: existing.cause,
                lastSeenMillis = moment,
                count = existing.count + 1,
            )
        }

        occurrences++
        if (level >= LogLevel.ERROR) errorOccurrences++

        while (records.size > capacity) {
            val oldest = records.keys.firstOrNull() ?: break
            records.remove(oldest)
        }
        id
    }

    override fun recent(limit: Int): List<ErrorRecord> = synchronized(records) {
        records.values.sortedByDescending { it.lastSeenMillis }.take(limit)
    }

    override fun get(id: ErrorId): ErrorRecord? = synchronized(records) { records[id] }

    override fun summary(): ErrorSummary = synchronized(records) {
        ErrorSummary(distinct = records.size, occurrences = occurrences, errors = errorOccurrences)
    }

    override fun clear(): Unit = synchronized(records) {
        records.clear()
        occurrences = 0
        errorOccurrences = 0
    }

    public companion object {
        public const val DEFAULT_CAPACITY: Int = 64
        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
    }
}
