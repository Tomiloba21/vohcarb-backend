-- People using the app. Named app_user because "user" is reserved in several databases.
CREATE TABLE app_user (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    email        VARCHAR(255),
    display_name VARCHAR(128) NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    CONSTRAINT uq_app_user_email UNIQUE (email)
);

-- How a user signs in: provider is 'dev' now, 'google' later; subject is the provider's id for them.
CREATE TABLE user_identity (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    provider   VARCHAR(32)  NOT NULL,
    subject    VARCHAR(255) NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    CONSTRAINT uq_user_identity UNIQUE (provider, subject),
    CONSTRAINT fk_user_identity_user FOREIGN KEY (user_id) REFERENCES app_user (id)
);
CREATE INDEX idx_user_identity_user ON user_identity (user_id);

CREATE TABLE user_preferences (
    user_id        BIGINT      NOT NULL PRIMARY KEY,
    min_difficulty SMALLINT    NOT NULL,
    max_difficulty SMALLINT    NOT NULL,
    words_per_day  SMALLINT    NOT NULL,
    timezone       VARCHAR(64) NOT NULL,
    updated_at     DATETIME(6) NOT NULL,
    CONSTRAINT fk_user_preferences_user FOREIGN KEY (user_id) REFERENCES app_user (id)
);

-- Categories the user wants in their feed; none means all categories.
CREATE TABLE user_preferred_category (
    user_id     BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, category_id),
    CONSTRAINT fk_user_preferred_category_user FOREIGN KEY (user_id) REFERENCES user_preferences (user_id),
    CONSTRAINT fk_user_preferred_category_category FOREIGN KEY (category_id) REFERENCES category (id)
);

-- Local times of day (in the user's timezone) for reminder notifications.
CREATE TABLE user_reminder_time (
    user_id   BIGINT NOT NULL,
    remind_at TIME   NOT NULL,
    PRIMARY KEY (user_id, remind_at),
    CONSTRAINT fk_user_reminder_time_user FOREIGN KEY (user_id) REFERENCES user_preferences (user_id)
);
