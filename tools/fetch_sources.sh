#!/usr/bin/env bash
# Fetch every source the dictionary build needs.
#
# Deliberately not automated beyond the base download: each source carries a
# different licence, and the provenance for each is recorded in docs/sources.md.
# Adding a source here means adding it there too.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA="$HERE/data"
mkdir -p "$DATA"

fetch() {  # fetch <url> <filename> <expected_bytes|->
  local url="$1" name="$2" want="$3"
  if [ -f "$DATA/$name" ]; then
    echo "  have $name"
    return
  fi
  echo "  GET  $name"
  curl -fL --retry 3 --retry-delay 5 -o "$DATA/$name.part" "$url"
  mv "$DATA/$name.part" "$DATA/$name"
  if [ "$want" != "-" ]; then
    local got
    got="$(stat -c%s "$DATA/$name")"
    if [ "$got" != "$want" ]; then
      echo "  SIZE MISMATCH: $name expected $want got $got" >&2
      exit 1
    fi
    echo "  OK   $name ($got bytes, matches expected)"
  else
    echo "  OK   $name ($(stat -c%s "$DATA/$name") bytes)"
  fi
}

# --- Primary: Wiktionary English, extracted by Wiktextract. CC BY-SA 4.0. ---
# 523,338,320 bytes on 2026-10-02. The size is asserted so a truncated or
# HTML-error download cannot pass silently.
fetch "https://kaikki.org/dictionary/English/kaikki.org-dictionary-English.jsonl.gz" \
      "kaikki.org-dictionary-English.jsonl.gz" \
      "523338320"

# --- Taxonomy: Open English WordNet 2025. WordNet Licence + CC BY 4.0. ---
fetch "https://en-word.net/static/english-wordnet-2025.xml.gz" \
      "english-wordnet-2025.xml.gz" \
      "-"

echo
echo "Sources in $DATA:"
ls -la "$DATA" | tail -n +2 | awk '{printf "  %-46s %10.1f MB\n", $9, $5/1048576}'
echo
echo "Next: python3 build_dictionary.py --source $DATA/kaikki.org-dictionary-English.jsonl.gz"