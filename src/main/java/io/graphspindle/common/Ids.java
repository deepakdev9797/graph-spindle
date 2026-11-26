package io.graphspindle.common;

import java.security.SecureRandom;
import java.util.UUID;

/** Engine-generated identifiers: UUIDv7 (RFC 9562), lowercase with dashes (SCHEMA §7.2). */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String newId() {
        return uuidV7(System.currentTimeMillis()).toString();
    }

    static UUID uuidV7(long epochMillis) {
        byte[] r = new byte[10];
        RANDOM.nextBytes(r);
        long msb = (epochMillis & 0xFFFF_FFFF_FFFFL) << 16;
        msb |= 0x7000L | ((r[0] & 0x0FL) << 8) | (r[1] & 0xFFL);
        long lsb = 0x8000_0000_0000_0000L | ((r[2] & 0x3FL) << 56);
        for (int i = 3; i < 10; i++) {
            lsb |= (r[i] & 0xFFL) << (8 * (9 - i));
        }
        return new UUID(msb, lsb);
    }
}
