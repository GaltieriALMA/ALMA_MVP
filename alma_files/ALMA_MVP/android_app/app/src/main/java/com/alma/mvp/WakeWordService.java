package com.alma.mvp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.MediaPlayer;
import android.provider.MediaStore;
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
    private File currentAudioFile;
    private VoskWakeWord voskWakeWord;

    private boolean speaking = false;
    private boolean conversationActive = false;
    private boolean destroyed = false;

    private volatile byte[] wakeAckAudio;
    private static final long WAKE_SECURITY_COOLDOWN_MS = 4000L;
    private long lastWakeAcceptedAt = 0L;

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
                handler.postDelayed(this::preloadWakeAcknowledgement, 1500);
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

        boolean wantsVideo =
                normalized.contains("video")
                || normalized.contains("youtube")
                || ((normalized.startsWith("pone ")
                        || normalized.startsWith("poneme ")
                        || normalized.startsWith("reproduci ")
                        || normalized.startsWith("reproduce "))
                    && normalized.contains("altavoz"));

        if (!wantsVideo) {
            return false;
        }

        String query = normalized
                .replaceFirst("^(busca|buscame|mostra|mostrame|pone|poneme|reproduci|reproduce)\\s+", "")
                .replaceFirst("^un\\s+", "")
                .replaceFirst("^video\\s+(de|del)?\\s*", "")
                .replaceAll("\\b(en\\s+)?youtube\\b", "")
                .replaceAll("\\b(en\\s+el\\s+|por\\s+el\\s+)?altavoz\\b", "")
                .replaceAll("\\s+", " ")
                .trim();

        if (query.isEmpty()) {
            return false;
        }

        speaking = true;
        updateNotification("Buscando en YouTube: " + query);

        new Thread(() -> {
            String token = tokenStore.load();

            if (token == null || token.trim().isEmpty()) {
                handler.post(() -> {
                    speaking = false;
                    updateNotification("Abrí ALMA para configurar la clave");
                    resetToWakeMode();
                });
                return;
            }

            try {
                org.json.JSONObject result = api.searchYouTube(query, token);
                String videoId = result.getString("video_id");
                String title = result.optString("title", query);

                handler.post(() -> {
                    try {
                        Intent intent = new Intent(
                                Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://www.youtube.com/watch?v=" + videoId)
                        );
                        intent.setPackage("com.google.android.youtube");
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Exception e) {
                        Intent fallback = new Intent(
                                Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://www.youtube.com/watch?v=" + videoId)
                        );
                        fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(fallback);
                    }

                    speaking = false;
                    stopSelf();
                });

            } catch (Exception e) {
                handler.post(() -> {
                    speaking = false;
                    updateNotification("No pude buscar el video");
                    resetToWakeMode();
                });
            }
        }, "ALMA-YouTube").start();

        return true;
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
            currentAudioFile = file;

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
                currentAudioFile = null;
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
    public void onInterrupt(String recognizedText) {
        if (destroyed || !speaking) return;

        MediaPlayer player = currentPlayer;
        currentPlayer = null;

        if (player != null) {
            try {
                player.setOnCompletionListener(null);
                player.setOnErrorListener(null);
                player.stop();
            } catch (Exception ignored) {
            }

            try {
                player.release();
            } catch (Exception ignored) {
            }
        }

        File file = currentAudioFile;
        currentAudioFile = null;

        if (file != null) {
            try {
                file.delete();
            } catch (Exception ignored) {
            }
        }

        speaking = false;
        conversationActive = true;
        updateNotification("Te escucho");

        if (voskWakeWord != null) {
            voskWakeWord.startConversation(
                    CONVERSATION_IDLE_MS
            );
        }
    }

    @Override
    public void onConversationAudio(byte[] pcm16, String localText) {
        if (destroyed || speaking) return;


        speaking = true;
        updateNotification("Entendiendo tu voz");

        new Thread(() -> {
            String token = tokenStore.load();

            if (token == null || token.trim().isEmpty()) {
                handler.post(() -> {
                    speaking = false;
                    resetToWakeMode();
                });
                return;
            }

            try {
                String transcript = api.transcribe(pcm16, token);

                handler.post(() -> {
                    speaking = false;

                    String text = transcript == null
                            ? ""
                            : transcript.trim();

                    if (text.isEmpty()) {
                        resumeConversationListening();
                        return;
                    }

                    if (isEndPhrase(text)) {
                        resetToWakeMode();
                        return;
                    }

                    sendToAlma(text);
                });

            } catch (Exception e) {
                handler.post(() -> {
                    speaking = false;

                    String fallback = localText == null
                            ? ""
                            : localText.trim();

                    if (!fallback.isEmpty()) {
                        if (isEndPhrase(fallback)) {
                            resetToWakeMode();
                        } else {
                            sendToAlma(fallback);
                        }
                    } else {
                        updateNotification("Seguimos escuchando");
                        resumeConversationListening();
                    }
                });
            }
        }, "ALMA-Transcribe").start();
    }

    @Override
    public void onConversationTimeout() {
        if (destroyed) return;

        resetToWakeMode();
    }

    @Override
    public void onError(Exception error) {
        if (destroyed) return;

        if (speaking) {
            updateNotification("ALMA está hablando");
            return;
        }

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
