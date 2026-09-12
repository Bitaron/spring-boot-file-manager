package io.github.bitaron.filemanager.core.apikey;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyHasherTest {

    @Test
    void hashesToKnownSha256HexDigest() {
        // echo -n "fm_test" | sha256sum
        String hash = ApiKeyHasher.hash("fm_test");

        assertThat(hash).isEqualTo("212bf8d7edf1d056370483b845e7cf6a69a58bde350c7bcf8d4b1bbf2cad4eb8");
    }

    @Test
    void hashIs64LowercaseHexChars() {
        String hash = ApiKeyHasher.hash(ApiKeySecret.generate().value());

        assertThat(hash).matches("^[0-9a-f]{64}$");
    }

    @Test
    void isDeterministic() {
        String secret = ApiKeySecret.generate().value();

        assertThat(ApiKeyHasher.hash(secret)).isEqualTo(ApiKeyHasher.hash(secret));
    }

    @Test
    void differentSecretsHashDifferently() {
        String hashA = ApiKeyHasher.hash(ApiKeySecret.generate().value());
        String hashB = ApiKeyHasher.hash(ApiKeySecret.generate().value());

        assertThat(hashA).isNotEqualTo(hashB);
    }
}
