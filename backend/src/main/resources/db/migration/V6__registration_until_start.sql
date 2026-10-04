-- =====================================================================
-- WODZ | V6 - Inscrições só antes do início do campeonato
-- O campeonato começa à 0h do dia de início, no horário de Brasília.
-- Antes, um campeonato que continuasse PUBLISHED no dia da prova seguia
-- aceitando inscrições, e o fim do período de inscrição podia passar do
-- início. Espelhado em Championship.isAcceptingRegistrations.
-- =====================================================================

CREATE OR REPLACE FUNCTION fn_registration_is_open(p_championship_id BIGINT)
RETURNS BOOLEAN
LANGUAGE sql STABLE AS $$
    SELECT c.registration_open
       AND c.status = 'PUBLISHED'
       AND (now() AT TIME ZONE 'America/Sao_Paulo')::date < c.start_date
       AND (c.registration_opens_at IS NULL OR now() >= c.registration_opens_at)
       AND (c.registration_closes_at IS NULL OR now() <= c.registration_closes_at)
      FROM championship c
     WHERE c.id = p_championship_id
$$;
