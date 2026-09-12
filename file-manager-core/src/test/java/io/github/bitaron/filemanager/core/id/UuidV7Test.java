package io.github.bitaron.filemanager.core.id;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UuidV7Test {

    private static final RandomGenerator RANDOM = RandomGenerator.getDefault();

    @Test
    void reportsVersion7AndIetfVariant() {
        UUID id = UuidV7.randomUuid();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void embedsTheGivenUnixMillisTimestamp() {
        long unixMillis = Instant.parse("2026-09-12T00:00:00Z").toEpochMilli();

        UUID id = UuidV7.randomUuid(unixMillis, RANDOM);

        long extractedMillis = id.getMostSignificantBits() >>> 16;
        assertThat(extractedMillis).isEqualTo(unixMillis);
    }

    @Test
    void ordersByTimestampWhenGeneratedAtDifferentTimes() {
        long earlier = Instant.parse("2026-09-12T00:00:00Z").toEpochMilli();
        long later = Instant.parse("2026-09-12T00:00:01Z").toEpochMilli();

        UUID earlierId = UuidV7.randomUuid(earlier, RANDOM);
        UUID laterId = UuidV7.randomUuid(later, RANDOM);

        assertThat(earlierId).isLessThan(laterId);
    }

    @Test
    void generatesDistinctValuesOnEachCall() {
        Set<UUID> generated = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            generated.add(UuidV7.randomUuid());
        }

        assertThat(generated).hasSize(10_000);
    }
}
