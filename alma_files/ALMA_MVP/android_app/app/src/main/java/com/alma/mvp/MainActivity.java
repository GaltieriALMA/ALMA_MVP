package com.alma.mvp;

import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {
    private final AlmaApiClient api = new AlmaApiClient();
    private final String userId = "android_local_user";
    private final String sessionId = UUID.randomUUID().toString();

    private TextView chatText;
    private EditText messageInput;
    private Button sendButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        chatText = findViewById(R.id.chatText);
        messageInput = findViewById(R.id.messageInput);
        sendButton = findViewById(R.id.sendButton);

        sendButton.setOnClickListener(v -> sendMessage());
    }

    private void sendMessage() {
        String message = messageInput.getText().toString().trim();
        if (message.isEmpty()) return;

        append("Vos: " + message);
        messageInput.setText("");
        sendButton.setEnabled(false);

        new Thread(() -> {
            try {
                String reply = api.chat(userId, sessionId, message);
                runOnUiThread(() -> append("ALMA: " + reply));
            } catch (Exception e) {
                runOnUiThread(() -> append("ALMA: No pude conectarme. Intentá nuevamente."));
            } finally {
                runOnUiThread(() -> sendButton.setEnabled(true));
            }
        }).start();
    }

    private void append(String line) {
        chatText.append("\n\n" + line);
    }
}
