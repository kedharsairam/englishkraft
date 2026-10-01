# EnglishKraft — data provenance

Every number in this file was **measured**, not estimated. Every figure was read
out of the data or out of the installed SDK. Where a number is a guess it says so.

## Primary source

| | |
|---|---|
| **What** | Wiktionary, English edition, extracted by Wiktextract |
| **Where** | `https://kaikki.org/dictionary/English/kaikki.org-dictionary-English.jsonl.gz` |
| **Extracted** | 2026-09-28 (from the 2026-09-02 enwiktionary dump) |
| **Downloaded** | 2026-10-02, HTTP 200, `content-length` 523,338,320 |
| **On disk** | 499 MB compressed / ~24 GB uncompressed |
| **Licence** | Wiktionary text is **CC BY-SA 4.0** (and GFDL). Share-alike. |

### Measured from the full extract (full pass, all 1,492,836 records)

| | |
|---|---|
| records (headword × part of speech) | **1,492,836** |
| distinct word forms | **1,390,507** |
| senses | **1,787,236** |
| senses carrying register/quality tags | **1,246,597 — 69.7%** |
| example sentences already present | **765,322** |

Parts of speech: noun 831,966 · verb 222,221 · name 200,625 · adj 186,151 ·
adv 27,625 · phrase 5,258 · intj 4,967 · prep_phrase 3,045 · prefix 2,508 ·
suffix 1,675 · proverb 1,583 · pron 1,045 · contraction 952 · prep 882

Quality/register tags across the whole dictionary — this is the gate that keeps
obscure or unsuitable senses away from a learner:

| tag | senses | tag | senses |
|---|---|---|---|
| obsolete | 40,897 | rare | 19,169 |
| archaic | 25,059 | derogatory | 9,215 |
| dated | 11,498 | vulgar | 5,097 |
| historical | 2,052 | offensive | 2,162 |
| colloquial | 2,045 | euphemistic | 2,017 |
| slang | 6,621 | nonstandard | 3,917 |
| informal | 3,784 | uncommon | 3,654 |
| figurative | 3,969 | dialectal | 1,305 |

## Secondary sources

| Source | Licence | Used for | Scale |
|---|---|---|---|
| `globalwordnet/english-wordnet` 2025 | WordNet Licence + **CC BY 4.0** | taxonomy: hypernym / hyponym / antonym | 107,519 synsets · 135,969 lemmas · 185,129 senses · 234,810 relations · 11.4 MB gz |
| `skywind3000/ECDICT` | MIT (repo) — **data provenance is mixed, see caution** | corpus frequency ranks, Collins stars, Oxford 3000 flag, exam tags | 760k entries |
| New General Service List (Bauer 2013) | freely published list | the teaching order for the vocabulary ladder | 2,800 words = >92% coverage of general English |
| Academic Word List (Coxhead) | freely published list | academic vocabulary, later phase | 570 word families ≈ 10% of academic text |
| Tatoeba | CC BY / CC0 | candidate example sentences | millions of human-written pairs |
| COCA | free download | corpus frequency verification | 1bn+ words |
| FSRS (`open-spaced-repetition`) | **MIT** | spaced-repetition scheduling | 4,085★, powers Anki |

### Caution on ECDICT

The repository is MIT, but its own README shows the English definitions are
compiled from EDictAZ.txt, the `cdict` RPM, scraped websites and the **BNC
corpus** (Oxford Text Archive — not freely redistributable). An MIT licence on
the repository does not relicense data the author compiled. Frequency *ranks*
derived from a corpus are a different question from redistributing corpus text,
but this is a judgement call, not a formality, and it is recorded here rather
than hidden.

## Architecture decision: two database files

`SQLiteDatabase.openDatabase(FileDescriptor, ...)` **does not exist in the public
SDK.** Verified against `android-37.0/android.jar` — the only three overloads take
a `String` path or a `File`. The FileDescriptor form is `@hide` in AOSP and is
blocked by hidden-API restrictions since API 28.

**Consequence: a database inside an APK cannot be opened in place. It must be
copied to internal storage.** There is no zero-copy option on the public SDK, so
the copy is designed for rather than avoided.

| File | Contents | Lifecycle |
|---|---|---|
| `dictionary.db` | read-only corpus | copied from assets once, never written |
| `progress.db` | wordbook, SRS cards, placement results | internal storage from day one |

Splitting them means a future corpus update never costs the user their progress.

## Pipeline decisions

- **`WITHOUT ROWID`** on `sense`, `form`, `relation` — they are pure key→value
  maps, and on this row count it is the single largest size lever available.
- **Tags are stored, never applied at build time.** An A1 learner and a C2
  learner read the same rows and filter differently at query time. Dropping
  `obsolete` at build time would destroy capability the upper levels need.
- **Duplicate `(headword, pos)` records are merged, not ignored.** 5,213 of the
  first 40,000 records collide. `INSERT OR IGNORE` alone dropped them and left
  their senses and forms orphaned against an id that was never written — those
  words then returned nothing at all.
- **The build must pass five integrity checks before the database ships.**
  Four failed on the first run. See `tools/build_dictionary.py::report`.

## Open questions, recorded not decided

- Whether to ship `name` entries (200,625 records). Proper names are arguably
  not English vocabulary, but the brief is "every English word". Measured, not
  assumed — the size cost is in `docs/DATA.md`.
- Etymology is the largest single text column. It is also the least reliable
  field in the source. Currently carried; the decision is pending evidence.