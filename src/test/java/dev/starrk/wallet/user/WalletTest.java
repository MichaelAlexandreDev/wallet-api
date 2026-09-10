package dev.starrk.wallet.user;

import java.math.BigDecimal;

import dev.starrk.wallet.shared.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletTest {
    @Test
    void preservesDecimalPrecision() {
        var wallet = new Wallet();
        wallet.credit(new BigDecimal("0.10"));
        wallet.credit(new BigDecimal("0.20"));
        wallet.debit(new BigDecimal("0.15"));
        assertThat(wallet.getBalance()).isEqualByComparingTo("0.15");
    }

    @Test
    void doesNotChangeBalanceWhenFundsAreInsufficient() {
        var wallet = new Wallet();
        wallet.credit(new BigDecimal("50.00"));
        assertThatThrownBy(() -> wallet.debit(new BigDecimal("50.01"))).isInstanceOf(ApiException.class);
        assertThat(wallet.getBalance()).isEqualByComparingTo("50.00");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-1", "0.001"})
    void rejectsInvalidAmounts(String amount) {
        var wallet = new Wallet();
        BigDecimal value = amount == null ? null : new BigDecimal(amount);
        assertThatThrownBy(() -> wallet.credit(value)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> wallet.debit(value)).isInstanceOf(ApiException.class);
        assertThat(wallet.getBalance()).isZero();
    }

    @Test
    void rejectsBalanceOverflow() {
        var wallet = new Wallet();
        wallet.credit(new BigDecimal("99999999999999999.99"));
        assertThatThrownBy(() -> wallet.credit(new BigDecimal("0.01"))).isInstanceOf(ApiException.class);
        assertThat(wallet.getBalance()).isEqualByComparingTo("99999999999999999.99");
    }
}
