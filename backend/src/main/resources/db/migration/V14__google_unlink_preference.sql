-- Guarda somente a preferência do usuário; a identidade Google é removida ao desvincular.
ALTER TABLE app_user ADD COLUMN google_link_disabled BOOLEAN NOT NULL DEFAULT FALSE;
