package com.alma.mvp.tv

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Android keycodes, as carried by RemoteKeyInject. */
object AtvKey {
    const val HOME = 3
    const val BACK = 4
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val POWER = 26
    const val MEDIA_STOP = 86
    const val MEDIA_REWIND = 89
    const val MEDIA_FAST_FORWARD = 90
    const val MENU = 82
    const val MEDIA_PLAY = 126
    const val MEDIA_PAUSE = 127
    const val VOLUME_MUTE = 164
    const val INFO = 165
    const val CHANNEL_UP = 166
    const val CHANNEL_DOWN = 167
    const val TV_INPUT = 178
    const val TV_INPUT_HDMI_1 = 243
    const val TV_INPUT_HDMI_2 = 244
    const val TV_INPUT_HDMI_3 = 245
    const val TV_INPUT_HDMI_4 = 246
    const val ALL_APPS = 284
}

/**
 * The Android TV remote channel (port 6466).
 *
 * Connecting requires a certificate the TV has already accepted through
 * [AtvPairingSession]; an unpaired certificate is refused at the TLS layer.
 *
 * The TV drives the conversation — it asks for configuration, activates the
 * session, and then pings periodically. Missing a ping reply drops the
 * connection, so a reader loop runs for as long as the client is open.
 */
class AtvRemoteClient(
    private val host: String,
    private val identity: AtvIdentity,
    private val onPowerState: (Boolean) -> Unit = {},
    private val onDisconnect: (String) -> Unit = {},
) : Closeable {

    private var connection: AtvTls.Connection? = null
    private var output: OutputStream? = null
    private var scope: CoroutineScope? = null

    /** Serialises all socket writes off the caller's thread. */
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "atv-remote-writer").apply { isDaemon = true }
    }

    @Volatile private var ready = false

    /** True once the TV reports it is awake. */
    @Volatile var isOn = false
        private set

    val isConnected: Boolean get() = ready

    /** Connects and completes the handshake, returning once the TV is ready. */
    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            teardownConnection()
            // A bounded read during the handshake: without it, a TV that never
            // finishes the exchange leaves the UI stuck on "Connecting" forever.
            val conn = AtvTls.connect(
                host = host,
                port = REMOTE_PORT,
                identity = identity,
                readTimeoutMs = HANDSHAKE_TIMEOUT_MS,
            )
            connection = conn
            output = conn.socket.outputStream

            val input = conn.socket.inputStream

            // The handshake is synchronous up to the point the TV activates the
            // session; after that a background loop keeps answering pings.
            while (!ready) {
                val msg = parseProto(input.readFramed())
                Log.d(TAG, "rx fields=${msg.keys.sorted()}")
                handle(msg)
            }
            Log.d(TAG, "Remote channel ready, isOn=$isOn")
            conn.socket.soTimeout = 0

            val job = SupervisorJob()
            CoroutineScope(Dispatchers.IO + job).also { created ->
                scope = created
                created.launch {
                    try {
                        // Pings arrive every few seconds and would swamp the log.
                        while (true) handle(parseProto(input.readFramed()))
                    } catch (t: Throwable) {
                        Log.w(TAG, "reader loop ended", t)
                        if (ready) {
                            ready = false
                            onDisconnect(t.message ?: "Connection to the TV was lost")
                        }
                    }
                }
            }
            Unit
        }.onFailure { teardownConnection() }
    }

    private fun handle(msg: Map<Int, List<ProtoValue>>) {
        when {
            // The TV asks what the client supports before anything else.
            msg.has(FIELD_CONFIGURE) -> send {
                message(FIELD_CONFIGURE) {
                    varint(1, FEATURES)
                    message(2) { // device_info
                        varint(3, 1)
                        string(4, "1")
                        string(5, "atvremote")
                        string(6, "1.0.0")
                    }
                }
            }

            msg.has(FIELD_SET_ACTIVE) -> send {
                message(FIELD_SET_ACTIVE) { varint(1, FEATURES) }
            }

            // Unanswered pings are treated by the TV as a dead client.
            msg.has(FIELD_PING_REQUEST) -> {
                val value = msg.message(FIELD_PING_REQUEST)?.num(1) ?: 0L
                send { message(FIELD_PING_RESPONSE) { varint(1, value) } }
            }

            msg.has(FIELD_START) -> {
                isOn = msg.message(FIELD_START)?.num(1) == 1L
                Log.d(TAG, "TV reports isOn=$isOn")
                onPowerState(isOn)
                ready = true
            }
        }
    }

    fun sendKey(keyCode: Int) {
        Log.d(TAG, "tx key=$keyCode")
        send {
            message(FIELD_KEY_INJECT) {
                varint(1, keyCode)
                varint(2, DIRECTION_SHORT)
            }
        }
    }

    /** Opens an app by deep link, e.g. a Netflix URL. */
    fun launchAppLink(link: String) = send {
        message(FIELD_APP_LINK) { string(1, link) }
    }

    /**
     * Encodes on the calling thread but always writes on [writer].
     *
     * Key presses arrive from the UI thread, where Android forbids network I/O
     * outright; a single-threaded executor also keeps presses in the order they
     * were made.
     */
    private fun send(build: ProtoWriter.() -> Unit) {
        val payload = ProtoWriter().apply(build).toByteArray()
        val stream = output ?: run {
            Log.w(TAG, "send() with no open stream")
            return
        }
        runCatching {
            writer.execute {
                runCatching { stream.writeFramed(payload) }
                    .onFailure {
                        Log.w(TAG, "send failed", it)
                        ready = false
                        onDisconnect(it.message ?: "Could not reach the TV")
                    }
            }
        }.onFailure { Log.w(TAG, "writer rejected the message", it) }
    }

    override fun close() {
        teardownConnection()
        writer.shutdownNow()
    }

    /** Drops the socket but keeps the writer, so the client can reconnect. */
    private fun teardownConnection() {
        ready = false
        scope?.cancel()
        scope = null
        runCatching { connection?.socket?.close() }
        connection = null
        output = null
    }

    private companion object {
        const val TAG = "TvRemote"
        const val REMOTE_PORT = 6466
        const val HANDSHAKE_TIMEOUT_MS = 8_000

        const val FIELD_CONFIGURE = 1
        const val FIELD_SET_ACTIVE = 2
        const val FIELD_PING_REQUEST = 8
        const val FIELD_PING_RESPONSE = 9
        const val FIELD_KEY_INJECT = 10
        const val FIELD_START = 40
        const val FIELD_APP_LINK = 90

        const val DIRECTION_SHORT = 3

        /** PING | KEY | POWER | VOLUME | APP_LINK */
        const val FEATURES = 1 or 2 or 32 or 64 or 512
    }
}
