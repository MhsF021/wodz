-- Senhas alteradas revogam os JWTs já emitidos sem armazenar tokens no banco.
ALTER TABLE app_user ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE app_user ADD CONSTRAINT ck_app_user_token_version CHECK (token_version >= 0);
