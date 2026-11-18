package com.phonepageremote.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class SetupActivity extends Activity {
    private static final int BLUE = 0xff002fa7;
    private static final int INK = 0xff182135;
    private static final int MUTED = 0xff495269;
    private static final int LINE = 0xffdadee7;
    private final Runnable observer = this::refresh;
    private SecretStore store;
    private LinearLayout content;
    private TextView receiverStatus;
    private TextView gestureStatus;
    private TextView keyView;
    private Button receiverButton;
    private Button settingsButton;
    private Button copyButton;
    private String secret;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        store = new SecretStore(this);
        makeContent();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ReceiverService.observeSetup(observer);
        loadSecret();
        refresh();
    }

    @Override
    protected void onPause() {
        ReceiverService.observeSetup(null);
        super.onPause();
    }

    private void makeContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        scroll.setFillViewport(true);
        if (Build.VERSION.SDK_INT >= 35) {
            getWindow().setDecorFitsSystemWindows(false);
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets margins = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(margins.left, margins.top, margins.right, margins.bottom);
                return WindowInsets.CONSUMED;
            });
        }
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(24), dp(24), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        TextView heading = text(30, INK, true, 0);
        heading.setText(R.string.app_name);
        if (Build.VERSION.SDK_INT >= 28) {
            heading.setAccessibilityHeading(true);
        }
        text(15, MUTED, false, 6).setText(R.string.intro);

        section(R.string.receiver_title);
        receiverStatus = text(15, INK, false, 6);
        receiverStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        receiverButton = button(R.string.start_receiver, true, v -> receiverAction());
        settingsButton = button(R.string.open_app_settings, false, v -> openSettings());

        section(R.string.accessibility_title);
        gestureStatus = text(15, MUTED, false, 6);
        button(R.string.accessibility_settings, false,
                v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        section(R.string.secret_title);
        text(15, MUTED, false, 6).setText(R.string.secret_help);
        keyView = text(14, BLUE, true, 10);
        keyView.setPadding(dp(12), dp(10), dp(12), dp(10));
        keyView.setBackground(shape(0xfff7f7f8, LINE));
        keyView.setFontFeatureSettings("tnum");
        copyButton = button(R.string.copy_secret, false, v -> copySecret());
        button(R.string.rotate_secret, false, v -> new AlertDialog.Builder(this)
                .setTitle(R.string.rotate_secret)
                .setMessage(R.string.rotate_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.rotate_secret, (dialog, which) -> rotateSecret())
                .show());
    }

    private void section(int name) {
        View rule = new View(this);
        rule.setBackgroundColor(LINE);
        LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        ruleParams.topMargin = dp(22);
        content.addView(rule, ruleParams);
        TextView title = text(19, INK, true, 12);
        title.setText(name);
        if (Build.VERSION.SDK_INT >= 28) {
            title.setAccessibilityHeading(true);
        }
    }

    private TextView text(int size, int color, boolean bold, int top) {
        TextView view = new TextView(this);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        add(view, top);
        return view;
    }

    private Button button(int label, boolean primary, View.OnClickListener click) {
        Button view = new Button(this);
        view.setText(label);
        view.setTextSize(15);
        view.setTextColor(primary ? Color.WHITE : BLUE);
        view.setAllCaps(false);
        view.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        view.setPadding(dp(14), dp(8), dp(14), dp(8));
        view.setMinHeight(dp(48));
        view.setStateListAnimator(null);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x33ffffff : 0x20002fa7),
                shape(primary ? BLUE : Color.WHITE, primary ? BLUE : LINE), null));
        view.setOnClickListener(click);
        add(view, 8);
        return view;
    }

    private void add(View view, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top);
        content.addView(view, params);
    }

    private GradientDrawable shape(int fill, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int dp(int size) {
        return Math.round(size * getResources().getDisplayMetrics().density);
    }

    private BluetoothAdapter adapter() {
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        return manager == null ? null : manager.getAdapter();
    }

    private List<String> missingPermissions() {
        List<String> missing = new ArrayList<>(3);
        if (Build.VERSION.SDK_INT >= 31) {
            addMissing(missing, Manifest.permission.BLUETOOTH_CONNECT);
            addMissing(missing, Manifest.permission.BLUETOOTH_ADVERTISE);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            addMissing(missing, Manifest.permission.POST_NOTIFICATIONS);
        }
        return missing;
    }

    private void addMissing(List<String> missing, String permission) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            missing.add(permission);
        }
    }

    private void refresh() {
        gestureStatus.setText(PageGestureService.isConnected()
                ? R.string.accessibility_on : R.string.accessibility_off);
        settingsButton.setVisibility(View.GONE);
        receiverButton.setEnabled(true);
        receiverButton.setAlpha(1f);
        if (ReceiverService.active()) {
            receiverStatus.setText(ReceiverService.statusText(this));
            receiverButton.setText(R.string.stop_receiver);
        } else if (!missingPermissions().isEmpty()) {
            receiverStatus.setText(R.string.permissions_missing);
            receiverButton.setText(R.string.permit);
            settingsButton.setText(R.string.open_app_settings);
            settingsButton.setVisibility(View.VISIBLE);
        } else if (!ReceiverService.notificationsReady(this)) {
            receiverStatus.setText(R.string.notifications_disabled);
            receiverButton.setText(R.string.open_notification_settings);
        } else {
            BluetoothAdapter adapter = adapter();
            if (adapter == null) {
                receiverStatus.setText(R.string.unsupported);
                receiverButton.setText(R.string.start_receiver);
                receiverButton.setEnabled(false);
                receiverButton.setAlpha(0.5f);
            } else if (!adapter.isEnabled()) {
                receiverStatus.setText(R.string.bluetooth_off);
                receiverButton.setText(R.string.enable_bluetooth);
            } else {
                receiverStatus.setText(ReceiverService.statusText(this));
                receiverButton.setText(R.string.start_receiver);
            }
        }
    }

    private void receiverAction() {
        if (ReceiverService.active()) {
            stopService(new Intent(this, ReceiverService.class));
            return;
        }
        List<String> missing = missingPermissions();
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), 1);
        } else if (!ReceiverService.notificationsReady(this)) {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        } else {
            BluetoothAdapter adapter = adapter();
            if (adapter == null) {
                return;
            }
            try {
                if (!adapter.isEnabled()) {
                    startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 2);
                } else {
                    startForegroundService(new Intent(this, ReceiverService.class));
                }
            } catch (IllegalStateException | SecurityException e) {
                receiverStatus.setText(R.string.start_error);
            }
        }
    }

    private void openSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null)));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refresh();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        refresh();
    }

    private void loadSecret() {
        try {
            showSecret(store.getOrCreate());
        } catch (IOException e) {
            secret = null;
            keyView.setText(R.string.secret_error);
            copyButton.setEnabled(false);
            copyButton.setAlpha(0.5f);
        }
    }

    private void showSecret(byte[] key) {
        secret = SecretStore.encode(key);
        keyView.setText(secret);
        copyButton.setEnabled(true);
        copyButton.setAlpha(1f);
    }

    private void rotateSecret() {
        try {
            byte[] key = store.saveNew();
            ReceiverService.updateSecret(key);
            showSecret(key);
        } catch (IOException e) {
            Toast.makeText(this, R.string.secret_error, Toast.LENGTH_SHORT).show();
        }
    }

    private void copySecret() {
        if (secret == null) {
            return;
        }
        ClipData clip = ClipData.newPlainText(getString(R.string.secret_title), secret);
        if (Build.VERSION.SDK_INT >= 33) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        getSystemService(ClipboardManager.class).setPrimaryClip(clip);
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, R.string.secret_copied, Toast.LENGTH_SHORT).show();
        }
    }
}
