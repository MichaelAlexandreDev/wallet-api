package dev.starrk.wallet.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração dos tokens de acesso. O segredo vem de variável de ambiente e precisa ter ao menos
 * 256 bits, o mínimo exigido para assinaturas HS256.
 */
@ConfigurationProperties("security.jwt")
public record JwtProperties(String secret, String issuer, Duration ttl) {
    static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Defina security.jwt.secret (variável JWT_SECRET).");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "security.jwt.secret deve ter ao menos " + MIN_SECRET_BYTES + " bytes.");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("Defina security.jwt.issuer.");
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("security.jwt.ttl deve ser positivo.");
        }
    }

    byte[] secretBytes() {
        return secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "JwtProperties[issuer=" + issuer + ", ttl=" + ttl + ", secret=redacted]";
    }
}
