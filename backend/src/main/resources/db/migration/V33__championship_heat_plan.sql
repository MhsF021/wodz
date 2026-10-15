-- A configuração é explícita: eventos legados precisam informar a quantidade.
ALTER TABLE championship ADD COLUMN heat_lanes INT CHECK (heat_lanes BETWEEN 1 AND 8);
ALTER TABLE championship ADD COLUMN heat_first_start TIMESTAMPTZ;
ALTER TABLE championship ADD COLUMN heat_interval_minutes INT CHECK (heat_interval_minutes BETWEEN 0 AND 240);
ALTER TABLE championship ADD COLUMN heats_require_review BOOLEAN NOT NULL DEFAULT FALSE;

CREATE FUNCTION fn_championship_heats_complete(p_id BIGINT)
RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT EXISTS (SELECT 1 FROM heat h JOIN workout w ON w.id=h.workout_id
        JOIN category c ON c.id=w.category_id WHERE c.championship_id=p_id)
    AND NOT EXISTS (
        SELECT 1 FROM team t JOIN category c ON c.id=t.category_id
        LEFT JOIN workout w ON w.category_id=c.id
        WHERE c.championship_id=p_id AND t.status='CONFIRMED'
        AND (w.id IS NULL OR NOT EXISTS (
            SELECT 1 FROM heat h JOIN heat_lane l ON l.heat_id=h.id
            WHERE h.workout_id=w.id AND l.team_id=t.id)))
    AND NOT EXISTS (
        SELECT 1 FROM heat h JOIN workout w ON w.id=h.workout_id
        JOIN category c ON c.id=w.category_id JOIN heat_lane l ON l.heat_id=h.id
        JOIN team t ON t.id=l.team_id
        WHERE c.championship_id=p_id AND (t.status<>'CONFIRMED' OR NOT EXISTS (
            SELECT 1 FROM heat base JOIN heat_lane bl ON bl.heat_id=base.id
            WHERE base.workout_id=(SELECT id FROM workout WHERE category_id=c.id ORDER BY number,id LIMIT 1)
            AND base.number=h.number AND bl.team_id=l.team_id AND bl.lane=l.lane)))
$$;

CREATE PROCEDURE sp_generate_championship_heats(p_id BIGINT, p_lanes INT,
    p_first TIMESTAMPTZ, p_interval INT)
LANGUAGE plpgsql AS $$
DECLARE r RECORD; b RECORD; v_base BIGINT; v_id BIGINT; v_start TIMESTAMPTZ := p_first;
BEGIN
    PERFORM id FROM championship WHERE id=p_id FOR UPDATE;
    IF p_lanes IS NULL OR p_lanes NOT BETWEEN 1 AND 8 THEN
        RAISE EXCEPTION 'Informe a quantidade de equipes por bateria (1 a 8)';
    END IF;
    IF p_first IS NULL OR p_interval IS NULL OR p_interval NOT BETWEEN 0 AND 240 THEN
        RAISE EXCEPTION 'Informe o horário inicial e o intervalo após o término';
    END IF;
    IF EXISTS (SELECT 1 FROM heat h JOIN workout w ON w.id=h.workout_id
        JOIN category c ON c.id=w.category_id WHERE c.championship_id=p_id AND h.status<>'UPCOMING') THEN
        RAISE EXCEPTION 'As baterias não podem ser geradas novamente depois do início';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM team t JOIN category c ON c.id=t.category_id
        WHERE c.championship_id=p_id AND t.status='CONFIRMED') THEN
        RAISE EXCEPTION 'Não há equipes confirmadas para montar baterias';
    END IF;
    IF EXISTS (SELECT 1 FROM category c WHERE c.championship_id=p_id
        AND EXISTS (SELECT 1 FROM team WHERE category_id=c.id AND status='CONFIRMED')
        AND NOT EXISTS (SELECT 1 FROM workout WHERE category_id=c.id)) THEN
        RAISE EXCEPTION 'Cadastre as provas de todas as categorias com equipes confirmadas';
    END IF;
    IF EXISTS (SELECT 1 FROM workout w JOIN category c ON c.id=w.category_id
        WHERE c.championship_id=p_id AND (w.time_cap_seconds IS NULL OR w.time_cap_seconds<=0)) THEN
        RAISE EXCEPTION 'Informe a duração / time cap de todas as provas antes de montar as baterias';
    END IF;
    DELETE FROM heat WHERE workout_id IN (SELECT w.id FROM workout w
        JOIN category c ON c.id=w.category_id WHERE c.championship_id=p_id);
    FOR r IN SELECT w.*, c.display_order FROM workout w JOIN category c ON c.id=w.category_id
        WHERE c.championship_id=p_id AND EXISTS (
            SELECT 1 FROM team WHERE category_id=c.id AND status='CONFIRMED')
        ORDER BY w.number,c.display_order,c.id,w.id
    LOOP
        SELECT id INTO v_base FROM workout WHERE category_id=r.category_id ORDER BY number,id LIMIT 1;
        IF r.id=v_base THEN
            CALL sp_generate_heats(r.id,p_lanes,v_start,p_interval);
            SELECT max(scheduled_at)+make_interval(secs=>r.time_cap_seconds,mins=>p_interval)
                INTO v_start FROM heat WHERE workout_id=r.id;
        ELSE
            FOR b IN SELECT * FROM heat WHERE workout_id=v_base ORDER BY number LOOP
                INSERT INTO heat(workout_id,number,scheduled_at,duration_seconds,interval_minutes)
                VALUES (r.id,b.number,v_start,r.time_cap_seconds,p_interval) RETURNING id INTO v_id;
                INSERT INTO heat_lane(heat_id,team_id,lane)
                    SELECT v_id,team_id,lane FROM heat_lane WHERE heat_id=b.id ORDER BY lane;
                v_start := v_start+make_interval(secs=>r.time_cap_seconds,mins=>p_interval);
            END LOOP;
        END IF;
    END LOOP;
END $$;

-- A mesma operação atende fechamento manual, fechamento por data e início.
CREATE FUNCTION fn_prepare_championship_heats(p_id BIGINT, p_fallback BOOLEAN)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
DECLARE c championship%ROWTYPE;
BEGIN
    SELECT * INTO c FROM championship WHERE id=p_id FOR UPDATE;
    IF c.status IN ('FINISHED','CANCELLED') THEN RETURN FALSE; END IF;
    IF c.heat_lanes IS NULL OR c.heat_first_start IS NULL OR c.heat_interval_minutes IS NULL THEN
        RAISE EXCEPTION 'Informe a quantidade de equipes por bateria, o horário inicial e o intervalo na aba Baterias';
    END IF;
    IF fn_championship_heats_complete(p_id) THEN RETURN FALSE; END IF;
    IF NOT EXISTS (SELECT 1 FROM team t JOIN category cat ON cat.id=t.category_id
        WHERE cat.championship_id=p_id AND t.status='CONFIRMED') THEN RETURN FALSE; END IF;
    CALL sp_generate_championship_heats(p_id,c.heat_lanes,c.heat_first_start,c.heat_interval_minutes);
    IF p_fallback THEN UPDATE championship SET heats_require_review=TRUE WHERE id=p_id; END IF;
    RETURN TRUE;
END $$;
