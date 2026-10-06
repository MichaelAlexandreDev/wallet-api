ALTER TABLE transfers
    ADD COLUMN idempotency_key VARCHAR(128),
    ADD COLUMN request_fingerprint VARCHAR(64),
    ADD CONSTRAINT ck_transfers_idempotency_pair
        CHECK ((idempotency_key IS NULL) = (request_fingerprint IS NULL)),
    ADD CONSTRAINT ck_transfers_idempotency_key
        CHECK (idempotency_key IS NULL OR (length(idempotency_key) BETWEEN 1 AND 128 AND btrim(idempotency_key) <> '')),
    ADD CONSTRAINT ck_transfers_request_fingerprint
        CHECK (request_fingerprint IS NULL OR request_fingerprint ~ '^[0-9a-f]{64}$');

CREATE UNIQUE INDEX uq_transfers_payer_idempotency_key
    ON transfers(payer_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
