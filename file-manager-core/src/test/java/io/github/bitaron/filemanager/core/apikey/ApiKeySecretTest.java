package io.github.bitaron.filemanager.core.apikey;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeySecretTest {

    @Test
    void generatesValueMatchingFmPrefixAnd43Base64UrlChars() {
        ApiKeySecret secret = ApiKeySecret.generate();

        assertThat(secret.value()).matches("^fm_[A-Za-z0-9_-]{43}$");
    }

    @Test
    void generatesDistinctValuesOnEachCall() {
        Set<String> generated = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            generated.add(ApiKeySecret.generate().value());
        }

        assertThat(generated).hasSize(1_000);
    }

    @Test
    void rejectsValueWithoutFmPrefix() {
        String withoutPrefix = ApiKeySecret.generate().value().substring(3);

        assertThatThrownBy(() -> new ApiKeySecret(withoutPrefix))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsValueOfWrongLength() {
        assertThatThrownBy(() -> new ApiKeySecret("fm_tooshort"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullValue() {
        assertThatThrownBy(() -> new ApiKeySecret(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isWellFormedAcceptsGeneratedSecretsAndRejectsMalformedInput() {
        assertThat(ApiKeySecret.isWellFormed(ApiKeySecret.generate().value())).isTrue();
        assertThat(ApiKeySecret.isWellFormed("not-an-api-key")).isFalse();
        assertThat(ApiKeySecret.isWellFormed(null)).isFalse();
    }
}
