package dev.th7bo.sidequest.platform.core.presence

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Path

/**
 * Finding the running Discord.
 *
 * Discord listens on `discord-ipc-0` through `discord-ipc-9` — several, because several Discord clients can
 * run at once — under a runtime directory that depends on how it was installed. The search is wide because
 * the wrong answer is invisible: a mod that only checked `/tmp` would work for most people and be
 * unexplainably broken for anybody running Discord from Flatpak.
 *
 * **Not finding one is the normal case, not an error.** Most sessions have no Discord running, and this
 * says so with [DiscordIpcException.isExpected] rather than by logging a failure every thirty seconds.
 */
public object DiscordSockets {

    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /**
     * Connects to whichever Discord answers first.
     *
     * @throws DiscordIpcException when none does.
     */
    public fun open(): DiscordPipe {
        for (candidate in candidates()) {
            runCatching { return connect(candidate) }
        }
        throw DiscordIpcException(notFoundMessage(), isExpected = true)
    }

    /** Every path worth trying, in the order they are worth trying. For the diagnostics command. */
    public fun candidates(): List<String> {
        if (isWindows) return (0..LAST_SOCKET).map { """\\.\pipe\discord-ipc-$it""" }

        val bases = listOfNotNull(
            System.getenv("XDG_RUNTIME_DIR"),
            System.getenv("TMPDIR"),
            System.getenv("TMP"),
            System.getenv("TEMP"),
            "/tmp",
        ).distinct()

        return bases.flatMap { base -> SANDBOX_DIRS.map { if (it.isEmpty()) base else "$base/$it" } }
            .flatMap { dir -> (0..LAST_SOCKET).map { "$dir/discord-ipc-$it" } }
    }

    private fun connect(path: String): DiscordPipe =
        if (isWindows) WindowsPipe(path) else UnixSocket(Path.of(path))

    /**
     * Why nothing was found, in terms of something the reader can do.
     *
     * The Flatpak case is worth naming specifically because it is the one where Discord *is* running, the
     * socket *does* exist, and this process simply cannot see it — which looks exactly like Discord being
     * closed, and is fixed by a command nobody would guess.
     */
    private fun notFoundMessage(): String {
        if (!isWindows && File(FLATPAK_INFO).exists()) {
            return "Discord's socket is not visible from inside this Flatpak sandbox. " +
                "Grant it with: flatpak override --user <this launcher> --filesystem=xdg-run/discord-ipc-0"
        }
        return "No running Discord client was found"
    }

    /**
     * Sandbox-forwarded socket directories, relative to a runtime directory.
     *
     * Flatpak and Snap each put a confined application's sockets under a prefix of their own. The empty
     * entry is the ordinary unconfined install and is first because it is the common case.
     */
    private val SANDBOX_DIRS = listOf(
        "",
        "app/com.discordapp.Discord",
        "app/com.discordapp.DiscordCanary",
        "app/com.discordapp.DiscordPTB",
        "snap.discord",
        "snap.discord-canary",
        "snap.discord-ptb",
    )

    private const val LAST_SOCKET = 9
    private const val FLATPAK_INFO = "/.flatpak-info"
}

/**
 * A Windows named pipe.
 *
 * Opened as a [RandomAccessFile] because a named pipe cannot be opened as a [java.io.FileInputStream] —
 * reading and writing have to be the same handle, and that is the class that offers both.
 */
private class WindowsPipe(path: String) : DiscordPipe {
    private val pipe = RandomAccessFile(path, "rw")

    override val input: InputStream = object : InputStream() {
        override fun read(): Int = pipe.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = pipe.read(b, off, len)
    }

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = pipe.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = pipe.write(b, off, len)
    }

    override fun close() = pipe.close()
}

/** A Unix domain socket, which the JDK has spoken natively since 16. */
private class UnixSocket(path: Path) : DiscordPipe {
    private val channel: SocketChannel = SocketChannel.open(StandardProtocolFamily.UNIX).apply {
        try {
            connect(UnixDomainSocketAddress.of(path))
        } catch (e: IOException) {
            close()
            throw e
        }
    }

    override val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and BYTE_MASK
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = channel.read(ByteBuffer.wrap(b, off, len))
    }

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        // A channel write is not obliged to take everything offered, so the loop is not optional: a short
        // write would silently truncate a frame, and a truncated frame desynchronises the connection for
        // good rather than failing.
        override fun write(b: ByteArray, off: Int, len: Int) {
            val buffer = ByteBuffer.wrap(b, off, len)
            while (buffer.hasRemaining()) channel.write(buffer)
        }
    }

    override fun close() = channel.close()

    private companion object {
        const val BYTE_MASK = 0xFF
    }
}
