-- Categoria do gasto, do ponto de vista de quem pagou. Preenchida sob demanda pelo categorizador de IA
-- ou por regra, e gravada uma única vez.
ALTER TABLE transfers
    ADD COLUMN category VARCHAR(20),
    ADD CONSTRAINT ck_transfers_category CHECK (category IS NULL OR category IN (
        'FOOD', 'GROCERIES', 'TRANSPORT', 'HOUSING', 'BILLS', 'HEALTH', 'EDUCATION',
        'SHOPPING', 'LEISURE', 'PERSONAL_TRANSFER', 'OTHER'));
