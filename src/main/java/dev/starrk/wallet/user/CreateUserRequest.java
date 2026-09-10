package dev.starrk.wallet.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank @Size(max = 120) String fullName,
        @NotBlank @Pattern(regexp = "[0-9]{11}|[0-9]{14}", message = "informe CPF ou CNPJ somente com dígitos") String document,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 8, max = 64) String password,
        @NotNull UserType type) {
    @Override
    public String toString() {
        return "CreateUserRequest[redacted]";
    }
}
