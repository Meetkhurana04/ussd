package com.ussdchat.app;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private RecyclerView chatRecyclerView;
    private EditText messageInput;
    private ImageButton sendButton;
    private TextView statusDot;

    private ChatAdapter chatAdapter;
    private List<ChatMessage> chatMessages = new ArrayList<>();

    private boolean ussdSessionActive = false;

    // Broadcast receiver to get USSD responses from AccessibilityService
    private BroadcastReceiver ussdResponseReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.ussdchat.USSD_RESPONSE".equals(intent.getAction())) {
                String response = intent.getStringExtra("response");
                boolean sessionEnd = intent.getBooleanExtra("session_end", false);

                if (response != null && !response.isEmpty()) {
                    addBotMessage(response);
                }

                if (sessionEnd) {
                    ussdSessionActive = false;
                    updateStatus(false);
                    addBotMessage("✅ Session ended.");
                    // Stop overlay
                    stopService(new Intent(MainActivity.this, OverlayService.class));
                }
            } else if ("com.ussdchat.USSD_ERROR".equals(intent.getAction())) {
                String error = intent.getStringExtra("error");
                addBotMessage("❌ Error: " + (error != null ? error : "Unknown error"));
                ussdSessionActive = false;
                updateStatus(false);
                stopService(new Intent(MainActivity.this, OverlayService.class));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        chatRecyclerView = findViewById(R.id.chatRecyclerView);
        messageInput = findViewById(R.id.messageInput);
        sendButton = findViewById(R.id.sendButton);
        statusDot = findViewById(R.id.statusDot);

        // Setup RecyclerView
        chatAdapter = new ChatAdapter(chatMessages);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        chatRecyclerView.setLayoutManager(lm);
        chatRecyclerView.setAdapter(chatAdapter);

        // Welcome message
        addBotMessage("👋 Welcome to UPI Chat!\n\nType \"hello\" or \"start\" to begin UPI session.\nType \"cancel\" or \"exit\" to end session.");

        // Send button click
        sendButton.setOnClickListener(v -> sendMessage());

        // Enter key on keyboard
        messageInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage();
                return true;
            }
            return false;
        });

        // Request permissions
        requestNeededPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Register receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.ussdchat.USSD_RESPONSE");
        filter.addAction("com.ussdchat.USSD_ERROR");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(ussdResponseReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(ussdResponseReceiver, filter);
        }

        // Check accessibility service status
        if (!isAccessibilityServiceEnabled()) {
            addBotMessage("⚠️ Accessibility Service is OFF.\nPlease enable 'UPI Chat' in Settings > Accessibility.");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(ussdResponseReceiver);
        } catch (Exception ignored) {}
    }

    private void sendMessage() {
        String text = messageInput.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return;

        // Add user message to chat
        addUserMessage(text);
        messageInput.setText("");

        String lower = text.toLowerCase();

        // Handle cancel/exit
        if (lower.equals("cancel") || lower.equals("exit") || lower.equals("quit")) {
            if (ussdSessionActive) {
                // Tell accessibility service to press Cancel on USSD dialog
                Intent cancelIntent = new Intent("com.ussdchat.USSD_CANCEL");
                sendBroadcast(cancelIntent);
                addBotMessage("🔄 Cancelling session...");
                ussdSessionActive = false;
                updateStatus(false);
                // Small delay then stop overlay
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    stopService(new Intent(this, OverlayService.class));
                }, 1000);
            } else {
                addBotMessage("No active session to cancel.");
            }
            return;
        }

        // Handle start/hello - initiate USSD
        if (!ussdSessionActive && (lower.equals("hello") || lower.equals("start") || lower.equals("hi"))) {
            if (!isAccessibilityServiceEnabled()) {
                addBotMessage("⚠️ Please enable Accessibility Service first!\nGo to Settings > Accessibility > UPI Chat");
                openAccessibilitySettings();
                return;
            }
            if (!Settings.canDrawOverlays(this)) {
                addBotMessage("⚠️ Please grant Overlay permission!");
                requestOverlayPermission();
                return;
            }

            // Start overlay to hide USSD dialog
            startOverlayService();

            // Dial USSD *99#
            addBotMessage("🔄 Starting UPI session...");
            ussdSessionActive = true;
            updateStatus(true);

            // Small delay to ensure overlay is up before USSD dialog appears
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                dialUssd("*99#");
            }, 500);
            return;
        }

        // If session is active, send input to USSD dialog
        if (ussdSessionActive) {
            // Send user input to accessibility service
            Intent inputIntent = new Intent("com.ussdchat.USSD_INPUT");
            inputIntent.putExtra("input", text);
            sendBroadcast(inputIntent);
            addBotMessage("🔄 Processing...");
        } else {
            addBotMessage("No active session. Type \"hello\" to start.");
        }
    }

    private void dialUssd(String ussdCode) {
        try {
            String encodedHash = Uri.encode("#");
            String ussd = ussdCode.replace("#", encodedHash);
            Intent intent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + ussd));
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.CALL_PHONE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startActivity(intent);
            } else {
                ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.CALL_PHONE}, 100);
            }
        } catch (Exception e) {
            addBotMessage("❌ Failed to dial USSD: " + e.getMessage());
            ussdSessionActive = false;
            updateStatus(false);
        }
    }

    private void startOverlayService() {
        Intent intent = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void addUserMessage(String text) {
        chatMessages.add(new ChatMessage(text, true));
        chatAdapter.notifyItemInserted(chatMessages.size() - 1);
        chatRecyclerView.scrollToPosition(chatMessages.size() - 1);
    }

    private void addBotMessage(String text) {
        runOnUiThread(() -> {
            chatMessages.add(new ChatMessage(text, false));
            chatAdapter.notifyItemInserted(chatMessages.size() - 1);
            chatRecyclerView.scrollToPosition(chatMessages.size() - 1);
        });
    }

    private void updateStatus(boolean online) {
        runOnUiThread(() -> {
            if (online) {
                statusDot.setText("● Online");
                statusDot.setTextColor(0xFF4CAF50);
            } else {
                statusDot.setText("● Offline");
                statusDot.setTextColor(0xFFFF6B6B);
            }
        });
    }

    private boolean isAccessibilityServiceEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> services = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_GENERIC);
        for (AccessibilityServiceInfo info : services) {
            if (info.getResolveInfo().serviceInfo.packageName.equals(getPackageName())) {
                return true;
            }
        }
        return false;
    }

    private void openAccessibilitySettings() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivity(intent);
    }

    private void requestOverlayPermission() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void requestNeededPermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(android.Manifest.permission.CALL_PHONE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(android.Manifest.permission.POST_NOTIFICATIONS);
        }
        ActivityCompat.requestPermissions(this, perms.toArray(new String[0]), 100);

        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission();
        }
    }

    // ========== Chat Message Model ==========
    static class ChatMessage {
        String text;
        boolean isUser;

        ChatMessage(String text, boolean isUser) {
            this.text = text;
            this.isUser = isUser;
        }
    }

    // ========== Chat Adapter ==========
    static class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.VH> {
        private List<ChatMessage> messages;

        ChatAdapter(List<ChatMessage> messages) {
            this.messages = messages;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout ll = new LinearLayout(parent.getContext());
            ll.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            ll.setPadding(8, 4, 8, 4);

            TextView tv = new TextView(parent.getContext());
            tv.setPadding(32, 20, 32, 20);
            tv.setTextSize(15);
            tv.setMaxWidth((int)(parent.getWidth() * 0.75));
            ll.addView(tv);

            return new VH(ll, tv);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            ChatMessage msg = messages.get(position);
            holder.textView.setText(msg.text);

            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) holder.textView.getLayoutParams();
            LinearLayout parent = (LinearLayout) holder.textView.getParent();

            if (msg.isUser) {
                holder.textView.setBackgroundResource(R.drawable.chat_bubble_user);
                holder.textView.setTextColor(0xFF000000);
                parent.setGravity(Gravity.END);
            } else {
                holder.textView.setBackgroundResource(R.drawable.chat_bubble_bot);
                holder.textView.setTextColor(0xFF000000);
                parent.setGravity(Gravity.START);
            }
        }

        @Override
        public int getItemCount() {
            return messages.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            TextView textView;

            VH(View itemView, TextView tv) {
                super(itemView);
                this.textView = tv;
            }
        }
    }
}
