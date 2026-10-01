#!/usr/bin/env python3
"""
EnglishKraft — build dictionary.db from the Wiktionary extract.

Reads the Wiktextract JSONL extract and distils it into the schema in
schema.sql. Nothing is generated, paraphrased or rewritten: every gloss and
every example written to the database is text that was already in the source.

Usage:
    ./build_dictionary.py --source kaikki.org-dictionary-English.jsonl.gz \
                          --out ../app/src/main/assets/dictionary.db

Run with --report-only to measure an existing database without rebuilding.
"""

import argparse
import gzip
import json
import os
import sqlite3
import sys
import time
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))

# Relations worth carrying. The source emits many more; these are the ones an
# English learner can act on.
KEEP_RELATIONS = {
    "antonyms": "antonym",
    "hypernyms": "hypernym",
    "hyponyms": "hyponym",
    "synonyms": "synonym",
    "meronyms": "meronym",
    "holonyms": "holonym",
}

def flatten(value):
    """Source tags arrive as str, or {'form': 'x'}, or lists of either."""
    if value is None:
        return None
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        return ",".join(str(v) for v in value.values() if v is not None)
    if isinstance(value, list):
        parts = [flatten(v) for v in value]
        parts = [p for p in parts if p]
        return ",".join(parts) if parts else None
    return str(value)


def first_example(examples):
    """Return exactly one attested example verbatim.

    Wiktionary stores examples as bare strings or as {'text': ...}. Take the
    first and keep it byte-for-byte. We never normalise punctuation or case.
    """
    if not examples:
        return None
    ex = examples[0]
    if isinstance(ex, str):
        return ex
    if isinstance(ex, dict):
        for key in ("text", "example", "usage"):
            v = ex.get(key)
            if isinstance(v, str) and v.strip():
                return v
    return None


def first_ipa(sounds):
    """Pick an IPA transcription where the source records one.

    Prefer generic/unspecified accents so we do not assert one pronunciation as
    THE pronunciation. Falls back to the first entry the source offers.
    """
    if not sounds:
        return None
    best = None
    for s in sounds:
        ipa = flatten(s.get("ipa"))
        if not ipa:
            continue
        accent = flatten(s.get("accent")) or ""
        if not best:
            best = ipa
        if accent in ("", "General", "generic", "Received Pronunciation"):
            return ipa
    return best


def relation_target(value):
    """Extract just the word from a relation entry.

    The source stores relations as objects:
        {"word": "animal", "source": "thesaurus:dog", "_dis1": "0 0 0 ..."}
    and occasionally as a bare string. Taking str(value) wrote the whole
    object repr into the target column, which is exactly the "wrong data"
    this project must never ship. Returns None when there is no usable word.
    """
    if isinstance(value, str):
        word = value
    elif isinstance(value, dict):
        word = value.get("word")
    else:
        return None
    if not isinstance(word, str):
        return None
    word = word.strip()
    if not word or len(word) > 64:
        return None
    # A word, not a serialised object. Cheap guard against a shape change.
    if any(ch in word for ch in "{}[]'\"|"):
        return None
    return word.lower()


def build(source_path, out_path, batch=20000, limit=None, verbose=True):
    started = time.time()

    if os.path.exists(out_path):
        os.remove(out_path)
    # -wal/-shm siblings must go too, or SQLite reopens a stale write-ahead log.
    for suffix in ("-wal", "-shm"):
        side = out_path + suffix
        if os.path.exists(side):
            os.remove(side)

    conn = sqlite3.connect(out_path)
    conn.executescript(open(os.path.join(HERE, "schema.sql")).read())
    conn.execute("PRAGMA journal_mode=OFF")     # build-only; never shipped
    conn.execute("PRAGMA synchronous=OFF")     # build-only
    conn.execute("PRAGMA cache_size=-131072")  # 128 MB page cache while building

    n_records = 0
    next_id = 1
    n_merged = 0
    pos_counts = Counter()
    skipped_bad = 0
    senses, forms, relations = [], [], []

    def flush():
        nonlocal senses, forms, relations
        if senses:
            conn.executemany(
                "INSERT OR REPLACE INTO sense (entry_id, ord, gloss, tags, example)"
                " VALUES (?,?,?,?,?)", senses)
        if forms:
            conn.executemany(
                "INSERT OR IGNORE INTO form (form, entry_id, form_tag) VALUES (?,?,?)",
                forms)
        if relations:
            conn.executemany(
                "INSERT OR IGNORE INTO relation (entry_id, rel, target, rel_tags)"
                " VALUES (?,?,?,?)", relations)
        senses, forms, relations = [], [], []

    opener = gzip.open if source_path.endswith(".gz") else open
    with opener(source_path, "rt", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            n_records += 1
            try:
                rec = json.loads(line)
            except Exception:
                skipped_bad += 1
                continue

            word = rec.get("word")
            pos = rec.get("pos")
            if not word or not pos:
                skipped_bad += 1
                continue

            head = word.lower()
            key = (head, pos)

            # The source can emit the same (headword, pos) twice. INSERT OR IGNORE
            # would silently drop the second one and leave its senses and forms
            # pointing at an id that was never written -- the word then returns
            # nothing at all. So: write the entry, and if it collided, reuse the
            # existing id and keep merging into it.
            cur = conn.execute(
                "INSERT OR IGNORE INTO entry"
                " (id, headword, headword_lc, pos, ipa, etymology)"
                " VALUES (?,?,?,?,?,?)",
                (next_id, word, head, pos,
                 first_ipa(rec.get("sounds")),
                 flatten(rec.get("etymology_text"))))
            if cur.rowcount == 1:
                entry_id = next_id
                next_id += 1
            else:
                entry_id = conn.execute(
                    "SELECT id FROM entry WHERE headword_lc=? AND pos=?",
                    key).fetchone()[0]
                n_merged += 1
            pos_counts[pos] += 1

            # Senses -------------------------------------------------------
            for i, sense in enumerate(rec.get("senses") or [], start=1):
                glosses = sense.get("glosses") or []
                if not glosses:
                    continue
                gloss = flatten(glosses[0])
                if not gloss:
                    continue
                senses.append((
                    entry_id,
                    i,
                    gloss,
                    flatten(sense.get("tags")) or "",
                    first_example(sense.get("examples")),
                ))

            # Inflected forms ---------------------------------------------
            for f in rec.get("forms") or []:
                if not isinstance(f, dict):
                    continue
                form_text = f.get("form")
                if not form_text:
                    continue
                fl = form_text.lower()
                if fl == head:
                    continue
                forms.append((fl, entry_id, flatten(f.get("tags"))))

            # Relations ---------------------------------------------------
            for key, rel in KEEP_RELATIONS.items():
                for item in rec.get(key) or []:
                    target = relation_target(item)
                    if not target or target == head:
                        continue
                    rel_tags = flatten(item.get("tags")) if isinstance(item, dict) else None
                    relations.append((entry_id, rel, target, rel_tags))

            if n_records % batch == 0:
                flush()
                conn.commit()
                if verbose:
                    print(f"  {n_records:>9,} records"
                          f"  {time.time()-started:>6.0f}s", flush=True)

            if limit and n_records >= limit:
                break

    flush()

    # Commit before VACUUM: VACUUM cannot run inside an open transaction, and
    # without this commit every row above is discarded when the process exits.
    conn.commit()

    elapsed = time.time() - started
    size = os.path.getsize(out_path)
    if verbose:
        print(f"\n  records read       {n_records:,}")
        print(f"  malformed skipped  {skipped_bad:,}")
        print(f"  entries written    {next_id - 1:,}")
        print(f"  duplicate records merged into an existing entry: {n_merged:,}")
        print(f"  database size      {size/1048576:.1f} MB")
        print(f"  elapsed            {elapsed:.0f}s")

    conn.execute("VACUUM")
    conn.execute("ANALYZE")
    conn.commit()
    conn.close()

    if verbose:
        print(f"  after VACUUM       {os.path.getsize(out_path)/1048576:.1f} MB")
        print(f"  total              {time.time()-started:.0f}s")
        print("  POS:", dict(pos_counts.most_common(12)))

    return n_records, next_id - 1, n_merged


def report(db_path):
    """Per-table and per-column sizes, so size decisions are made on numbers."""
    conn = sqlite3.connect(db_path)
    size = os.path.getsize(db_path)
    print(f"\n=== {db_path} — {size/1048576:.1f} MB ===\n")

    print(f"  {'table':<12} {'rows':>12} {'MB':>9}  {'% of db':>7}")
    print("  " + "-" * 46)
    for table in ("entry", "sense", "form", "relation"):
        try:
            rows = conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
        except sqlite3.Error:
            continue
        try:
            conn.execute("CREATE VIRTUAL TABLE temp._s USING dbstat(main)")
            mb = conn.execute(
                "SELECT SUM(pgsize)/1048576.0 FROM _s WHERE name=?",
                (table,)).fetchone()[0] or 0
            conn.execute("DROP TABLE temp._s")
        except sqlite3.Error:
            mb = 0
        pct = 100 * mb * 1048576 / size if size else 0
        print(f"  {table:<12} {rows:>12,} {mb:>9.1f}  {pct:>6.1f}%")

    print("\n  --- text volume in entry (the size levers) ---")
    for col in ("etymology", "ipa", "headword", "headword_lc", "pos"):
        try:
            n, total = conn.execute(
                f"SELECT COUNT({col}), COALESCE(SUM(LENGTH({col})),0) FROM entry"
            ).fetchone()
            print(f"    {col:<14} {n:>9,} non-null  {total/1048576:>8.1f} MB of text")
        except sqlite3.Error:
            pass

    try:
        n, total = conn.execute(
            "SELECT COUNT(example), COALESCE(SUM(LENGTH(example)),0) FROM sense").fetchone()
        print(f"    {'example':<14} {n:>9,} non-null  {total/1048576:>8.1f} MB of text")
        n, total = conn.execute(
            "SELECT COUNT(gloss), COALESCE(SUM(LENGTH(gloss)),0) FROM sense").fetchone()
        print(f"    {'gloss':<14} {n:>9,} non-null  {total/1048576:>8.1f} MB of text")
    except sqlite3.Error:
        pass

    print("\n  --- integrity (must pass before the DB is shipped) ---")
    checks = [
        ("entry ids contiguous",
         "SELECT MIN(id)=1 AND MAX(id)=COUNT(*) FROM entry"),
        ("every sense has an entry",
         "SELECT COUNT(*)=0 FROM sense s LEFT JOIN entry e ON e.id=s.entry_id WHERE e.id IS NULL"),
        ("every form has an entry",
         "SELECT COUNT(*)=0 FROM form f LEFT JOIN entry e ON e.id=f.entry_id WHERE e.id IS NULL"),
        ("every relation has an entry",
         "SELECT COUNT(*)=0 FROM relation r LEFT JOIN entry e ON e.id=r.entry_id WHERE e.id IS NULL"),
        ("no duplicate (headword_lc,pos)",
         "SELECT COUNT(*)=0 FROM (SELECT headword_lc,pos FROM entry GROUP BY 1,2 HAVING COUNT(*)>1)"),
        ("lookup index present",
         "SELECT COUNT(*)>0 FROM sqlite_master WHERE type='index' AND name='idx_entry_lc'"),
        # Guard against a serialised source object leaking into a word column.
        # `_dis1` is a key that appears ONLY inside the source's relation objects
        # ("_dis1": "0 0 0 0") and never in English or mathematics, so it is the
        # one unambiguous discriminator.
        #
        # Braces are deliberately NOT used as a signal. Two earlier versions used
        # them and both misfired:
        #   - 1,106 valid glosses begin with "(" (Wiktionary qualifiers, e.g.
        #     "(UK, Ireland) Burning; ablaze.")
        #   - 92 valid glosses contain "{" because they define integer sets, e.g.
        #     "The set of positive integers, {1, 2, 3, ...}."
        #
        # Character tests use instr(), never LIKE: in SQLite LIKE, `_` is a
        # single-character wildcard, so `LIKE '%_%'` matches EVERY non-empty
        # string and passes everything without testing anything.
        ("no serialised source object in any word column",
         "SELECT COUNT(*)=0 FROM ("
         " SELECT gloss FROM sense WHERE instr(gloss,'_dis1')>0"
         " UNION ALL SELECT target FROM relation WHERE instr(target,'_dis1')>0"
         " UNION ALL SELECT headword FROM entry WHERE instr(headword,'_dis1')>0"
         " UNION ALL SELECT example FROM sense WHERE instr(example,'_dis1')>0"
         ")"),
        ("every headword is non-empty",
         "SELECT COUNT(*)=0 FROM entry WHERE headword='' OR headword_lc=''"),
        ("every sense has a gloss",
         "SELECT COUNT(*)=0 FROM sense WHERE gloss IS NULL OR gloss=''"),
        # Multi-word relation targets are joined with spaces, so a target that is
        # also a real headword is resolvable by the app. Targets nobody can look
        # up are dead weight but not corruption, so this is reported, not enforced.
        ("relation targets that resolve to an entry (reported, not enforced)",
         "SELECT COUNT(*)>0 FROM relation r JOIN entry e ON e.headword_lc=r.target"),
    ]
    failed = 0
    for label, sql in checks:
        try:
            ok = conn.execute(sql).fetchone()[0]
        except sqlite3.Error as e:
            ok = False
            label += f"  ({e})"
        if not ok:
            failed += 1
        print(f"    {'PASS' if ok else 'FAIL'}  {label}")
    print(f"\n  {len(checks) - failed}/{len(checks)} checks passed")
    conn.close()
    return failed


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", required=False,
                    help="kaikki.org-dictionary-English.jsonl.gz")
    ap.add_argument("--out", default=os.path.join(HERE, "..", "dictionary.db"))
    ap.add_argument("--limit", type=int, default=None)
    ap.add_argument("--report-only", action="store_true")
    args = ap.parse_args()

    if args.report_only:
        report(args.out)
        sys.exit(0)

    if not args.source or not os.path.exists(args.source):
        sys.exit(f"source not found: {args.source!r}\n"
                 f"see tools/fetch_sources.sh")
    build(args.source, os.path.abspath(args.out), limit=args.limit)