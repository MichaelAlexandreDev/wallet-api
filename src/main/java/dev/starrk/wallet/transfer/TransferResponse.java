package dev.starrk.wallet.transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferResponse(UUID id, BigDecimal value, Long payer, Long payee,
        Instant createdAt, String notificationStatus) {
    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(transfer.getId(), transfer.getAmount(), transfer.getPayerId(),
                transfer.getPayeeId(), transfer.getCreatedAt(),
                transfer.getNotifiedAt() == null ? "PENDING" : "SENT");
    }
}
