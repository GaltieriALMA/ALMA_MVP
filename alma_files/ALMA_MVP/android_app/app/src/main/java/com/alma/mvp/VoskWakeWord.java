package com.alma.mvp;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.util.Locale;
import java.util.regex.Pattern;

public final class VoskWakeWord implements RecognitionListener {

    public interface Listener {
        void onWakeWord();
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
    private SpeechService speechService;

    private Mode desiredMode = Mode.NONE;
    private Mode activeMode = Mode.NONE;

    private boolean loadingModel = false;
    private boolean destroyed = false;

    private long conversationTimeoutMs = 5000L;
    private long lastVoiceActivity = 0L;

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

    private void startDesiredMode() {
        if (destroyed || model == null || desiredMode == Mode.NONE) {
            return;
        }

        try {
            if (desiredMode == Mode.WAKE) {
                recognizer = new Recognizer(
                        model,
                        SAMPLE_RATE,
                        "[\"alma\", \"[unk]\"]"
                );
            } else {
                recognizer = new Recognizer(
                        model,
                        SAMPLE_RATE
                );
            }

            activeMode = desiredMode;

            speechService = new SpeechService(
                    recognizer,
                    SAMPLE_RATE
            );

            speechService.startListening(this);

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

    private final Runnable conversationTimeoutRunnable = () -> {
        if (destroyed || activeMode != Mode.CONVERSATION) {
            return;
        }

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
                conversationTimeoutRunnable,
                conversationTimeoutMs - idle
        );
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
        if (text == null || text.isEmpty()) {
            return false;
        }

        return WAKE_WORD.matcher(
                text.toLowerCase(Locale.ROOT)
        ).find();
    }

    private void wakeDetected() {
        stopListening();

        if (listener != null) {
            listener.onWakeWord();
        }
    }

    private void conversationDetected(String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }

        stopListening();

        if (listener != null) {
            listener.onConversationText(text.trim());
        }
    }

    @Override
    public void onPartialResult(String hypothesis) {
        String text =
                textFromJson(hypothesis, "partial");

        if (activeMode == Mode.WAKE) {
            if (containsWakeWord(text)) {
                wakeDetected();
            }
            return;
        }

        if (activeMode == Mode.CONVERSATION
                && !text.isEmpty()) {
            lastVoiceActivity =
                    System.currentTimeMillis();
        }
    }

    @Override
    public void onResult(String hypothesis) {
        String text =
                textFromJson(hypothesis, "text");

        if (activeMode == Mode.WAKE) {
            if (containsWakeWord(text)) {
                wakeDetected();
            }
            return;
        }

        if (activeMode == Mode.CONVERSATION) {
            conversationDetected(text);
        }
    }

    @Override
    public void onFinalResult(String hypothesis) {
        String text =
                textFromJson(hypothesis, "text");

        if (activeMode == Mode.WAKE) {
            if (containsWakeWord(text)) {
                wakeDetected();
            }
            return;
        }

        if (activeMode == Mode.CONVERSATION) {
            conversationDetected(text);
        }
    }

    @Override
    public void onError(Exception exception) {
        stopEngine();

        if (!destroyed && listener != null) {
            listener.onError(exception);
        }
    }

    @Override
    public void onTimeout() {
        if (activeMode == Mode.CONVERSATION) {
            stopListening();

            if (listener != null) {
                listener.onConversationTimeout();
            }
        }
    }

    private void stopEngine() {
        handler.removeCallbacks(conversationTimeoutRunnable);

        if (speechService != null) {
            try {
                speechService.cancel();
            } catch (Exception ignored) {
            }

            try {
                speechService.shutdown();
            } catch (Exception ignored) {
            }

            speechService = null;
        }

        if (recognizer != null) {
            try {
                recognizer.close();
            } catch (Exception ignored) {
            }

            recognizer = null;
        }

        activeMode = Mode.NONE;
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
