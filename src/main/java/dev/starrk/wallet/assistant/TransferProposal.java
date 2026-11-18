package dev.starrk.wallet.assistant;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import dev.starrk.wallet.user.User;

/**
 * Transferência sugerida pelo assistente. Não fica gravada e não move dinheiro: para executá-la, o
 * cliente envia {@code confirmation.body} em {@code POST /transfer} com o próprio token e a chave
 * {@code idempotencyKey}. Confirmar duas vezes devolve o mesmo recibo.
 */
public record TransferProposal(
        String idempotencyKey,
        Long payeeId,
        String payeeName,
        String payeeEmail,
        BigDecimal value,
        String description,
        List<String> warnings,
        Confirmation confirmation) {

    static TransferProposal of(User payee, BigDecimal value, String description, List<String> warnings) {
        String key = "assistant-" + UUID.randomUUID();
        return new TransferProposal(key, payee.getId(), payee.getFullName(), payee.getEmail(), value, description,
                List.copyOf(warnings),
                new Confirmation("POST", "/transfer", key, new Body(value, payee.getId(), description)));
    }

    public record Confirmation(String method, String path, String idempotencyKey, Body body) {
    }

    public record Body(BigDecimal value, Long payee, String description) {
    }
}
