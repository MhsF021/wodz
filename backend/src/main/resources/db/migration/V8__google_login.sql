-- =====================================================================
-- WODZ | V8 - Login com Google
-- google_sub é o identificador estável da pessoa no Google (claim "sub" do
-- ID token). Quem só entra com o Google não tem senha.
-- =====================================================================

ALTER TABLE app_user ADD COLUMN google_sub VARCHAR(64);
CREATE UNIQUE INDEX ux_app_user_google_sub ON app_user (google_sub) WHERE google_sub IS NOT NULL;

ALTER TABLE app_user ALTER COLUMN password_hash DROP NOT NULL;
