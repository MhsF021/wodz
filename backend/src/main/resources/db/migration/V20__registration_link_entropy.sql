-- Preserva links existentes e amplia a aleatoriedade dos próximos links públicos.
-- A view depende do tipo da coluna; sua recriação ocorre na mesma transação Flyway.
DROP VIEW vw_team_overview;
ALTER TABLE team ALTER COLUMN registration_code TYPE VARCHAR(32);

CREATE VIEW vw_team_overview AS
SELECT t.id AS team_id, t.name AS team_name, t.box_name, t.city, t.status,
       t.registration_code, t.created_at, t.confirmed_at,
       cat.id AS category_id, cat.name AS category_name, cat.price, cat.championship_id,
       u.name AS captain_name, u.email AS captain_email, u.phone AS captain_phone,
       (SELECT count(*) FROM team_member m WHERE m.team_id = t.id) AS member_count,
       (SELECT string_agg(m.full_name, ', ' ORDER BY m.is_captain DESC, m.full_name)
          FROM team_member m WHERE m.team_id = t.id) AS member_names,
       lp.status AS last_payment_status, lp.method AS last_payment_method, lp.paid_at
  FROM team t
  JOIN category cat ON cat.id = t.category_id
  JOIN app_user u ON u.id = t.captain_id
  LEFT JOIN LATERAL (
      SELECT p.status, p.method, p.paid_at FROM payment p WHERE p.team_id = t.id
       ORDER BY (p.status = 'APPROVED') DESC, p.created_at DESC LIMIT 1
  ) lp ON TRUE;

CREATE OR REPLACE FUNCTION fn_team_before_insert()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    v_championship_id BIGINT;
    v_slots INT;
BEGIN
    SELECT championship_id INTO v_championship_id
      FROM category WHERE id = NEW.category_id FOR UPDATE;

    IF NOT COALESCE(fn_registration_is_open(v_championship_id), FALSE) THEN
        RAISE EXCEPTION 'As inscrições deste campeonato não estão abertas';
    END IF;
    v_slots := fn_category_slots_left(NEW.category_id);
    IF v_slots IS NOT NULL AND v_slots <= 0 THEN
        RAISE EXCEPTION 'Esta categoria não tem mais vagas';
    END IF;
    IF NEW.registration_code IS NULL THEN
        -- UUID v4 aleatório do PostgreSQL, sem fallback com random()/timestamp.
        NEW.registration_code := upper(replace(gen_random_uuid()::TEXT, '-', ''));
    END IF;
    RETURN NEW;
END $$;
