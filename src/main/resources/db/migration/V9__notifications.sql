-- Phones that can receive push notifications (an FCM registration token each).
CREATE TABLE device_token (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    token        VARCHAR(512) NOT NULL,
    -- ANDROID or IOS
    platform     VARCHAR(16)  NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    last_seen_at DATETIME(6)  NOT NULL,
    CONSTRAINT uq_device_token UNIQUE (token),
    CONSTRAINT fk_device_token_user FOREIGN KEY (user_id) REFERENCES app_user (id)
);
CREATE INDEX idx_device_token_user ON device_token (user_id);

-- One row per reminder sent, so a reminder goes out once even if the job runs late or twice.
CREATE TABLE reminder_log (
    user_id    BIGINT      NOT NULL,
    -- The user's local date and reminder time it was sent for
    local_date DATE        NOT NULL,
    remind_at  TIME        NOT NULL,
    sent_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, local_date, remind_at),
    CONSTRAINT fk_reminder_log_user FOREIGN KEY (user_id) REFERENCES app_user (id)
);
