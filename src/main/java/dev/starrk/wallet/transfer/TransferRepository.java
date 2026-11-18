package dev.starrk.wallet.transfer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {
    Optional<Transfer> findByPayerIdAndIdempotencyKey(Long payerId, String idempotencyKey);

    @Query("""
            select t from Transfer t
            where t.payerId = :userId or t.payeeId = :userId
            order by t.createdAt desc, t.id
            """)
    List<Transfer> findStatement(long userId, Pageable pageable);

    /** Grava a categoria só se ainda não houver uma, sem segurar transação durante a chamada ao LLM. */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update Transfer t set t.category = :category where t.id = :id and t.category is null")
    int assignCategoryIfAbsent(UUID id, TransferCategory category);

    @Query(value = """
            SELECT * FROM transfers
            WHERE notification_status = 'PENDING' AND next_notification_at <= CURRENT_TIMESTAMP
            ORDER BY next_notification_at, id
            LIMIT 1 FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Transfer> findNextPendingNotification();
}
