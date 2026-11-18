package com.phonepageremote.android;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The short-code pairing exchange, kept free of Android types so it can be tested
 * on a plain JVM.
 *
 * The computer invents the 16-byte key and writes it to the PAIR characteristic.
 * Both ends then derive the same six-digit code from that key, the user compares
 * the two screens and taps allow on the phone. A man in the middle that swaps the
 * key makes the two codes differ, and the person comparing them rejects the
 * pairing — the same reasoning Bluetooth's numeric comparison uses, with no key
 * exchange to get wrong.
 *
 * Wire format, computer to phone: [version=1][16-byte key].
 * What the computer reads back afterwards: one byte, one of the RESULT_* values.
 */
final class PairRequest {
    static final int VERSION = 1;
    static final int LENGTH = 17;

    static final byte RESULT_PENDING = 0;
    static final byte RESULT_ALLOWED = 1;
    static final byte RESULT_DENIED = 2;
    static final byte RESULT_TIMEOUT = 3;
    static final byte RESULT_IDLE = 4;

    private static final int KEY_LENGTH = 16;
    private static final int MODULUS = 1_000_000;
    private static final byte[] LABEL = "ppr-pair-code".getBytes(StandardCharsets.UTF_8);

    private PairRequest() {
    }

    /** Six digits both screens must show identically. */
    static String code(byte[] key) {
        byte[] tag = tag(key);
        long value = ((long) (tag[0] & 0xff) << 24) | ((tag[1] & 0xff) << 16)
                | ((tag[2] & 0xff) << 8) | (tag[3] & 0xff);
        return String.format("%06d", value % MODULUS);
    }

    /** The 17-byte write the computer sends. */
    static byte[] build(byte[] key) {
        if (key == null || key.length != KEY_LENGTH) {
            throw new IllegalArgumentException("Expected a 16-byte key");
        }
        byte[] out = new byte[LENGTH];
        out[0] = (byte) VERSION;
        System.arraycopy(key, 0, out, 1, KEY_LENGTH);
        return out;
    }

    /** Returns the key carried by a write, or null when the write is not one. */
    static byte[] parse(byte[] value) {
        if (value == null || value.length != LENGTH || value[0] != (byte) VERSION) {
            return null;
        }
        byte[] key = new byte[KEY_LENGTH];
        System.arraycopy(value, 1, key, 0, KEY_LENGTH);
        return key;
    }

    private static byte[] tag(byte[] key) {
        if (key == null || key.length != KEY_LENGTH) {
            throw new IllegalArgumentException("Expected a 16-byte key");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(LABEL);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
