-- =====================================================================
-- WODZ | V2 - Functions e procedures de negócio
-- =====================================================================

-- ---------------------------------------------------------------------
-- Validação de CPF (dígitos verificadores)
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_cpf_is_valid(p_cpf TEXT)
RETURNS BOOLEAN
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    d TEXT := regexp_replace(COALESCE(p_cpf, ''), '\D', '', 'g');
    s INT;
    r INT;
    i INT;
BEGIN
    IF length(d) <> 11 OR d ~ '^(\d)\1{10}$' THEN
        RETURN FALSE;
    END IF;

    s := 0;
    FOR i IN 1..9 LOOP
        s := s + substr(d, i, 1)::INT * (11 - i);
    END LOOP;
    r := (s * 10) % 11;
    IF r = 10 THEN r := 0; END IF;
    IF r <> substr(d, 10, 1)::INT THEN
        RETURN FALSE;
    END IF;

    s := 0;
    FOR i IN 1..10 LOOP
        s := s + substr(d, i, 1)::INT * (12 - i);
    END LOOP;
    r := (s * 10) % 11;
    IF r = 10 THEN r := 0; END IF;
    RETURN r = substr(d, 11, 1)::INT;
END $$;

-- ---------------------------------------------------------------------
-- Formatação de resultados para o leaderboard
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_format_duration(p_ms INT)
RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    v_min INT;
    v_sec INT;
BEGIN
    IF p_ms IS NULL THEN
        RETURN NULL;
    END IF;
    v_min := p_ms / 60000;
    v_sec := (p_ms % 60000) / 1000;
    RETURN lpad(v_min::TEXT, 2, '0') || ':' || lpad(v_sec::TEXT, 2, '0')
        || CASE WHEN p_ms % 1000 >= 100 THEN '.' || ((p_ms % 1000) / 100)::TEXT ELSE '' END;
END $$;

CREATE OR REPLACE FUNCTION fn_format_result(
    p_type           VARCHAR,
    p_status         VARCHAR,
    p_time_ms        INT,
    p_reps           INT,
    p_load_kg        NUMERIC,
    p_reps_per_round INT)
RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN
    IF p_status IN ('DNS', 'DQ') THEN
        RETURN p_status;
    END IF;
    IF p_status = 'CAPPED' THEN
        RETURN 'CAP + ' || COALESCE(p_reps, 0) || ' reps';
    END IF;
    RETURN CASE p_type
        WHEN 'FOR_TIME' THEN fn_format_duration(p_time_ms)
        WHEN 'MAX_LOAD' THEN trim_scale(p_load_kg)::TEXT || ' kg'
        WHEN 'AMRAP' THEN
            CASE WHEN COALESCE(p_reps_per_round, 0) > 0
                 THEN (p_reps / p_reps_per_round) || ' rds + ' || (p_reps % p_reps_per_round)
                 ELSE p_reps || ' reps'
            END
        ELSE p_reps || ' reps'
    END;
END $$;

-- ---------------------------------------------------------------------
-- Inscrições: janela aberta? vagas restantes?
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_registration_is_open(p_championship_id BIGINT)
RETURNS BOOLEAN
LANGUAGE sql STABLE AS $$
    SELECT c.registration_open
       AND c.status = 'PUBLISHED'
       AND (c.registration_opens_at IS NULL OR now() >= c.registration_opens_at)
       AND (c.registration_closes_at IS NULL OR now() <= c.registration_closes_at)
      FROM championship c
     WHERE c.id = p_championship_id
$$;

-- NULL = categoria sem limite de vagas
CREATE OR REPLACE FUNCTION fn_category_slots_left(p_category_id BIGINT)
RETURNS INT
LANGUAGE sql STABLE AS $$
    SELECT CASE
             WHEN cat.max_teams IS NULL THEN NULL
             ELSE cat.max_teams - (SELECT count(*)
                                     FROM team t
                                    WHERE t.category_id = cat.id
                                      AND t.status <> 'CANCELLED')::INT
           END
      FROM category cat
     WHERE cat.id = p_category_id
$$;

-- ---------------------------------------------------------------------
-- Busca pública de campeonatos (sem acento, tolerante a erro de digitação)
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_search_championships(p_term TEXT, p_state TEXT)
RETURNS SETOF championship
LANGUAGE sql STABLE AS $$
    SELECT c.*
      FROM championship c
     WHERE c.status IN ('PUBLISHED', 'IN_PROGRESS', 'FINISHED')
       AND (COALESCE(p_state, '') = '' OR c.state = upper(p_state))
       AND (COALESCE(p_term, '') = ''
            OR f_unaccent(lower(c.name || ' ' || c.city || ' ' || c.venue_name))
               LIKE '%' || f_unaccent(lower(p_term)) || '%'
            OR similarity(f_unaccent(lower(c.name)), f_unaccent(lower(p_term))) > 0.3)
     ORDER BY (c.end_date < current_date),          -- futuros e em andamento primeiro
              CASE WHEN c.end_date >= current_date THEN c.start_date END ASC,
              c.start_date DESC
     LIMIT 50
$$;

-- ---------------------------------------------------------------------
-- Ranking de uma prova: calcula colocação e pontos de cada equipe.
--   FOR_TIME : menor tempo; equipes que estouraram o cap (CAPPED)
--              ficam abaixo de quem terminou, ordenadas por reps.
--   AMRAP / MAX_REPS : mais repetições
--   MAX_LOAD : maior carga
--   Desempate: tiebreak_ms (menor vence). DNS/DQ empatam na lanterna.
-- Sistema POINTS_TABLE: 1º = points_first_place, cai points_step por posição.
-- Sistema RANK_SUM:     pontos = colocação (menor soma vence).
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_rank_workout(p_workout_id BIGINT)
RETURNS VOID
LANGUAGE plpgsql AS $$
DECLARE
    v_type   VARCHAR(20);
    v_system VARCHAR(20);
    v_first  INT;
    v_step   INT;
BEGIN
    SELECT w.type, c.scoring_system, c.points_first_place, c.points_step
      INTO v_type, v_system, v_first, v_step
      FROM workout w
      JOIN category cat   ON cat.id = w.category_id
      JOIN championship c ON c.id = cat.championship_id
     WHERE w.id = p_workout_id;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    WITH ranked AS (
        SELECT s.id,
               s.status,
               RANK() OVER (
                   ORDER BY
                       CASE s.status WHEN 'VALID' THEN 0 WHEN 'CAPPED' THEN 1 ELSE 2 END,
                       CASE WHEN v_type = 'FOR_TIME' AND s.status = 'VALID'
                            THEN s.time_ms END ASC NULLS LAST,
                       CASE WHEN v_type IN ('AMRAP', 'MAX_REPS') OR s.status = 'CAPPED'
                            THEN s.reps END DESC NULLS LAST,
                       CASE WHEN v_type = 'MAX_LOAD'
                            THEN s.load_kg END DESC NULLS LAST,
                       s.tiebreak_ms ASC NULLS LAST
               ) AS pos
          FROM score s
         WHERE s.workout_id = p_workout_id
    )
    UPDATE score s
       SET placement = r.pos,
           points = CASE
                        WHEN v_system = 'RANK_SUM' THEN r.pos
                        WHEN r.status IN ('DNS', 'DQ') THEN 0
                        ELSE GREATEST(v_first - (r.pos - 1) * v_step, 0)
                    END
      FROM ranked r
     WHERE r.id = s.id;
END $$;

-- ---------------------------------------------------------------------
-- Leaderboard geral de uma categoria
-- Equipe sem resultado numa prova que já tem resultados:
--   RANK_SUM     -> recebe a última colocação possível (nº de equipes)
--   POINTS_TABLE -> recebe 0 pontos
-- Desempate geral: mais primeiros lugares.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_category_leaderboard(p_category_id BIGINT)
RETURNS TABLE (
    overall_rank       BIGINT,
    team_id            BIGINT,
    team_name          VARCHAR,
    box_name           VARCHAR,
    total_points       NUMERIC,
    workouts_completed BIGINT,
    first_places       BIGINT
)
LANGUAGE plpgsql STABLE AS $$
#variable_conflict use_column
DECLARE
    v_system  VARCHAR(20);
    v_penalty NUMERIC;
BEGIN
    SELECT c.scoring_system INTO v_system
      FROM category cat
      JOIN championship c ON c.id = cat.championship_id
     WHERE cat.id = p_category_id;

    SELECT CASE WHEN v_system = 'RANK_SUM' THEN count(*) ELSE 0 END
      INTO v_penalty
      FROM team t
     WHERE t.category_id = p_category_id
       AND t.status = 'CONFIRMED';

    RETURN QUERY
    WITH scored_workouts AS (
        SELECT w.id
          FROM workout w
         WHERE w.category_id = p_category_id
           AND EXISTS (SELECT 1 FROM score s WHERE s.workout_id = w.id)
    ),
    totals AS (
        SELECT t.id         AS t_id,
               t.name       AS t_name,
               t.box_name   AS t_box,
               COALESCE(SUM(CASE WHEN sw.id IS NULL THEN 0
                                 ELSE COALESCE(s.points, v_penalty) END), 0) AS t_total,
               COUNT(s.id)                                    AS t_done,
               COUNT(s.id) FILTER (WHERE s.placement = 1)     AS t_firsts
          FROM team t
          LEFT JOIN scored_workouts sw ON TRUE
          LEFT JOIN score s ON s.workout_id = sw.id AND s.team_id = t.id
         WHERE t.category_id = p_category_id
           AND t.status = 'CONFIRMED'
         GROUP BY t.id, t.name, t.box_name
    )
    SELECT RANK() OVER (ORDER BY
               CASE WHEN v_system = 'RANK_SUM'  THEN x.t_total END ASC,
               CASE WHEN v_system <> 'RANK_SUM' THEN x.t_total END DESC,
               x.t_firsts DESC),
           x.t_id,
           x.t_name,
           x.t_box,
           x.t_total,
           x.t_done,
           x.t_firsts
      FROM totals x
     ORDER BY 1, x.t_name;
END $$;

-- ---------------------------------------------------------------------
-- PROCEDURE: iniciar bateria
-- Marca a bateria como EM ANDAMENTO e, por padrão, conclui a bateria que
-- estava rodando no mesmo campeonato (fluxo de um único floor).
-- ---------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE sp_start_heat(p_heat_id BIGINT, p_finish_running BOOLEAN DEFAULT TRUE)
LANGUAGE plpgsql AS $$
DECLARE
    v_championship_id BIGINT;
    v_status          VARCHAR(20);
BEGIN
    SELECT cat.championship_id, h.status
      INTO v_championship_id, v_status
      FROM heat h
      JOIN workout w    ON w.id = h.workout_id
      JOIN category cat ON cat.id = w.category_id
     WHERE h.id = p_heat_id
       FOR UPDATE OF h;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Bateria % não encontrada', p_heat_id USING ERRCODE = 'no_data_found';
    END IF;
    IF v_status = 'FINISHED' THEN
        RAISE EXCEPTION 'Esta bateria já foi concluída. Reabra-a antes de iniciar de novo.';
    END IF;

    IF p_finish_running THEN
        UPDATE heat h
           SET status = 'FINISHED'
          FROM workout w
          JOIN category cat ON cat.id = w.category_id
         WHERE h.workout_id = w.id
           AND cat.championship_id = v_championship_id
           AND h.status = 'IN_PROGRESS'
           AND h.id <> p_heat_id;
    END IF;

    UPDATE heat SET status = 'IN_PROGRESS' WHERE id = p_heat_id;
END $$;

-- ---------------------------------------------------------------------
-- PROCEDURE: gerar baterias de uma prova automaticamente
-- Distribui as equipes confirmadas em baterias de p_lanes raias.
-- Ordem: pior colocada no leaderboard primeiro (líderes na última bateria),
-- como é praxe em campeonatos; antes de haver resultados, ordem de inscrição.
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
    v_idx         INT := 0;
    v_heat_no     INT := 0;
    r             RECORD;
BEGIN
    IF p_lanes IS NULL OR p_lanes < 1 THEN
        RAISE EXCEPTION 'Informe ao menos 1 raia por bateria';
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

    DELETE FROM heat WHERE workout_id = p_workout_id;

    FOR r IN
        SELECT t.id AS team_id
          FROM team t
          LEFT JOIN fn_category_leaderboard(v_category_id) lb ON lb.team_id = t.id
         WHERE t.category_id = v_category_id
           AND t.status = 'CONFIRMED'
         ORDER BY lb.overall_rank DESC NULLS FIRST, t.confirmed_at NULLS LAST, t.id
    LOOP
        IF v_idx % p_lanes = 0 THEN
            v_heat_no := v_heat_no + 1;
            INSERT INTO heat (workout_id, number, scheduled_at)
            VALUES (p_workout_id, v_heat_no,
                    p_first_start + make_interval(mins => (v_heat_no - 1) * p_interval_minutes))
            RETURNING id INTO v_heat_id;
        END IF;
        INSERT INTO heat_lane (heat_id, team_id, lane)
        VALUES (v_heat_id, r.team_id, (v_idx % p_lanes) + 1);
        v_idx := v_idx + 1;
    END LOOP;

    IF v_idx = 0 THEN
        RAISE EXCEPTION 'Não há equipes confirmadas nesta categoria para montar baterias';
    END IF;
END $$;

-- ---------------------------------------------------------------------
-- PROCEDURE: expira Pix/boletos vencidos (chamada por job agendado)
-- ---------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE sp_expire_pending_payments()
LANGUAGE plpgsql AS $$
BEGIN
    UPDATE payment
       SET status = 'EXPIRED',
           status_detail = 'Prazo de pagamento encerrado'
     WHERE status = 'PENDING'
       AND expires_at IS NOT NULL
       AND expires_at < now();
END $$;
