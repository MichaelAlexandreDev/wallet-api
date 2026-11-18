package dev.starrk.wallet.user;

import java.net.URI;
import java.util.List;

import dev.starrk.wallet.auth.AuthenticatedUser;
import dev.starrk.wallet.transfer.TransferResponse;
import dev.starrk.wallet.transfer.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
public class UserController {
    private final UserService service;
    private final TransferService transfers;

    public UserController(UserService service, TransferService transfers) {
        this.service = service;
        this.transfers = transfers;
    }

    @PostMapping
    ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        var user = service.create(request);
        return ResponseEntity.created(URI.create("/users/" + user.id())).body(user);
    }

    @GetMapping("/me")
    UserResponse me(@AuthenticationPrincipal Jwt token) {
        return service.find(AuthenticatedUser.id(token));
    }

    @GetMapping("/me/transfers")
    List<TransferResponse> statement(@AuthenticationPrincipal Jwt token,
            @RequestParam(defaultValue = "20") int limit) {
        return transfers.statement(AuthenticatedUser.id(token), limit);
    }

    @GetMapping("/{id}")
    UserResponse find(@AuthenticationPrincipal Jwt token, @PathVariable Long id) {
        return service.findOwned(id, AuthenticatedUser.id(token));
    }
}
