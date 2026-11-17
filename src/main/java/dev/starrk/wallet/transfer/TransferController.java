package dev.starrk.wallet.transfer;

import java.net.URI;
import java.util.UUID;

import dev.starrk.wallet.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transfer")
public class TransferController {
    private final TransferService service;

    public TransferController(TransferService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<TransferResponse> transfer(@AuthenticationPrincipal Jwt token,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        var transfer = service.transfer(AuthenticatedUser.id(token), request, idempotencyKey);
        return ResponseEntity.created(URI.create("/transfer/" + transfer.id())).body(transfer);
    }

    @GetMapping("/{id}")
    TransferResponse find(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
        return service.find(id, AuthenticatedUser.id(token));
    }
}
