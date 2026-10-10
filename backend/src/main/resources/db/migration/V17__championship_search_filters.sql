-- Filtra antes do limite de resultados, para que eventos encerrados não desapareçam
-- quando já houver 50 eventos futuros na busca geral.
CREATE OR REPLACE FUNCTION fn_search_championships(p_term TEXT, p_state TEXT, p_filter TEXT)
RETURNS SETOF championship LANGUAGE sql STABLE AS $$
    SELECT c.* FROM championship c
    WHERE c.status IN ('PUBLISHED', 'IN_PROGRESS', 'FINISHED')
      AND (COALESCE(p_state, '') = '' OR c.state = upper(p_state))
      AND (COALESCE(p_term, '') = ''
           OR f_unaccent(lower(c.name || ' ' || c.city || ' ' || c.venue_name)) LIKE '%' || f_unaccent(lower(p_term)) || '%'
           OR similarity(f_unaccent(lower(c.name)), f_unaccent(lower(p_term))) > 0.3)
      AND (p_filter = 'ALL'
           OR (p_filter = 'IN_PROGRESS' AND c.status = 'IN_PROGRESS')
           OR (p_filter = 'OPEN' AND fn_registration_is_open(c.id))
           OR (p_filter = 'FINISHED' AND c.status = 'FINISHED'))
    ORDER BY (c.end_date < current_date),
             CASE WHEN c.end_date >= current_date THEN c.start_date END ASC,
             c.start_date DESC, c.id
    LIMIT 50
$$;
