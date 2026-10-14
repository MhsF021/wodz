-- Novas inscrições cotam preço por atleta x quantidade de integrantes.
-- Preserva o total das inscrições anteriores à mudança, inclusive checkouts já emitidos.
ALTER TABLE team ADD COLUMN registration_amount NUMERIC(12,2);
ALTER TABLE team ADD CONSTRAINT ck_team_registration_amount
    CHECK (registration_amount IS NULL OR registration_amount >= 0);

UPDATE team t
   SET registration_amount = COALESCE(
       (SELECT c.amount FROM checkout_session c WHERE c.team_id = t.id ORDER BY c.id DESC LIMIT 1),
       (SELECT p.amount FROM payment p WHERE p.team_id = t.id ORDER BY (p.status = 'APPROVED') DESC, p.id DESC LIMIT 1),
       (SELECT c.price FROM category c WHERE c.id = t.category_id));

DROP VIEW vw_team_overview;
CREATE VIEW vw_team_overview AS
SELECT t.id AS team_id, t.name AS team_name, t.box_name, t.city, t.status,
       t.registration_code, t.created_at, t.confirmed_at,
       cat.id AS category_id, cat.name AS category_name,
       COALESCE(t.registration_amount, cat.price) AS price, cat.championship_id,
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
