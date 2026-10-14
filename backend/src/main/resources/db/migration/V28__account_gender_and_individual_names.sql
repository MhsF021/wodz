-- Contas existentes completam o sexo em Minha conta; não inferimos dados pessoais.
ALTER TABLE app_user ADD COLUMN gender VARCHAR(1) CHECK (gender IN ('M', 'F'));

-- Uma inscrição individual usa o nome completo do atleta, inclusive nomes longos.
-- PostgreSQL exige recriar as views dependentes ao ampliar o campo.
-- Reutiliza as definições atuais, preservando juízes, horários e dados financeiros.
DO $$
DECLARE
    v_schedule TEXT := pg_get_viewdef('vw_heat_schedule'::regclass, true);
    v_scores TEXT := pg_get_viewdef('vw_score_board'::regclass, true);
    v_teams TEXT := pg_get_viewdef('vw_team_overview'::regclass, true);
BEGIN
    DROP VIEW vw_heat_schedule, vw_score_board, vw_team_overview;
    ALTER TABLE team ALTER COLUMN name TYPE VARCHAR(120);
    EXECUTE 'CREATE VIEW vw_heat_schedule AS ' || v_schedule;
    EXECUTE 'CREATE VIEW vw_score_board AS ' || v_scores;
    EXECUTE 'CREATE VIEW vw_team_overview AS ' || v_teams;
END $$;
UPDATE team t
   SET name = upper(trim(m.full_name))
  FROM category c, team_member m
 WHERE t.category_id = c.id
   AND c.team_size = 1
   AND m.team_id = t.id
   AND NOT EXISTS (SELECT 1 FROM team_member other WHERE other.team_id = t.id AND other.id <> m.id);
