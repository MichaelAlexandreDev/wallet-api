package dev.starrk.wallet;

public final class TestSecrets {
    /** Segredo de assinatura usado só nos testes; tem os 32 bytes mínimos exigidos para HS256. */
    public static final String JWT_SECRET = "test-only-jwt-secret-with-32-bytes-or-more";

    private TestSecrets() {
    }
}
