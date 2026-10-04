-- =====================================================================
-- WODZ | V3 - Triggers (regras de integridade e automações)
-- =====================================================================

-- ---------------------------------------------------------------------
-- updated_at automático
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_set_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END $$;

CREATE TRIGGER trg_app_user_updated_at     BEFORE UPDATE ON app_user     FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();
CREATE TRIGGER trg_championship_updated_at BEFORE UPDATE ON championship FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();
CREATE TRIGGER trg_category_updated_at     BEFORE UPDATE ON category     FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();
CREATE TRIGGER trg_workout_updated_at      BEFORE UPDATE ON workout      FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();
CREATE TRIGGER trg_team_updated_at         BEFORE UPDATE ON team         FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();
CREATE TRIGGER trg_payment_updated_at      BEFORE UPDATE ON payment      FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();
CREATE TRIGGER trg_heat_updated_at         BEFORE UPDATE ON heat         FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();
CREATE TRIGGER trg_score_updated_at        BEFORE UPDATE ON score        FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();

-- ---------------------------------------------------------------------
-- Equipe: só entra com inscrições abertas e vaga na categoria.
-- O lock na categoria serializa inscrições simultâneas (evita overbooking).
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_team_before_insert()
RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    v_championship_id BIGINT;
    v_slots           INT;
BEGIN
    SELECT championship_id INTO v_championship_id
      FROM category
     WHERE id = NEW.category_id
       FOR UPDATE;

    IF NOT COALESCE(fn_registration_is_open(v_championship_id), FALSE) THEN
        RAISE EXCEPTION 'As inscrições deste campeonato não estão abertas';
    END IF;

    v_slots := fn_category_slots_left(NEW.category_id);
    IF v_slots IS NOT NULL AND v_slots <= 0 THEN
        RAISE EXCEPTION 'Esta categoria não tem mais vagas';
    END IF;

    IF NEW.registration_code IS NULL THEN
        NEW.registration_code := upper(substr(md5(random()::TEXT || clock_timestamp()::TEXT), 1, 10));
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_team_before_insert
    BEFORE INSERT ON team
    FOR EACH ROW EXECUTE FUNCTION fn_team_before_insert();

-- Equipe cancelada: sai das baterias que ainda não começaram e
-- tem as cobranças pendentes canceladas.
CREATE OR REPLACE FUNCTION fn_team_after_status_change()
RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status = 'CANCELLED' AND OLD.status <> 'CANCELLED' THEN
        DELETE FROM heat_lane hl
         USING heat h
         WHERE hl.heat_id = h.id
           AND hl.team_id = NEW.id
           AND h.status = 'UPCOMING';

        UPDATE payment
           SET status = 'CANCELLED',
               status_detail = 'Inscrição cancelada'
         WHERE team_id = NEW.id
           AND status = 'PENDING';
    END IF;
    RETURN NULL;
END $$;

CREATE TRIGGER trg_team_after_status
    AFTER UPDATE OF status ON team
    FOR EACH ROW EXECUTE FUNCTION fn_team_after_status_change();

-- ---------------------------------------------------------------------
-- Atleta: CPF válido, limite de atletas por equipe e um atleta (CPF)
-- em no máximo uma equipe ativa por campeonato.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_team_member_validate()
RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    v_team_size       INT;
    v_championship_id BIGINT;
    v_count           INT;
BEGIN
    NEW.cpf := regexp_replace(NEW.cpf, '\D', '', 'g');
    IF NOT fn_cpf_is_valid(NEW.cpf) THEN
        RAISE EXCEPTION 'CPF inválido para o atleta %', NEW.full_name;
    END IF;

    SELECT cat.team_size, cat.championship_id
      INTO v_team_size, v_championship_id
      FROM team t
      JOIN category cat ON cat.id = t.category_id
     WHERE t.id = NEW.team_id;

    SELECT count(*) INTO v_count
      FROM team_member
     WHERE team_id = NEW.team_id
       AND id <> NEW.id;

    IF v_count >= v_team_size THEN
        RAISE EXCEPTION 'A equipe já tem os % atleta(s) permitidos na categoria', v_team_size;
    END IF;

    IF EXISTS (
        SELECT 1
          FROM team_member m
          JOIN team t       ON t.id = m.team_id
          JOIN category cat ON cat.id = t.category_id
         WHERE m.cpf = NEW.cpf
           AND m.team_id <> NEW.team_id
           AND t.status <> 'CANCELLED'
           AND cat.championship_id = v_championship_id) THEN
        RAISE EXCEPTION 'O atleta % já está inscrito em outra equipe deste campeonato', NEW.full_name;
    END IF;

    RETURN NEW;
END $$;

CREATE TRIGGER trg_team_member_validate
    BEFORE INSERT OR UPDATE ON team_member
    FOR EACH ROW EXECUTE FUNCTION fn_team_member_validate();

-- ---------------------------------------------------------------------
-- Pagamento: data de pagamento e proteção contra regressão de status
-- (webhooks podem chegar fora de ordem).
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_payment_before_write()
RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF OLD.status = 'APPROVED'
           AND NEW.status IN ('PENDING', 'REJECTED', 'EXPIRED', 'CANCELLED') THEN
            NEW.status := OLD.status;
            NEW.status_detail := OLD.status_detail;
        END IF;
    END IF;

    IF NEW.status = 'APPROVED' AND NEW.paid_at IS NULL THEN
        NEW.paid_at := now();
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_payment_before_write
    BEFORE INSERT OR UPDATE OF status ON payment
    FOR EACH ROW EXECUTE FUNCTION fn_payment_before_write();

-- Pagamento aprovado confirma a equipe; estorno cancela a inscrição.
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
           AND status <> 'CONFIRMED';
    ELSIF NEW.status = 'REFUNDED' AND v_old_status = 'APPROVED' THEN
        UPDATE team SET status = 'CANCELLED' WHERE id = NEW.team_id;
    END IF;
    RETURN NULL;
END $$;

CREATE TRIGGER trg_payment_after_status
    AFTER INSERT OR UPDATE OF status ON payment
    FOR EACH ROW EXECUTE FUNCTION fn_payment_after_status_change();

-- ---------------------------------------------------------------------
-- Bateria: carimba início/fim conforme o status
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_heat_status_timestamps()
RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    v_old_status VARCHAR(20);
BEGIN
    IF TG_OP = 'UPDATE' THEN
        v_old_status := OLD.status;
    END IF;

    IF NEW.status = 'IN_PROGRESS' AND v_old_status IS DISTINCT FROM 'IN_PROGRESS' THEN
        NEW.started_at  := now();
        NEW.finished_at := NULL;
    ELSIF NEW.status = 'FINISHED' AND v_old_status IS DISTINCT FROM 'FINISHED' THEN
        NEW.started_at  := COALESCE(NEW.started_at, now());
        NEW.finished_at := now();
    ELSIF NEW.status = 'UPCOMING' THEN
        NEW.started_at  := NULL;
        NEW.finished_at := NULL;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_heat_status_timestamps
    BEFORE INSERT OR UPDATE OF status ON heat
    FOR EACH ROW EXECUTE FUNCTION fn_heat_status_timestamps();

-- Raia: equipe confirmada, da categoria da prova, em uma só bateria por prova
CREATE OR REPLACE FUNCTION fn_heat_lane_validate()
RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    v_workout_id   BIGINT;
    v_workout_cat  BIGINT;
    v_team_cat     BIGINT;
    v_team_status  VARCHAR(20);
    v_team_name    VARCHAR(80);
BEGIN
    SELECT w.id, w.category_id INTO v_workout_id, v_workout_cat
      FROM heat h JOIN workout w ON w.id = h.workout_id
     WHERE h.id = NEW.heat_id;

    SELECT t.category_id, t.status, t.name INTO v_team_cat, v_team_status, v_team_name
      FROM team t WHERE t.id = NEW.team_id;

    IF v_team_cat IS DISTINCT FROM v_workout_cat THEN
        RAISE EXCEPTION 'A equipe % não pertence à categoria desta prova', v_team_name;
    END IF;
    IF v_team_status <> 'CONFIRMED' THEN
        RAISE EXCEPTION 'A equipe % ainda não está confirmada', v_team_name;
    END IF;
    IF EXISTS (
        SELECT 1
          FROM heat_lane hl
          JOIN heat h ON h.id = hl.heat_id
         WHERE h.workout_id = v_workout_id
           AND hl.team_id = NEW.team_id
           AND hl.id <> NEW.id) THEN
        RAISE EXCEPTION 'A equipe % já está em outra bateria desta prova', v_team_name;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_heat_lane_validate
    BEFORE INSERT OR UPDATE ON heat_lane
    FOR EACH ROW EXECUTE FUNCTION fn_heat_lane_validate();

-- ---------------------------------------------------------------------
-- Prova: tipo e categoria não mudam depois que há resultados
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_workout_guard()
RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.type <> OLD.type OR NEW.category_id <> OLD.category_id)
       AND EXISTS (SELECT 1 FROM score WHERE workout_id = OLD.id) THEN
        RAISE EXCEPTION 'Não é possível mudar o tipo ou a categoria de uma prova que já tem resultados';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_workout_guard
    BEFORE UPDATE OF type, category_id ON workout
    FOR EACH ROW EXECUTE FUNCTION fn_workout_guard();

-- ---------------------------------------------------------------------
-- Resultado: valida os campos conforme o tipo da prova e limpa o que
-- não se aplica (tempo para For Time, reps para AMRAP, kg para PR...).
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_score_validate()
RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    v_type        VARCHAR(20);
    v_cap         INT;
    v_workout_cat BIGINT;
    v_team_cat    BIGINT;
    v_team_status VARCHAR(20);
BEGIN
    SELECT w.type, w.time_cap_seconds, w.category_id
      INTO v_type, v_cap, v_workout_cat
      FROM workout w WHERE w.id = NEW.workout_id;

    SELECT t.category_id, t.status INTO v_team_cat, v_team_status
      FROM team t WHERE t.id = NEW.team_id;

    IF v_team_cat IS DISTINCT FROM v_workout_cat THEN
        RAISE EXCEPTION 'A equipe não pertence à categoria desta prova';
    END IF;
    IF v_team_status <> 'CONFIRMED' THEN
        RAISE EXCEPTION 'Só equipes confirmadas podem receber resultado';
    END IF;
    IF NEW.status = 'CAPPED' AND v_type <> 'FOR_TIME' THEN
        RAISE EXCEPTION 'CAP só se aplica a provas For Time';
    END IF;

    IF NEW.status IN ('DNS', 'DQ') THEN
        NEW.time_ms := NULL;
        NEW.reps := NULL;
        NEW.load_kg := NULL;
        NEW.tiebreak_ms := NULL;
        RETURN NEW;
    END IF;

    IF v_type = 'FOR_TIME' THEN
        NEW.load_kg := NULL;
        IF NEW.status = 'VALID' THEN
            IF NEW.time_ms IS NULL THEN
                RAISE EXCEPTION 'Informe o tempo da equipe';
            END IF;
            IF v_cap IS NOT NULL AND NEW.time_ms > v_cap * 1000 THEN
                RAISE EXCEPTION 'Tempo acima do time cap. Lance como CAP + repetições';
            END IF;
            NEW.reps := NULL;
        ELSE
            IF NEW.reps IS NULL THEN
                RAISE EXCEPTION 'Informe as repetições feitas até o time cap';
            END IF;
            NEW.time_ms := NULL;
        END IF;
    ELSIF v_type IN ('AMRAP', 'MAX_REPS') THEN
        IF NEW.reps IS NULL THEN
            RAISE EXCEPTION 'Informe o total de repetições';
        END IF;
        NEW.time_ms := NULL;
        NEW.load_kg := NULL;
    ELSIF v_type = 'MAX_LOAD' THEN
        IF NEW.load_kg IS NULL THEN
            RAISE EXCEPTION 'Informe a carga em kg';
        END IF;
        NEW.time_ms := NULL;
        NEW.reps := NULL;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_score_validate
    BEFORE INSERT OR UPDATE OF status, time_ms, reps, load_kg, tiebreak_ms, team_id, workout_id ON score
    FOR EACH ROW EXECUTE FUNCTION fn_score_validate();

-- Recalcula colocações/pontos da prova a cada lançamento.
-- O UPDATE feito por fn_rank_workout só toca placement/points, então
-- não dispara esta trigger de novo (UPDATE OF ...).
CREATE OR REPLACE FUNCTION fn_score_rerank()
RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM fn_rank_workout(OLD.workout_id);
        RETURN NULL;
    END IF;

    PERFORM fn_rank_workout(NEW.workout_id);
    IF TG_OP = 'UPDATE' THEN
        IF NEW.workout_id <> OLD.workout_id THEN
            PERFORM fn_rank_workout(OLD.workout_id);
        END IF;
    END IF;
    RETURN NULL;
END $$;

CREATE TRIGGER trg_score_rerank
    AFTER INSERT OR DELETE OR UPDATE OF status, time_ms, reps, load_kg, tiebreak_ms, workout_id ON score
    FOR EACH ROW EXECUTE FUNCTION fn_score_rerank();

-- Auditoria. A aplicação informa o usuário via set_config('app.current_user_id').
CREATE OR REPLACE FUNCTION fn_score_audit()
RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    v_user BIGINT := NULLIF(current_setting('app.current_user_id', TRUE), '')::BIGINT;
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO score_audit (score_id, workout_id, team_id, operation, new_data, changed_by)
        VALUES (NEW.id, NEW.workout_id, NEW.team_id, 'I', to_jsonb(NEW), v_user);
    ELSIF TG_OP = 'UPDATE' THEN
        INSERT INTO score_audit (score_id, workout_id, team_id, operation, old_data, new_data, changed_by)
        VALUES (NEW.id, NEW.workout_id, NEW.team_id, 'U', to_jsonb(OLD), to_jsonb(NEW), v_user);
    ELSE
        INSERT INTO score_audit (score_id, workout_id, team_id, operation, old_data, changed_by)
        VALUES (OLD.id, OLD.workout_id, OLD.team_id, 'D', to_jsonb(OLD), v_user);
    END IF;
    RETURN NULL;
END $$;

CREATE TRIGGER trg_score_audit
    AFTER INSERT OR DELETE OR UPDATE OF status, time_ms, reps, load_kg, tiebreak_ms, notes ON score
    FOR EACH ROW EXECUTE FUNCTION fn_score_audit();

-- ---------------------------------------------------------------------
-- Campeonato: mudou o sistema de pontuação -> recalcula todas as provas
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_championship_rescore()
RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    r RECORD;
BEGIN
    IF NEW.scoring_system IS DISTINCT FROM OLD.scoring_system
       OR NEW.points_first_place IS DISTINCT FROM OLD.points_first_place
       OR NEW.points_step IS DISTINCT FROM OLD.points_step THEN
        FOR r IN
            SELECT w.id
              FROM workout w
              JOIN category cat ON cat.id = w.category_id
             WHERE cat.championship_id = NEW.id
        LOOP
            PERFORM fn_rank_workout(r.id);
        END LOOP;
    END IF;
    RETURN NULL;
END $$;

CREATE TRIGGER trg_championship_rescore
    AFTER UPDATE OF scoring_system, points_first_place, points_step ON championship
    FOR EACH ROW EXECUTE FUNCTION fn_championship_rescore();
