-- Word catalog. Rows are written by the importer (see app.vohcarb.catalog.importer); the API only reads them.

CREATE TABLE category (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    slug        VARCHAR(64)  NOT NULL,
    name        VARCHAR(128) NOT NULL,
    description VARCHAR(255),
    CONSTRAINT uq_category_slug UNIQUE (slug)
);

CREATE TABLE word (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    lemma      VARCHAR(128) NOT NULL,
    slug       VARCHAR(160) NOT NULL,
    -- 1 (common) .. 5 (rare); filled from frequency data in a later import
    difficulty SMALLINT,
    source     VARCHAR(32)  NOT NULL,
    CONSTRAINT uq_word_slug UNIQUE (slug)
);
CREATE INDEX idx_word_lemma ON word (lemma);

CREATE TABLE sense (
    id             BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    word_id        BIGINT        NOT NULL,
    sort_order     INT           NOT NULL,
    part_of_speech VARCHAR(16)   NOT NULL,
    definition     VARCHAR(1000) NOT NULL,
    category_id    BIGINT,
    source_ref     VARCHAR(64),
    CONSTRAINT fk_sense_word FOREIGN KEY (word_id) REFERENCES word (id),
    CONSTRAINT fk_sense_category FOREIGN KEY (category_id) REFERENCES category (id)
);
CREATE INDEX idx_sense_word ON sense (word_id, sort_order);

CREATE TABLE example (
    id         BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    sense_id   BIGINT        NOT NULL,
    sort_order INT           NOT NULL,
    sentence   VARCHAR(1000) NOT NULL,
    source     VARCHAR(255),
    CONSTRAINT fk_example_sense FOREIGN KEY (sense_id) REFERENCES sense (id)
);
CREATE INDEX idx_example_sense ON example (sense_id, sort_order);

CREATE TABLE pronunciation (
    id      BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    word_id BIGINT       NOT NULL,
    ipa     VARCHAR(128) NOT NULL,
    variety VARCHAR(16),
    CONSTRAINT fk_pronunciation_word FOREIGN KEY (word_id) REFERENCES word (id)
);
CREATE INDEX idx_pronunciation_word ON pronunciation (word_id);

-- Denormalised from sense.category_id so "words in category X" is a single indexed lookup.
CREATE TABLE word_category (
    word_id     BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    PRIMARY KEY (word_id, category_id),
    CONSTRAINT fk_word_category_word FOREIGN KEY (word_id) REFERENCES word (id),
    CONSTRAINT fk_word_category_category FOREIGN KEY (category_id) REFERENCES category (id)
);
CREATE INDEX idx_word_category_category ON word_category (category_id, word_id);

INSERT INTO category (slug, name, description) VALUES
    ('actions',       'Actions & activities',     'Things people do'),
    ('animals',       'Animals',                  'Creatures great and small'),
    ('objects',       'Objects & inventions',     'Things people make and use'),
    ('qualities',     'Qualities & traits',       'Words that describe what something is like'),
    ('body',          'Body & health',            'The body and what it does'),
    ('mind',          'Mind & ideas',             'Thinking, knowing and believing'),
    ('communication', 'Language & communication', 'Speaking, writing and expressing'),
    ('events',        'Events & happenings',      'Things that happen'),
    ('feelings',      'Feelings & emotions',      'How people feel'),
    ('food',          'Food & drink',             'Eating, drinking and cooking'),
    ('groups',        'Groups & organisations',   'Collections of people and things'),
    ('places',        'Places',                   'Locations and spaces'),
    ('motives',       'Motives',                  'Reasons people act'),
    ('nature',        'Nature',                   'Natural objects and the world'),
    ('people',        'People',                   'Roles, types and kinds of people'),
    ('phenomena',     'Natural phenomena',        'Weather, forces and effects'),
    ('plants',        'Plants',                   'Trees, flowers and growing things'),
    ('possessions',   'Money & possessions',      'Owning, buying and wealth'),
    ('processes',     'Processes',                'How things develop and work'),
    ('quantities',    'Quantities & measures',    'Amounts, units and numbers'),
    ('relations',     'Relations',                'How things connect to each other'),
    ('shapes',        'Shapes',                   'Forms and figures'),
    ('states',        'States & conditions',      'Situations and conditions'),
    ('substances',    'Substances & materials',   'What things are made of'),
    ('time',          'Time',                     'Moments, periods and timing'),
    ('movement',      'Movement',                 'Moving and travelling'),
    ('competition',   'Sports & competition',     'Games, contests and fighting'),
    ('society',       'Society',                  'Social life and institutions'),
    ('creation',      'Creating & making',        'Building, art and invention'),
    ('perception',    'Senses & perception',      'Seeing, hearing, touching'),
    ('contact',       'Touch & contact',          'Hitting, holding and joining'),
    ('change',        'Change',                   'Becoming different');
