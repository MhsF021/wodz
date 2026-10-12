ALTER TABLE checkout_session
    ADD COLUMN method VARCHAR(10) NOT NULL DEFAULT 'CARD' CHECK (method IN ('CARD', 'PIX')),
    ADD COLUMN provider_payment_id VARCHAR(100),
    ADD COLUMN pix_payer TEXT,
    ADD COLUMN pix_description TEXT,
    ADD COLUMN terms_accepted_at TIMESTAMPTZ,
    ADD COLUMN terms_version VARCHAR(40);
CREATE UNIQUE INDEX ux_checkout_pix_payment ON checkout_session(provider_payment_id)
    WHERE provider_payment_id IS NOT NULL;
COMMENT ON COLUMN checkout_session.pix_payer IS 'CPF e email cifrados (AES-GCM), preservam o payload para retries idempotentes.';
