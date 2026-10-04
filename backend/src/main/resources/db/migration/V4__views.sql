-- =====================================================================
-- WODZ | V4 - Views de leitura (consumidas via JdbcTemplate)
-- =====================================================================

-- Grade de baterias com as raias em JSON
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
                       'boxName', t.box_name) ORDER BY hl.lane)
              FROM heat_lane hl
              JOIN team t ON t.id = hl.team_id
             WHERE hl.heat_id = h.id),
           '[]'::json)     AS lanes
  FROM heat h
  JOIN workout w       ON w.id = h.workout_id
  JOIN category cat    ON cat.id = w.category_id
  JOIN championship ch ON ch.id = cat.championship_id;

-- Resultados por prova já formatados para exibição
CREATE OR REPLACE VIEW vw_score_board AS
SELECT s.id          AS score_id,
       s.workout_id,
       w.number      AS workout_number,
       w.name        AS workout_name,
       w.type        AS workout_type,
       w.category_id,
       s.team_id,
       t.name        AS team_name,
       s.status,
       s.time_ms,
       s.reps,
       s.load_kg,
       s.tiebreak_ms,
       s.placement,
       s.points,
       s.notes,
       fn_format_result(w.type, s.status, s.time_ms, s.reps, s.load_kg, w.reps_per_round) AS result_display,
       s.updated_at
  FROM score s
  JOIN workout w ON w.id = s.workout_id
  JOIN team t    ON t.id = s.team_id;

-- Visão do organizador sobre as equipes inscritas
CREATE OR REPLACE VIEW vw_team_overview AS
SELECT t.id                AS team_id,
       t.name              AS team_name,
       t.box_name,
       t.city,
       t.status,
       t.registration_code,
       t.created_at,
       t.confirmed_at,
       cat.id              AS category_id,
       cat.name            AS category_name,
       cat.price,
       cat.championship_id,
       u.name              AS captain_name,
       u.email             AS captain_email,
       u.phone             AS captain_phone,
       (SELECT count(*) FROM team_member m WHERE m.team_id = t.id) AS member_count,
       (SELECT string_agg(m.full_name, ', ' ORDER BY m.is_captain DESC, m.full_name)
          FROM team_member m WHERE m.team_id = t.id)               AS member_names,
       lp.status           AS last_payment_status,
       lp.method           AS last_payment_method,
       lp.paid_at
  FROM team t
  JOIN category cat ON cat.id = t.category_id
  JOIN app_user u   ON u.id = t.captain_id
  LEFT JOIN LATERAL (
        SELECT p.status, p.method, p.paid_at
          FROM payment p
         WHERE p.team_id = t.id
         ORDER BY (p.status = 'APPROVED') DESC, p.created_at DESC
         LIMIT 1) lp ON TRUE;

-- Números do painel do organizador
CREATE OR REPLACE VIEW vw_championship_summary AS
SELECT c.id AS championship_id,
       count(DISTINCT cat.id)                                   AS categories,
       count(t.id) FILTER (WHERE t.status = 'CONFIRMED')        AS teams_confirmed,
       count(t.id) FILTER (WHERE t.status = 'PENDING_PAYMENT')  AS teams_pending,
       COALESCE((SELECT sum(p.amount)
                   FROM payment p
                   JOIN team t2     ON t2.id = p.team_id
                   JOIN category c2 ON c2.id = t2.category_id
                  WHERE c2.championship_id = c.id
                    AND p.status = 'APPROVED'), 0)              AS revenue
  FROM championship c
  LEFT JOIN category cat ON cat.championship_id = c.id
  LEFT JOIN team t       ON t.category_id = cat.id
 GROUP BY c.id;
