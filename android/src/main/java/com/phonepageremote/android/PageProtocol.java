package com.phonepageremote.android;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** BLE authentication state. The receiver accesses it only on its main thread. */
final class PageProtocol {
    static final UUID SERVICE = UUID.fromString("0054e118-e465-4b7f-85e7-1b0963092301");
    static final UUID CHALLENGE = UUID.fromString("0054e118-e465-4b7f-85e7-1b0963092302");
    static final UUID COMMAND = UUID.fromString("0054e118-e465-4b7f-85e7-1b0963092303");
    static final UUID PAIR = UUID.fromString("0054e118-e465-4b7f-85e7-1b0963092304");
    static final int KEY_LENGTH = 16;
    static final int INVALID = -1;
    private static final int TAG_LENGTH = 16;
    private static final long VALID_MS = 15_000L;

    private final Mac mac;
    private final SecureRandom random = new SecureRandom();
    private final LongSupplier now;
    private final Map<String, Challenge> pending = new HashMap<>();

    PageProtocol(byte[] key, LongSupplier now) {
        this.now = now;
        try {
            mac = Mac.getInstance("HmacSHA256");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
        replaceSecret(key);
    }

    void replaceSecret(byte[] key) {
        if (key.length != KEY_LENGTH) {
            throw new IllegalArgumentException("Expected 16-byte secret");
        }
        try {
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 key rejected", e);
        }
        clear();
    }

    byte[] issue(String address) {
        long time = now.getAsLong();
        pending.values().removeIf(challenge -> time >= challenge.expiresAt);
        byte[] challenge = new byte[16];
        random.nextBytes(challenge);
        pending.put(address, new Challenge(challenge, time + VALID_MS));
        return challenge;
    }

    int consume(String address, byte[] value) {
        Challenge challenge = pending.remove(address);
        if (challenge == null || now.getAsLong() >= challenge.expiresAt
                || value == null || value.length != 1 + TAG_LENGTH) {
            return INVALID;
        }
        int command = value[0] & 0xff;
        if (command > 4) {
            return INVALID;
        }
        mac.update(challenge.value);
        mac.update(value[0]);
        byte[] expected = Arrays.copyOf(mac.doFinal(), TAG_LENGTH);
        return MessageDigest.isEqual(expected, Arrays.copyOfRange(value, 1, value.length))
                ? command : INVALID;
    }

    void invalidate(String address) {
        pending.remove(address);
    }

    void clear() {
        pending.clear();
    }

    private static final class Challenge {
        final byte[] value;
        final long expiresAt;

        Challenge(byte[] value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }
}
