ALTER TABLE workout ADD COLUMN max_reps INT;
ALTER TABLE workout ADD CONSTRAINT ck_workout_max_reps
    CHECK (max_reps IS NULL OR (max_reps > 0 AND type IN ('FOR_TIME','AMRAP')));

-- Protege o teto também nos lançamentos efetuados diretamente no banco.
CREATE FUNCTION fn_score_rep_limit() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_limit INT;
BEGIN
    SELECT max_reps INTO v_limit FROM workout WHERE id=NEW.workout_id;
    IF NEW.status IN ('VALID','CAPPED') AND v_limit IS NOT NULL AND NEW.reps > v_limit THEN
        RAISE EXCEPTION 'Esta prova permite no máximo % repetições. Revise o resultado.', v_limit;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_score_rep_limit BEFORE INSERT OR UPDATE OF status,reps,workout_id ON score
    FOR EACH ROW EXECUTE FUNCTION fn_score_rep_limit();
