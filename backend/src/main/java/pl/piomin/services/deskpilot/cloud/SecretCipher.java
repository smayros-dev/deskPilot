package pl.piomin.services.deskpilot.cloud;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts API keys stored in the database with AES-256-GCM. The master key comes from {@code DESKPILOT_SECRET_KEY}
 * (base64 of 32 random bytes, e.g. {@code openssl rand -base64 32}). Without it, a key is generated once into
 * {@code ~/.deskpilot/secret.key} so local development works out of the box; production must set the variable,
 * otherwise stored keys cannot be read on another machine.
 */
@Component
public class SecretCipher {

    private static final Logger LOG = LoggerFactory.getLogger(SecretCipher.class);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${agent.secret-key:}") String configured,
                        @Value("${agent.secret-key-file:${user.home}/.deskpilot/secret.key}") String keyFile) {
        this.key = new SecretKeySpec(masterKey(configured, Path.of(keyFile)), "AES");
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt the secret", e);
        }
    }

    /** @throws IllegalStateException if the value was encrypted with another master key or was tampered with */
    public String decrypt(String encrypted) {
        try {
            byte[] in = Base64.getDecoder().decode(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            return new String(cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("A stored API key cannot be decrypted (was DESKPILOT_SECRET_KEY changed?)", e);
        }
    }

    private byte[] masterKey(String configured, Path keyFile) {
        if (!configured.isBlank()) {
            return checked(Base64.getDecoder().decode(configured.strip()));
        }
        try {
            if (Files.exists(keyFile)) {
                return checked(Base64.getDecoder().decode(Files.readString(keyFile).strip()));
            }
            byte[] generated = new byte[32];
            random.nextBytes(generated);
            Files.createDirectories(keyFile.getParent());
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(generated));
            LOG.warn("No DESKPILOT_SECRET_KEY set: generated a local master key in {}. Set the variable in production.", keyFile);
            return generated;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read or create the master key file " + keyFile, e);
        }
    }

    private static byte[] checked(byte[] key) {
        if (key.length != 32) {
            throw new IllegalStateException("DESKPILOT_SECRET_KEY must be 32 bytes, base64-encoded (openssl rand -base64 32)");
        }
        return key;
    }
}
