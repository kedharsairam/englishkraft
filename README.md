# EnglishKraft

An offline English course and dictionary. Every word in the language, no network,
no permissions, nothing recorded.

The dictionary is the substrate; the course is the product. Remove the dictionary
and you still have a course. Remove the course and you have a dictionary, which
does not need 404 MB.

## What is in the database today

| | |
|---|---|
| entries (headword × part of speech) | 1,456,903 |
| senses | 1,745,189 |
| inflected forms | 880,940 |
| lexical relations | 270,728 |
| WordNet senses cross-referenced | 145,695 |
| attested example sentences | 366,506 |
| IPA transcriptions | 129,301 |
| senses with a usage/quality tag | 1,217,027 |

`427.3 MB` on disk, `165.4 MB` compressed in the APK, built in 94 seconds.
Full measurements in [docs/DATA.md](docs/DATA.md).

## The rule everything is built around

**Nothing is generated. Every gloss and every example is text that already
existed in the source, written by a human.**

That single rule is what makes it possible to build a learning app that does not
teach mistakes. A wrong grammar rule teaches a learner something false, which is
worse than a missing feature. So the app never invents a sentence, never
paraphrases a definition, and never generates an explanation.

Usage and register tags are **stored, not applied at build time.** 69.7% of senses
carry one. An A1 learner sees none of the `obsolete`, `archaic`, `vulgar` or
`dated` senses; a C2 learner sees all of them. Same rows, different filter.

## Building the database

```bash
tools/fetch_sources.sh                 # 499 MB compressed, ~24 GB uncompressed
python3 tools/build_dictionary.py \
    --source tools/data/kaikki.org-dictionary-English.jsonl.gz \
    --out   dictionary.db
python3 tools/build_dictionary.py --report-only --out dictionary.db
```

The build refuses to be trusted on its own numbers. Ten integrity checks run as
part of the report, and every one has been tested in both directions — a check
that cannot fail is worse than no check. Six defects were caught this way,
including one that would have shipped 106,916 rows of
`{'word': 'pack', 'source': ...}` into the search index, and one that silently
dropped 10,067 taxonomy relations while reporting success.

## Why the database is copied on first launch

`SQLiteDatabase.openDatabase(FileDescriptor, ...)` is not in the public SDK.
Verified against `android-37.0/android.jar`: only three overloads exist and all
take a path or a `File`. The FileDescriptor form is `@hide` in AOSP and blocked
by hidden-API restrictions since API 28.

So there is no zero-copy option on the public SDK. The copy is designed for
rather than avoided, and `progress.db` (wordbook, spaced-repetition cards,
placement results) is kept separate so a corpus update never costs the user
their progress.

## Provenance

Every source, its licence and its measured scale is in
[docs/sources.md](docs/sources.md). The code is MIT. The dictionary content is
**not** — it is derived from Wiktionary (CC BY-SA 4.0) and the Open English
WordNet (CC BY 4.0). See [LICENSE](LICENSE) and [DATA-LICENCE.md](DATA-LICENCE.md).