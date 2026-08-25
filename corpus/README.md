# Corpus

Two scripts and no data. `fetch.py` downloads the text, `build.py` turns it into the dictionary
the app ships. `raw/` is gitignored: the corpora are large, and their licensing is a good deal
less clear than a word-frequency table derived from them.

```bash
python3 fetch.py --language pl --megabytes 250
python3 build.py --language pl --words 120000 --title-weight 20
```

`build.py` writes `../app/src/main/assets/dictionary-<language>.bin`, which **is** committed. The
dictionary is a finished structure rather than counts, unlike the character tables in the sibling
repositories — a front-coded, key-sequence-ordered word list is not something the device can
rebuild in the time a keyboard has to start.

## Sources, and why these two

**OpenSubtitles** supplies running speech. It teaches the dictionary ordinary vocabulary and,
more importantly, the frequencies that decide which word a key sequence offers first.

**Wikidata** supplies film, series, actor and musician labels — CC0, and the workload the
keyboard actually faces. Statistics from running text generalise worst exactly here, which is why
`--title-weight` exists: it sets the domain mix deliberately rather than accepting whatever the
download happened to contain. At weight 1 a few hundred thousand title tokens vanish under
several million tokens of speech and change no ranking at all.

Hunspell and sjp.pl would both give a larger Polish vocabulary and are **deliberately not used**:
GPL/LGPL/MPL against this project's MIT. A table of word frequencies derived from a corpus is a
set of facts; a vendored word list is somebody's work.

## Why the format is what it is

Words are sorted by **key sequence**, not alphabetically. Everything follows from that:

- Every word a sequence spells is contiguous, so exact matches are a binary search.
- Every word it completes to is contiguous with them, so predictive completion is the same
  search and a forward scan.
- Sorting by count *inside* a sequence means the first candidate is right most of the time with
  no ranking work at runtime at all.

Sorted alphabetically the same file would need a separate digit index roughly its own size again.

Letters are stored as alphabet indices rather than UTF-8, which is worth about 15% on Polish on
its own: `ą ć ę ł ń ó ś ź ż` are two bytes each in UTF-8 and one here. Front coding against the
previous word removes most of the rest. A checkpoint every 32 words stores a zero shared prefix,
which is what lets a binary search start in the middle of the file.

## What the sizes came out at

Measured on 250 MB of OpenSubtitles per language plus the Wikidata labels, 2026-08-25. The
`words` column is what `--words` was set to; the rest is the file on disk.

Run `build.py` and it prints the same numbers for whatever corpus you gave it, including the
share of weighted use that lands on the first candidate — which is the figure that decides
whether T9 is worth shipping, and it is cheap here and expensive to recover later.

## Two things to know before trusting a figure from this

**The corpus is the ceiling, not `--words`.** At 40 MB per language the vocabulary saturated well
below 150k types for Polish, so asking for 200k silently gave fewer. Check the "spellable types"
line `build.py` prints before believing a word count.

**Held-out coverage is not workload coverage.** Splitting subtitles and testing on the held-out
part measures how well the dictionary covers *more subtitles*. The keyboard's real input is
`../bench/queries-v1.tsv`, which is mostly proper nouns and deliberately contains
out-of-dictionary channel names. `./gradlew :core:bench` is the figure that counts.
