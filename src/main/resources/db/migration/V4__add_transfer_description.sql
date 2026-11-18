-- Mensagem opcional escrita pelo pagador e exibida ao recebedor. É texto de terceiros:
-- o assistente de IA a trata como dado não confiável.
ALTER TABLE transfers
    ADD COLUMN description VARCHAR(140),
    ADD CONSTRAINT ck_transfers_description
        CHECK (description IS NULL OR (btrim(description) <> '' AND description !~ '[[:cntrl:]]'));

-- Extrato: transferências enviadas e recebidas de um usuário, das mais recentes para as mais antigas.
CREATE INDEX idx_transfers_payer_created ON transfers(payer_id, created_at DESC);
CREATE INDEX idx_transfers_payee_created ON transfers(payee_id, created_at DESC);
