package com.phonepageremote.android;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.PowerManager;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

public final class PageGestureService extends AccessibilityService {
    private static final long SWIPE_MS = 225L;
    private static PageGestureService connected;
    private boolean busy;

    static boolean isConnected() {
        return connected != null;
    }

    static boolean trySwipe(int command) {
        return connected != null && connected.dispatchSwipe(command);
    }

    @Override
    protected void onServiceConnected() {
        connected = this;
        ReceiverService.refreshSetup();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        clearConnection();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        clearConnection();
        super.onDestroy();
    }

    private void clearConnection() {
        if (connected == this) {
            connected = null;
            ReceiverService.refreshSetup();
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @SuppressWarnings("deprecation")
    private boolean dispatchSwipe(int command) {
        if (busy) {
            return false;
        }
        PowerManager power = getSystemService(PowerManager.class);
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if (!power.isInteractive() || keyguard.isKeyguardLocked() || keyguard.isDeviceLocked()) {
            return false;
        }

        Display display = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null || display.getState() != Display.STATE_ON) {
            return false;
        }
        Point size = new Point();
        display.getRealSize(size);
        if (size.x <= 0 || size.y <= 0) {
            return false;
        }
        Path path = new Path();
        switch (command) {
            case 1:
                path.moveTo(size.x * 0.79f, size.y * 0.50f);
                path.lineTo(size.x * 0.21f, size.y * 0.50f);
                break;
            case 2:
                path.moveTo(size.x * 0.21f, size.y * 0.50f);
                path.lineTo(size.x * 0.79f, size.y * 0.50f);
                break;
            case 3:
                path.moveTo(size.x * 0.50f, size.y * 0.75f);
                path.lineTo(size.x * 0.50f, size.y * 0.25f);
                break;
            case 4:
                path.moveTo(size.x * 0.50f, size.y * 0.25f);
                path.lineTo(size.x * 0.50f, size.y * 0.75f);
                break;
            default:
                return false;
        }
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, SWIPE_MS))
                .build();
        busy = true;
        try {
            if (dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription description) {
                    busy = false;
                }

                @Override
                public void onCancelled(GestureDescription description) {
                    busy = false;
                }
            }, null)) {
                return true;
            }
        } catch (IllegalStateException | SecurityException e) {
            // Android can disconnect the accessibility service during dispatch.
        }
        busy = false;
        return false;
    }
}
