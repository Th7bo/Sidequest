package dev.th7bo.sidequest.platform.lifecycle

/**
 * Something that can be undone.
 *
 * Everything the platform hands back is one of these, and everything a feature creates
 * gets collected into one. The alternative — an `unregister(listener)` for each kind of
 * registration — is the design where a feature that unloads leaves a listener behind and
 * nobody notices until it fires against a disposed object.
 */
public fun interface Registration {

    public fun cancel()

    public companion object {
        /** A registration that does nothing, for paths that conditionally register. */
        public val None: Registration = Registration {}
    }
}

/**
 * A bag of registrations that are cancelled together.
 *
 * Cancellation is idempotent and collects failures rather than stopping at the first:
 * one badly-behaved listener must not strand every registration after it in the list,
 * which would leave exactly the ghost state this type exists to prevent.
 *
 * Registering into a closed scope throws. Silently dropping the registration would let a
 * feature believe it is listening when it is not.
 */
public class RegistrationScope(
    /** For error messages; the owner this scope belongs to. */
    public val debugName: String = "scope",
) : Registration {

    private val registrations = ArrayList<Registration>()

    public var isClosed: Boolean = false
        private set

    /** Number of live registrations. Exposed so a leak shows up in a test, not in game. */
    public val size: Int get() = registrations.size

    public fun add(registration: Registration): Registration {
        check(!isClosed) { "Cannot register into '$debugName' after it has been closed" }
        registrations.add(registration)
        return registration
    }

    /** Removes a registration without cancelling it, for handles that ended on their own. */
    public fun forget(registration: Registration) {
        registrations.remove(registration)
    }

    override fun cancel() {
        if (isClosed) return
        isClosed = true

        var failure: Throwable? = null
        // Reverse order: later registrations may depend on earlier ones, the same reason
        // a stack unwinds the way it does.
        for (index in registrations.indices.reversed()) {
            try {
                registrations[index].cancel()
            } catch (thrown: Throwable) {
                if (failure == null) failure = thrown else failure.addSuppressed(thrown)
            }
        }
        registrations.clear()
        failure?.let { throw IllegalStateException("Failures while closing '$debugName'", it) }
    }
}

/** Adds this registration to [scope] and returns it. */
public fun <T : Registration> T.ownedBy(scope: RegistrationScope): T {
    scope.add(this)
    return this
}
