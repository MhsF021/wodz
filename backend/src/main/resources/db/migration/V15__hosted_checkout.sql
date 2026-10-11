-- Mantém o histórico financeiro e separa preferências das tentativas de pagamento.
CREATE TABLE checkout_session (
    id BIGSERIAL PRIMARY KEY,
    team_id BIGINT NOT NULL REFERENCES team(id),
    payout_account_id BIGINT NOT NULL REFERENCES payout_account(id),
    amount NUMERIC(10,2) NOT NULL CHECK (amount > 0),
    application_fee NUMERIC(10,2) CHECK (application_fee >= 0 AND application_fee <= amount),
    external_reference VARCHAR(64) NOT NULL UNIQUE,
    provider_preference_id VARCHAR(120),
    checkout_url TEXT,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_checkout_team ON checkout_session(team_id, id DESC);
CREATE INDEX ix_checkout_created ON checkout_session(created_at);
ALTER TABLE payment ADD COLUMN checkout_session_id BIGINT REFERENCES checkout_session(id);
CREATE INDEX ix_payment_checkout ON payment(checkout_session_id);
ALTER TABLE payment DROP CONSTRAINT ck_payment_method;
ALTER TABLE payment ADD CONSTRAINT ck_payment_method
    CHECK (method IN ('PIX', 'CREDIT_CARD', 'DEBIT_CARD', 'BOLETO', 'MERCADO_PAGO'));

-- O Checkout Pro define os meios disponíveis; o WODZ não coleta boleto nem dados de pagamento.
CREATE OR REPLACE FUNCTION fn_payment_reject_boleto()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.method = 'BOLETO' AND NEW.checkout_session_id IS NULL
       AND (TG_OP = 'INSERT' OR OLD.method <> 'BOLETO') THEN
        RAISE EXCEPTION 'Boleto só é aceito no checkout hospedado';
    END IF;
    RETURN NEW;
END $$;
