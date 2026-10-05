-- =====================================================================
-- WODZ | V10 - Recebimento pelo organizador (split 1:1 do Mercado Pago)
-- O organizador conecta a própria conta Mercado Pago por OAuth e o dinheiro
-- das inscrições cai direto nela. Sem conta conectada, o campeonato não pode
-- ser publicado nem abrir inscrições.
-- =====================================================================

CREATE TABLE payout_account (
    id                BIGSERIAL    PRIMARY KEY,
    user_id           BIGINT       NOT NULL REFERENCES app_user (id),
    provider          VARCHAR(30)  NOT NULL,
    provider_user_id  VARCHAR(40)  NOT NULL,
    account_label     VARCHAR(200),
    -- tokens OAuth cifrados pela aplicação (AES-256-GCM, APP_ENCRYPTION_KEY)
    access_token      TEXT,
    refresh_token     TEXT,
    public_key        VARCHAR(120),
    live_mode         BOOLEAN      NOT NULL DEFAULT FALSE,
    expires_at        TIMESTAMPTZ,
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    connected_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    disconnected_at   TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_payout_account_status CHECK (status IN ('ACTIVE', 'DISCONNECTED')),
    CONSTRAINT ck_payout_account_token CHECK (status <> 'ACTIVE' OR access_token IS NOT NULL)
);
-- No máximo uma conta ativa por organizador.
CREATE UNIQUE INDEX ux_payout_account_active ON payout_account (user_id) WHERE status = 'ACTIVE';

CREATE TRIGGER trg_payout_account_updated_at
    BEFORE UPDATE ON payout_account FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();

CREATE TABLE payout_oauth_state (
    state          VARCHAR(64)  PRIMARY KEY,
    user_id        BIGINT       NOT NULL REFERENCES app_user (id),
    code_verifier  TEXT         NOT NULL,
    expires_at     TIMESTAMPTZ  NOT NULL
);

ALTER TABLE payment ADD COLUMN payout_account_id BIGINT REFERENCES payout_account (id);
ALTER TABLE payment ADD COLUMN application_fee NUMERIC(10,2);
ALTER TABLE payment ADD CONSTRAINT ck_payment_application_fee
    CHECK (application_fee IS NULL OR (application_fee >= 0 AND application_fee <= amount));

-- ---------------------------------------------------------------------
-- Publicar ou abrir inscrições exige conta de recebimento ativa. Só vale na
-- transição: campeonatos já publicados antes desta versão continuam como estão.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_championship_requires_payout()
RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.status = 'PUBLISHED' AND (TG_OP = 'INSERT' OR OLD.status IS DISTINCT FROM 'PUBLISHED'))
       OR (NEW.registration_open AND (TG_OP = 'INSERT' OR NOT OLD.registration_open)) THEN
        IF NOT EXISTS (SELECT 1
                         FROM payout_account p
                        WHERE p.user_id = NEW.owner_id
                          AND p.status = 'ACTIVE') THEN
            RAISE EXCEPTION 'Conecte sua conta Mercado Pago em "Recebimentos" antes de abrir o campeonato';
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_championship_requires_payout
    BEFORE INSERT OR UPDATE OF status, registration_open ON championship
    FOR EACH ROW EXECUTE FUNCTION fn_championship_requires_payout();
