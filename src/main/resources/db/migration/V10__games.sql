-- Word games (docs/games.md). Daily challenges are generated from the date, so only plays and answers are stored.

-- One person's play of one daily challenge, globally (scope_key 0) or in a group (scope_key = group id).
CREATE TABLE game_play (
    id           BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT      NOT NULL,
    -- e.g. NAME_PLACE_ANIMAL_THING, CATEGORY_SPRINT, COMPLETE_THE_WORD_EASY
    challenge    VARCHAR(32) NOT NULL,
    play_date    DATE        NOT NULL,
    -- Group plays point at their group; a separate non-null key keeps the unique constraint working
    -- (NULLs never collide in a unique index).
    group_id     BIGINT,
    scope_key    BIGINT      NOT NULL,
    started_at   DATETIME(6) NOT NULL,
    submitted_at DATETIME(6),
    -- Complete-the-word: letters revealed per word before submitting, e.g. "0,1,0,2,0,0,0,0,0,0"
    reveals      VARCHAR(64) NOT NULL DEFAULT '',
    CONSTRAINT uq_game_play UNIQUE (user_id, challenge, play_date, scope_key),
    CONSTRAINT fk_game_play_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_game_play_group FOREIGN KEY (group_id) REFERENCES friend_group (id)
);
CREATE INDEX idx_game_play_board ON game_play (challenge, play_date, scope_key);

CREATE TABLE game_answer (
    id         BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    play_id    BIGINT      NOT NULL,
    -- Round (Name-Place-Animal-Thing), word number (complete the word) or order typed (sprint)
    item       INT         NOT NULL,
    -- NAME, PLACE, ANIMAL or THING; empty for the other games
    slot       VARCHAR(16) NOT NULL,
    answer     VARCHAR(64) NOT NULL,
    -- Lower case, letters only: what answers are compared by
    normalized VARCHAR(64) NOT NULL,
    -- VALID, INVALID, or UNLISTED (a Name/Place not in the lists: other group players can reject it)
    verdict    VARCHAR(16) NOT NULL,
    -- Points that don't depend on other players; Name-Place-Animal-Thing points are worked out when read
    points     INT         NOT NULL,
    elapsed_ms INT,
    CONSTRAINT fk_game_answer_play FOREIGN KEY (play_id) REFERENCES game_play (id)
);
CREATE INDEX idx_game_answer_play ON game_answer (play_id);

-- Group players' votes on each other's UNLISTED answers.
CREATE TABLE game_vote (
    answer_id BIGINT  NOT NULL,
    voter_id  BIGINT  NOT NULL,
    accepted  BOOLEAN NOT NULL,
    PRIMARY KEY (answer_id, voter_id),
    CONSTRAINT fk_game_vote_answer FOREIGN KEY (answer_id) REFERENCES game_answer (id),
    CONSTRAINT fk_game_vote_voter FOREIGN KEY (voter_id) REFERENCES app_user (id)
);

-- First names, from the US SSA baby-name data (public domain) and any extra list.
CREATE TABLE game_name (
    normalized VARCHAR(64) NOT NULL PRIMARY KEY,
    display    VARCHAR(64) NOT NULL
);

-- Countries and cities from GeoNames (CC BY 4.0).
CREATE TABLE game_place (
    normalized VARCHAR(96) NOT NULL PRIMARY KEY,
    display    VARCHAR(96) NOT NULL,
    -- COUNTRY or CITY
    place_type VARCHAR(16) NOT NULL,
    country    VARCHAR(2)
);
