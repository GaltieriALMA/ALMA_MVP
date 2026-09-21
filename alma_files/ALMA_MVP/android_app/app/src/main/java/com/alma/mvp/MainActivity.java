package com.alma.mvp;

import android.os.Bundle;
import android.content.Intent;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.media.MediaPlayer;
import java.io.File;
import java.io.FileOutputStream;
import android.text.InputType;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private final AlmaApiClient api = new AlmaApiClient();
    private final String userId = "android_local_user";
    private final String sessionId = UUID.randomUUID().toString();

    private TextView chatText;
    private EditText messageInput;
    private Button sendButton;
    private Button voiceButton;
    private TextToSpeech tts;
    private static final int VOICE_REQUEST_CODE = 1001;
    private SecureTokenStore tokenStore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tokenStore = new SecureTokenStore(this);

        chatText = findViewById(R.id.chatText);
        messageInput = findViewById(R.id.messageInput);
        sendButton = findViewById(R.id.sendButton);
        voiceButton = findViewById(R.id.voiceButton);
        voiceButton.setOnClickListener(v -> startVoiceRecognition());

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("es", "AR"));
                tts.setSpeechRate(0.88f);
                tts.setPitch(1.00f);
            }
        });

        sendButton.setOnClickListener(v -> {
            if (tokenStore.load() == null) {
                requestAccessKey();
            } else {
                sendMessage();
            }
        });
    }

    private void startVoiceRecognition() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Hablale a ALMA");
        startActivityForResult(intent, VOICE_REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VOICE_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                messageInput.setText(results.get(0));
                sendMessage();
            }
        }
    }

    private void requestAccessKey() {
        final EditText input = new EditText(this);
        input.setHint("Clave de acceso ALMA");
        input.setInputType(
                InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_VARIATION_PASSWORD
        );

        new AlertDialog.Builder(this)
                .setTitle("Activar ALMA")
                .setMessage("Ingresá la clave privada de acceso. Se guardará cifrada en este dispositivo.")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("Guardar", (dialog, which) -> {
                    String token = input.getText().toString().trim();
                    if (token.isEmpty()) {
                        append("ALMA: La clave no puede estar vacía.");
                        return;
                    }
                    try {
                        tokenStore.save(token);
                        append("ALMA: Acceso configurado.");
                    } catch (Exception e) {
                        append("ALMA: No pude guardar la clave de forma segura.");
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void sendMessage() {
        String message = messageInput.getText().toString().trim();
        if (message.isEmpty()) return;

        String token = tokenStore.load();
        if (token == null) {
            requestAccessKey();
            return;
        }

        append("Vos: " + message);
        messageInput.setText("");
        sendButton.setEnabled(false);

        new Thread(() -> {
            try {
                String reply = api.chat(userId, sessionId, message, token);
                byte[] audio = api.tts(reply, token);
                runOnUiThread(() -> {
                    append("ALMA: " + reply);
                    playAudio(audio);


                });
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("401")) {
                    tokenStore.clear();
                    runOnUiThread(() -> {
                        append("ALMA: La clave de acceso no es válida. Volvé a ingresarla.");
                        requestAccessKey();
                    });
                } else {
                    runOnUiThread(() ->
                            append("ALMA ERROR: " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()))
                    );
                }
            } finally {
                runOnUiThread(() -> sendButton.setEnabled(true));
            }
        }).start();
    }
    private void playAudio(byte[] audio) {
                try {
                            File file = File.createTempFile("alma_voice_", ".mp3", getCacheDir());
                                        try (FileOutputStream out = new FileOutputStream(file)) {
                                                        out.write(audio);
                                                                    }

                                                                                MediaPlayer player = new MediaPlayer();
                                                                                            player.setDataSource(file.getAbsolutePath());
                                                                                                        player.setOnCompletionListener(mp -> {
                                                                                                                        mp.release();
                                                                                                                                        file.delete();
                                                                                                                                                    });
                                                                                                                                                                player.prepare();
                                                                                                                                                                            player.start();
                                                                                                                                                                                    } catch (Exception e) {
                                                                                                                                                                                                append("ALMA: No pude reproducir la voz.");
                                                                                                                                                                                                        }
                                                                                                                                                                                                            }
    
     private void append(String line) {
        chatText.append("\n\n" + line);
    }
}
