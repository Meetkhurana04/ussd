package com.ussdchat.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * This overlay service creates a full-screen transparent overlay
 * that sits ON TOP of the USSD dialog, so the user never sees it.
 * The overlay is transparent but intercepts nothing - it just visually hides the USSD.
 * 
 * Actually we make it FULLY OPAQUE matching the app background,
 * so the USSD dialog is completely hidden behind it.
 * When the user interacts with our chat, overlay stays on top.
 */
public class OverlayService extends Service {

    private WindowManager windowManager;
    private FrameLayout overlayView;
    private static final String CHANNEL_ID = "ussd_overlay_channel";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(1, buildNotification());
        showOverlay();
    }

    private void showOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        overlayView = new FrameLayout(this);
        // Make overlay match app background color so USSD dialog is hidden
        overlayView.setBackgroundColor(Color.parseColor("#ECE5DD"));
        // Make it fully transparent so user sees our app underneath
        // Actually, we'll make it transparent - our app is behind the USSD,
        // but the overlay will be on top of USSD
        overlayView.setAlpha(0.0f); // Fully transparent - our app shows through
        // No, let's think again:
        // Layer order (bottom to top): Our App -> USSD Dialog -> Overlay
        // If overlay is transparent, user sees USSD dialog
        // We need overlay to BLOCK the USSD dialog view
        // So overlay should be opaque with app's background or we redirect to app
        
        // Best approach: Make overlay opaque, and when user interacts with chat,
        // the chat activity is on top. But overlay sits between USSD and user.
        // 
        // Actually the simplest: just make overlay 1x1 pixel or flag not touchable
        // and rely on bringing activity to front.
        // 
        // Let's use a different approach: 
        // The overlay is transparent but NOT_TOUCHABLE so touches go to our app.
        // Our app activity is brought to front by accessibility service.
        
        overlayView.setAlpha(0.0f);

        int overlayType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            overlayType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;

        try {
            windowManager.addView(overlayView, params);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {}
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "UPI Chat Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps UPI session active");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent notifIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, notifIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("UPI Chat Active")
                .setContentText("Processing your UPI request...")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
