package dev.th7bo.sidequest.platform.minecraft

import dev.th7bo.sidequest.platform.asset.AssetFetch
import dev.th7bo.sidequest.platform.asset.AssetId
import dev.th7bo.sidequest.platform.asset.AssetStore
import dev.th7bo.sidequest.platform.asset.StoredAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest as JdkRequest
import java.net.http.HttpResponse as JdkResponse
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Asset bytes over the JDK's HTTP client.
 *
 * Same reasoning as [JdkHttpTransport] for using the JDK rather than a library, and the same thinness — but
 * with one piece of real logic that could not be moved upwards, because it has to happen *while* the response
 * is arriving:
 *
 * **The size limit is enforced on the stream, not on the result.** The obvious implementation asks for the
 * whole body as a byte array and checks its length, and by then a client has already allocated whatever
 * somebody's server decided to send. So this reads incrementally and abandons the response the moment it
 * passes the cap. The `Content-Length` header is checked first as a cheap early exit, and is not believed —
 * a header saying 1 KiB in front of a 900 MiB body is precisely the case the streaming cap exists for.
 */
class JdkAssetTransport(
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .build(),
) : dev.th7bo.sidequest.platform.asset.AssetTransport {

    override suspend fun fetch(url: String, maxBytes: Long): AssetFetch = withContext(Dispatchers.IO) {
        val request = runCatching {
            JdkRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
                .GET()
                .build()
        }.getOrElse { thrown ->
            return@withContext AssetFetch.Failure("bad asset url: ${thrown.message}")
        }

        try {
            val response = client.sendAsync(request, JdkResponse.BodyHandlers.ofInputStream()).await()
            if (response.statusCode() !in 200..299) {
                // The stream still has to be closed, or the connection is not returned to the pool.
                response.body().use { it.close() }
                return@withContext AssetFetch.Failure("HTTP ${response.statusCode()}", response.statusCode())
            }

            val declaredLength = response.headers().firstValueAsLong("content-length")
                .takeIf { it.isPresent }?.asLong
            if (declaredLength != null && declaredLength > maxBytes) {
                response.body().use { it.close() }
                return@withContext AssetFetch.TooLarge(maxBytes, declaredLength)
            }

            val declaredType = response.headers().firstValue("content-type").orElse(null)

            response.body().use { stream ->
                when (val read = readCapped(stream, maxBytes)) {
                    null -> AssetFetch.TooLarge(maxBytes, declaredLength)
                    else -> AssetFetch.Body(read, declaredType)
                }
            }
        } catch (timeout: HttpTimeoutException) {
            AssetFetch.Failure(timeout.message ?: "timed out")
        } catch (thrown: Exception) {
            AssetFetch.Failure(thrown.message ?: thrown::class.simpleName ?: "failed")
        }
    }

    /**
     * Reads at most [maxBytes], or null when the stream had more.
     *
     * Reads one byte past the limit on purpose: that is what distinguishes a file of exactly the maximum
     * size, which is allowed, from one that merely starts that way.
     */
    private fun readCapped(stream: InputStream, maxBytes: Long): ByteArray? {
        val limit = maxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val buffer = ByteArray(CHUNK)
        val collected = java.io.ByteArrayOutputStream(INITIAL_BUFFER)

        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (collected.size() + count > limit) return null
            collected.write(buffer, 0, count)
        }
        return collected.toByteArray()
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10L

        /** Longer than a JSON call: an asset is megabytes and somebody's server is on domestic upload. */
        const val READ_TIMEOUT_SECONDS = 30L

        const val CHUNK = 16 * 1024
        const val INITIAL_BUFFER = 64 * 1024
    }
}

/**
 * The asset cache on disk.
 *
 * A file per asset, named by its id, which is the whole index — the store needs no manifest of its own and
 * therefore has no manifest to fall out of step with the directory. That is worth more than it sounds:
 * a cache with a separate index has two sources of truth and a crash between writing them.
 *
 * Sharded into a directory per two-character prefix. Not for lookup speed, which a hash map already gives —
 * for the file systems and file managers that get unhappy somewhere in the low thousands of entries.
 *
 * Writes go through a temporary file and an atomic move, for the same reason the JSON storage does: a crash
 * mid-write must leave the previous file intact rather than a half one, and a half-written asset that still
 * hashes to nothing would be re-downloaded every session forever.
 */
class FileAssetStore(private val root: Path) : AssetStore {

    override suspend fun read(id: AssetId): ByteArray? = withContext(Dispatchers.IO) {
        val file = pathFor(id)
        if (!file.exists() || !file.isRegularFile()) return@withContext null
        runCatching { Files.readAllBytes(file) }.getOrNull()
    }

    override suspend fun write(id: AssetId, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        val file = pathFor(id)
        runCatching {
            Files.createDirectories(file.parent)
            val temporary = file.resolveSibling(file.name + ".tmp")
            Files.write(temporary, bytes)
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        Unit
    }

    override suspend fun delete(id: AssetId): Unit = withContext(Dispatchers.IO) {
        runCatching { pathFor(id).deleteIfExists() }
        Unit
    }

    override suspend fun list(): List<StoredAsset> = withContext(Dispatchers.IO) {
        if (!root.exists()) return@withContext emptyList()
        runCatching {
            Files.walk(root, SHARD_DEPTH + 1).use { paths ->
                paths.filter { it.isRegularFile() }
                    .toList()
                    .mapNotNull { file ->
                        // A file whose name is not an id is something else that ended up in the directory.
                        // Ignored rather than deleted: this is not the place to decide it is rubbish.
                        val id = AssetId.parseOrNull(file.name) ?: return@mapNotNull null
                        StoredAsset(
                            id = id,
                            bytes = file.fileSize(),
                            lastUsedMillis = file.getLastModifiedTime().toMillis(),
                        )
                    }
            }
        }.getOrElse { emptyList() }
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        if (!root.exists()) return@withContext
        runCatching {
            Files.walk(root).use { paths ->
                // Deepest first, so a directory is empty by the time it is removed.
                paths.sorted(Comparator.reverseOrder()).toList().forEach { path ->
                    if (path != root) runCatching { Files.deleteIfExists(path) }
                }
            }
        }
        Unit
    }

    /** `<root>/ab/abcdef…`. The shard is a prefix of the hash, which is uniformly distributed by definition. */
    private fun pathFor(id: AssetId): Path = root.resolve(id.value.take(SHARD_LENGTH)).resolve(id.value)

    private companion object {
        const val SHARD_LENGTH = 2
        const val SHARD_DEPTH = 1
    }
}
