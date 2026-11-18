package dev.starrk.wallet.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 254) String email,
        @NotBlank @Size(max = 64) String password) {
    @Override
    public String toString() {
        return "LoginRequest[redacted]";
    }
}
