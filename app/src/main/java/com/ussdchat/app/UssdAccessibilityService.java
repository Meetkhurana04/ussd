package com.ussdchat.app;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

public class UssdAccessibilityService extends AccessibilityService {

    private static final String TAG = "UssdService";
    private String lastResponse = "";

    // Receiver to get user input from MainActivity
    private BroadcastReceiver inputReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.ussdchat.USSD_INPUT".equals(intent.getAction())) {
                String input = intent.getStringExtra("input");
                if (input != null) {
                    handleUserInput(input);
                }
            } else if ("com.ussdchat.USSD_CANCEL".equals(intent.getAction())) {
                cancelUssdDialog();
            }
        }
    };

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "Accessibility Service Connected");

        IntentFilter filter = new IntentFilter();
        filter.addAction("com.ussdchat.USSD_INPUT");
        filter.addAction("com.ussdchat.USSD_CANCEL");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(inputReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(inputReceiver, filter);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        int eventType = event.getEventType();

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            String packageName = event.getPackageName() != null ?
                    event.getPackageName().toString() : "";

            // Check if it's the phone/telecom USSD dialog
            if (packageName.contains("com.android.phone") ||
                packageName.contains("com.android.server.telecom") ||
                packageName.contains("com.samsung") ||
                packageName.contains("telecom")) {

                Log.d(TAG, "USSD Dialog detected from: " + packageName);
                extractUssdContent(event);
            }
        }
    }

    private void extractUssdContent(AccessibilityEvent event) {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) {
            // Try from event source
            rootNode = event.getSource();
        }
        if (rootNode == null) return;

        // Extract all text from the USSD dialog
        StringBuilder content = new StringBuilder();
        extractAllText(rootNode, content);

        String response = content.toString().trim();

        // Filter out button texts and clean up
        response = cleanResponse(response);

        if (!response.isEmpty() && !response.equals(lastResponse)) {
            lastResponse = response;
            Log.d(TAG, "USSD Response: " + response);

            // Send response to MainActivity
            Intent intent = new Intent("com.ussdchat.USSD_RESPONSE");
            intent.putExtra("response", response);

            // Check if it's a final response (no input field = session ends)
            boolean hasInputField = hasEditText(rootNode);
            boolean hasSendButton = hasSendOrReplyButton(rootNode);

            if (!hasInputField && !hasSendButton) {
                // This is a final/notification USSD - has only OK/Cancel
                intent.putExtra("session_end", true);
                // Auto-click OK/Cancel to dismiss
                clickButton(rootNode, "OK", "Cancel", "Dismiss");
            } else {
                intent.putExtra("session_end", false);
            }

            sendBroadcast(intent);

            // Bring our app to front (extra safety to hide USSD)
            bringAppToFront();
        }

        rootNode.recycle();
    }

    private void extractAllText(AccessibilityNodeInfo node, StringBuilder sb) {
        if (node == null) return;

        // Get text from this node
        if (node.getText() != null) {
            String text = node.getText().toString();
            // Skip button labels
            if (!isButtonText(node, text)) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(text);
            }
        }

        // Also check content description
        if (node.getContentDescription() != null && node.getText() == null) {
            String desc = node.getContentDescription().toString();
            if (sb.length() > 0) sb.append("\n");
            sb.append(desc);
        }

        // Recurse into children
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                extractAllText(child, sb);
                child.recycle();
            }
        }
    }

    private boolean isButtonText(AccessibilityNodeInfo node, String text) {
        String className = node.getClassName() != null ? node.getClassName().toString() : "";
        if (className.contains("Button")) return true;
        String lower = text.toLowerCase().trim();
        return lower.equals("send") || lower.equals("cancel") || lower.equals("ok") ||
               lower.equals("reply") || lower.equals("dismiss");
    }

    private String cleanResponse(String response) {
        // Remove common USSD dialog title artifacts
        response = response.replace("USSD", "").trim();
        // Remove empty lines
        String[] lines = response.split("\n");
        StringBuilder clean = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                if (clean.length() > 0) clean.append("\n");
                clean.append(trimmed);
            }
        }
        return clean.toString();
    }

    private boolean hasEditText(AccessibilityNodeInfo node) {
        if (node == null) return false;
        String className = node.getClassName() != null ? node.getClassName().toString() : "";
        if (className.contains("EditText")) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean found = hasEditText(child);
                child.recycle();
                if (found) return true;
            }
        }
        return false;
    }

    private boolean hasSendOrReplyButton(AccessibilityNodeInfo node) {
        if (node == null) return false;
        String className = node.getClassName() != null ? node.getClassName().toString() : "";
        if (className.contains("Button")) {
            String text = node.getText() != null ? node.getText().toString().toLowerCase() : "";
            if (text.contains("send") || text.contains("reply")) return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean found = hasSendOrReplyButton(child);
                child.recycle();
                if (found) return true;
            }
        }
        return false;
    }

    private void handleUserInput(String input) {
        Log.d(TAG, "Handling user input: " + input);

        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) {
            sendError("USSD dialog not found");
            return;
        }

        // Find EditText and set the input
        boolean inputSet = setEditTextValue(rootNode, input);

        if (inputSet) {
            // Click Send/Reply button
            boolean clicked = clickButton(rootNode, "Send", "Reply", "OK");
            if (!clicked) {
                Log.w(TAG, "Could not find Send/Reply button, trying OK");
                clickButton(rootNode, "OK", "Send", "Reply");
            }
            lastResponse = ""; // Reset to catch new response
        } else {
            sendError("Could not find input field in USSD dialog");
        }

        rootNode.recycle();
    }

    private boolean setEditTextValue(AccessibilityNodeInfo node, String value) {
        if (node == null) return false;

        String className = node.getClassName() != null ? node.getClassName().toString() : "";

        if (className.contains("EditText")) {
            // Focus the EditText
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);

            // Clear existing text
            Bundle clearArgs = new Bundle();
            clearArgs.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs);

            // Set new text
            Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            boolean result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            Log.d(TAG, "Set text result: " + result + " value: " + value);
            return result;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean result = setEditTextValue(child, value);
                child.recycle();
                if (result) return true;
            }
        }
        return false;
    }

    private boolean clickButton(AccessibilityNodeInfo node, String... buttonTexts) {
        if (node == null) return false;

        String className = node.getClassName() != null ? node.getClassName().toString() : "";
        String nodeText = node.getText() != null ? node.getText().toString().toLowerCase() : "";

        if (className.contains("Button")) {
            for (String btnText : buttonTexts) {
                if (nodeText.contains(btnText.toLowerCase())) {
                    boolean clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Log.d(TAG, "Clicked button: " + btnText + " result: " + clicked);
                    return clicked;
                }
            }
        }

        // Also check for clickable views that act as buttons
        if (node.isClickable()) {
            for (String btnText : buttonTexts) {
                if (nodeText.contains(btnText.toLowerCase())) {
                    boolean clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Log.d(TAG, "Clicked clickable view: " + btnText + " result: " + clicked);
                    return clicked;
                }
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean result = clickButton(child, buttonTexts);
                child.recycle();
                if (result) return true;
            }
        }
        return false;
    }

    private void cancelUssdDialog() {
        Log.d(TAG, "Cancelling USSD dialog");
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode != null) {
            boolean clicked = clickButton(rootNode, "Cancel", "Dismiss", "OK");
            if (!clicked) {
                // Try pressing BACK key
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
            rootNode.recycle();
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK);
        }

        // Notify session end
        Intent intent = new Intent("com.ussdchat.USSD_RESPONSE");
        intent.putExtra("response", "Session cancelled by user.");
        intent.putExtra("session_end", true);
        sendBroadcast(intent);

        lastResponse = "";
    }

    private void bringAppToFront() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT |
                    Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Could not bring app to front", e);
        }
    }

    private void sendError(String error) {
        Intent intent = new Intent("com.ussdchat.USSD_ERROR");
        intent.putExtra("error", error);
        sendBroadcast(intent);
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Accessibility Service Interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(inputReceiver);
        } catch (Exception ignored) {}
    }
}
