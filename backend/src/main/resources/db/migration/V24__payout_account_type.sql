-- O tipo test_user da conta é independente do live_mode financeiro do OAuth.
-- Vínculos legados ficam null: o próximo uso renova o OAuth e valida /users/me
-- sob lock antes de consultar ou criar pagamentos. Não inferir o tipo nem o
-- live_mode antigo a partir do outro campo.
ALTER TABLE payout_account ADD COLUMN test_account BOOLEAN;
