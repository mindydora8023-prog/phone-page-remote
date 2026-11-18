package com.phonepageremote.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Carries the allow/deny actions of the pairing notification back into the running
 * receiver service. Not exported: only the app's own pending intents reach it.
 */
public final class PairDecisionReceiver extends BroadcastReceiver {
    static final String ACTION = "com.phonepageremote.android.PAIR_DECISION";
    static final String EXTRA_ADDRESS = "address";
    static final String EXTRA_ALLOW = "allow";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        String address = intent.getStringExtra(EXTRA_ADDRESS);
        if (address == null) {
            return;
        }
        ReceiverService.decidePair(address, intent.getBooleanExtra(EXTRA_ALLOW, false));
    }
}
