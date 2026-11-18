package dev.starrk.wallet.demo;

import java.math.BigDecimal;

import dev.starrk.wallet.auth.AuthenticatedUser;
import dev.starrk.wallet.user.UserRepository;
import dev.starrk.wallet.user.UserResponse;
import dev.starrk.wallet.user.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Profile("demo")
@RestController
public class DemoDepositController {
    private final UserRepository users;

    public DemoDepositController(UserRepository users) {
        this.users = users;
    }

    @PostMapping("/users/{id}/deposits")
    @Transactional
    public UserResponse deposit(@AuthenticationPrincipal Jwt token, @PathVariable Long id,
            @Valid @RequestBody DepositRequest request) {
        if (id != AuthenticatedUser.id(token)) {
            throw UserService.notFound();
        }
        var user = users.findByIdForUpdate(id).orElseThrow(UserService::notFound);
        user.getWallet().credit(request.value());
        return UserResponse.from(user);
    }

    public record DepositRequest(
            @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal value) {
    }
}
