package pl.piomin.services.deskpilot.cloud;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {

    private static final String MASTER = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    @TempDir
    Path dir;

    @Test
    void encryptsWithAFreshIvAndDecryptsBack() {
        SecretCipher cipher = new SecretCipher(MASTER, dir.resolve("unused.key").toString());

        String first = cipher.encrypt("sk-secret");
        String second = cipher.encrypt("sk-secret");

        assertThat(first).isNotEqualTo(second).doesNotContain("sk-secret");
        assertThat(cipher.decrypt(first)).isEqualTo("sk-secret");
    }

    @Test
    void anotherMasterKeyCannotReadTheSecret() {
        String encrypted = new SecretCipher(MASTER, dir.resolve("a.key").toString()).encrypt("sk-secret");
        SecretCipher other = new SecretCipher("", dir.resolve("generated.key").toString());

        assertThatThrownBy(() -> other.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DESKPILOT_SECRET_KEY");
    }

    @Test
    void withoutAConfiguredKeyOneIsGeneratedOnceAndReused() throws Exception {
        Path file = dir.resolve("secret.key");
        String encrypted = new SecretCipher("", file.toString()).encrypt("sk-secret");

        assertThat(Files.readString(file)).isNotBlank();
        assertThat(new SecretCipher("", file.toString()).decrypt(encrypted)).isEqualTo("sk-secret");
    }

    @Test
    void rejectsAMasterKeyOfTheWrongLength() {
        assertThatThrownBy(() -> new SecretCipher(Base64.getEncoder().encodeToString(new byte[16]), dir.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
