package dev.starrk.wallet.transfer;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {
    @Query(value = """
            SELECT * FROM transfers
            WHERE notified_at IS NULL AND next_notification_at <= CURRENT_TIMESTAMP
            ORDER BY next_notification_at, id
            LIMIT 1 FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Transfer> findNextPendingNotification();
}
