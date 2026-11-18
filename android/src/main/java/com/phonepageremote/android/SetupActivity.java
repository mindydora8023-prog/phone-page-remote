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
import android.graphics.Insets;
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

/**
 * One screen: masthead, receiver, accessibility, pairing key.
 *
 * The screen only decides state and wiring; every view comes from {@link Design}.
 * It is built in code because the Android half is compiled with aapt2 only (no
 * Gradle, so no AppCompat/Material), and it is styled from the same tokens the
 * Windows page uses so both ends read as one product.
 */
public final class SetupActivity extends Activity {
    private final Runnable observer = this::refresh;
    private SecretStore store;
    private Design ui;
    private TextView receiverStatus;
    private TextView receiverMessage;
    private TextView gestureStatus;
    private TextView keyView;
    private Button receiverButton;
    private Button settingsButton;
    private Button copyButton;
    private LinearLayout receiverCard;
    private LinearLayout gestureCard;
    private LinearLayout pairBlock;
    private TextView pairText;
    private String secret;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        store = new SecretStore(this);
        ui = new Design(this);
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
        scroll.setBackgroundColor(ui.color(R.color.page));
        scroll.setFillViewport(true);
        if (Build.VERSION.SDK_INT >= 35) {
            getWindow().setDecorFitsSystemWindows(false);
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets margins = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(margins.left, margins.top, margins.right, margins.bottom);
                return WindowInsets.CONSUMED;
            });
        }

        LinearLayout content = ui.stack();
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        content.addView(ui.brandRule(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(R.dimen.brand_rule)));

        LinearLayout body = ui.column(getResources().getDisplayMetrics().widthPixels);
        content.addView(body);

        LinearLayout masthead = ui.row();
        masthead.setGravity(Gravity.TOP);
        masthead.addView(ui.display(R.string.app_name),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        receiverStatus = ui.statusChip();
        receiverStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.leftMargin = ui.dp(R.dimen.gap_m);
        statusParams.topMargin = ui.dp(R.dimen.gap_s);
        masthead.addView(receiverStatus, statusParams);
        ui.add(body, masthead, 0);

        ui.add(body, ui.caption(R.string.intro), R.dimen.gap_s);

        ui.section(body, R.string.receiver_title);
        receiverCard = ui.currentCard();
        receiverMessage = ui.add(body, ui.bodyText(R.string.stopped), 0);
        receiverButton = ui.add(body, ui.primary(R.string.start_receiver, v -> receiverAction()),
                R.dimen.gap_m);
        LinearLayout receiverActions = ui.row();
        receiverActions.setGravity(Gravity.END);
        settingsButton = ui.addChip(receiverActions, R.string.open_app_settings, v -> openSettings());
        ui.add(body, receiverActions, 0);

        // Same allow/deny as the notification, but inside the app: notifications are
        // easy to fold away on ROMs that file unknown apps into a silent group.
        pairBlock = ui.stack();
        ui.section(pairBlock, R.string.pair_pending_title);
        pairText = ui.add(pairBlock, ui.bodyText(R.string.pair_pending_text), 0);
        LinearLayout pairActions = ui.row();
        pairActions.setGravity(Gravity.END);
        ui.addChip(pairActions, R.string.pair_allow, v -> approvePair(true));
        ui.addChip(pairActions, R.string.pair_deny, v -> approvePair(false));
        ui.add(pairBlock, pairActions, R.dimen.gap_s);
        pairBlock.setVisibility(View.GONE);
        ui.add(body, pairBlock, R.dimen.gap_s);

        ui.section(body, R.string.accessibility_title);
        gestureCard = ui.currentCard();
        gestureStatus = ui.add(body, ui.bodyText(R.string.accessibility_off), 0);
        LinearLayout gestureActions = ui.row();
        gestureActions.setGravity(Gravity.END);
        ui.addChip(gestureActions, R.string.accessibility_settings,
                v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        ui.add(body, gestureActions, R.dimen.gap_s);

        ui.section(body, R.string.secret_title);
        ui.add(body, ui.caption(R.string.secret_help), 0);
        keyView = ui.add(body, ui.keyCard(), R.dimen.gap_m);
        LinearLayout keyActions = ui.row();
        keyActions.setGravity(Gravity.END);
        copyButton = ui.addChip(keyActions, R.string.copy_secret, v -> copySecret());
        ui.addChip(keyActions, R.string.rotate_secret, v -> new AlertDialog.Builder(this)
                .setTitle(R.string.rotate_secret)
                .setMessage(R.string.rotate_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.rotate_secret, (dialog, which) -> rotateSecret())
                .show());
        ui.add(body, keyActions, R.dimen.gap_m);
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

    /** Reflect one receiver state in the chip plus the explanation line. */
    private void setReceiverState(int state, boolean emphasized, String message) {
        receiverStatus.setText(state);
        ui.markStatus(receiverStatus, emphasized);
        receiverMessage.setText(message);
    }

    private void refresh() {
        gestureStatus.setText(PageGestureService.isConnected()
                ? R.string.accessibility_on : R.string.accessibility_off);
        settingsButton.setVisibility(View.GONE);

        BluetoothAdapter adapter = adapter();
        int reported = ReceiverService.statusResource();
        SetupState.Next next = SetupState.decide(
                ReceiverService.active(),
                !missingPermissions().isEmpty(),
                ReceiverService.notificationsReady(this),
                adapter != null,
                adapter != null && adapter.isEnabled(),
                reported == R.string.starting,
                reported == R.string.bluetooth_error);

        int chip;
        switch (next.chip) {
            case RUNNING: chip = R.string.state_running; break;
            case PERMISSION: chip = R.string.state_permission; break;
            case STARTING: chip = R.string.state_starting; break;
            case FAILED: chip = R.string.state_failed; break;
            default: chip = R.string.stopped; break;
        }
        String message;
        switch (next.message) {
            case PERMISSIONS_MISSING: message = getString(R.string.permissions_missing); break;
            case NOTIFICATIONS_DISABLED: message = getString(R.string.notifications_disabled); break;
            case UNSUPPORTED: message = getString(R.string.unsupported); break;
            case BLUETOOTH_OFF: message = getString(R.string.bluetooth_off); break;
            default: message = ReceiverService.statusText(this); break;
        }
        int label;
        switch (next.action) {
            case STOP: label = R.string.stop_receiver; break;
            case PERMISSION: label = R.string.permit; break;
            case NOTIFICATIONS: label = R.string.open_notification_settings; break;
            case ENABLE_BLUETOOTH: label = R.string.enable_bluetooth; break;
            default: label = R.string.start_receiver; break;
        }

        ReceiverService.PairInfo pending = ReceiverService.pendingPair();
        pairBlock.setVisibility(pending == null ? View.GONE : View.VISIBLE);
        if (pending != null) {
            pairText.setText(getString(R.string.pair_pending_text, pending.code));
        }

        ui.markCard(receiverCard, ReceiverService.active());
        ui.markCard(gestureCard, PageGestureService.isConnected());
        setReceiverState(chip, next.emphasized, message);
        receiverButton.setText(label);
        receiverButton.setEnabled(next.buttonEnabled);
        receiverButton.setAlpha(next.buttonEnabled ? 1f : 0.5f);
        if (next.action == SetupState.Action.PERMISSION) {
            settingsButton.setText(R.string.open_app_settings);
            settingsButton.setVisibility(View.VISIBLE);
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
                setReceiverState(R.string.state_failed, true, getString(R.string.start_error));
            }
        }
    }

    private void approvePair(boolean allow) {
        ReceiverService.PairInfo pending = ReceiverService.pendingPair();
        if (pending != null) {
            ReceiverService.decidePair(pending.address, allow);
            // An allowed pairing replaces the key: show the new one right away
            // instead of waiting for the next onResume.
            if (allow) loadSecret();
            refresh();
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







