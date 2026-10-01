-- EnglishKraft — dictionary.db
--
-- Read-only corpus shipped in the APK. Never written to at runtime.
-- Mutable user state (wordbook, SRS cards, placement results) lives in a
-- separate progress.db so that a corpus update never costs the user their data.
--
-- Design rules, non-negotiable:
--   1. Nothing here is generated. Every gloss and example is text that already
--      existed in the source extract, written by a human.
--   2. Register/quality tags are STORED, not applied at build time. An A1 learner
--      and a C2 learner read the same rows and filter differently at query time.
--      Dropping data here would destroy capability the upper levels need.
--   3. WITHOUT ROWID wherever the table is a pure key -> value map. On this data
--      volume that is the single largest size lever available.

PRAGMA page_size = 4096;

-- ---------------------------------------------------------------------------
-- entry: one row per (headword, part of speech).
-- 1,492,836 rows measured from the 2026-09-28 extract.
-- ---------------------------------------------------------------------------
CREATE TABLE entry (
    id           INTEGER PRIMARY KEY,   -- dense, so sense/form stay compact
    headword     TEXT NOT NULL,         -- as the source writes it
    headword_lc  TEXT NOT NULL,         -- lowercase; the lookup key
    pos          TEXT NOT NULL,         -- noun verb adj adv name phrase intj ...
    ipa          TEXT,                  -- only where a source records it
    etymology    TEXT,
    freq_rank    INTEGER,               -- corpus rank; lower = more common
    exam_tags    TEXT,                  -- space-separated: CET4 IELTS TOEFL ...
    oxford       INTEGER,               -- 1 if in the Oxford 3000
    collins      INTEGER,               -- Collins star rating, 1-5
    UNIQUE (headword_lc, pos)
);

-- ---------------------------------------------------------------------------
-- sense: numbered senses. 1,787,236 rows measured.
-- `ord` is the number the source prints, so citations into the source line up.
-- ---------------------------------------------------------------------------
CREATE TABLE sense (
    entry_id  INTEGER NOT NULL,
    ord       INTEGER NOT NULL,
    gloss     TEXT NOT NULL,
    tags      TEXT,                     -- comma-separated gate; '' when unlabelled
    example   TEXT,                     -- attested, verbatim, never written by us
    PRIMARY KEY (entry_id, ord)
) WITHOUT ROWID;

-- ---------------------------------------------------------------------------
-- form: inflected form -> entry. This is what makes `running` find `run`.
-- A form may map to more than one entry (noun "runs" vs verb "runs"); the app
-- shows both rather than guessing.
-- ---------------------------------------------------------------------------
CREATE TABLE form (
    form      TEXT NOT NULL,            -- lowercase
    entry_id  INTEGER NOT NULL,
    form_tag  TEXT,                     -- plural / past / third-person ...
    PRIMARY KEY (form, entry_id)
) WITHOUT ROWID;

-- ---------------------------------------------------------------------------
-- relation: lexical relations, from two independent sources.
--
-- src is part of the key on purpose. A relation both Wiktionary and WordNet
-- record is kept twice, so the app can show that two independent sources agree.
-- Folding them into one row would discard the only corroboration available for
-- a relation. src is 'wik' (crowd-edited) or 'wn' (professionally curated).
--
-- `target` holds only the word. The source stores each relation as an object
-- ({"word": ..., "source": ..., "_dis1": ...}); stringifying that object is
-- how 106,916 rows of "{'word': 'pack', 'source': ...}" got in on the first run.
-- rel_tags carries the relation's own register flags, e.g. obsolete, historical.
--
-- Only relations whose BOTH ends resolve to an entry are stored. A taxonomy with
-- dangling ends is worse than no taxonomy.
-- ---------------------------------------------------------------------------
CREATE TABLE relation (
    entry_id  INTEGER NOT NULL,
    rel       TEXT NOT NULL,            -- antonym hypernym hyponym synonym ...
    target    TEXT NOT NULL,            -- lowercase word ONLY
    rel_tags  TEXT,                     -- register flags on this relation
    src       TEXT NOT NULL,            -- 'wik' crowd-edited | 'wn' curated
    PRIMARY KEY (entry_id, rel, target, src)
) WITHOUT ROWID;

-- ---------------------------------------------------------------------------
-- wn_sense: WordNet's own definition of a sense.
--
-- Deliberately NOT merged into `sense`. Wiktionary and WordNet are independent
-- opinions on the same words, and keeping them apart means the two can be
-- compared rather than assumed to agree. This is the only cross-check available
-- at this corpus size.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS wn_sense (
    entry_id   INTEGER NOT NULL,
    sense_no   INTEGER NOT NULL,
    synset_id  TEXT NOT NULL,
    wn_gloss   TEXT NOT NULL,
    wn_example TEXT,
    PRIMARY KEY (entry_id, sense_no)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS idx_wn_sense ON wn_sense (entry_id);

-- ---------------------------------------------------------------------------
-- Indexes. entry_lc drives every lookup; form drives inflection resolution.
-- ---------------------------------------------------------------------------
CREATE INDEX idx_entry_lc  ON entry (headword_lc);
CREATE INDEX idx_form      ON form (form);
CREATE INDEX idx_freq      ON entry (freq_rank) WHERE freq_rank IS NOT NULL;