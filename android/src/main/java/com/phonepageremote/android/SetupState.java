package com.phonepageremote.android;

/**
 * The receiver section's decision, kept free of Android types so it can be run on
 * a plain JVM by {@link SetupStateTest}. Everything the UI shows about the
 * receiver comes from here: which chip state, which explanation, which button.
 *
 * The caller passes plain booleans instead of resource ids on purpose: the test
 * compiles without R.java, exactly like PageProtocolTest.
 */
final class SetupState {
    enum Chip {
        STOPPED, PERMISSION, STARTING, FAILED, RUNNING
    }

    enum Action {
        START, STOP, PERMISSION, NOTIFICATIONS, ENABLE_BLUETOOTH
    }

    enum Message {
        SERVICE_TEXT, PERMISSIONS_MISSING, NOTIFICATIONS_DISABLED, UNSUPPORTED, BLUETOOTH_OFF
    }

    static final class Next {
        final Chip chip;
        final Action action;
        final Message message;
        final boolean buttonEnabled;
        /** True when the chip must draw attention: everything except "stopped". */
        final boolean emphasized;

        Next(Chip chip, Action action, Message message, boolean buttonEnabled) {
            this.chip = chip;
            this.action = action;
            this.message = message;
            this.buttonEnabled = buttonEnabled;
            this.emphasized = chip != Chip.STOPPED;
        }

        @Override
        public String toString() {
            return chip + "/" + action + "/" + message + (buttonEnabled ? "" : "/disabled")
                    + (emphasized ? "/emphasized" : "");
        }
    }

    private SetupState() {
    }

    /**
     * Ordered the way the screen is read: a live session outranks every hint,
     * then the two things the user must grant, then the radio, and only then
     * whatever the service last reported.
     */
    static Next decide(boolean active, boolean permissionsMissing, boolean notificationsReady,
            boolean adapterPresent, boolean adapterEnabled,
            boolean serviceStarting, boolean serviceFailed) {
        if (active) {
            return new Next(Chip.RUNNING, Action.STOP, Message.SERVICE_TEXT, true);
        }
        if (permissionsMissing) {
            return new Next(Chip.PERMISSION, Action.PERMISSION, Message.PERMISSIONS_MISSING, true);
        }
        if (!notificationsReady) {
            return new Next(Chip.PERMISSION, Action.NOTIFICATIONS, Message.NOTIFICATIONS_DISABLED, true);
        }
        if (!adapterPresent) {
            return new Next(Chip.STOPPED, Action.START, Message.UNSUPPORTED, false);
        }
        if (!adapterEnabled) {
            return new Next(Chip.STOPPED, Action.ENABLE_BLUETOOTH, Message.BLUETOOTH_OFF, true);
        }
        if (serviceStarting) {
            return new Next(Chip.STARTING, Action.START, Message.SERVICE_TEXT, true);
        }
        if (serviceFailed) {
            return new Next(Chip.FAILED, Action.START, Message.SERVICE_TEXT, true);
        }
        return new Next(Chip.STOPPED, Action.START, Message.SERVICE_TEXT, true);
    }
}
