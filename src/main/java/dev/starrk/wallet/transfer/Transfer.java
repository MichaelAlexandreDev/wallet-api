package dev.starrk.wallet.transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    @Column(length = 128)
    private String idempotencyKey;

    @Column(length = 64)
    private String requestFingerprint;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant notifiedAt;

    @Column(nullable = false)
    private Instant nextNotificationAt;

    @Column(nullable = false)
    private int notificationAttempts;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private NotificationStatus notificationStatus = NotificationStatus.PENDING;

    protected Transfer() {
    }

    public Transfer(Long payerId, Long payeeId, BigDecimal amount) {
        this(payerId, payeeId, amount, null, null);
    }

    public Transfer(Long payerId, Long payeeId, BigDecimal amount, String idempotencyKey,
            String requestFingerprint) {
        this.id = UUID.randomUUID();
        this.payerId = payerId;
        this.payeeId = payeeId;
        this.amount = amount;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.nextNotificationAt = createdAt;
    }

    public void recordNotificationAttempt(boolean delivered, Instant now, long retryDelaySeconds, int maxAttempts) {
        notificationAttempts++;
        if (delivered) {
            notifiedAt = now;
            notificationStatus = NotificationStatus.SENT;
        } else if (notificationAttempts >= maxAttempts) {
            notificationStatus = NotificationStatus.FAILED;
        } else {
            nextNotificationAt = now.plusSeconds(retryDelaySeconds);
        }
    }

    public UUID getId() { return id; }
    public Long getPayerId() { return payerId; }
    public Long getPayeeId() { return payeeId; }
    public BigDecimal getAmount() { return amount; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getNotifiedAt() { return notifiedAt; }
    public Instant getNextNotificationAt() { return nextNotificationAt; }
    public int getNotificationAttempts() { return notificationAttempts; }
    public NotificationStatus getNotificationStatus() { return notificationStatus; }
}
