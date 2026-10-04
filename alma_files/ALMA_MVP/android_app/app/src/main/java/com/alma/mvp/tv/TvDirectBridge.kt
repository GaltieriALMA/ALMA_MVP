package com.alma.mvp.tv

import android.app.Activity
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.*
import java.net.Inet4Address
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
    private const val DEFAULT_HOST = "192.168.1.7"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
            val started = session.start("ALMA")

            if (started.isFailure) {
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
                    .setMessage("Ingresá el código que aparece en la pantalla del televisor. Se hace una sola vez.")
                    .setView(input)
                    .setPositiveButton("Vincular", null)
                    .setNegativeButton("Cancelar") { _, _ ->
                        session.close()
                    }
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

                                withContext(Dispatchers.Main) {
                                    dialog.dismiss()
                                }

                                answer(
                                    callback,
                                    true,
                                    "Televisor vinculado. ALMA ya puede controlarlo directamente."
                                )
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
            val prefs = activity.getSharedPreferences(PREFS, 0)
            var host = prefs.getString(HOST_KEY, DEFAULT_HOST) ?: DEFAULT_HOST

            if (!portOpen(host, 6466, 500) && !portOpen(host, 6467, 500)) {
                host = discoverHost(activity) ?: run {
                    answer(callback, false, "No pude encontrar el televisor.")
                    return@launch
                }
            }

            val identity = runCatching {
                AtvCertificateStore.identity(activity.applicationContext)
            }.getOrElse {
                answer(callback, false, "No pude acceder a la vinculación de la TV.")
                return@launch
            }

            val client = AtvRemoteClient(host, identity)
            val connected = client.connect()

            if (connected.isFailure) {
                client.close()
                answer(
                    callback,
                    false,
                    "La TV todavía no autorizó a ALMA. Decime: ALMA, vinculá la TV."
                )
                return@launch
            }

            val key = when (action) {
                "volume_up" -> AtvKey.VOLUME_UP
                "volume_down" -> AtvKey.VOLUME_DOWN
                "mute" -> AtvKey.VOLUME_MUTE
                "home" -> AtvKey.HOME
                "back" -> AtvKey.BACK
                "power_off" -> 223
                "power_on" -> 224
                else -> null
            }

            if (key == null) {
                client.close()
                answer(callback, false, "No reconocí esa orden para la TV.")
                return@launch
            }

            client.sendKey(key)
            delay(700)
            client.close()

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

    private fun portOpen(host: String, port: Int, timeout: Int): Boolean =
        runCatching {
            Socket().use {
                it.connect(InetSocketAddress(host, port), timeout)
            }
            true
        }.getOrDefault(false)
}
