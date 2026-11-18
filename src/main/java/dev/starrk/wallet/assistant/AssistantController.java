package dev.starrk.wallet.assistant;

import dev.starrk.wallet.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/assistant")
public class AssistantController {
    private final AssistantService service;

    public AssistantController(AssistantService service) {
        this.service = service;
    }

    @PostMapping("/chat")
    AssistantService.AssistantReply chat(@AuthenticationPrincipal Jwt token, @Valid @RequestBody ChatRequest request) {
        return service.chat(AuthenticatedUser.id(token), request.message().strip());
    }

    public record ChatRequest(@NotBlank @Size(max = 2000) String message) {
    }
}
