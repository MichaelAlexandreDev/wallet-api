package dev.starrk.wallet.assistant;

import java.util.UUID;

import dev.starrk.wallet.auth.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CategorizationController {
    private final CategorizationService service;

    public CategorizationController(CategorizationService service) {
        this.service = service;
    }

    @PostMapping("/transfer/{id}/category")
    CategorizationService.CategoryResponse categorize(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
        return service.categorize(id, AuthenticatedUser.id(token));
    }
}
