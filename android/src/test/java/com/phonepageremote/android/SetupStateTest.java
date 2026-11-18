package com.phonepageremote.android;

/**
 * Plain-JVM checks for {@link SetupState}. Run by android/build.ps1 before the APK
 * is assembled, the same way PageProtocolTest runs: no Gradle, no JUnit.
 */
public final class SetupStateTest {
    private static int checks;

    public static void main(String[] args) {
        // A live session outranks every hint.
        expect("running beats everything",
                SetupState.decide(true, true, false, false, false, true, true),
                SetupState.Chip.RUNNING, SetupState.Action.STOP,
                SetupState.Message.SERVICE_TEXT, true, true);

        // The two things the user must grant.
        expect("missing permissions",
                SetupState.decide(false, true, false, true, true, false, false),
                SetupState.Chip.PERMISSION, SetupState.Action.PERMISSION,
                SetupState.Message.PERMISSIONS_MISSING, true, true);
        expect("notifications off",
                SetupState.decide(false, false, false, true, true, false, false),
                SetupState.Chip.PERMISSION, SetupState.Action.NOTIFICATIONS,
                SetupState.Message.NOTIFICATIONS_DISABLED, true, true);
        expect("permissions outrank notifications",
                SetupState.decide(false, true, false, true, true, false, false),
                SetupState.Chip.PERMISSION, SetupState.Action.PERMISSION,
                SetupState.Message.PERMISSIONS_MISSING, true, true);

        // The radio.
        expect("no BLE support disables the button",
                SetupState.decide(false, false, true, false, false, false, false),
                SetupState.Chip.STOPPED, SetupState.Action.START,
                SetupState.Message.UNSUPPORTED, false, false);
        expect("bluetooth off",
                SetupState.decide(false, false, true, true, false, false, false),
                SetupState.Chip.STOPPED, SetupState.Action.ENABLE_BLUETOOTH,
                SetupState.Message.BLUETOOTH_OFF, true, false);
        expect("radio problems outrank the service status",
                SetupState.decide(false, false, true, true, false, true, true),
                SetupState.Chip.STOPPED, SetupState.Action.ENABLE_BLUETOOTH,
                SetupState.Message.BLUETOOTH_OFF, true, false);

        // What the service itself reported.
        expect("service starting",
                SetupState.decide(false, false, true, true, true, true, false),
                SetupState.Chip.STARTING, SetupState.Action.START,
                SetupState.Message.SERVICE_TEXT, true, true);
        expect("service failed",
                SetupState.decide(false, false, true, true, true, false, true),
                SetupState.Chip.FAILED, SetupState.Action.START,
                SetupState.Message.SERVICE_TEXT, true, true);
        expect("starting outranks failed",
                SetupState.decide(false, false, true, true, true, true, true),
                SetupState.Chip.STARTING, SetupState.Action.START,
                SetupState.Message.SERVICE_TEXT, true, true);
        expect("idle and healthy",
                SetupState.decide(false, false, true, true, true, false, false),
                SetupState.Chip.STOPPED, SetupState.Action.START,
                SetupState.Message.SERVICE_TEXT, true, false);

        // Only "stopped" is a quiet chip: the other four must draw attention, or the
        // screen looks broken exactly when it is telling the user something.
        quietChipIsOnlyStopped();

        System.out.println("Setup state validation passed: " + checks + " assertions");
    }

    private static void expect(String name, SetupState.Next got, SetupState.Chip chip,
            SetupState.Action action, SetupState.Message message, boolean enabled,
            boolean emphasized) {
        checks++;
        if (got.chip != chip || got.action != action || got.message != message
                || got.buttonEnabled != enabled || got.emphasized != emphasized) {
            throw new AssertionError(name + ": got " + got + ", want " + chip + "/" + action
                    + "/" + message + (enabled ? "" : "/disabled")
                    + (emphasized ? "/emphasized" : ""));
        }
    }

    /** Every non-stopped path must set emphasized, checked through decide() itself. */
    private static void quietChipIsOnlyStopped() {
        SetupState.Next[] attention = {
            SetupState.decide(true, false, true, true, true, false, false),
            SetupState.decide(false, true, true, true, true, false, false),
            SetupState.decide(false, false, true, true, true, true, false),
            SetupState.decide(false, false, true, true, true, false, true),
        };
        for (SetupState.Next next : attention) {
            checks++;
            if (!next.emphasized || next.chip == SetupState.Chip.STOPPED) {
                throw new AssertionError(next.chip + " stayed quiet");
            }
        }
        checks++;
        if (SetupState.decide(false, false, true, true, true, false, false).emphasized) {
            throw new AssertionError("stopped was emphasized");
        }
    }
}
