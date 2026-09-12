package io.github.bitaron.filemanager.core.id;

import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Generates RFC 9562 UUIDv7 values: a 48-bit big-endian Unix millisecond timestamp followed by
 * random bits, used as the surrogate primary key for every entity in the schema (see ADR 0003).
 *
 * <p>Time-ordering avoids the b-tree index fragmentation a fully random (v4) primary key causes
 * as a table grows, at no extra implementation cost over v4 (see ADR 0003's "considered options").
 * Ids are generated in application code rather than by a database-native function, matching this
 * codebase's general preference for app-layer control over id/hash generation (decision #15) and
 * keeping id generation independent of which database engine a deployment picks.
 */
public final class UuidV7 {

    private static final long VERSION_7 = 0x7000L;
    private static final long RAND_A_MASK = 0x0FFFL;
    private static final long VARIANT_2_MASK = 0x8000000000000000L;
    private static final long RAND_B_MASK = 0x3FFFFFFFFFFFFFFFL;

    private UuidV7() {
    }

    /** Generates a new UUIDv7 using the current wall-clock time. */
    public static UUID randomUuid() {
        return randomUuid(System.currentTimeMillis(), RandomGenerator.getDefault());
    }

    /** Package-visible seam for deterministic tests: same algorithm, injectable time/randomness. */
    static UUID randomUuid(long unixTimeMillis, RandomGenerator random) {
        long timestampBits = (unixTimeMillis & 0xFFFFFFFFFFFFL) << 16;
        long randA = random.nextLong() & RAND_A_MASK;
        long mostSigBits = timestampBits | VERSION_7 | randA;

        long randB = random.nextLong() & RAND_B_MASK;
        long leastSigBits = VARIANT_2_MASK | randB;

        return new UUID(mostSigBits, leastSigBits);
    }
}
