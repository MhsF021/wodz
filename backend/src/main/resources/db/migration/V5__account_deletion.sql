-- =====================================================================
-- WODZ | V5 - Exclusão de conta
-- A conta excluída não é apagada: os dados pessoais são anonimizados e a
-- linha continua existindo, porque campeonatos (owner_id), equipes
-- (captain_id) e resultados (created_by) apontam para ela e o histórico
-- dos leaderboards precisa ficar intacto.
-- =====================================================================

ALTER TABLE app_user ADD COLUMN deleted_at TIMESTAMPTZ;
