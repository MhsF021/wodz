-- Null preserva como incertas as tentativas legadas sem resultado de emissão comprovado.
ALTER TABLE checkout_session ADD COLUMN pix_creation_rejected_at TIMESTAMPTZ;
COMMENT ON COLUMN checkout_session.pix_creation_rejected_at IS
    'Recusa definitiva da primeira emissão Pix; preserva payload, referência e validade originais.';
