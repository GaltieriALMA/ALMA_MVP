package com.alma.mvp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.io.File;
import java.io.FileOutputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

public class WakeWordService extends Service implements RecognitionListener, VoskWakeWord.Listener {

    private static final String CHANNEL_ID = "alma_hands_free";
    private static final int NOTIFICATION_ID = 41;
    private static final long CONVERSATION_IDLE_MS = 5000L;
    private static final Pattern WAKE_WORD = Pattern.compile("\\balma\\b");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AlmaApiClient api = new AlmaApiClient();
    private final String userId = "android_local_user";
    private final String sessionId = UUID.randomUUID().toString();

    private SpeechRecognizer recognizer;
    private Intent recognizerIntent;
    private SecureTokenStore tokenStore;
    private MediaPlayer currentPlayer;
private VoskWakeWord voskWakeWord;
    private boolean listening = false;
    private boolean speaking = false;
    private boolean conversationActive = false;
    private boolean destroyed = false;
    private long lastConversationActivity = 0L;

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

    voskWakeWord.startWake();    
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
     voskWakeWord.startWake();   
        return START_STICKY;
    }

    private void setupRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("Reconocimiento de voz no disponible");
            return;
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(this);

        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        );
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-AR");
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        recognizerIntent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                1200L
        );
        recognizerIntent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                700L
        );
    }

    private void startListening() {
        if (destroyed || speaking || listening || recognizer == null) {
            return;
        }

        try {
            recognizer.startListening(recognizerIntent);
            listening = true;
        } catch (Exception ignored) {
            listening = false;
            scheduleListening(1000);
        }
    }

    private void scheduleListening(long delayMs) {
        handler.removeCallbacks(listenRunnable);
        handler.postDelayed(listenRunnable, delayMs);
    }

    private final Runnable listenRunnable = this::startListening;

    private String firstResult(Bundle bundle) {
        if (bundle == null) {
            return "";
        }

        ArrayList<String> results =
                bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);

        if (results == null || results.isEmpty()) {
            return "";
        }

        String first = results.get(0);
        return first == null ? "" : first.trim();
    }

    private String normalize(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return normalized.toLowerCase(Locale.ROOT);
    }

    private boolean containsWakeWord(String text) {
        return WAKE_WORD.matcher(normalize(text)).find();
    }

    private String removeWakeWord(String text) {
        return text.replaceFirst("(?i)\\balma\\b[\\s,:-]*", "").trim();
    }

    private void activateConversation(String recognizedText) {
        if (conversationActive || speaking) {
            return;
        }

        conversationActive = true;
        lastConversationActivity = System.currentTimeMillis();
        listening = false;

        if (recognizer != null) {
            try {
                recognizer.cancel();
            } catch (Exception ignored) {
            }
        }

        updateNotification("Conversación activa");

        String remainder = removeWakeWord(recognizedText);

        if (!remainder.isEmpty()) {
            sendToAlma(remainder);
        } else {
            resumeConversationListening();
        }
    }

    private void resumeConversationListening() {
        lastConversationActivity = System.currentTimeMillis();
        updateNotification("Conversación activa");
        voskWakeWord.startConversation(CONVERSATION_IDLE_MS);
    }

    private void resetToWakeMode() {
        conversationActive = false;
        updateNotification("Esperando que digas \"ALMA\"");
        voskWakeWord.startWake();
    }

    private void handleNoSpeech() {
        if (conversationActive) {
            long idle = System.currentTimeMillis() - lastConversationActivity;

            if (idle >= CONVERSATION_IDLE_MS) {
                resetToWakeMode();
                return;
            }
        }

        scheduleListening(500);
    }

    private void sendToAlma(String message) {
        if (message == null || message.trim().isEmpty()) {
            handleNoSpeech();
            return;
        }

        lastConversationActivity = System.currentTimeMillis();
        speaking = true;
        listening = false;
        updateNotification("ALMA está pensando");

        if (recognizer != null) {
            try {
                recognizer.cancel();
            } catch (Exception ignored) {
            }
        }

        new Thread(() -> {
            String token = tokenStore.load();

            if (token == null || token.trim().isEmpty()) {
                handler.post(() -> {
                    speaking = false;
                    conversationActive = false;
                    updateNotification("Abrí ALMA para configurar la clave");
                    scheduleListening(1500);
                });
                return;
            }

            try {
                String reply = api.chat(userId, sessionId, message, token);
                byte[] audio = api.tts(reply, token);

                handler.post(() ->
                        playAudio(audio, this::resumeConversationListening)
                );
            } catch (Exception e) {
                handler.post(() -> {
                    speaking = false;
                    updateNotification("Error de conexión. ALMA sigue escuchando");
                    resumeConversationListening();
                });
            }
        }).start();
    }

    private void speakAlmaText(String text, Runnable afterPlayback) {
        speaking = true;
        listening = false;

        new Thread(() -> {
            String token = tokenStore.load();

            if (token == null || token.trim().isEmpty()) {
                handler.post(() -> {
                    speaking = false;
                    conversationActive = false;
                    updateNotification("Abrí ALMA para configurar la clave");
                    scheduleListening(1500);
                });
                return;
            }

            try {
                byte[] audio = api.tts(text, token);
                handler.post(() -> playAudio(audio, afterPlayback));
            } catch (Exception e) {
                handler.post(() -> {
                    speaking = false;
                    if (afterPlayback != null) {
                        afterPlayback.run();
                    }
                });
            }
        }).start();
    }

    private void playAudio(byte[] audio, Runnable afterPlayback) {
        try {
            File file = File.createTempFile(
                    "alma_handsfree_",
                    ".mp3",
                    getCacheDir()
            );

            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(audio);
            }

            if (currentPlayer != null) {
                try {
                    currentPlayer.release();
                } catch (Exception ignored) {
                }
            }

            currentPlayer = new MediaPlayer();
            currentPlayer.setDataSource(file.getAbsolutePath());

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

            currentPlayer.setOnErrorListener((mp, what, extra) -> {
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
            });

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

        if (manager == null) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "ALMA manos libres",
                NotificationManager.IMPORTANCE_LOW
        );

        channel.setDescription(
                "ALMA escucha la palabra de activación mientras el modo manos libres está activo."
        );

        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String status) {
        Intent openApp = new Intent(this, MainActivity.class);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
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
    public void onReadyForSpeech(Bundle params) {
        listening = true;
    }

    @Override
    public void onBeginningOfSpeech() {
    }

    @Override
    public void onRmsChanged(float rmsdB) {
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
    }

    @Override
    public void onEndOfSpeech() {
        listening = false;
    }

    @Override
    public void onError(int error) {
        listening = false;

        if (destroyed || speaking) {
            return;
        }

        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            updateNotification("Falta permiso de micrófono");
            return;
        }

        handleNoSpeech();
    }

    @Override
    public void onResults(Bundle results) {
        listening = false;

        if (destroyed || speaking) {
            return;
        }

        String text = firstResult(results);

        if (text.isEmpty()) {
            handleNoSpeech();
            return;
        }

        if (!conversationActive) {
            if (containsWakeWord(text)) {
                activateConversation(text);
            } else {
                scheduleListening(300);
            }
            return;
        }

        sendToAlma(text);
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        if (destroyed || speaking || conversationActive) {
            return;
        }

        String text = firstResult(partialResults);

        if (!text.isEmpty() && containsWakeWord(text)) {
            activateConversation(text);
        }
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
    }
@Override
public void onWakeWord() {
    if (destroyed || speaking) {
        return;
    }
    activateConversation("alma");
}

@Override
public void onConversationText(String text) {
    if (destroyed || speaking) {
        return;
    }
    sendToAlma(text);
}

@Override
public void onConversationTimeout() {
    if (destroyed) {
        return;
    }
    resetToWakeMode();
}

@Override
public void onError(Exception error) {
    if (destroyed) {
        return;
    }
    updateNotification("Error de reconocimiento. Reintentando");
    resetToWakeMode();
}
    @Override
    public void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
if (voskWakeWord != null) {
    voskWakeWord.destroy();
    voskWakeWord = null;
}
        if (recognizer != null) {
            try {
                recognizer.cancel();
                recognizer.destroy();
            } catch (Exception ignored) {
            }

            recognizer = null;
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
