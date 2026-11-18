package dev.starrk.wallet.auth;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Lê o ID do usuário autenticado a partir do token. É a única fonte de identidade da API:
 * IDs vindos do corpo ou da URL nunca decidem de quem é a carteira.
 */
public final class AuthenticatedUser {
    /** Até 18 dígitos cabem sempre em um {@code long}. O decoder recusa tokens fora deste formato. */
    static final String SUBJECT_PATTERN = "[1-9][0-9]{0,17}";

    private AuthenticatedUser() {
    }

    public static long id(Jwt token) {
        return Long.parseLong(token.getSubject());
    }
}
