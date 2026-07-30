package dev.th7bo.sidequest.platform.core.backend

import dev.th7bo.sidequest.platform.backend.StoredSession
import dev.th7bo.sidequest.platform.backend.TokenStore
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.storage.StorageProvider
import dev.th7bo.sidequest.platform.storage.StorageScope
import kotlinx.serialization.Serializable

/**
 * Keeps the refresh token in the platform's own storage.
 *
 * **On "secure token storage".** The plan asks for it, and this is an honest partial answer. A file on the
 * same disk as the game is not a keychain: anything running as the user can read it. What it *is* is a file
 * written atomically, in the mod's own directory, holding only the long-lived half — the access token is
 * never persisted, because it lives for minutes and storing one would leave a credential a restart cannot
 * invalidate.
 *
 * Better is possible and is a later change behind this interface: an OS keychain, or a key derived from
 * something the user provides. Pretending a JSON file is a vault would be worse than saying which one it
 * is, because the mitigation that actually matters is on the server — the token is revocable, per device,
 * and checked on every request.
 *
 * Scoped [StorageScope.Account] rather than global: two Minecraft accounts on one machine are two devices
 * to the backend, and sharing one credential between them would mean one signing the other out.
 */
public class StoredTokenStore(
    storage: StorageProvider,
    scope: StorageScope,
) : TokenStore {

    @Serializable
    private data class Held(val session: StoredSession? = null)

    private val repository = storage.repository(
        id = SqId.sidequest("backend.session"),
        scope = scope,
        serializer = Held.serializer(),
        default = { Held() },
    )

    override suspend fun load(): StoredSession? = repository.load().value.session

    override suspend fun save(session: StoredSession): Unit = repository.save(Held(session))

    /** Deletes the file rather than writing an empty one, so nothing is left to recover. */
    override suspend fun clear() {
        repository.delete()
    }
}
