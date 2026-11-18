package com.phonepageremote.android;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public final class ReceiverService extends Service {
    private static final String ACTION_STOP = "com.phonepageremote.android.STOP";
    static final String CHANNEL = "ble_receiver";
    private static final int NOTIFICATION_ID = 26;
    private static final int ATT_UNLIKELY_ERROR = 0x0e;
    private static final long START_TIMEOUT_MS = 8_000L;
    private static int status = R.string.stopped;
    private static int statusCode;
    private static Runnable setupObserver;
    private static Session liveSession;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver adapterChanges = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    != BluetoothAdapter.STATE_ON) {
                shutdown(R.string.bluetooth_off, 0);
            }
        }
    };
    private boolean registered;
    private Session session;

    static void observeSetup(Runnable observer) {
        setupObserver = observer;
    }

    static void refreshSetup() {
        if (setupObserver != null) {
            setupObserver.run();
        }
    }

    static boolean active() {
        return liveSession != null;
    }

    static String statusText(Context context) {
        return status == R.string.bluetooth_error
                ? context.getString(status, statusCode) : context.getString(status);
    }

    /** The resource of the last reported state, so the setup screen can show it as a chip. */
    static int statusResource() {
        return status;
    }

    static void updateSecret(byte[] secret) {
        if (liveSession != null) {
            liveSession.protocol.replaceSecret(secret);
        }
    }

    static boolean hasBluetoothPermissions(Context context) {
        return Build.VERSION.SDK_INT < 31
                || (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED
                    && context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                        == PackageManager.PERMISSION_GRANTED);
    }

    static boolean notificationsReady(Context context) {
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = notifications.getNotificationChannel(CHANNEL);
        return notifications.areNotificationsEnabled()
                && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown(R.string.stopped, 0);
            return START_NOT_STICKY;
        }
        if (session != null) {
            return START_NOT_STICKY;
        }
        try {
            if (!hasBluetoothPermissions(this) || !notificationsReady(this)) {
                shutdown(R.string.start_error, 0);
                return START_NOT_STICKY;
            }
            NotificationManager notifications = getSystemService(NotificationManager.class);
            notifications.createNotificationChannel(new NotificationChannel(CHANNEL,
                    getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW));
            startForeground(NOTIFICATION_ID, notification(R.string.starting));

            BluetoothManager bluetooth = getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = bluetooth == null ? null : bluetooth.getAdapter();
            if (adapter == null) {
                shutdown(R.string.unsupported, 0);
            } else if (!adapter.isEnabled()) {
                shutdown(R.string.bluetooth_off, 0);
            } else {
                BluetoothLeAdvertiser advertiser = adapter.getBluetoothLeAdvertiser();
                if (advertiser == null) {
                    shutdown(R.string.unsupported, 0);
                } else {
                    registerAdapterChanges();
                    byte[] key = new SecretStore(this).getOrCreate();
                    session = new Session(advertiser, key);
                    liveSession = session;
                    report(R.string.starting, 0);
                    session.open(bluetooth);
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.w("PageRemote", "Receiver start failed", e);
            shutdown(R.string.start_error, 0);
        }
        return START_NOT_STICKY;
    }

    private void registerAdapterChanges() {
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            // Bluetooth can broadcast from its own privileged process.
            registerReceiver(adapterChanges, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(adapterChanges, filter);
        }
        registered = true;
    }

    private Notification notification(int message) {
        int pendingFlags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, SetupActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), pendingFlags);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ReceiverService.class).setAction(ACTION_STOP), pendingFlags);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_pages)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(message))
                .setContentIntent(open)
                .setDeleteIntent(stop)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(null,
                        getString(R.string.notification_stop), stop).build());
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private static void report(int message, int code) {
        status = message;
        statusCode = code;
        refreshSetup();
    }

    private void shutdown(int message, int code) {
        closeSession();
        report(message, code);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void closeSession() {
        if (session != null) {
            Session old = session;
            session = null;
            if (liveSession == old) {
                liveSession = null;
            }
            old.close();
        }
        if (registered) {
            unregisterReceiver(adapterChanges);
            registered = false;
        }
    }

    @Override
    public void onDestroy() {
        boolean wasActive = session != null;
        closeSession();
        if (wasActive) {
            report(R.string.stopped, 0);
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
        super.onDestroy();
    }

    private final class Session {
        final PageProtocol protocol;
        private final BluetoothLeAdvertiser advertiser;
        private final Set<String> connected = new HashSet<>();
        private BluetoothGattServer server;
        private boolean advertisingRequested;
        private boolean ready;
        private final Runnable timeout = () -> {
            if (isCurrent() && !ready) {
                shutdown(R.string.timeout, 0);
            }
        };

        private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                main.post(() -> {
                    if (isCurrent()) {
                        ready = true;
                        main.removeCallbacks(timeout);
                        reportConnectionStatus();
                    }
                });
            }

            @Override
            public void onStartFailure(int errorCode) {
                main.post(() -> {
                    if (isCurrent()) {
                        shutdown(R.string.bluetooth_error, errorCode);
                    }
                });
            }
        };

        private final BluetoothGattServerCallback gattCallback = new BluetoothGattServerCallback() {
            @Override
            public void onServiceAdded(int result, BluetoothGattService service) {
                main.post(() -> {
                    if (!isCurrent() || !PageProtocol.SERVICE.equals(service.getUuid())) {
                        return;
                    }
                    if (result == BluetoothGatt.GATT_SUCCESS) {
                        advertise();
                    } else {
                        shutdown(R.string.bluetooth_error, result);
                    }
                });
            }

            @Override
            public void onConnectionStateChange(BluetoothDevice device, int result, int nextState) {
                main.post(() -> {
                    if (!isCurrent()) {
                        return;
                    }
                    String address = device.getAddress();
                    protocol.invalidate(address);
                    if (result == BluetoothGatt.GATT_SUCCESS && nextState == BluetoothProfile.STATE_CONNECTED) {
                        connected.add(address);
                    } else {
                        connected.remove(address);
                    }
                    if (ready) {
                        reportConnectionStatus();
                    }
                });
            }

            @Override
            public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                    BluetoothGattCharacteristic characteristic) {
                main.post(() -> read(device, requestId, offset, characteristic));
            }

            @Override
            public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                    BluetoothGattCharacteristic characteristic, boolean preparedWrite,
                    boolean responseNeeded, int offset, byte[] value) {
                byte[] packet = value == null ? null : value.clone();
                main.post(() -> write(device, requestId, characteristic, preparedWrite,
                        responseNeeded, offset, packet));
            }

            @Override
            public void onExecuteWrite(BluetoothDevice device, int requestId, boolean execute) {
                main.post(() -> {
                    if (isCurrent()) {
                        protocol.invalidate(device.getAddress());
                        respond(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, null);
                    }
                });
            }
        };

        Session(BluetoothLeAdvertiser advertiser, byte[] secret) {
            this.advertiser = advertiser;
            protocol = new PageProtocol(secret, SystemClock::elapsedRealtime);
        }

        void open(BluetoothManager bluetooth) {
            server = bluetooth.openGattServer(ReceiverService.this, gattCallback);
            if (server == null) {
                shutdown(R.string.start_error, 0);
                return;
            }
            BluetoothGattService service = new BluetoothGattService(PageProtocol.SERVICE,
                    BluetoothGattService.SERVICE_TYPE_PRIMARY);
            service.addCharacteristic(new BluetoothGattCharacteristic(PageProtocol.CHALLENGE,
                    BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ));
            service.addCharacteristic(new BluetoothGattCharacteristic(PageProtocol.COMMAND,
                    BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE));
            if (server.addService(service)) {
                main.postDelayed(timeout, START_TIMEOUT_MS);
            } else {
                shutdown(R.string.start_error, 0);
            }
        }

        private boolean isCurrent() {
            return session == this;
        }

        private void advertise() {
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setConnectable(true)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder()
                    .addServiceUuid(new ParcelUuid(PageProtocol.SERVICE))
                    .build();
            try {
                advertisingRequested = true;
                advertiser.startAdvertising(settings, data, advertiseCallback);
            } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
                shutdown(R.string.start_error, 0);
            }
        }

        private void reportConnectionStatus() {
            int message = connected.isEmpty() ? R.string.listening : R.string.connected;
            report(message, 0);
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(message));
        }

        private void read(BluetoothDevice device, int requestId, int offset,
                BluetoothGattCharacteristic characteristic) {
            if (!isCurrent()) {
                return;
            }
            String address = device.getAddress();
            protocol.invalidate(address);
            if (offset != 0) {
                respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, null);
            } else if (ready && connected.contains(address)
                    && PageProtocol.CHALLENGE.equals(characteristic.getUuid())) {
                byte[] challenge = protocol.issue(address);
                if (!respond(device, requestId, BluetoothGatt.GATT_SUCCESS, challenge)) {
                    protocol.invalidate(address);
                }
            } else {
                respond(device, requestId, ATT_UNLIKELY_ERROR, null);
            }
        }

        private void write(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic,
                boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            if (!isCurrent()) {
                return;
            }
            String address = device.getAddress();
            int result;
            if (!responseNeeded || preparedWrite || !PageProtocol.COMMAND.equals(characteristic.getUuid())) {
                protocol.invalidate(address);
                result = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
            } else if (offset != 0) {
                protocol.invalidate(address);
                result = BluetoothGatt.GATT_INVALID_OFFSET;
            } else if (!ready || !connected.contains(address)) {
                protocol.invalidate(address);
                result = ATT_UNLIKELY_ERROR;
            } else {
                int command = protocol.consume(address, value);
                boolean accepted = command == 0 || (command > 0 && PageGestureService.trySwipe(command));
                result = accepted ? BluetoothGatt.GATT_SUCCESS : ATT_UNLIKELY_ERROR;
            }
            if (responseNeeded) {
                respond(device, requestId, result, null);
            }
        }

        private boolean respond(BluetoothDevice device, int requestId, int result, byte[] value) {
            try {
                return server.sendResponse(device, requestId, result, 0, value);
            } catch (IllegalStateException | SecurityException e) {
                return false;
            }
        }

        void close() {
            ready = false;
            main.removeCallbacks(timeout);
            protocol.clear();
            connected.clear();
            if (advertisingRequested) {
                try {
                    advertiser.stopAdvertising(advertiseCallback);
                } catch (IllegalStateException | SecurityException e) {
                    // Bluetooth may already have been switched off.
                }
            }
            if (server != null) {
                try {
                    server.close();
                } catch (IllegalStateException | SecurityException e) {
                    // Bluetooth may already have been switched off.
                }
                server = null;
            }
        }
    }
}
