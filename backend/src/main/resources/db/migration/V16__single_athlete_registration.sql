-- Uma conta pode ter somente uma inscrição ativa por campeonato, inclusive sem pagamento.
-- Preserva inscrições antigas e canceladas; não reescreve o histórico financeiro.
CREATE OR REPLACE FUNCTION fn_team_single_registration()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    v_championship_id BIGINT;
BEGIN
    IF NEW.status = 'CANCELLED' THEN RETURN NEW; END IF;
    IF TG_OP = 'UPDATE' THEN
        IF OLD.status <> 'CANCELLED' AND OLD.captain_id = NEW.captain_id AND OLD.category_id = NEW.category_id THEN
            RETURN NEW;
        END IF;
    END IF;

    -- Mesma ordem de bloqueios do serviço: conta antes da categoria.
    PERFORM 1 FROM app_user WHERE id = NEW.captain_id FOR UPDATE;
    SELECT championship_id INTO v_championship_id FROM category WHERE id = NEW.category_id;
    IF EXISTS (
        SELECT 1 FROM team t JOIN category c ON c.id = t.category_id
        WHERE t.captain_id = NEW.captain_id AND c.championship_id = v_championship_id
          AND t.status <> 'CANCELLED' AND t.id <> COALESCE(NEW.id, 0)
    ) THEN
        RAISE EXCEPTION 'Você já possui uma inscrição para esse campeonato'
            USING ERRCODE = '23505', CONSTRAINT = 'uq_team_captain_championship_active';
    END IF;
    RETURN NEW;
END $$;

-- Executa antes de trg_team_before_insert, que bloqueia a categoria para conferir vagas.
CREATE TRIGGER trg_team_00_single_registration
    BEFORE INSERT OR UPDATE OF captain_id, category_id, status ON team
    FOR EACH ROW EXECUTE FUNCTION fn_team_single_registration();
