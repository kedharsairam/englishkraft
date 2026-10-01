# DATA.md — measured database facts

Every number here was read out of a real build. Nothing is an estimate.
Regenerate with:

```bash
python3 tools/build_dictionary.py --report-only --out dictionary.db
```

## Totals

| | |
|---|---|
| entries (headword × part of speech) | **1,456,903** |
| senses | **1,745,189** |
| inflected forms | **880,940** |
| relations | **270,728** — 98,301 from Wiktionary, 172,447 from WordNet |
| senses carrying a usage tag | 1,217,027 |
| senses with an attested example | 366,506 |
| entries with an IPA transcription | 129,301 |
| entries with etymology text | 519,738 |
| WordNet senses matched | 145,695 |

## Taxonomy coverage

The Open English WordNet 2025 edition is imported by `tools/import_wordnet.py`.

| | |
|---|---|
| synsets | 107,519 |
| lexical entries | 135,969 |
| relations resolved end-to-end | **172,447** |
| entries with a WordNet gloss | 96,796 (71.2% of WordNet lemmas) |
| entries with **both** a Wiktionary and a WordNet gloss | **96,653** |
| relations **both** sources record independently | **3,140** |
| synsets with at least one member present in our DB | 93,495 (87.0%) |

Two numbers here are deliberate and worth explaining:

**71.2%, not 100%.** 38,513 WordNet lemmas have no matching entry. 34,901 of
those words are absent from Wiktionary's English dump altogether, and they are
overwhelmingly hyphenated compounds and date-like strings — `1-dodecanol`,
`1530s`, `11 November`, `.22-caliber`. Only 108 are numerals. Padding the
database with them buys little; they are left out and the coverage is stated.

The remaining 3,612 exist in our database under a *different* part of speech —
WordNet files `0` as an adjective, Wiktionary as a noun. **Matching these
word-only would fabricate taxonomy links**, which is exactly the wrong data this
project must not ship. Strict part-of-speech matching is kept, and the shortfall
is reported instead of hidden.

Allowing word-only matching for relation *targets* was measured: it recovers
1,935 relations, +1.0%. Not worth the risk, so it was not done.

**3,140 corroborated relations.** `src` is part of the relation key precisely so
a link both sources record is kept twice. The app can then say two independent
sources agree — the only corroboration available at this corpus size.

WordNet states a relation from the general side only, so `spaniel`'s hyponyms are
stored directly while its hypernym is derived by reverse lookup. Verified:
`cocker spaniel → spaniel → gun dog → hunting dog`.

## Size

| | |
|---|---|
| `dictionary.db` on disk | **427.3 MB** |
| gzipped — what the APK carries | **165.4 MB** |
| build time, full extract | 86 s (+8 s WordNet import) |
| total device footprint (APK + extracted copy) | ~590 MB |

Per-table share:

| table | MB | % |
|---|---|---|
| sense | 180.7 | 42.3% |
| entry | 114.0 | 26.7% |
| form | 27.5 | 6.4% |
| relation | 8.9 | 2.1% |
| wn_sense | 26.6 | 6.2% |

Text volume, which is where the size actually lives:

| column | rows | MB of text |
|---|---|---|
| gloss | 1,745,189 | 76.5 |
| example | 366,506 | 54.3 |
| etymology | 519,738 | 46.3 |
| headword | 1,456,903 | 14.8 |
| headword_lc | 1,456,903 | 14.8 |
| pos | 1,456,903 | 5.4 |
| ipa | 129,301 | 1.4 |

### Measured cost of each cut, if the size ever needs to come down

| configuration | raw | APK |
|---|---|---|
| as built — everything | 404 MB | **155.7 MB** |
| − etymology | ~350 MB | ~140 MB |
| − names (`pos='name'`, 200,625 entries) | ~384 MB | ~147 MB |
| − names − etymology | ~336 MB | ~133 MB |
| − names − etymology − examples | ~276 MB | ~105 MB |

## Build notes

- **35,933 records** collided on `(headword, pos)` and were merged into the
  existing entry rather than dropped. Dropping them left senses and forms
  pointing at an id that was never written, so those words returned nothing.
- **42,047 senses** were skipped because the source listed them without a gloss.
  Counted, not silently dropped: 1,787,236 in the extract, 1,745,189 shipped.
- The source itself repeats identical gloss text at different sense numbers
  (`accretion`/noun has senses 2 and 3 byte-identical). Preserved as-is: the
  sense numbers carry the citations, and correcting them would be guessing.

## Source data quality, measured

- **69.7% of senses in the extract carry a register or quality tag**, which is
  what lets the app show an A1 learner none of the `obsolete` / `archaic` /
  `vulgar` senses while a C2 learner sees all of them. Verified working:
  `thee` → `archaic, dialectal`, `knight` → `historical`, `ye` → `archaic`.
- **Inflection resolution verified** on real forms: `running` → run,
  `wolves` → wolf, `wrote` → write, `better` → good / well, `mice` → mouse.

## Integrity: 10/10 checks, each verified to be able to fail

```
PASS  entry ids contiguous
PASS  every sense has an entry
PASS  every form has an entry
PASS  every relation has an entry
PASS  no duplicate (headword_lc,pos)
PASS  lookup index present
PASS  no serialised source object in any word column
PASS  every headword is non-empty
PASS  every sense has a gloss
PASS  relation targets that resolve to an entry (reported, not enforced)
```

Six defects were caught this way, and three of the checks themselves were wrong
before they were right. Recorded in `tools/build_dictionary.py` next to the
check, because the reasoning is the point:

| bug | how it was found |
|---|---|
| 35,933 entries silently dropped, senses orphaned | `every sense has an entry` |
| 106,916 relation rows containing `{'word': 'pack', 'source': ...}` | `no serialised source object in any word column` |
| build wrote 0 rows (commit removed by an earlier edit) | row counts, not a check |
| check rejected 1,106 valid `(UK, Ireland)` glosses | positive test on real data |
| check rejected 92 valid `{1, 2, 3}` integer-set glosses | positive test on real data |
| check `LIKE '%_%'` matched every row — `_` is a LIKE wildcard | injection test |
| WordNet synset representative taken as `members[0]`, dropping 10,067 relations | `spaniel` had no hypernym |

That last one is the reason to spot-check real words rather than trust a count.
The import reported 162,380 relations and no error. The number was only wrong
because a synset listed `sporting dog` before `gun dog`, and `sporting dog` is
absent from Wiktionary — so every relation through that synset was dropped.
`synset m` picks the first member that *resolves*, not the first that exists.

A check that cannot fail is worse than no check, and a check that misfires gets
deleted. Each one was tested in both directions.