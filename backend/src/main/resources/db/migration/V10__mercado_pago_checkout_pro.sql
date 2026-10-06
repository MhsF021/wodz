-- Pagamentos iniciados no Checkout Pro ainda não têm método conhecido.
ALTER TABLE payment ADD COLUMN checkout_url TEXT;

ALTER TABLE payment DROP CONSTRAINT ck_payment_method;
ALTER TABLE payment ADD CONSTRAINT ck_payment_method
    CHECK (method IN ('PIX', 'CREDIT_CARD', 'DEBIT_CARD', 'CHECKOUT_PRO', 'BOLETO'));
