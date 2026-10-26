-- Spaced-repetition state per (user, word). Created on the word's first review.
CREATE TABLE review_card (
    id               BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id          BIGINT      NOT NULL,
    word_id          BIGINT      NOT NULL,
    repetitions      INT         NOT NULL,
    -- SM-2 ease factor × 1000 (2500 = 2.5), kept as an integer so it compares exactly
    ease_permille    INT         NOT NULL,
    interval_days    INT         NOT NULL,
    lapses           INT         NOT NULL,
    due_at           DATETIME(6) NOT NULL,
    last_reviewed_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_review_card UNIQUE (user_id, word_id),
    CONSTRAINT fk_review_card_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_review_card_word FOREIGN KEY (word_id) REFERENCES word (id)
);
CREATE INDEX idx_review_card_due ON review_card (user_id, due_at);

-- Every answer, for stats and streaks. reviewed_at is the device time; the unique key makes
-- re-sent offline reviews harmless.
CREATE TABLE review_log (
    id            BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT      NOT NULL,
    word_id       BIGINT      NOT NULL,
    grade         VARCHAR(8)  NOT NULL,
    reviewed_at   DATETIME(6) NOT NULL,
    interval_days INT         NOT NULL,
    CONSTRAINT uq_review_log UNIQUE (user_id, word_id, reviewed_at),
    CONSTRAINT fk_review_log_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_review_log_word FOREIGN KEY (word_id) REFERENCES word (id)
);
CREATE INDEX idx_review_log_user_time ON review_log (user_id, reviewed_at);
