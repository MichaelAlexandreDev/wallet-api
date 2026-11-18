package dev.starrk.wallet.transfer;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record TransferRequest(
        @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal value,
        @NotNull @Positive Long payee,
        @Size(max = 140) @Pattern(regexp = "[^\\p{Cntrl}]*[^\\p{Cntrl}\\s][^\\p{Cntrl}]*",
                message = "deve ter texto visível e não pode conter caracteres de controle") String description) {

    public TransferRequest(BigDecimal value, Long payee) {
        this(value, payee, null);
    }
}
