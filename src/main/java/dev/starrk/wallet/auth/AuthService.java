package dev.starrk.wallet.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

import dev.starrk.wallet.shared.ApiException;
import dev.starrk.wallet.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    static final String INVALID_CREDENTIALS = "E-mail ou senha inválidos.";
    private static final int BCRYPT_MAX_BYTES = 72;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    /**
     * Hash de uma senha aleatória, comparado quando o e-mail não existe. Assim o login gasta o mesmo
     * tempo de BCrypt nos dois casos e a duração da resposta não revela quais e-mails estão cadastrados.
     */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, TokenService tokens) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw invalidCredentials();
        }
        String email = request.email().strip().toLowerCase(Locale.ROOT);
        var user = users.findByEmail(email);
        String hash = user.map(found -> found.getPasswordHash()).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(request.password(), hash);
        if (user.isEmpty() || !matches) {
            throw invalidCredentials();
        }
        var token = tokens.issue(user.get().getId());
        long expiresIn = Duration.between(token.issuedAt(), token.expiresAt()).toSeconds();
        return new LoginResponse(token.value(), "Bearer", expiresIn);
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS);
    }
}
