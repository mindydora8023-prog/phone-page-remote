package com.phonepageremote.android;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;
import java.security.SecureRandom;

final class SecretStore {
    private static final String KEY = "secret_hex";
    private static final String HEX = "0123456789abcdef";
    private final SharedPreferences preferences;

    SecretStore(Context context) {
        preferences = context.getSharedPreferences("pairing", Context.MODE_PRIVATE);
    }

    byte[] getOrCreate() throws IOException {
        byte[] current = decode(preferences.getString(KEY, ""));
        return current != null ? current : saveNew();
    }

    byte[] saveNew() throws IOException {
        byte[] next = new byte[PageProtocol.KEY_LENGTH];
        new SecureRandom().nextBytes(next);
        String previous = preferences.getString(KEY, null);
        if (!preferences.edit().putString(KEY, encode(next)).commit()) {
            // SharedPreferences updates its in-memory value even if the disk write fails.
            preferences.edit().putString(KEY, previous).commit();
            throw new IOException("Pairing secret could not be persisted");
        }
        return next;
    }

    static String encode(byte[] value) {
        StringBuilder text = new StringBuilder(value.length * 2);
        for (byte item : value) {
            text.append(HEX.charAt((item & 0xff) >>> 4));
            text.append(HEX.charAt(item & 0x0f));
        }
        return text.toString();
    }

    private static byte[] decode(String text) {
        byte[] value = new byte[PageProtocol.KEY_LENGTH];
        if (text.length() != value.length * 2) {
            return null;
        }
        for (int i = 0; i < value.length; i++) {
            int high = HEX.indexOf(text.charAt(i * 2));
            int low = HEX.indexOf(text.charAt(i * 2 + 1));
            if (high < 0 || low < 0) {
                return null;
            }
            value[i] = (byte) ((high << 4) | low);
        }
        return value;
    }
}
