-- Não assumir que tokens legados pertencem à aplicação atualmente configurada.
-- O próximo uso renova o grant com as credenciais da aplicação e valida a conta
-- antes de persistir sua origem. Vínculos históricos mantêm a origem do OAuth.
ALTER TABLE payout_account ADD COLUMN application_id VARCHAR(40);
COMMENT ON COLUMN payout_account.application_id IS 'Aplicação que emitiu o grant OAuth; null até revalidação de vínculos legados.';
