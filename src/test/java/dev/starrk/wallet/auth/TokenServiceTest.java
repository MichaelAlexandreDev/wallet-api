package dev.starrk.wallet.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import dev.starrk.wallet.TestSecrets;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");

    private final SecurityConfiguration configuration = new SecurityConfiguration();
    private final JwtProperties properties =
            new JwtProperties(TestSecrets.JWT_SECRET, "wallet-api", Duration.ofMinutes(15));
    private final TokenService tokens =
            new TokenService(configuration.jwtEncoder(properties), properties, fixedAt(NOW));

    @Test
    void issuesSignedTokenIdentifyingTheUser() {
        var issued = tokens.issue(42);

        var jwt = decoderAt(NOW).decode(issued.value());
        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("wallet-api");
        assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(jwt.getClaimAsString("scope")).isEqualTo("wallet:read transfer:write");
        assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
        assertThat(issued.expiresAt()).isEqualTo(jwt.getExpiresAt());
    }

    @Test
    void tokenStopsBeingAcceptedAfterExpiration() {
        String token = tokens.issue(42).value();

        assertThat(decoderAt(NOW.plus(Duration.ofMinutes(15))).decode(token).getSubject()).isEqualTo("42");
        assertThatThrownBy(() -> decoderAt(NOW.plus(Duration.ofMinutes(16))).decode(token))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void doesNotExposeTheTokenInLogs() {
        assertThat(tokens.issue(42).toString()).doesNotContain("eyJ").contains("redacted");
        assertThat(properties.toString()).doesNotContain(TestSecrets.JWT_SECRET);
    }

    @Test
    void rejectsMissingOrWeakConfiguration() {
        assertThatThrownBy(() -> new JwtProperties("", "wallet-api", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JwtProperties("x".repeat(31), "wallet-api", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JwtProperties(TestSecrets.JWT_SECRET, " ", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JwtProperties(TestSecrets.JWT_SECRET, "wallet-api", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private JwtDecoder decoderAt(Instant instant) {
        return configuration.jwtDecoder(properties, fixedAt(instant));
    }

    private static Clock fixedAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }
}
