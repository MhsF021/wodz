-- Uma aprovação recebida após o cancelamento fica no histórico financeiro,
-- sem reativar a inscrição nem ocupar a vaga de uma nova equipe do capitão.
CREATE OR REPLACE FUNCTION fn_payment_after_status_change()
RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    v_old_status VARCHAR(20);
BEGIN
    IF TG_OP = 'UPDATE' THEN
        v_old_status := OLD.status;
    END IF;

    IF NEW.status = 'APPROVED' AND v_old_status IS DISTINCT FROM 'APPROVED' THEN
        UPDATE team
           SET status = 'CONFIRMED',
               confirmed_at = COALESCE(confirmed_at, now())
         WHERE id = NEW.team_id
           AND status = 'PENDING_PAYMENT';
    ELSIF NEW.status = 'REFUNDED' AND v_old_status = 'APPROVED' THEN
        UPDATE team SET status = 'CANCELLED' WHERE id = NEW.team_id;
    END IF;
    RETURN NULL;
END $$;
