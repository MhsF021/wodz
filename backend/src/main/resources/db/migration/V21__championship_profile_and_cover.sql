ALTER TABLE championship ADD COLUMN profile_url VARCHAR(500);
ALTER TABLE championship_image ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'COVER';
ALTER TABLE championship_image DROP CONSTRAINT championship_image_pkey;
ALTER TABLE championship_image ADD PRIMARY KEY (championship_id, kind);
ALTER TABLE championship_image ADD CONSTRAINT championship_image_kind CHECK (kind IN ('COVER', 'PROFILE'));

-- Preserva a foto de perfil dos eventos existentes com uma chave independente.
UPDATE championship SET profile_url = banner_url;
INSERT INTO championship_image (championship_id, kind, image_key, content)
SELECT championship_id, 'PROFILE', gen_random_uuid(), content FROM championship_image WHERE kind = 'COVER';
UPDATE championship c SET profile_url = '/api/public/championship-images/' || i.image_key
FROM championship_image i WHERE i.championship_id = c.id AND i.kind = 'PROFILE';
