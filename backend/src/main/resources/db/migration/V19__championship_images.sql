CREATE TABLE championship_image (
    championship_id BIGINT PRIMARY KEY REFERENCES championship(id) ON DELETE CASCADE,
    image_key UUID NOT NULL UNIQUE,
    content BYTEA NOT NULL CHECK (octet_length(content) BETWEEN 1 AND 1048576)
);
