-- Duração e descanso pertencem à bateria e não mudam se o WOD for editado depois.
ALTER TABLE heat ADD COLUMN duration_seconds INT CHECK (duration_seconds > 0);
ALTER TABLE heat ADD COLUMN interval_minutes INT NOT NULL DEFAULT 0 CHECK (interval_minutes BETWEEN 0 AND 240);
-- Último término usado para reagendar a sequência, inclusive depois de reabrir uma bateria.
ALTER TABLE heat ADD COLUMN schedule_end_at TIMESTAMPTZ;
UPDATE heat h SET duration_seconds = w.time_cap_seconds FROM workout w WHERE w.id = h.workout_id;

-- Horário de parede: um lock demorado não pode carimbar o início da transação como início da prova.
CREATE OR REPLACE FUNCTION fn_heat_status_timestamps()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_old_status VARCHAR(20);
BEGIN
    IF TG_OP = 'UPDATE' THEN v_old_status := OLD.status; END IF;
    IF NEW.status = 'IN_PROGRESS' AND v_old_status IS DISTINCT FROM 'IN_PROGRESS' THEN
        NEW.started_at := clock_timestamp();
        NEW.finished_at := NULL;
    ELSIF NEW.status = 'FINISHED' AND v_old_status IS DISTINCT FROM 'FINISHED' THEN
        NEW.finished_at := clock_timestamp();
        NEW.started_at := COALESCE(NEW.started_at, NEW.finished_at);
    ELSIF NEW.status = 'UPCOMING' THEN
        NEW.started_at := NULL;
        NEW.finished_at := NULL;
    END IF;
    RETURN NEW;
END $$;

-- Mantém os horários históricos; somente grades novas passam a somar duração + descanso.
CREATE OR REPLACE PROCEDURE sp_generate_heats(
    p_workout_id BIGINT, p_lanes INT, p_first_start TIMESTAMPTZ, p_interval_minutes INT)
LANGUAGE plpgsql AS $$
DECLARE
    v_category_id BIGINT;
    v_duration INT;
    v_heat_id BIGINT;
    v_total INT;
    v_heats INT;
    v_size INT;
    v_lane INT := 0;
    v_heat_no INT := 0;
    r RECORD;
BEGIN
    IF p_lanes IS NULL OR p_lanes < 1 OR p_lanes > 8 THEN
        RAISE EXCEPTION 'Cada bateria tem de 1 a 8 equipes';
    END IF;
    IF p_interval_minutes IS NULL OR p_interval_minutes < 0 OR p_interval_minutes > 240 THEN
        RAISE EXCEPTION 'Intervalo entre baterias inválido';
    END IF;
    IF p_first_start IS NULL THEN RAISE EXCEPTION 'Informe o horário da primeira bateria'; END IF;
    SELECT category_id, time_cap_seconds INTO v_category_id, v_duration
      FROM workout WHERE id = p_workout_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Prova não encontrada' USING ERRCODE = 'no_data_found'; END IF;
    IF v_duration IS NULL OR v_duration <= 0 THEN
        RAISE EXCEPTION 'Informe a duração / time cap da prova antes de montar as baterias';
    END IF;
    IF EXISTS (SELECT 1 FROM heat WHERE workout_id = p_workout_id AND status <> 'UPCOMING') THEN
        RAISE EXCEPTION 'Esta prova já tem baterias iniciadas ou concluídas; não é possível regerar';
    END IF;
    SELECT count(*) INTO v_total FROM team WHERE category_id = v_category_id AND status = 'CONFIRMED';
    IF v_total = 0 THEN RAISE EXCEPTION 'Não há equipes confirmadas nesta categoria para montar baterias'; END IF;
    v_heats := ceil(v_total::NUMERIC / p_lanes)::INT;
    DELETE FROM heat WHERE workout_id = p_workout_id;
    FOR r IN
        SELECT t.id AS team_id FROM team t
          LEFT JOIN fn_category_leaderboard(v_category_id) lb ON lb.team_id = t.id
         WHERE t.category_id = v_category_id AND t.status = 'CONFIRMED'
         ORDER BY lb.overall_rank DESC NULLS FIRST, t.confirmed_at NULLS LAST, t.id
    LOOP
        IF v_lane = 0 THEN
            v_heat_no := v_heat_no + 1;
            v_size := v_total / v_heats
                + CASE WHEN v_heat_no > v_heats - v_total % v_heats THEN 1 ELSE 0 END;
            INSERT INTO heat (workout_id, number, scheduled_at, duration_seconds, interval_minutes)
            VALUES (p_workout_id, v_heat_no,
                    p_first_start + make_interval(secs => (v_heat_no - 1) * (v_duration + p_interval_minutes * 60)),
                    v_duration, p_interval_minutes)
            RETURNING id INTO v_heat_id;
        END IF;
        v_lane := v_lane + 1;
        INSERT INTO heat_lane (heat_id, team_id, lane) VALUES (v_heat_id, r.team_id, v_lane);
        IF v_lane = v_size THEN v_lane := 0; END IF;
    END LOOP;
END $$;

-- Início real e término tardio reajustam todas as próximas, inclusive outras provas/categorias.
-- Preserva pausas já planejadas, além do descanso mínimo, e não altera baterias iniciadas.
CREATE FUNCTION fn_heat_reschedule_future()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    v_championship_id BIGINT;
    v_old_end TIMESTAMPTZ;
    v_new_end TIMESTAMPTZ;
    v_delta INTERVAL;
    v_next TIMESTAMPTZ;
    v_start TIMESTAMPTZ;
    r RECORD;
BEGIN
    IF NEW.status = OLD.status OR NEW.duration_seconds IS NULL OR NEW.status = 'UPCOMING' THEN RETURN NEW; END IF;
    v_old_end := COALESCE(OLD.schedule_end_at,
        COALESCE(OLD.started_at, OLD.scheduled_at) + make_interval(secs => NEW.duration_seconds));
    v_new_end := NEW.started_at + make_interval(secs => NEW.duration_seconds);
    IF NEW.status = 'FINISHED' THEN v_new_end := GREATEST(v_new_end, NEW.finished_at); END IF;
    UPDATE heat SET schedule_end_at = v_new_end WHERE id = NEW.id;
    v_delta := v_new_end - v_old_end;
    IF v_delta = interval '0 seconds' THEN RETURN NEW; END IF;
    SELECT cat.championship_id INTO v_championship_id
      FROM workout w JOIN category cat ON cat.id = w.category_id WHERE w.id = NEW.workout_id;
    v_next := v_new_end + make_interval(mins => NEW.interval_minutes);
    FOR r IN
        SELECT h.id, h.scheduled_at, COALESCE(h.duration_seconds, w.time_cap_seconds, 0) AS duration_seconds,
               h.interval_minutes
          FROM heat h JOIN workout w ON w.id = h.workout_id JOIN category cat ON cat.id = w.category_id
         WHERE cat.championship_id = v_championship_id AND h.status = 'UPCOMING' AND h.id <> NEW.id
           AND (h.scheduled_at, h.id) > (OLD.scheduled_at, OLD.id)
         ORDER BY h.scheduled_at, h.id FOR UPDATE OF h
    LOOP
        v_start := GREATEST(r.scheduled_at + v_delta, v_next);
        UPDATE heat SET scheduled_at = v_start WHERE id = r.id;
        v_next := v_start + make_interval(secs => r.duration_seconds, mins => r.interval_minutes);
    END LOOP;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_heat_reschedule_future AFTER UPDATE OF status ON heat
    FOR EACH ROW EXECUTE FUNCTION fn_heat_reschedule_future();

CREATE OR REPLACE VIEW vw_heat_schedule AS
SELECT h.id AS heat_id, h.number AS heat_number, h.scheduled_at, h.status, h.started_at, h.finished_at,
       w.id AS workout_id, w.number AS workout_number, w.name AS workout_name, w.type AS workout_type,
       w.time_cap_seconds, cat.id AS category_id, cat.name AS category_name, cat.display_order AS category_order,
       ch.id AS championship_id, ch.slug AS championship_slug, ch.name AS championship_name,
       COALESCE((SELECT json_agg(json_build_object(
           'lane', hl.lane, 'teamId', t.id, 'teamName', t.name, 'boxName', t.box_name, 'judgeName', hl.judge_name)
           ORDER BY hl.lane) FROM heat_lane hl JOIN team t ON t.id = hl.team_id WHERE hl.heat_id = h.id), '[]'::json) AS lanes,
       h.duration_seconds, h.interval_minutes
  FROM heat h JOIN workout w ON w.id = h.workout_id JOIN category cat ON cat.id = w.category_id
  JOIN championship ch ON ch.id = cat.championship_id;
