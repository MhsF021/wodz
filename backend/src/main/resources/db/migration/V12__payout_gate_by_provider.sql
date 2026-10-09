-- A exigência de conta ativa passa ao serviço, onde o provedor de pagamento é conhecido.
-- O modo sandbox não tem OAuth e precisa continuar permitindo campeonatos de demonstração.
DROP TRIGGER trg_championship_requires_payout ON championship;
DROP FUNCTION fn_championship_requires_payout();
