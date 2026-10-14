ALTER TABLE team_member ADD COLUMN user_id BIGINT REFERENCES app_user(id);
CREATE INDEX ix_team_member_user ON team_member(user_id) WHERE user_id IS NOT NULL;
CREATE INDEX ix_team_member_unlinked_email ON team_member(lower(email)) WHERE user_id IS NULL AND NOT is_captain;
