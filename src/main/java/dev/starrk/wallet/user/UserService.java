package dev.starrk.wallet.user;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import dev.starrk.wallet.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private final UserRepository repository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserService(UserRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        String email = request.email().strip().toLowerCase(Locale.ROOT);
        if (repository.existsByDocumentOrEmail(request.document(), email)) {
            throw new ApiException(HttpStatus.CONFLICT, "CPF/CNPJ ou e-mail já cadastrado.");
        }
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Senha deve ter até 72 bytes em UTF-8.");
        }
        var user = new User(request.fullName().strip(), request.document(), email,
                passwordEncoder.encode(request.password()), request.type());
        return UserResponse.from(repository.saveAndFlush(user));
    }

    @Transactional(readOnly = true)
    public UserResponse find(Long id) {
        return UserResponse.from(repository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Usuário não encontrado.")));
    }
}
