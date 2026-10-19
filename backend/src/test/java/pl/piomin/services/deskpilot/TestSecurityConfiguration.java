package pl.piomin.services.deskpilot;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Stands in for Keycloak: tokens are signed with a key generated for the test run and verified by a real
 * {@link JwtDecoder}, so the whole security filter chain runs, including role mapping.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSecurityConfiguration {

    private static final KeyPair KEYS = generateKeys();

    @Bean
    JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
    }

    @Bean
    TestTokens testTokens() {
        return new TestTokens();
    }

    /** Mints Keycloak-shaped access tokens: {@code preferred_username} and {@code realm_access.roles}. */
    public static class TestTokens {

        private final NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(
                new RSAKey.Builder((RSAPublicKey) KEYS.getPublic()).privateKey((RSAPrivateKey) KEYS.getPrivate()).build())));

        public String token(String username, String... roles) {
            Instant now = Instant.now();
            JwtClaimsSet claims = JwtClaimsSet.builder()
                    .issuer("http://localhost:8180/realms/deskpilot")
                    .subject(username + "-id")
                    .issuedAt(now)
                    .expiresAt(now.plusSeconds(300))
                    .claim("preferred_username", username)
                    .claim("realm_access", Map.of("roles", List.of(roles)))
                    .build();
            return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                    .getTokenValue();
        }
    }

    private static KeyPair generateKeys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
