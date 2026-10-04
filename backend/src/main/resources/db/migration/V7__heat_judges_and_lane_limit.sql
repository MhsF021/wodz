-- =====================================================================
-- WODZ | V7 - Judge por raia e no máximo 8 equipes por bateria
-- =====================================================================

-- Nome do judge que acompanhou a raia; informado junto com o resultado.
ALTER TABLE heat_lane ADD COLUMN judge_name VARCHAR(120);

-- No máximo 8 raias por bateria. NOT VALID: baterias antigas montadas com
-- mais raias continuam como estão; a regra vale para tudo daqui em diante.
ALTER TABLE heat_lane ADD CONSTRAINT ck_heat_lane_max CHECK (lane <= 8) NOT VALID;

-- ---------------------------------------------------------------------
-- PROCEDURE: gerar baterias de uma prova (substitui a versão da V2)
-- Até 8 raias e distribuição equilibrada: 17 equipes viram 5 + 6 + 6,
-- não 8 + 8 + 1. As piores colocadas correm primeiro e as baterias finais,
-- dos líderes, recebem as equipes que sobram da divisão.
-- ---------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE sp_generate_heats(
    p_workout_id       BIGINT,
    p_lanes            INT,
    p_first_start      TIMESTAMPTZ,
    p_interval_minutes INT)
LANGUAGE plpgsql AS $$
DECLARE
    v_category_id BIGINT;
    v_heat_id     BIGINT;
    v_total       INT;
    v_heats       INT;
    v_size        INT;
    v_lane        INT := 0;
    v_heat_no     INT := 0;
    r             RECORD;
BEGIN
    IF p_lanes IS NULL OR p_lanes < 1 OR p_lanes > 8 THEN
        RAISE EXCEPTION 'Cada bateria tem de 1 a 8 equipes';
    END IF;
    IF p_interval_minutes IS NULL OR p_interval_minutes < 0 THEN
        RAISE EXCEPTION 'Intervalo entre baterias inválido';
    END IF;

    SELECT category_id INTO v_category_id FROM workout WHERE id = p_workout_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Prova % não encontrada', p_workout_id USING ERRCODE = 'no_data_found';
    END IF;

    IF EXISTS (SELECT 1 FROM heat WHERE workout_id = p_workout_id AND status <> 'UPCOMING') THEN
        RAISE EXCEPTION 'Esta prova já tem baterias iniciadas ou concluídas; não é possível regerar';
    END IF;

    SELECT count(*) INTO v_total
      FROM team
     WHERE category_id = v_category_id
       AND status = 'CONFIRMED';
    IF v_total = 0 THEN
        RAISE EXCEPTION 'Não há equipes confirmadas nesta categoria para montar baterias';
    END IF;
    v_heats := ceil(v_total::NUMERIC / p_lanes)::INT;

    DELETE FROM heat WHERE workout_id = p_workout_id;

    FOR r IN
        SELECT t.id AS team_id
          FROM team t
          LEFT JOIN fn_category_leaderboard(v_category_id) lb ON lb.team_id = t.id
         WHERE t.category_id = v_category_id
           AND t.status = 'CONFIRMED'
         ORDER BY lb.overall_rank DESC NULLS FIRST, t.confirmed_at NULLS LAST, t.id
    LOOP
        IF v_lane = 0 THEN
            v_heat_no := v_heat_no + 1;
            v_size := v_total / v_heats
                      + CASE WHEN v_heat_no > v_heats - v_total % v_heats THEN 1 ELSE 0 END;
            INSERT INTO heat (workout_id, number, scheduled_at)
            VALUES (p_workout_id, v_heat_no,
                    p_first_start + make_interval(mins => (v_heat_no - 1) * p_interval_minutes))
            RETURNING id INTO v_heat_id;
        END IF;
        v_lane := v_lane + 1;
        INSERT INTO heat_lane (heat_id, team_id, lane) VALUES (v_heat_id, r.team_id, v_lane);
        IF v_lane = v_size THEN
            v_lane := 0;
        END IF;
    END LOOP;
END $$;

-- Grade de baterias: mesma view da V4, agora com o judge de cada raia
CREATE OR REPLACE VIEW vw_heat_schedule AS
SELECT h.id                AS heat_id,
       h.number            AS heat_number,
       h.scheduled_at,
       h.status,
       h.started_at,
       h.finished_at,
       w.id                AS workout_id,
       w.number            AS workout_number,
       w.name              AS workout_name,
       w.type              AS workout_type,
       w.time_cap_seconds,
       cat.id              AS category_id,
       cat.name            AS category_name,
       cat.display_order   AS category_order,
       ch.id               AS championship_id,
       ch.slug             AS championship_slug,
       ch.name             AS championship_name,
       COALESCE(
           (SELECT json_agg(json_build_object(
                       'lane', hl.lane,
                       'teamId', t.id,
                       'teamName', t.name,
                       'boxName', t.box_name,
                       'judgeName', hl.judge_name) ORDER BY hl.lane)
              FROM heat_lane hl
              JOIN team t ON t.id = hl.team_id
             WHERE hl.heat_id = h.id),
           '[]'::json)     AS lanes
  FROM heat h
  JOIN workout w       ON w.id = h.workout_id
  JOIN category cat    ON cat.id = w.category_id
  JOIN championship ch ON ch.id = cat.championship_id;
