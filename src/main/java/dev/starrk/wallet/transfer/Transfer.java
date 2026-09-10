package dev.starrk.wallet.transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "transfers")
public class Transfer {
    @Id
    private UUID id;

    @Column(nullable = false)
    private Long payerId;

    @Column(nullable = false)
    private Long payeeId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant notifiedAt;

    @Column(nullable = false)
    private Instant nextNotificationAt;

    @Column(nullable = false)
    private int notificationAttempts;

    protected Transfer() {
    }

    public Transfer(Long payerId, Long payeeId, BigDecimal amount) {
        this.id = UUID.randomUUID();
        this.payerId = payerId;
        this.payeeId = payeeId;
        this.amount = amount;
        this.createdAt = Instant.now();
        this.nextNotificationAt = createdAt;
    }

    public void recordNotificationAttempt(boolean delivered, Instant now, long retryDelaySeconds) {
        notificationAttempts++;
        if (delivered) {
            notifiedAt = now;
        } else {
            nextNotificationAt = now.plusSeconds(retryDelaySeconds);
        }
    }

    public UUID getId() { return id; }
    public Long getPayerId() { return payerId; }
    public Long getPayeeId() { return payeeId; }
    public BigDecimal getAmount() { return amount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getNotifiedAt() { return notifiedAt; }
    public Instant getNextNotificationAt() { return nextNotificationAt; }
    public int getNotificationAttempts() { return notificationAttempts; }
}
