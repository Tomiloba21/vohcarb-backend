-- Baseline migration. Catalog tables (word, sense, example, category...) arrive in Phase 1.
CREATE TABLE app_meta (
    meta_key   VARCHAR(64)  NOT NULL PRIMARY KEY,
    meta_value VARCHAR(255) NOT NULL
);

INSERT INTO app_meta (meta_key, meta_value) VALUES ('schema_owner', 'vohcarb');
