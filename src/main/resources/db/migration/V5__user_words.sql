-- A user's relationship with a word. One row per (user, word), created the first time anything is set.
CREATE TABLE user_word (
    id         BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    word_id    BIGINT      NOT NULL,
    seen       BOOLEAN     NOT NULL DEFAULT FALSE,
    liked      BOOLEAN     NOT NULL DEFAULT FALSE,
    saved      BOOLEAN     NOT NULL DEFAULT FALSE,
    -- "I already know this word": never show it in the feed again
    known      BOOLEAN     NOT NULL DEFAULT FALSE,
    -- When the change happened on the device; newer wins when two devices disagree
    changed_at DATETIME(6) NOT NULL,
    -- app_user.sync_revision at the time of the last change; clients pull rows above their last revision
    revision   BIGINT      NOT NULL,
    CONSTRAINT uq_user_word UNIQUE (user_id, word_id),
    CONSTRAINT fk_user_word_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_user_word_word FOREIGN KEY (word_id) REFERENCES word (id)
);
CREATE INDEX idx_user_word_revision ON user_word (user_id, revision);
CREATE INDEX idx_user_word_changed ON user_word (user_id, changed_at);

-- Per-user counter, bumped once per changed user_word row.
ALTER TABLE app_user ADD COLUMN sync_revision BIGINT NOT NULL DEFAULT 0;
