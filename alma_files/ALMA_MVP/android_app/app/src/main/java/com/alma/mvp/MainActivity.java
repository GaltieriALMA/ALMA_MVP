package com.alma.mvp;

import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {
    private final AlmaApiClient api = new AlmaApiClient();
    private final String userId = "android_local_user";
    private final String sessionId = UUID.randomUUID().toString();

    private TextView chatText;
    private EditText messageInput;
    private Button sendButton;
    private SecureTokenStore tokenStore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tokenStore = new SecureTokenStore(this);

        chatText = findViewById(R.id.chatText);
        messageInput = findViewById(R.id.messageInput);
        sendButton = findViewById(R.id.sendButton);

        sendButton.setOnClickListener(v -> {
            if (tokenStore.load() == null) {
                requestAccessKey();
            } else {
                sendMessage();
            }
        });
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
                runOnUiThread(() -> append("ALMA: " + reply));
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("401")) {
                    tokenStore.clear();
                    runOnUiThread(() -> {
                        append("ALMA: La clave de acceso no es válida. Volvé a ingresarla.");
                        requestAccessKey();
                    });
                } else {
                    runOnUiThread(() ->
                            append("ALMA: No pude conectarme. Intentá nuevamente.")
                    );
                }
            } finally {
                runOnUiThread(() -> sendButton.setEnabled(true));
            }
        }).start();
    }

    private void append(String line) {
        chatText.append("\n\n" + line);
    }
}
