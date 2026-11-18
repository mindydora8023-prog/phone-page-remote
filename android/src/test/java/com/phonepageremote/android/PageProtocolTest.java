package com.phonepageremote.android;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/** Standalone JVM coverage for the same protocol class that is bundled in the APK. */
public final class PageProtocolTest {
    private static final String WINDOWS_1 = "00:01:02:03:04:05";
    private static final String WINDOWS_2 = "00:01:02:03:04:06";
    private static int checks;

    public static void main(String[] args) throws Exception {
        byte[] key = new byte[16];
        byte[] fixedChallenge = new byte[16];
        for (int i = 0; i < 16; i++) {
            key[i] = (byte) i;
            fixedChallenge[i] = (byte) (i + 16);
        }
        byte[] reference = packet(key, fixedChallenge, 3);
        StringBuilder hex = new StringBuilder();
        for (byte item : reference) {
            hex.append(String.format("%02x", item & 0xff));
        }
        expect(hex.toString().equals("03d2b5cca4bce827e635239a0e5b89a988"), "independent known vector");

        AtomicLong now = new AtomicLong(1L);
        PageProtocol auth = new PageProtocol(key, now::get);
        for (int command = 0; command <= 4; command++) {
            byte[] challenge = auth.issue(WINDOWS_1);
            expect(challenge.length == 16, "challenge length");
            byte[] message = packet(key, challenge, command);
            expect(auth.consume(WINDOWS_1, message) == command, "authenticate command " + command);
            expect(auth.consume(WINDOWS_1, message) == PageProtocol.INVALID, "reject replay");
        }

        byte[] first = auth.issue(WINDOWS_1);
        byte[] second = auth.issue(WINDOWS_1);
        expect(!Arrays.equals(first, second), "each read produces a fresh challenge");
        expect(auth.consume(WINDOWS_1, packet(key, first, 0)) == PageProtocol.INVALID, "reject superseded challenge");
        expect(auth.consume(WINDOWS_1, packet(key, second, 0)) == PageProtocol.INVALID, "failed write consumes challenge");

        byte[] a = auth.issue(WINDOWS_1);
        byte[] b = auth.issue(WINDOWS_2);
        expect(auth.consume(WINDOWS_2, packet(key, a, 1)) == PageProtocol.INVALID, "reject challenge from other peer");
        expect(auth.consume(WINDOWS_1, packet(key, a, 1)) == 1, "other peer write leaves original challenge intact");
        expect(auth.consume(WINDOWS_2, packet(key, b, 1)) == PageProtocol.INVALID, "other peer challenge consumed");

        a = auth.issue(WINDOWS_1);
        now.addAndGet(14_999L);
        expect(auth.consume(WINDOWS_1, packet(key, a, 0)) == 0, "valid before deadline");
        a = auth.issue(WINDOWS_1);
        now.addAndGet(15_000L);
        expect(auth.consume(WINDOWS_1, packet(key, a, 0)) == PageProtocol.INVALID, "expires at 15 seconds");

        a = auth.issue(WINDOWS_1);
        expect(auth.consume(WINDOWS_1, new byte[16]) == PageProtocol.INVALID, "reject short write");
        expect(auth.consume(WINDOWS_1, packet(key, a, 0)) == PageProtocol.INVALID, "short write consumes challenge");
        a = auth.issue(WINDOWS_1);
        expect(auth.consume(WINDOWS_1, new byte[18]) == PageProtocol.INVALID, "reject long write");
        a = auth.issue(WINDOWS_1);
        expect(auth.consume(WINDOWS_1, null) == PageProtocol.INVALID, "reject null write");
        a = auth.issue(WINDOWS_1);
        expect(auth.consume(WINDOWS_1, packet(key, a, 5)) == PageProtocol.INVALID, "reject unknown command with valid MAC");
        a = auth.issue(WINDOWS_1);
        expect(auth.consume(WINDOWS_1, packet(key, a, 255)) == PageProtocol.INVALID, "reject unsigned out-of-range command");
        a = auth.issue(WINDOWS_1);
        byte[] tampered = packet(key, a, 1);
        tampered[0] = 2;
        expect(auth.consume(WINDOWS_1, tampered) == PageProtocol.INVALID, "tag covers command byte");
        a = auth.issue(WINDOWS_1);
        tampered = packet(key, a, 1);
        tampered[16] ^= 1;
        expect(auth.consume(WINDOWS_1, tampered) == PageProtocol.INVALID, "reject modified tag");

        a = auth.issue(WINDOWS_1);
        b = auth.issue(WINDOWS_2);
        auth.invalidate(WINDOWS_1);
        expect(auth.consume(WINDOWS_1, packet(key, a, 0)) == PageProtocol.INVALID, "disconnect invalidates peer");
        expect(auth.consume(WINDOWS_2, packet(key, b, 0)) == 0, "disconnect preserves other peer");
        a = auth.issue(WINDOWS_1);
        b = auth.issue(WINDOWS_2);
        auth.clear();
        expect(auth.consume(WINDOWS_1, packet(key, a, 0)) == PageProtocol.INVALID, "stop invalidates first peer");
        expect(auth.consume(WINDOWS_2, packet(key, b, 0)) == PageProtocol.INVALID, "stop invalidates second peer");

        byte[] replacement = key.clone();
        replacement[0] ^= 1;
        a = auth.issue(WINDOWS_1);
        auth.replaceSecret(replacement);
        expect(auth.consume(WINDOWS_1, packet(replacement, a, 0)) == PageProtocol.INVALID, "rotate clears challenges");
        a = auth.issue(WINDOWS_1);
        expect(auth.consume(WINDOWS_1, packet(key, a, 0)) == PageProtocol.INVALID, "rotate rejects old key");
        a = auth.issue(WINDOWS_1);
        expect(auth.consume(WINDOWS_1, packet(replacement, a, 0)) == 0, "rotate activates new key");
        System.out.println("Protocol validation passed: " + checks + " assertions");
    }

    private static byte[] packet(byte[] key, byte[] challenge, int command) throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] innerPad = new byte[64];
        byte[] outerPad = new byte[64];
        for (int i = 0; i < 64; i++) {
            byte k = i < key.length ? key[i] : 0;
            innerPad[i] = (byte) (k ^ 0x36);
            outerPad[i] = (byte) (k ^ 0x5c);
        }
        sha256.update(innerPad);
        sha256.update(challenge);
        sha256.update((byte) command);
        byte[] intermediate = sha256.digest();
        sha256.update(outerPad);
        byte[] hash = sha256.digest(intermediate);
        byte[] value = new byte[17];
        value[0] = (byte) command;
        System.arraycopy(hash, 0, value, 1, 16);
        return value;
    }

    private static void expect(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
        checks++;
    }
}
