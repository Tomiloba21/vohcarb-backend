-- Finished live matches (docs/games.md, phase G3). Rooms themselves live in memory; only results are kept.
CREATE TABLE live_match (
    id          BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    challenge   VARCHAR(32) NOT NULL,
    -- FRIENDS, PUBLIC or QUICK
    room_kind   VARCHAR(16) NOT NULL,
    group_id    BIGINT,
    finished_at DATETIME(6) NOT NULL
);

CREATE TABLE live_match_player (
    match_id BIGINT NOT NULL,
    user_id  BIGINT NOT NULL,
    score    INT    NOT NULL,
    -- 1 for the winner; equal scores share a place
    place    INT    NOT NULL,
    PRIMARY KEY (match_id, user_id),
    CONSTRAINT fk_live_match_player_match FOREIGN KEY (match_id) REFERENCES live_match (id),
    CONSTRAINT fk_live_match_player_user FOREIGN KEY (user_id) REFERENCES app_user (id)
);
CREATE INDEX idx_live_match_player_user ON live_match_player (user_id);
