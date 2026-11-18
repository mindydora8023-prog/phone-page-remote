package com.phonepageremote.android;

import java.util.Arrays;

/**
 * Plain-JVM checks for {@link PairRequest}: framing, rejection of foreign writes,
 * and the code derivation the Windows side must reproduce exactly.
 */
public final class PairRequestTest {
    private static int checks;

    public static void main(String[] args) {
        byte[] key = new byte[16];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 1);
        }

        // Pinned vector. The Windows test asserts the same code for the same key;
        // changing either derivation breaks one of the two builds.
        expect("pinned code", PairRequest.code(key), "702144");

        // Six digits, always, and stable for the same key.
        check("six digits for a zero key", PairRequest.code(new byte[16]).matches("\\d{6}"));
        check("code length", PairRequest.code(key).length() == 6);
        check("code is stable", PairRequest.code(key).equals(PairRequest.code(key)));
        check("different keys differ",
                !PairRequest.code(key).equals(PairRequest.code("aad03252fdf0f092a981ca71c77c4af8".length() == 32
                        ? hex("aad03252fdf0f092a981ca71c77c4af8") : key)));
        check("code is numeric", PairRequest.code(key).matches("\\d{6}"));

        // Framing.
        byte[] wire = PairRequest.build(key);
        check("wire length", wire.length == PairRequest.LENGTH);
        check("wire version", wire[0] == (byte) PairRequest.VERSION);
        check("round trip", Arrays.equals(PairRequest.parse(wire), key));

        // Anything that is not one of our writes must be refused.
        check("null refused", PairRequest.parse(null) == null);
        check("short refused", PairRequest.parse(new byte[16]) == null);
        check("long refused", PairRequest.parse(new byte[18]) == null);
        byte[] wrongVersion = PairRequest.build(key);
        wrongVersion[0] = 9;
        check("version refused", PairRequest.parse(wrongVersion) == null);
        check("empty refused", PairRequest.parse(new byte[0]) == null);

        // build() refuses a key of the wrong size instead of truncating it.
        boolean threw = false;
        try {
            PairRequest.build(new byte[15]);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        check("short key refused by build", threw);

        // Result codes are distinct: the Windows poller switches on them.
        check("result codes distinct",
                PairRequest.RESULT_PENDING != PairRequest.RESULT_ALLOWED
                        && PairRequest.RESULT_ALLOWED != PairRequest.RESULT_DENIED
                        && PairRequest.RESULT_DENIED != PairRequest.RESULT_TIMEOUT
                        && PairRequest.RESULT_TIMEOUT != PairRequest.RESULT_IDLE);

        System.out.println("Pair request validation passed: " + checks + " assertions");
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static void check(String name, boolean ok) {
        checks++;
        if (!ok) {
            throw new AssertionError(name);
        }
    }

    private static void expect(String name, String got, String want) {
        checks++;
        if (!want.equals(got)) {
            throw new AssertionError(name + ": got " + got + ", want " + want);
        }
    }
}
