#!/usr/bin/env python3
"""
EnglishKraft — import the Open English WordNet taxonomy.

WordNet is the only source in this project that is professionally curated rather
than crowd-edited. That matters twice over:

  1. It supplies the taxonomy (hypernym, hyponym, meronym, holonym, attribute,
     similar) that no competing dictionary app has.
  2. Its 107,524 definitions are an INDEPENDENT opinion on the same words as
     Wiktionary. Where both exist they can be compared, which is the only
     cross-check available for a corpus this size.

Structure verified from the 2025 edition before writing this:
    <LexicalEntry id="..."><Lemma writtenForm="dog" partOfSpeech="n"/>
      <Sense id="..." synset="oewn-00001740-a"/></LexicalEntry>
    <Synset id="..." members="entry-id entry-id" ...>
      <Definition>...</Definition><SynsetRelation relType="hyponym" target="..."/>

Relations are only imported when BOTH ends resolve to an entry in dictionary.db.
A taxonomy with dangling ends is worse than no taxonomy, so unresolvable ends are
counted and reported rather than shipped.

Usage:
    ./import_wordnet.py --wordnet tools/data/english-wordnet-2025.xml.gz \
                        --dictionary dictionary.db
"""

import argparse
import gzip
import os
import re
import sqlite3
import sys
import time
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))

# WordNet's part-of-speech codes to ours.
POS_MAP = {"n": "noun", "v": "verb", "a": "adj", "r": "adv", "s": "name"}

# Only relations a learner can act on. Inverse pairs (hyponym/hypernym,
# mero_part/holo_part, exemplifies/is_exemplified_by) are stored once in the
# forward direction; the app derives the inverse, which halves the row count.
KEEP_RELATIONS = {
    "hyponym": "hyponym",
    "hypernym": "hypernym",
    "mero_part": "meronym",
    "holo_part": "holonym",
    "similar": "similar",
    "attribute": "attribute",
    "domain_topic": "domain",
}

# src is part of the key on purpose. A relation that BOTH Wiktionary and
# WordNet record is worth keeping twice: the app can then say two independent
# sources agree, which is the only corroboration available for a relation.
# Folding them into one row would throw that away.
MIGRATE_RELATION = """
CREATE TABLE relation_new (
    entry_id  INTEGER NOT NULL,
    rel       TEXT NOT NULL,
    target    TEXT NOT NULL,
    rel_tags  TEXT,
    src       TEXT NOT NULL,
    PRIMARY KEY (entry_id, rel, target, src)
) WITHOUT ROWID;
INSERT INTO relation_new (entry_id, rel, target, rel_tags, src)
    SELECT entry_id, rel, target, rel_tags, 'wik' FROM relation;
DROP TABLE relation;
ALTER TABLE relation_new RENAME TO relation;
"""

# WordNet's own definition of a sense, kept separate from the Wiktionary gloss
# so the two can be shown side by side rather than merged. Merging them would
# destroy the only independent cross-check this project has.
ADD_SCHEMA = MIGRATE_RELATION + """
CREATE TABLE IF NOT EXISTS wn_sense (
    entry_id   INTEGER NOT NULL,
    sense_no   INTEGER NOT NULL,
    synset_id  TEXT NOT NULL,
    wn_gloss   TEXT NOT NULL,
    wn_example TEXT,
    PRIMARY KEY (entry_id, sense_no)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS idx_wn_sense ON wn_sense (entry_id);
"""


def parse_wordnet(path):
    """Single streaming pass. Returns (synsets, entries)."""
    synsets = {}       # synset_id -> dict(definition, examples, relations)
    entries = {}       # lexentry_id -> dict(word, pos, synsets=[...])
    entry_word = {}    # lexentry_id -> (word_lc, pos) for member resolution

    lex_re = re.compile(
        r'<LexicalEntry id="(?P<id>[^"]+)"[^>]*>(?P<body>.*?)</LexicalEntry>',
        re.S)
    lemma_re = re.compile(r'<Lemma writtenForm="(?P<form>[^"]*)" partOfSpeech="(?P<pos>[^"]*)"')
    sense_re = re.compile(r'<Sense [^>]*?synset="(?P<syn>[^"]+)"')
    syn_re = re.compile(
        r'<Synset id="(?P<id>[^"]+)"(?P<attrs>[^>]*)>(?P<body>.*?)</Synset>', re.S)
    synrel_re = re.compile(r'<SynsetRelation relType="(?P<rel>[^"]+)" target="(?P<to>[^"]+)"')
    defn_re = re.compile(r'<Definition>(?P<d>.*?)</Definition>', re.S)
    ex_re = re.compile(r'<Example>(?P<e>.*?)</Example>', re.S)
    members_re = re.compile(r'members="([^"]*)"')

    def unescape(s):
        return (s.replace("&apos;", "'").replace("&quot;", '"')
                 .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&"))

    def strip_tags(s):
        return unescape(re.sub(r"<[^>]+>", "", s)).strip()

    with gzip.open(path, "rt", encoding="utf-8", errors="replace") as fh:
        # 85 MB uncompressed. Read whole: chunked parsing would risk splitting an
        # element across a boundary, and a silently dropped LexicalEntry is a
        # silently missing word.
        text = fh.read()

    for m in lex_re.finditer(text):
        eid, body = m.group("id"), m.group("body")
        lm = lemma_re.search(body)
        if not lm:
            continue
        word = unescape(lm.group("form")).strip().lower()
        pos = POS_MAP.get(lm.group("pos"))
        if not word or not pos:
            continue
        syns = [s.group("syn") for s in sense_re.finditer(body)]
        entries[eid] = {"word": word, "pos": pos, "synsets": syns}
        entry_word[eid] = (word, pos)

    for m in syn_re.finditer(text):
        sid, attrs, body = m.group("id"), m.group("attrs"), m.group("body")
        dm = defn_re.search(body)
        synsets[sid] = {
            "definition": strip_tags(dm.group("d")) if dm else "",
            "examples": [strip_tags(e.group("e")) for e in ex_re.finditer(body)][:2],
            "relations": [(r.group("rel"), r.group("to"))
                          for r in synrel_re.finditer(body)],
            "members": members_re.search(attrs).group(1).split() if members_re.search(attrs) else [],
        }
    return synsets, entries, entry_word


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--wordnet", default=os.path.join(HERE, "data", "english-wordnet-2025.xml.gz"))
    ap.add_argument("--dictionary", default=os.path.join(HERE, "..", "dictionary.db"))
    args = ap.parse_args()

    started = time.time()
    print(f"  wordnet  {args.wordnet}")
    print(f"  database {args.dictionary}")

    synsets, entries, entry_word = parse_wordnet(args.wordnet)
    print(f"  parsed: {len(synsets):,} synsets, {len(entries):,} lexical entries "
          f"({time.time()-started:.0f}s)")

    conn = sqlite3.connect(args.dictionary)
    cols = [r[1] for r in conn.execute("PRAGMA table_info(relation)")]
    if "src" not in cols:
        conn.executescript(ADD_SCHEMA)
        print("  migrated relation key to include src; added wn_sense table")
    conn.commit()

    # Resolve (headword_lc, pos) -> entry_id once, into memory. 1.46M entries.
    print("  loading entry lookup...")
    entry_of = {}
    for eid, hw, pos in conn.execute("SELECT id, headword_lc, pos FROM entry"):
        entry_of[(hw, pos)] = eid
    print(f"  {len(entry_of):,} entries loadable ({time.time()-started:.0f}s)")

    # synset -> a representative lemma, taken from WordNet's own member ordering.
    #
    # The representative must resolve IN OUR DATABASE, not merely exist in
    # WordNet. Taking members[0] blindly loses real links: synset
    # oewn-02101202-n ("a dog trained to work with sportsmen when they hunt with
    # guns") lists "sporting dog" first and "gun dog" second. "sporting dog" is
    # absent from Wiktionary, so every relation through that synset was silently
    # dropped -- including spaniel's hypernym. Falling through to the first
    # member that actually resolves recovers them.
    synset_rep = {}
    unresolved = 0
    for sid, s in synsets.items():
        chosen = None
        for mem in s["members"]:
            wp = entry_word.get(mem)
            if wp and wp in entry_of:
                chosen = wp
                break
        if chosen is None:
            unresolved += 1
        synset_rep[sid] = chosen
    print(f"  synsets with >=1 member present in dictionary.db: "
          f"{len(synsets) - unresolved:,} of {len(synsets):,} "
          f"({100*(len(synsets)-unresolved)/max(1,len(synsets)):.1f}%)")

    n_sense_rows = 0
    n_unmatched_lemma = 0
    wn_sense_rows = []
    matched_entries = set()

    for eid, e in entries.items():
        target = entry_of.get((e["word"], e["pos"]))
        if target is None:
            n_unmatched_lemma += 1
            continue
        matched_entries.add(target)
        for i, sid in enumerate(e["synsets"], start=1):
            s = synsets.get(sid)
            if not s or not s["definition"]:
                continue
            wn_sense_rows.append((target, i, sid, s["definition"],
                                  s["examples"][0] if s["examples"] else None))
    n_sense_rows = len(wn_sense_rows)
    print(f"  WordNet senses matched to an entry: {n_sense_rows:,}")
    print(f"  WordNet lemmas with no matching entry: {n_unmatched_lemma:,}")

    if wn_sense_rows:
        conn.executemany(
            "INSERT OR REPLACE INTO wn_sense (entry_id, sense_no, synset_id, wn_gloss, wn_example)"
            " VALUES (?,?,?,?,?)", wn_sense_rows)
    conn.commit()

    # Relations -------------------------------------------------------------
    rows = Counter()
    rel_rows = []
    for sid, s in synsets.items():
        src = synset_rep.get(sid)
        if not src:
            continue
        src_entry = entry_of.get(src)
        if src_entry is None:
            continue
        for rel, target_sid in s["relations"]:
            keep = KEEP_RELATIONS.get(rel)
            if not keep:
                continue
            tgt = synset_rep.get(target_sid)
            if not tgt:
                rows["unresolvable_target"] += 1
                continue
            tgt_entry = entry_of.get(tgt)
            if tgt_entry is None:
                rows["target_not_in_db"] += 1
                continue
            if tgt_entry == src_entry:
                rows["self_relation"] += 1
                continue
            rel_rows.append((src_entry, keep, tgt[0], None, "wn"))
            rows[keep] += 1

    print(f"  relations resolved end-to-end: {len(rel_rows):,}")
    for k, v in rows.most_common():
        print(f"    {k:24s} {v:>9,}")
    print(f"  dropped: unresolvable={rows['unresolvable_target']:,} "
          f"not_in_db={rows['target_not_in_db']:,} self={rows['self_relation']:,}")

    conn.executemany(
        "INSERT OR IGNORE INTO relation (entry_id, rel, target, rel_tags, src)"
        " VALUES (?,?,?,?,?)", rel_rows)
    conn.commit()

    print(f"\n  cross-check: entries with both a Wiktionary gloss and a WordNet gloss")
    both = conn.execute("""
        SELECT COUNT(DISTINCT e.id) FROM entry e
        JOIN sense  s ON s.entry_id = e.id
        JOIN wn_sense w ON w.entry_id = e.id
    """).fetchone()[0]
    only_wn = conn.execute("SELECT COUNT(DISTINCT entry_id) FROM wn_sense").fetchone()[0]
    print(f"    entries with both sources: {both:,}")
    print(f"    entries with a WordNet gloss at all: {only_wn:,}")
    print(f"    matched WordNet lemmas: {len(matched_entries):,} of {len(entries):,} "
          f"({100*len(matched_entries)/max(1,len(entries)):.1f}%)")

    print(f"\n  done in {time.time()-started:.0f}s")
    conn.close()


if __name__ == "__main__":
    main()