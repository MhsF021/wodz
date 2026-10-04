-- =====================================================================
-- WODZ | V9 - Cartão de débito e fim do boleto
-- Novas cobranças: Pix, cartão de crédito ou cartão de débito. 'BOLETO'
-- continua aceito na constraint só para preservar o histórico financeiro
-- (boletos antigos pendentes ainda podem compensar ou expirar pelo job).
-- =====================================================================

ALTER TABLE payment DROP CONSTRAINT ck_payment_method;
ALTER TABLE payment ADD CONSTRAINT ck_payment_method
    CHECK (method IN ('PIX', 'CREDIT_CARD', 'DEBIT_CARD', 'BOLETO'));

-- Débito é sempre à vista.
ALTER TABLE payment ADD CONSTRAINT ck_payment_debit_installments
    CHECK (method <> 'DEBIT_CARD' OR (installments IS NOT NULL AND installments = 1));

-- Nenhum boleto novo, nem por INSERT nem trocando o método de um pagamento existente.
CREATE OR REPLACE FUNCTION fn_payment_reject_boleto()
RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.method = 'BOLETO' AND (TG_OP = 'INSERT' OR OLD.method <> 'BOLETO') THEN
        RAISE EXCEPTION 'Boleto não é mais aceito como forma de pagamento';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_payment_reject_boleto
    BEFORE INSERT OR UPDATE OF method ON payment
    FOR EACH ROW EXECUTE FUNCTION fn_payment_reject_boleto();
