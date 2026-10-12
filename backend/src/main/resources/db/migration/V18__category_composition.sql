-- Categorias existentes ficam sem composição até a organização defini-la.
-- Não deduzimos o sexo dos atletas pelo nome da categoria nem alteramos inscrições.
ALTER TABLE category ADD COLUMN gender_policy VARCHAR(10);
ALTER TABLE category ADD COLUMN allowed_male_counts INTEGER[] NOT NULL DEFAULT '{}';
ALTER TABLE category ADD CONSTRAINT ck_category_gender_policy CHECK (gender_policy IN ('FEMALE', 'MALE', 'MIXED'));

CREATE FUNCTION fn_category_composition_rule() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE n INT;
BEGIN
    IF NEW.gender_policy IS DISTINCT FROM 'MIXED' THEN
        IF cardinality(NEW.allowed_male_counts) <> 0 THEN RAISE EXCEPTION 'Composições adicionais exigem categoria mista'; END IF;
    ELSE
        IF NEW.team_size < 2 OR cardinality(NEW.allowed_male_counts) = 0 OR cardinality(NEW.allowed_male_counts) > 9 THEN
            RAISE EXCEPTION 'Escolha ao menos uma composição mista válida';
        END IF;
        FOREACH n IN ARRAY NEW.allowed_male_counts LOOP
            IF n IS NULL OR n < 1 OR n >= NEW.team_size THEN RAISE EXCEPTION 'A composição mista deve conter homens e mulheres'; END IF;
        END LOOP;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.gender_policy IS NOT NULL
       AND (OLD.gender_policy IS DISTINCT FROM NEW.gender_policy OR OLD.allowed_male_counts IS DISTINCT FROM NEW.allowed_male_counts OR OLD.team_size <> NEW.team_size)
       AND EXISTS (SELECT 1 FROM team WHERE category_id = NEW.id) THEN
        RAISE EXCEPTION 'Não é possível mudar a composição: a categoria já tem inscrições';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_category_composition_rule BEFORE INSERT OR UPDATE ON category FOR EACH ROW EXECUTE FUNCTION fn_category_composition_rule();

CREATE FUNCTION fn_check_team_composition(p_team BIGINT) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE c category; v_status TEXT; v_count INT; v_men INT;
BEGIN
    SELECT cat.* INTO c FROM category cat JOIN team t ON t.category_id = cat.id WHERE t.id = p_team;
    IF NOT FOUND OR c.gender_policy IS NULL THEN RETURN; END IF;
    SELECT status INTO v_status FROM team WHERE id = p_team;
    IF v_status = 'CANCELLED' THEN RETURN; END IF;
    SELECT count(*), count(*) FILTER (WHERE gender = 'M') INTO v_count, v_men FROM team_member WHERE team_id = p_team;
    IF v_count <> c.team_size OR (c.gender_policy = 'MALE' AND v_men <> v_count)
       OR (c.gender_policy = 'FEMALE' AND v_men <> 0)
       OR (c.gender_policy = 'MIXED' AND NOT v_men = ANY(c.allowed_male_counts)) THEN
        RAISE EXCEPTION 'A composição da equipe não atende às regras desta categoria';
    END IF;
END $$;

CREATE FUNCTION fn_team_composition_deferred() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'team_member' THEN
        IF TG_OP <> 'INSERT' THEN PERFORM fn_check_team_composition(OLD.team_id); END IF;
        IF TG_OP <> 'DELETE' THEN PERFORM fn_check_team_composition(NEW.team_id); END IF;
    ELSE
        PERFORM fn_check_team_composition(NEW.id);
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER trg_member_composition AFTER INSERT OR UPDATE OR DELETE ON team_member
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fn_team_composition_deferred();
CREATE CONSTRAINT TRIGGER trg_team_composition AFTER INSERT OR UPDATE ON team
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fn_team_composition_deferred();

CREATE FUNCTION fn_existing_category_composition() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE t RECORD;
BEGIN
    IF OLD.gender_policy IS NULL AND NEW.gender_policy IS NOT NULL THEN
        FOR t IN SELECT id FROM team WHERE category_id = NEW.id LOOP PERFORM fn_check_team_composition(t.id); END LOOP;
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER trg_existing_category_composition AFTER UPDATE ON category
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fn_existing_category_composition();
