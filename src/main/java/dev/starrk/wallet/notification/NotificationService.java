package dev.starrk.wallet.notification;

import java.time.Instant;

import dev.starrk.wallet.integration.NotificationClient;
import dev.starrk.wallet.transfer.TransferRepository;
import dev.starrk.wallet.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private final TransferRepository transfers;
    private final UserRepository users;
    private final NotificationClient client;
    private final long retryDelaySeconds;
    private final int maxAttempts;

    public NotificationService(TransferRepository transfers, UserRepository users, NotificationClient client,
            @Value("${notifications.retry-delay-seconds}") long retryDelaySeconds,
            @Value("${notifications.max-attempts}") int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("notifications.max-attempts deve ser pelo menos 1");
        }
        this.transfers = transfers;
        this.users = users;
        this.client = client;
        this.retryDelaySeconds = retryDelaySeconds;
        this.maxAttempts = maxAttempts;
    }

    @Transactional
    public boolean deliverNext() {
        var pending = transfers.findNextPendingNotification();
        if (pending.isEmpty()) {
            return false;
        }
        var transfer = pending.get();
        var payee = users.findById(transfer.getPayeeId()).orElseThrow();
        boolean delivered = false;
        try {
            client.send(transfer, payee.getEmail());
            delivered = true;
        } catch (RestClientException exception) {
            log.warn("Notificação pendente: transferId={}, tentativa={}",
                    transfer.getId(), transfer.getNotificationAttempts() + 1);
        }
        transfer.recordNotificationAttempt(delivered, Instant.now(), retryDelaySeconds, maxAttempts);
        return true;
    }
}
