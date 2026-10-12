ALTER TABLE category ADD COLUMN movement_standards JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE category ADD CONSTRAINT category_movement_standards_array
    CHECK (jsonb_typeof(movement_standards) = 'array' AND jsonb_array_length(movement_standards) <= 30);
