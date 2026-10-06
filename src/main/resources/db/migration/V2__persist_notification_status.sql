ALTER TABLE transfers
    ADD COLUMN notification_status VARCHAR(10) NOT NULL DEFAULT 'PENDING';

UPDATE transfers
SET notification_status = 'SENT'
WHERE notified_at IS NOT NULL;

ALTER TABLE transfers
    ADD CONSTRAINT ck_transfers_notification_status
        CHECK (notification_status IN ('PENDING', 'SENT', 'FAILED')),
    ADD CONSTRAINT ck_transfers_notification_status_notified_at
        CHECK ((notification_status = 'SENT') = (notified_at IS NOT NULL));

DROP INDEX idx_transfers_pending_notification;

CREATE INDEX idx_transfers_pending_notification
    ON transfers(next_notification_at) WHERE notification_status = 'PENDING';
