package dev.starrk.wallet.user;

import java.math.BigDecimal;

import dev.starrk.wallet.shared.ApiException;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import org.springframework.http.HttpStatus;

@Embeddable
public class Wallet {
    private static final BigDecimal MAX_BALANCE = new BigDecimal("99999999999999999.99");

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance = new BigDecimal("0.00");

    public BigDecimal getBalance() {
        return balance;
    }

    public void debit(BigDecimal amount) {
        validateAmount(amount);
        if (balance.compareTo(amount) < 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Saldo insuficiente.");
        }
        balance = balance.subtract(amount);
    }

    public void credit(BigDecimal amount) {
        validateAmount(amount);
        if (balance.add(amount).compareTo(MAX_BALANCE) > 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Limite de saldo da carteira excedido.");
        }
        balance = balance.add(amount);
    }

    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Valor deve ser positivo e ter até duas casas decimais.");
        }
    }
}
