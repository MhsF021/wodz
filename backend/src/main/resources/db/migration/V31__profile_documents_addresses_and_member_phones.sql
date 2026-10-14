-- Campos nulos preservam contas Google ainda incompletas e inscrições anteriores.
-- A API exige documento/telefone ao completar o perfil e telefones em novas inscrições.
ALTER TABLE app_user ADD COLUMN document VARCHAR(14);
ALTER TABLE app_user ADD COLUMN street VARCHAR(160);
ALTER TABLE app_user ADD COLUMN address_number VARCHAR(20);
ALTER TABLE app_user ADD COLUMN city VARCHAR(100);
ALTER TABLE app_user ADD COLUMN postal_code VARCHAR(8);
ALTER TABLE team_member ADD COLUMN phone VARCHAR(20);
