package dev.th7bo.sidequest.protocol

import kotlinx.serialization.Serializable

/**
 * Why a request failed, as a code rather than a status.
 *
 * HTTP statuses are too coarse for anything to act on: a 403 could be an expired token, a revoked
 * device, or a permission the group has taken away, and the right client behaviour is different for
 * each — refresh, re-pair, or tell the user. A client that only has the status has to guess, and it
 * guesses "retry", which loops.
 */
@Serializable
public enum class ApiErrorCode(
    /**
     * Whether trying again could work.
     *
     * The one property the retry policy reads. Getting it wrong in the retryable direction produces a
     * client that hammers a server which is telling it to stop.
     */
    public val isRetryable: Boolean,
) {
    /** Malformed request. Never worth retrying — the same request will fail the same way. */
    BAD_REQUEST(isRetryable = false),

    /** No token, or one that has expired. Refresh, then retry once. */
    UNAUTHENTICATED(isRetryable = false),

    /** The token was fine and the account may not do this. Telling the user is the only move. */
    FORBIDDEN(isRetryable = false),

    /** The device was revoked. Pairing again is the only way back, and the tokens should be dropped. */
    DEVICE_REVOKED(isRetryable = false),

    /**
     * The client speaks a protocol the server will not talk to.
     *
     * Terminal until one side updates. A client that retried would retry forever.
     */
    PROTOCOL_MISMATCH(isRetryable = false),

    NOT_FOUND(isRetryable = false),

    /** Slow down. Retryable, and the response carries how long to wait. */
    RATE_LIMITED(isRetryable = true),

    /** The server broke. Worth retrying with backoff — it may have been one bad moment. */
    INTERNAL(isRetryable = true),

    /** Could not reach the server at all. The most common failure by far, and always retryable. */
    UNAVAILABLE(isRetryable = true),

    /** Took too long. Retryable, but the backoff matters — a timeout usually means load. */
    TIMEOUT(isRetryable = true),
    ;
}

/**
 * A failure, as the server describes it.
 *
 * [message] is for a log and for a developer. Nothing here is shown to a user verbatim: server text in
 * a player's chat is how internal detail leaks out of a system, and the client has better words for
 * every one of these anyway.
 */
@Serializable
public data class ApiError(
    public val code: ApiErrorCode,
    public val message: String = "",
    /** For [ApiErrorCode.RATE_LIMITED], how long to wait. */
    public val retryAfterMillis: Long? = null,
    /** Echoed from the request, so a user's report can be found in the server's log. */
    public val requestId: String? = null,
) {
    override fun toString(): String = "$code${if (message.isEmpty()) "" else ": $message"}"
}

/**
 * The result of a call.
 *
 * A sealed result rather than exceptions, because a failed backend call is an *expected* outcome here,
 * not an exceptional one. This mod is used on a laptop on hotel wifi against a server in somebody's
 * cupboard; being unreachable is the normal case, and modelling the normal case as a thrown exception
 * produces code where every call site either forgets the try or wraps one.
 */
public sealed interface ApiResult<out T> {

    public data class Success<T>(
        public val value: T,
        /** Echoed request id, for correlating with the server's log. */
        public val requestId: String? = null,
    ) : ApiResult<T>

    public data class Failure(public val error: ApiError) : ApiResult<Nothing>

    public val isSuccess: Boolean get() = this is Success

    /** The value, or null on failure. For call sites where a failure means "carry on without it". */
    public fun valueOrNull(): T? = (this as? Success)?.value

    public fun errorOrNull(): ApiError? = (this as? Failure)?.error

    public companion object {
        public fun failure(code: ApiErrorCode, message: String = ""): Failure =
            Failure(ApiError(code, message))
    }
}

/** Maps the result's value, leaving a failure alone. */
public inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(value), requestId)
    is ApiResult.Failure -> this
}

/**
 * A batch of events being submitted.
 *
 * The one write endpoint, and the offline queue drains into it. A batch rather than one call per event
 * because a client that has been offline for an evening has a hundred of them, and a hundred round
 * trips over a slow connection is a minute of the game stuttering.
 */
@Serializable
public data class EventBatch(
    public val messages: List<RealtimeMessage>,
    public val protocolVersion: Int = Protocol.VERSION,
)

/**
 * What the server did with a batch.
 *
 * Accepted and rejected are listed separately, by message id, because a batch is not all-or-nothing: a
 * single event the server will not take — one whose permission the group has revoked since it was
 * queued — must not block the other ninety-nine. The client acknowledges both lists and retries
 * neither.
 */
@Serializable
public data class EventBatchResult(
    public val accepted: List<String> = emptyList(),
    /** Message id to the reason. Acknowledged, not retried: retrying would fail the same way. */
    public val rejected: Map<String, ApiErrorCode> = emptyMap(),
    /** The newest sequence after this batch, so a client knows where it now stands. */
    public val currentSequence: Long = 0,
)

/** Events the caller has missed, for a client that has been away. */
@Serializable
public data class EventPage(
    public val messages: List<RealtimeMessage> = emptyList(),
    public val currentSequence: Long = 0,
    /**
     * True when the requested point is older than the window the server keeps.
     *
     * The caller's cue that it has a hole and should re-fetch state rather than assume it is caught up.
     */
    public val resumeGap: Boolean = false,
)
