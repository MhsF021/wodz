ALTER TABLE payout_account ADD COLUMN credential_source VARCHAR(20) NOT NULL DEFAULT 'SELLER_OAUTH';
ALTER TABLE payout_account ADD CONSTRAINT ck_payout_credential_source
    CHECK (credential_source IN ('SELLER_OAUTH', 'PLATFORM_DIRECT'));
ALTER TABLE payout_account DROP CONSTRAINT ck_payout_account_status;
ALTER TABLE payout_account ADD CONSTRAINT ck_payout_account_status
    CHECK (status IN ('ACTIVE', 'DISCONNECTED', 'PLATFORM'));
ALTER TABLE payout_account ADD CONSTRAINT ck_payout_platform
    CHECK (status <> 'PLATFORM' OR (credential_source = 'PLATFORM_DIRECT' AND access_token IS NOT NULL
        AND test_account = FALSE AND live_mode = TRUE AND application_id IS NOT NULL));
CREATE UNIQUE INDEX ux_payout_platform_owner
    ON payout_account(user_id, provider_user_id, application_id) WHERE credential_source = 'PLATFORM_DIRECT';

ALTER TABLE payment ADD COLUMN provider_net_amount NUMERIC(10,2);
ALTER TABLE payment ADD COLUMN provider_refunded_amount NUMERIC(10,2);
ALTER TABLE payment ADD CONSTRAINT ck_payment_provider_refunded_amount
    CHECK (provider_refunded_amount IS NULL OR (provider_refunded_amount >= 0 AND provider_refunded_amount <= amount));
ALTER TABLE payment ADD CONSTRAINT ck_payment_provider_net_amount
    CHECK (provider_net_amount IS NULL OR provider_net_amount >= 0);

CREATE TABLE manual_pix_transfer (
    id BIGSERIAL PRIMARY KEY,
    organizer_id BIGINT NOT NULL REFERENCES app_user(id),
    created_by BIGINT NOT NULL REFERENCES app_user(id),
    bank_reference VARCHAR(120) NOT NULL UNIQUE,
    amount NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    executed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE manual_pix_transfer_payment (
    transfer_id BIGINT NOT NULL REFERENCES manual_pix_transfer(id),
    payment_id BIGINT NOT NULL UNIQUE REFERENCES payment(id),
    organizer_amount NUMERIC(10,2) NOT NULL CHECK (organizer_amount > 0),
    wodz_fee NUMERIC(10,2) NOT NULL CHECK (wodz_fee >= 0),
    provider_fee NUMERIC(10,2) NOT NULL CHECK (provider_fee >= 0),
    PRIMARY KEY(transfer_id, payment_id)
);
