package com.alma.mvp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

public class WakeWordService extends Service
        implements VoskWakeWord.Listener {

    private static final String CHANNEL_ID =
            "alma_hands_free_silent_v3";
    private static final int NOTIFICATION_ID = 41;
    private static final long CONVERSATION_IDLE_MS = 10000L;

    private static final Pattern END_PHRASE = Pattern.compile(
            "^(chau|chao|adios|listo|gracias|terminamos|"
                    + "eso es todo|nada mas|hasta luego)$"
    );

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private final AlmaApiClient api = new AlmaApiClient();
    private final String userId = "android_local_user";
    private final String sessionId = UUID.randomUUID().toString();

    private SecureTokenStore tokenStore;
    private MediaPlayer currentPlayer;
    private VoskWakeWord voskWakeWord;

    private boolean speaking = false;
    private boolean conversationActive = false;
    private boolean destroyed = false;

    private volatile byte[] wakeAckAudio;

    @Override
    public void onCreate() {
        super.onCreate();

        tokenStore = new SecureTokenStore(this);
        voskWakeWord = new VoskWakeWord(this, this);

        createNotificationChannel();

        startForeground(
                NOTIFICATION_ID,
                buildNotification("Esperando que digas \"ALMA\"")
        );

        preloadWakeAcknowledgement();
        voskWakeWord.startWake();
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {
        return START_STICKY;
    }

    private String normalize(String text) {
        if (text == null) return "";

        return Normalizer.normalize(
                        text,
                        Normalizer.Form.NFD
                )
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private String removeWakeWord(String text) {
        if (text == null) return "";

        return text.replaceFirst(
                "(?i)^\\s*(?:hola\\s+)?alma\\b[\\s,:-]*",
                ""
        ).trim();
    }

    private boolean isEndPhrase(String text) {
        return END_PHRASE.matcher(normalize(text)).matches();
    }

    private void activateConversation(String recognizedText) {
        if (destroyed || conversationActive || speaking) {
            return;
        }

        conversationActive = true;
        updateNotification("ALMA te escuchó");

        String remainder = removeWakeWord(recognizedText);

        if (!remainder.isEmpty()) {
            sendToAlma(remainder);
            return;
        }

        playWakeAcknowledgement();
    }

    private void playWakeAcknowledgement() {
        byte[] cached = wakeAckAudio;

        if (cached != null && cached.length > 0) {
            playAudio(
                    cached,
                    this::resumeConversationListening
            );
            return;
        }

        speakAlmaText(
                "Sí, te escucho.",
                this::resumeConversationListening
        );
    }

    private void preloadWakeAcknowledgement() {
        new Thread(() -> {
            String token = tokenStore.load();

            if (token == null || token.trim().isEmpty()) {
                return;
            }

            try {
                wakeAckAudio =
                        api.tts("Sí, te escucho.", token);
            } catch (Exception ignored) {
            }
        }, "ALMA-Wake-Ack-Preload").start();
    }

    private void resumeConversationListening() {
        if (destroyed || voskWakeWord == null) return;

        speaking = false;
        conversationActive = true;
        updateNotification("Conversación activa");

        voskWakeWord.startConversation(
                CONVERSATION_IDLE_MS
        );
    }

    private void resetToWakeMode() {
        if (destroyed || voskWakeWord == null) return;

        speaking = false;
        conversationActive = false;
        updateNotification("Esperando que digas \"ALMA\"");

        voskWakeWord.startWake();
    }

    private boolean handleVideoCommand(String message) {
        String normalized = normalize(message);
        String[] prefixes = {"busca un video de ", "busca video de ", "buscar un video de ", "buscar video de ", "buscame un video de ", "buscame video de ", "busca en youtube ", "buscame en youtube ", "mostrame un video de ", "mostra un video de ", "pone un video de ", "poneme un video de ", "reproduci un video de ", "reproducir un video de "};
        for (String prefix : prefixes) {
            if (normalized.startsWith(prefix)) {
                String query = normalized.substring(prefix.length()).trim();
                if (query.isEmpty()) return false;
                try {
                    Intent videoIntent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/results?search_query=" + android.net.Uri.encode(query)));
                    videoIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(videoIntent);
                    updateNotification("Buscando video: " + query);
                    resetToWakeMode();
                } catch (Exception e) {
                    updateNotification("No pude abrir YouTube");
                    resetToWakeMode();
                }
                return true;
            }
        }
        return false;
    }

    private void sendToAlma(String message) {
        if (message == null || message.trim().isEmpty()) {
            resetToWakeMode();
            return;
        }

        if (handleVideoCommand(message)) {
            return;
        }

        speaking = true;
        updateNotification("ALMA está pensando");

        new Thread(() -> {
            String token = tokenStore.load();

            if (token == null || token.trim().isEmpty()) {
                handler.post(() -> {
                    speaking = false;
                    conversationActive = false;
                    updateNotification(
                            "Abrí ALMA para configurar la clave"
                    );

                    handler.postDelayed(
                            this::resetToWakeMode,
                            1200
                    );
                });
                return;
            }

            try {
                String reply = api.chat(
                        userId,
                        sessionId,
                        message,
                        token
                );

                byte[] audio = api.tts(reply, token);

                handler.post(() ->
                        playAudio(
                                audio,
                                this::resumeConversationListening
                        )
                );

            } catch (Exception e) {
                handler.post(() -> {
                    speaking = false;
                    updateNotification(
                            "Error de conexión. ALMA sigue activa"
                    );
                    resumeConversationListening();
                });
            }
        }, "ALMA-Chat").start();
    }

    private void speakAlmaText(
            String text,
            Runnable afterPlayback
    ) {
        speaking = true;

        new Thread(() -> {
            String token = tokenStore.load();

            if (token == null || token.trim().isEmpty()) {
                handler.post(() -> {
                    speaking = false;
                    conversationActive = false;
                    updateNotification(
                            "Abrí ALMA para configurar la clave"
                    );
                    handler.postDelayed(
                            this::resetToWakeMode,
                            1200
                    );
                });
                return;
            }

            try {
                byte[] audio = api.tts(text, token);

                handler.post(() ->
                        playAudio(audio, afterPlayback)
                );

            } catch (Exception e) {
                handler.post(() -> {
                    speaking = false;

                    if (afterPlayback != null) {
                        afterPlayback.run();
                    }
                });
            }
        }, "ALMA-TTS").start();
    }

    private void playAudio(
            byte[] audio,
            Runnable afterPlayback
    ) {
        try {
            speaking = true;

            if (voskWakeWord != null) {
                voskWakeWord.stopListening();
            }

            File file = File.createTempFile(
                    "alma_handsfree_",
                    ".mp3",
                    getCacheDir()
            );

            try (FileOutputStream out =
                         new FileOutputStream(file)) {
                out.write(audio);
            }

            if (currentPlayer != null) {
                try {
                    currentPlayer.release();
                } catch (Exception ignored) {
                }
            }

            currentPlayer = new MediaPlayer();
            currentPlayer.setDataSource(
                    file.getAbsolutePath()
            );

            currentPlayer.setOnCompletionListener(mp -> {
                try {
                    mp.release();
                } catch (Exception ignored) {
                }

                currentPlayer = null;
                file.delete();
                speaking = false;

                if (afterPlayback != null) {
                    afterPlayback.run();
                }
            });

            currentPlayer.setOnErrorListener(
                    (mp, what, extra) -> {
                        try {
                            mp.release();
                        } catch (Exception ignored) {
                        }

                        currentPlayer = null;
                        file.delete();
                        speaking = false;

                        if (afterPlayback != null) {
                            afterPlayback.run();
                        }

                        return true;
                    }
            );

            currentPlayer.prepare();
            currentPlayer.start();

            updateNotification("ALMA está hablando");

        } catch (Exception e) {
            speaking = false;

            if (afterPlayback != null) {
                afterPlayback.run();
            }
        }
    }

    private void createNotificationChannel() {
        NotificationManager manager =
                getSystemService(NotificationManager.class);

        if (manager == null) return;

        NotificationChannel channel =
                new NotificationChannel(
                        CHANNEL_ID,
                        "ALMA manos libres",
                        NotificationManager.IMPORTANCE_LOW
                );

        channel.setDescription(
                "ALMA escucha la palabra de activación "
                        + "mientras el modo manos libres está activo."
        );
        channel.setSound(null, null);
        channel.enableVibration(false);

        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String status) {
        Intent openApp =
                new Intent(this, MainActivity.class);

        PendingIntent pendingIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        openApp,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE
                );

        return new Notification.Builder(
                this,
                CHANNEL_ID
        )
                .setSmallIcon(
                        android.R.drawable.ic_btn_speak_now
                )
                .setContentTitle("ALMA · Manos libres")
                .setContentText(status)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String status) {
        NotificationManager manager =
                getSystemService(NotificationManager.class);

        if (manager != null) {
            manager.notify(
                    NOTIFICATION_ID,
                    buildNotification(status)
            );
        }
    }

    @Override
    public void onWakeWord(String recognizedText) {
        if (destroyed || speaking) return;

        activateConversation(recognizedText);
    }

    @Override
    public void onConversationText(String text) {
        if (destroyed || speaking) return;

        if (isEndPhrase(text)) {
            resetToWakeMode();
            return;
        }

        sendToAlma(text);
    }

    @Override
    public void onConversationTimeout() {
        if (destroyed) return;

        resetToWakeMode();
    }

    @Override
    public void onError(Exception error) {
        if (destroyed) return;

        updateNotification(
                "Reiniciando reconocimiento"
        );

        if (voskWakeWord != null) {
            voskWakeWord.stopListening();
        }

        handler.postDelayed(
                this::resetToWakeMode,
                800
        );
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);

        if (voskWakeWord != null) {
            voskWakeWord.destroy();
            voskWakeWord = null;
        }

        if (currentPlayer != null) {
            try {
                currentPlayer.release();
            } catch (Exception ignored) {
            }
            currentPlayer = null;
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
