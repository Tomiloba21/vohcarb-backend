-- Position of the word in the frequency list (1 = most common); NULL when the list doesn't contain it.
-- word.difficulty is derived from it by FrequencyImporter.
ALTER TABLE word ADD COLUMN frequency_rank INT;

CREATE INDEX idx_word_difficulty ON word (difficulty, id);
