package dev.starrk.wallet.user;

import java.math.BigDecimal;

public record UserResponse(Long id, String fullName, String email, UserType type, BigDecimal balance) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getFullName(), user.getEmail(),
                user.getType(), user.getWallet().getBalance());
    }
}
