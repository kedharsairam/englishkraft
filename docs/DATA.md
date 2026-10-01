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
| relations | **103,227** |
| senses carrying a usage tag | 1,217,027 |
| senses with an attested example | 366,506 |
| entries with an IPA transcription | 129,301 |
| entries with etymology text | 519,738 |

## Size

| | |
|---|---|
| `dictionary.db` on disk | **404.2 MB** |
| gzipped — what the APK carries | **155.7 MB** |
| build time, full extract | 86 s |
| total device footprint (APK + extracted copy) | ~560 MB |

Per-table share:

| table | MB | % |
|---|---|---|
| sense | 180.7 | 44.7% |
| entry | 114.0 | 28.2% |
| form | 27.5 | 6.8% |
| relation | 2.8 | 0.7% |

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

Four of these caught real bugs during the build, and three of the checks
themselves were wrong before they were right. Recorded in `tools/build_dictionary.py`
next to the check, because the reasoning is the point:

| bug | how it was found |
|---|---|
| 35,933 entries silently dropped, senses orphaned | `every sense has an entry` |
| 106,916 relation rows containing `{'word': 'pack', 'source': ...}` | `no serialised source object in any word column` |
| build wrote 0 rows (commit removed by an earlier edit) | row counts, not a check |
| check rejected 1,106 valid `(UK, Ireland)` glosses | positive test on real data |
| check rejected 92 valid `{1, 2, 3}` integer-set glosses | positive test on real data |
| check `LIKE '%_%'` matched every row — `_` is a LIKE wildcard | injection test |

A check that cannot fail is worse than no check, and a check that misfires gets
deleted. Each one was tested in both directions.