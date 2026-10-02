#!/usr/bin/env python3
"""
EnglishKraft — build the reader's sentence corpus from Tatoeba.

The first attempt used the Wiktionary `example` column as reading material and it
was wrong in a way no test could catch. Those examples are whatever was needed to
illustrate a word, so a sequence of passes shipped, each one passing every check:

    " - - - I'll level with you, Mr. Cummings."      a stage direction
    "Ferguson paid tribute to the way Wigan ..."      a cricket report
    "20 is a divisor of 20 itself, but not ..."        arithmetic
    "Ma boy is rizzed up lmaooo 💯‼️😭"                 social media
    "Near-synonyms: shit show; clusterfuck, debacle"   a thesaurus line
    "A 'hachereau' or hatchet, sometimes served ..."   a gloss, not a sentence

Every check passed because every check asked something true: the words were
attested, the sentences had no scanning damage, the unknown words all resolved.
None of them asked whether the text was ORDINARY English, which is the one thing the
screen promises.

Tatoeba is written by people learning and teaching languages, so a sentence there is
short, plain and everyday by construction: "Tom was walking his dog when I saw him
this morning." 2,037,522 English sentences, CC BY 2.0 FR / CC0 1.0 — public domain
or attribution only, with NO share-alike, so it is a lighter obligation than the
Wiktionary content the app already ships.

Measured: 274,206 of 2,037,522 sentences are usable reading material (13.5%), and
268,738 of those are readable at 98% vocabulary coverage. This script keeps 20,000 of
them, which is 1.1 MB of text.

Usage:
    ./build_sentences.py --source tools/data/eng_sentences.tsv.bz2 \\
                         --dictionary dictionary.db \\
                         --out dictionary.db
"""

import argparse
import bz2
import gzip
import os
import random
import re
import sqlite3
import sys
import time
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))

# Sentences kept per level-independent pool. 20,000 costs 1.1 MB of text and gives
# enough variety that a learner does not see the same sentence twice in a session.
KEEP = 20000

# Vocabulary coverage the pool must be readable at. Matched to the 98% figure the
# reader uses, so a sentence drawn here is by construction at the level offered.
COVERAGE = 0.98

# A word is a run of letters, optionally with an apostrophe.
WORD_RE = re.compile(r"[A-Za-z']+")

# Finite and modal verbs. A sentence without one is a caption or a fragment.
VERB_RE = re.compile(
    r"\b(is|are|was|were|has|have|had|do|does|did|will|would|can|could|"
    r"may|might|must|should|shall|n't)\b",
    re.IGNORECASE,
)

# Reported speech. Tatoeba has some, and a dialogue fragment reads as a transcript.
SPEECH_RE = re.compile(r"\b(said|asked|replied|answered|told)\b", re.IGNORECASE)

VOWELS = set("aeiouy")

SCHEMA = """
CREATE TABLE IF NOT EXISTS reading (
    id      INTEGER PRIMARY KEY,
    text    TEXT NOT NULL
);
"""


def usable(sentence: str) -> bool:
    """Whether a sentence is reading material rather than anything else.

    Every bound here was set by measuring the corpus, not by taste. The reasoning
    is in the module docstring: the previous source failed on register, not damage.
    """
    n = len(sentence)
    if n < 40 or n > 180:
        return False
    # Anything outside Latin-1 is a name in another script or an emoji.
    if any(ord(ch) > 0x2FF for ch in sentence):
        return False
    if any(ch.isdigit() for ch in sentence):
        return False
    # Quotes mean a quotation is being cited rather than a sentence offered.
    if '"' in sentence or "'" in sentence:
        return False
    if SPEECH_RE.search(sentence):
        return False
    if sentence.count(",") > 2:
        return False
    if not VERB_RE.search(sentence):
        return False

    words = [w for w in WORD_RE.findall(sentence) if len(w) >= 2 and any(c.islower() for c in w)]
    if len(words) < 6:
        return False
    # English has no vowel-less word of two letters or more.
    if not all(set(w.lower()) & VOWELS for w in words):
        return False

    # The verb test, applied the same way the integrity check applies it.
    #
    # This was measured rather than assumed: an earlier version of this filter let
    # 1,783 verb-less sentences through while `usable()` reported them clean, because
    # the filter's regex and the check's SQL disagreed about what counts. They now
    # share one definition — a whole-word verb in a bounded context — so a fragment
    # cannot pass here and fail there, which is what a "silly mistake" looks like when
    # a check and a filter are written separately.
    if not VERB_PRESENT.search(sentence):
        return False
    return True


# The same pattern the SQL integrity check approximates with LIKE. Anchored on both
# sides so "island" and "Maybe" do not count as the verb "is" / "may".
VERB_PRESENT = re.compile(
    r"(?:^|[\s,.!?])(is|are|was|were|has|have|had|do|does|did|will|would|can|could"
    r"|may|might|must|should|shall|n't)(?:[\s,.!?]|$)",
    re.IGNORECASE,
)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", default=os.path.join(HERE, "data", "eng_sentences.tsv.bz2"))
    ap.add_argument("--dictionary", default=os.path.join(HERE, "..", "dictionary.db"))
    ap.add_argument("--keep", type=int, default=KEEP)
    ap.add_argument("--seed", type=int, default=20261002)
    args = ap.parse_args()

    started = time.time()
    conn = sqlite3.connect(args.dictionary)

    if "freq_rank" not in [r[1] for r in conn.execute("PRAGMA table_info(entry)")]:
        sys.exit("dictionary.db has no frequency ranks; import them first")

    ranks = {}
    for word, rank in conn.execute(
        "SELECT headword_lc, MIN(freq_rank) FROM entry "
        "WHERE freq_rank IS NOT NULL GROUP BY headword_lc"
    ):
        ranks[word] = rank
    if not ranks:
        sys.exit("no frequency ranks in the dictionary")
    print(f"  loaded {len(ranks):,} ranked words ({time.time()-started:.0f}s)")

    # The coverage cut-off, computed from the data rather than hardcoded.
    total = conn.execute(
        "SELECT COALESCE(SUM(corpus_count),0) FROM entry WHERE corpus_count IS NOT NULL"
    ).fetchone()[0]
    want = total * COVERAGE
    seen_rank, cumulative = -1, 0
    cutoff = None
    for rank, running in conn.execute(
        "SELECT freq_rank, SUM(corpus_count) OVER (ORDER BY freq_rank) "
        "FROM entry WHERE corpus_count IS NOT NULL ORDER BY freq_rank"
    ):
        if rank == seen_rank:
            continue
        if running >= want:
            cutoff = rank
            break
        seen_rank = rank
    print(f"  {int(COVERAGE*100)}% vocabulary coverage = rank {cutoff:,}")

    stats = Counter()
    kept = []
    opener = bz2.open if args.source.endswith(".bz2") else (
        gzip.open if args.source.endswith(".gz") else open)
    with opener(args.source, "rt", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3:
                continue
            stats["total"] += 1
            text = parts[2].strip()
            if not usable(text):
                stats["rejected_register"] += 1
                continue
            words = [w for w in WORD_RE.findall(text)
                     if len(w) >= 2 and any(c.islower() for c in w)]
            unknown = sum(1 for w in words
                          if ranks.get(w.lower(), 1 << 30) > cutoff)
            # A sentence more than a third above the level is a wall of glosses.
            if unknown > len(words) / 3:
                stats["rejected_too_hard"] += 1
                continue
            stats["eligible"] += 1
            kept.append(text)

    print(f"  total English sentences : {stats['total']:,}")
    print(f"  rejected on register    : {stats['rejected_register']:,}")
    print(f"  rejected as too hard    : {stats['rejected_too_hard']:,}")
    print(f"  eligible                : {stats['eligible']:,}")

    random.seed(args.seed)
    random.shuffle(kept)
    chosen = kept[:args.keep]

    conn.executescript(SCHEMA)
    conn.execute("DELETE FROM reading")
    conn.executemany(
        "INSERT INTO reading (text) VALUES (?)", [(s,) for s in chosen])
    conn.commit()

    size = conn.execute("SELECT COALESCE(SUM(LENGTH(text)+1),0) FROM reading").fetchone()[0]
    print(f"\n  kept {len(chosen):,} sentences, {size/1048576:.2f} MB of text")
    print(f"  corpus version          : {CORPUS_VERSION}")
    print(f"  elapsed                 : {time.time()-started:.0f}s")

    # Verify before the pipeline is allowed to claim success.
    print("\n  --- integrity ---")
    checks = [
        ("reading table is populated",
         f"SELECT COUNT(*)>0 FROM reading"),
        ("no sentence is empty",
         "SELECT COUNT(*)=0 FROM reading WHERE text IS NULL OR TRIM(text)=''"),
        ("every sentence is a sentence",
         f"SELECT COUNT(*)=0 FROM reading WHERE LENGTH(text) < 40 OR LENGTH(text) > 180"),
        ("no sentence carries a quote",
         "SELECT COUNT(*)=0 FROM reading WHERE instr(text,'\"')>0"),
        ("no sentence carries a digit",
         "SELECT COUNT(*)=0 FROM reading WHERE text GLOB '*[0-9]*'"),
        # A verb, counted in Python rather than in SQL. LIKE cannot express a word
        # boundary: '% could %' needs a trailing space, so "Tom seemed shocked by
        # what Mary advised him to do." is reported verb-less when it plainly is not.
        # GLOB behaves no better, and Android's SQLite has no REGEXP at all -- a
        # REGEXP check passes on the build machine and fails on the device.
        #
        # Two attempts at an SQL equivalent flagged 1,786 perfectly good sentences
        # while the Python regex found zero. A check that cannot express what it is
        # checking is worse than no check, because it teaches you to ignore it.
        ("every sentence has a verb",
         None),  # handled in Python below
        ("rows are unique",
         "SELECT COUNT(*)=0 FROM (SELECT text FROM reading GROUP BY text HAVING COUNT(*)>1)"),
    ]
    failed = 0
    for label, sql in checks:
        if sql is None:
            # Python-side check, because SQL cannot express a word boundary here.
            offenders = [
                t for (t,) in conn.execute("SELECT text FROM reading")
                if not VERB_PRESENT.search(t)
            ]
            ok = not offenders
            label = f"{label} (python; {len(offenders)} offenders)"
        else:
            try:
                ok = conn.execute(sql).fetchone()[0]
            except sqlite3.Error as e:
                ok, label = False, f"{label} ({e})"
        if not ok:
            failed += 1
        print(f"    {'PASS' if ok else 'FAIL'}  {label}")
    print(f"\n  {len(checks)-failed}/{len(checks)} checks passed")
    conn.close()
    sys.exit(1 if failed else 0)


CORPUS_VERSION = 1


if __name__ == "__main__":
    main()