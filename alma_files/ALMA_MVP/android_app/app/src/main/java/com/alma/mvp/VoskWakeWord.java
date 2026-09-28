package com.alma.mvp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.StorageService;

import java.util.Locale;
import java.util.regex.Pattern;

public final class VoskWakeWord {

    public interface Listener {
        void onWakeWord(String recognizedText);
        void onConversationText(String text);
        void onConversationTimeout();
        void onError(Exception error);
    }

    private enum Mode {
        NONE,
        WAKE,
        CONVERSATION
    }

    private static final float SAMPLE_RATE = 16000.0f;
    private static final Pattern WAKE_WORD =
            Pattern.compile("\\balma\\b");

    private final Context context;
    private final Listener listener;
    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private Model model;
    private Recognizer recognizer;
    private AudioRecord audioRecord;
    private Thread audioThread;

    private Mode desiredMode = Mode.NONE;
    private volatile Mode activeMode = Mode.NONE;

    private boolean loadingModel = false;
    private boolean destroyed = false;
    private volatile boolean stopRequested = false;

    private long conversationTimeoutMs = 10000L;
    private volatile long lastVoiceActivity = 0L;
private static final long PARTIAL_WAKE_DEBOUNCE_MS = 800L;
private volatile String pendingWakePartial = "";

private final Runnable partialWakeRunnable = () -> {
    if (destroyed || activeMode != Mode.WAKE) return;

    String text = pendingWakePartial;
    pendingWakePartial = "";

    if (containsWakeWord(text)) {
        wakeDetected(text);
    }
};
    public VoskWakeWord(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void startWake() {
        if (destroyed) return;
        desiredMode = Mode.WAKE;
        stopEngine();
        ensureModel();
    }

    public void startConversation(long timeoutMs) {
        if (destroyed) return;
        desiredMode = Mode.CONVERSATION;
        conversationTimeoutMs = timeoutMs;
        lastVoiceActivity = System.currentTimeMillis();
        stopEngine();
        ensureModel();
    }

    public void stopListening() {
        desiredMode = Mode.NONE;
        handler.removeCallbacks(conversationTimeoutRunnable);
        stopEngine();
    }

    private void ensureModel() {
        if (model != null) {
            startDesiredMode();
            return;
        }

        if (loadingModel) return;
        loadingModel = true;

        StorageService.unpack(
                context,
                "model-es",
                "model",
                loadedModel -> {
                    loadingModel = false;

                    if (destroyed) {
                        try {
                            loadedModel.close();
                        } catch (Exception ignored) {
                        }
                        return;
                    }

                    model = loadedModel;
                    startDesiredMode();
                },
                exception -> {
                    loadingModel = false;

                    if (!destroyed && listener != null) {
                        listener.onError(exception);
                    }
                }
        );
    }

    @SuppressLint("MissingPermission")
    private void startDesiredMode() {
        if (destroyed || model == null || desiredMode == Mode.NONE) return;

        try {
            recognizer = new Recognizer(model, SAMPLE_RATE);
            recognizer.setWords(true);
            recognizer.setEndpointerMode(Recognizer.EndpointerMode.SHORT);
            recognizer.setEndpointerDelays(3.0f, 0.35f, 10.0f);

            int minBufferBytes = AudioRecord.getMinBufferSize(
                    (int) SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );

            if (minBufferBytes <= 0) {
                throw new IllegalStateException(
                        "No se pudo calcular el buffer del micrófono"
                );
            }

            int bufferBytes = Math.max(minBufferBytes, 6400);

            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    (int) SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException(
                        "No se pudo inicializar el micrófono"
                );
            }

            activeMode = desiredMode;
            stopRequested = false;
            audioRecord.startRecording();

            if (audioRecord.getRecordingState()
                    != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException(
                        "No se pudo iniciar la grabación"
                );
            }

            audioThread = new Thread(
                    this::runAudioLoop,
                    "ALMA-Vosk-Mic"
            );
            audioThread.start();

            if (activeMode == Mode.CONVERSATION) {
                lastVoiceActivity = System.currentTimeMillis();
                handler.removeCallbacks(conversationTimeoutRunnable);
                handler.postDelayed(
                        conversationTimeoutRunnable,
                        conversationTimeoutMs
                );
            }

        } catch (Exception e) {
            stopEngine();

            if (!destroyed && listener != null) {
                listener.onError(e);
            }
        }
    }

    private void runAudioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        short[] buffer = new short[1600];

        try {
            while (!stopRequested) {
                AudioRecord recorder = audioRecord;
                Recognizer currentRecognizer = recognizer;

                if (recorder == null || currentRecognizer == null) {
                    return;
                }

                int read = recorder.read(buffer, 0, buffer.length);

                if (stopRequested) return;

                if (read < 0) {
                    throw new IllegalStateException(
                            "Error leyendo el micrófono: " + read
                    );
                }

                if (read == 0) continue;

                for (int i = 0; i < read; i++) {
                    int amplified = buffer[i] * 3;
                    if (amplified > Short.MAX_VALUE) amplified = Short.MAX_VALUE;
                    if (amplified < Short.MIN_VALUE) amplified = Short.MIN_VALUE;
                    buffer[i] = (short) amplified;
                }

                if (currentRecognizer.acceptWaveForm(buffer, read)) {
                    String hypothesis = currentRecognizer.getResult();
                    handler.post(() -> handleResult(hypothesis));
                } else {
                    String hypothesis = currentRecognizer.getPartialResult();
                    handler.post(() -> handlePartialResult(hypothesis));
                }
            }
        } catch (Exception e) {
            if (!stopRequested && !destroyed) {
                handler.post(() -> {
                    stopEngine();

                    if (!destroyed && listener != null) {
                        listener.onError(e);
                    }
                });
            }
        }
    }

    private final Runnable conversationTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyed || activeMode != Mode.CONVERSATION) return;

            long idle =
                    System.currentTimeMillis() - lastVoiceActivity;

            if (idle >= conversationTimeoutMs) {
                stopListening();

                if (listener != null) {
                    listener.onConversationTimeout();
                }
                return;
            }

            handler.postDelayed(
                    this,
                    conversationTimeoutMs - idle
            );
        }
    };

    private String textFromJson(String hypothesis, String key) {
        try {
            return new JSONObject(hypothesis)
                    .optString(key, "")
                    .trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean containsWakeWord(String text) {
        if (text == null || text.isEmpty()) return false;

        return WAKE_WORD.matcher(
                text.toLowerCase(Locale.ROOT)
        ).find();
    }

    private void wakeDetected(String recognizedText) {
        stopListening();

        if (listener != null) {
            listener.onWakeWord(recognizedText);
        }
    }

    private void conversationDetected(String text) {
        if (text == null || text.trim().isEmpty()) return;

        lastVoiceActivity = System.currentTimeMillis();
        stopListening();

        if (listener != null) {
            listener.onConversationText(text.trim());
        }
    }

    private void handlePartialResult(String hypothesis) {
    if (destroyed || activeMode == Mode.NONE) return;

    if (activeMode == Mode.WAKE) {
        String text = textFromJson(hypothesis, "partial");

        if (!containsWakeWord(text)) {
            return;
        }

        pendingWakePartial = text;
        handler.removeCallbacks(partialWakeRunnable);
        handler.postDelayed(
                partialWakeRunnable,
                PARTIAL_WAKE_DEBOUNCE_MS
        );
        return;
    }

    // En conversación no renovamos el tiempo con parciales.
    // Solo una frase final válida mantiene abierta la conversación.
    }

    private void handleResult(String hypothesis) {
        if (destroyed || activeMode == Mode.NONE) return;

        String text = textFromJson(hypothesis, "text");

        if (activeMode == Mode.WAKE) {
            if (containsWakeWord(text)) {
              handler.removeCallbacks(partialWakeRunnable);
pendingWakePartial = "";  
                wakeDetected(text);
            }
            return;
        }

        if (activeMode == Mode.CONVERSATION) {
            conversationDetected(text);
        }
    }

    private void stopEngine() {
        handler.removeCallbacks(conversationTimeoutRunnable);
handler.removeCallbacks(partialWakeRunnable);
pendingWakePartial = "";
        stopRequested = true;
        activeMode = Mode.NONE;

        AudioRecord recorder = audioRecord;
        audioRecord = null;

        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Exception ignored) {
            }
        }

        Thread thread = audioThread;
        audioThread = null;

        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.interrupt();
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (recorder != null) {
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
        }

        if (recognizer != null) {
            try {
                recognizer.close();
            } catch (Exception ignored) {
            }
            recognizer = null;
        }
    }

    public void destroy() {
        destroyed = true;
        desiredMode = Mode.NONE;
        handler.removeCallbacksAndMessages(null);
        stopEngine();

        if (model != null) {
            try {
                model.close();
            } catch (Exception ignored) {
            }
            model = null;
        }
    }
}
