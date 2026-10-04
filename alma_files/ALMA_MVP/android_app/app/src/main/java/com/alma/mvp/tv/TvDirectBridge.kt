package com.alma.mvp.tv

import android.app.Activity
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors

object TvDirectBridge {
    fun interface Callback {
        fun onResult(ok: Boolean, message: String)
    }

    private const val PREFS = "alma_tv_direct"
    private const val HOST_KEY = "host"
    private const val DEFAULT_HOST = "192.168.1.19"
    private const val TV_MAC = "b0:1c:0c:a2:52:c5"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandMutex = Mutex()

    @Volatile private var remote: AtvRemoteClient? = null
    @Volatile private var remoteHost: String? = null

    @JvmStatic
    fun pair(activity: Activity, callback: Callback) {
        scope.launch {
            val host = discoverHost(activity) ?: run {
                answer(callback, false, "No encontré el televisor en la red.")
                return@launch
            }

            val identity = runCatching {
                AtvCertificateStore.identity(activity.applicationContext)
            }.getOrElse {
                answer(callback, false, "No pude crear la identidad segura de ALMA.")
                return@launch
            }

            val session = AtvPairingSession(host, identity)

            if (session.start("ALMA").isFailure) {
                answer(callback, false, "No pude iniciar la vinculación con la TV.")
                return@launch
            }

            withContext(Dispatchers.Main) {
                val input = EditText(activity).apply {
                    hint = "Código de 6 caracteres"
                    inputType = InputType.TYPE_CLASS_TEXT
                }

                val dialog = AlertDialog.Builder(activity)
                    .setTitle("Vincular ALMA con la TV")
                    .setMessage("Ingresá el código que aparece en la TV. Esto se hace una sola vez.")
                    .setView(input)
                    .setPositiveButton("Vincular", null)
                    .setNegativeButton("Cancelar") { _, _ -> session.close() }
                    .create()

                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val code = input.text.toString().trim().uppercase()

                        scope.launch {
                            val result = session.complete(code)

                            if (result.isSuccess) {
                                activity.getSharedPreferences(PREFS, 0)
                                    .edit()
                                    .putString(HOST_KEY, host)
                                    .apply()

                                remote?.close()
                                remote = null
                                remoteHost = null

                                withContext(Dispatchers.Main) {
                                    dialog.dismiss()
                                }

                                answer(callback, true, "Televisor vinculado con ALMA.")
                            } else {
                                withContext(Dispatchers.Main) {
                                    input.error = "Revisá el código de la TV"
                                }
                            }
                        }
                    }
                }

                dialog.show()
            }
        }
    }

    @JvmStatic
    fun send(activity: Activity, action: String, callback: Callback) {
        scope.launch {
            commandMutex.withLock {
                val prefs = activity.getSharedPreferences(PREFS, 0)
                var host = prefs.getString(HOST_KEY, DEFAULT_HOST) ?: DEFAULT_HOST

                if (action == "power_on" &&
                    (remote == null || remoteHost != host || remote?.isConnected != true)) {

                    wakeByLan(host)

                    var online = false
                    for (i in 0 until 24) {
                        if (portOpen(host, 6466, 400)) {
                            online = true
                            break
                        }
                        delay(500)
                    }

                    if (!online) {
                        val found = discoverHost(activity)
                        if (found != null) {
                            host = found
                            online = true
                        }
                    }

                    if (!online) {
                        answer(callback, true, "Envié la señal de encendido al televisor.")
                        return@withLock
                    }
                }

                if ((remote == null || remoteHost != host || remote?.isConnected != true) &&
                    !portOpen(host, 6466, 600) && !portOpen(host, 6467, 600)) {
                    host = discoverHost(activity) ?: run {
                        answer(callback, false, "No pude encontrar el televisor.")
                        return@withLock
                    }
                }

                val identity = runCatching {
                    AtvCertificateStore.identity(activity.applicationContext)
                }.getOrElse {
                    answer(callback, false, "No pude acceder a la vinculación de la TV.")
                    return@withLock
                }

                var client = remote

                if (client == null || remoteHost != host || !client.isConnected) {
                    runCatching { client?.close() }

                    client = AtvRemoteClient(
                        host,
                        identity,
                        onDisconnect = {
                            remote = null
                            remoteHost = null
                        }
                    )

                    if (client.connect().isFailure) {
                        client.close()
                        remote = null
                        remoteHost = null
                        answer(callback, false, "No pude comunicarme con la TV.")
                        return@withLock
                    }

                    remote = client
                    remoteHost = host
                }

                val key = when (action) {
                    "volume_up" -> AtvKey.VOLUME_UP
                    "volume_down" -> AtvKey.VOLUME_DOWN
                    "mute" -> AtvKey.VOLUME_MUTE
                    "home" -> AtvKey.HOME
                    "back" -> AtvKey.BACK
                    "power_off" -> 177
                    "power_on" -> 224
                    else -> null
                }

                if (key == null) {
                    answer(callback, false, "No reconocí esa orden para la TV.")
                    return@withLock
                }

                client.sendKey(key)
                delay(300)

                prefs.edit().putString(HOST_KEY, host).apply()

                val reply = when (action) {
                    "volume_up" -> "Subí el volumen del televisor."
                    "volume_down" -> "Bajé el volumen del televisor."
                    "mute" -> "Cambié el silencio del televisor."
                    "home" -> "Abrí la pantalla principal del televisor."
                    "back" -> "Volví atrás en el televisor."
                    "power_off" -> "Apagué el televisor."
                    "power_on" -> "Encendí el televisor."
                    else -> "Listo."
                }

                answer(callback, true, reply)
            }
        }
    }

    private suspend fun answer(callback: Callback, ok: Boolean, message: String) {
        withContext(Dispatchers.Main) {
            callback.onResult(ok, message)
        }
    }

    private fun discoverHost(activity: Activity): String? {
        val prefs = activity.getSharedPreferences(PREFS, 0)
        val saved = prefs.getString(HOST_KEY, DEFAULT_HOST)

        if (saved != null &&
            (portOpen(saved, 6467, 350) || portOpen(saved, 6466, 350))) {
            return saved
        }

        val local = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?: return null

        val parts = local.hostAddress?.split(".") ?: return null
        if (parts.size != 4) return null

        val base = "${parts[0]}.${parts[1]}.${parts[2]}"
        val pool = Executors.newFixedThreadPool(32)

        return try {
            val jobs = (1..254).map { n ->
                pool.submit(Callable {
                    val host = "$base.$n"
                    if (portOpen(host, 6467, 180) || portOpen(host, 6466, 180)) host else null
                })
            }

            jobs.firstNotNullOfOrNull { job ->
                runCatching { job.get() }.getOrNull()
            }?.also {
                prefs.edit().putString(HOST_KEY, it).apply()
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun wakeByLan(host: String) {
        runCatching {
            val mac = TV_MAC.split(":").map { it.toInt(16).toByte() }.toByteArray()
            val data = ByteArray(6 + 16 * mac.size)

            for (i in 0 until 6) data[i] = 0xFF.toByte()
            for (i in 6 until data.size) {
                data[i] = mac[(i - 6) % mac.size]
            }

            val parts = host.split(".")
            val targets = linkedSetOf("255.255.255.255")

            if (parts.size == 4) {
                targets.add("${parts[0]}.${parts[1]}.${parts[2]}.255")
            }

            DatagramSocket().use { socket ->
                socket.broadcast = true

                repeat(10) {
                    for (target in targets) {
                        val address = InetAddress.getByName(target)
                        socket.send(DatagramPacket(data, data.size, address, 9))
                        socket.send(DatagramPacket(data, data.size, address, 7))
                    }
                    Thread.sleep(150)
                }
            }
        }
    }

    private fun portOpen(host: String, port: Int, timeout: Int): Boolean =
        runCatching {
            Socket().use {
                it.connect(InetSocketAddress(host, port), timeout)
            }
            true
        }.getOrDefault(false)
}
