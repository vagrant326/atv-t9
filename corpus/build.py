#!/usr/bin/env python3
"""Builds the T9 dictionary the keyboard ships.

    python3 build.py --language pl --words 120000 --title-weight 20

Reads everything in raw/ for the language and writes
app/src/main/assets/dictionary-<language>.bin.

Unlike the character tables in the sibling repositories this really is a finished structure
rather than counts: a front-coded, key-sequence-ordered word list cannot be rebuilt on the
device in the time a keyboard has to start. The encoder here and the reader in
`core/.../Dictionary.kt` are therefore two implementations of one format, and the round-trip
test in `DictionaryTest` is what keeps them honest.

Format, big-endian:

    magic        4 bytes  "T9D1"
    version      u8       1
    alphabetLen  u8       N, at most 63
    alphabet     N x u16  UTF-16 code units; byte value i+1 encodes alphabet[i]
    wordCount    u32
    indexStep    u16      words between checkpoints
    indexLen     u32
    index        indexLen x u32   offset of each checkpoint, from the start of entries
    entriesLen   u32
    entries      entriesLen bytes

One entry, words in (sequence ascending, count descending) order:

    b0           u8       high nibble shared prefix, low nibble suffix length
                          0xFF escapes to u8 shared, u8 suffixLen
    suffix       suffixLen bytes, each an alphabet index plus one
    score        u8       log-scaled count, 255 most frequent

Every checkpoint word stores a shared prefix of zero, so a search can start there.
"""

import argparse
import math
import os
import struct
import sys
from collections import Counter

from alphabet import ALPHABETS

HERE = os.path.dirname(__file__)
DEFAULT_RAW = os.path.join(HERE, "raw")
DEFAULT_ASSETS = os.path.join(HERE, os.pardir, "app", "src", "main", "assets")

MAGIC = b"T9D1"
VERSION = 1
INDEX_STEP = 32
ESCAPE = 0xFF

# ITU E.161, and it must agree with core/.../Keypad.kt character for character. The Polish
# letters fold onto their base key: that is what the dictionary is for.
KEYS = {
    "2": "abcąć",
    "3": "defę",
    "4": "ghi",
    "5": "jklł",
    "6": "mnońó",
    "7": "pqrsś",
    "8": "tuv",
    "9": "wxyzźż",
}
DIGIT = {letter: digit for digit, letters in KEYS.items() for letter in letters}


def sequence_of(word: str) -> str | None:
    digits = []
    for character in word:
        digit = DIGIT.get(character)
        if digit is None:
            return None
        digits.append(digit)
    return "".join(digits)


def count_words(language: str, raw: str, title_weight: int) -> Counter:
    """Counts spellable word types across every raw file for the language.

    Words carrying anything the keypad cannot reach are dropped whole rather than repaired.
    Stripping the apostrophe out of `don't` would file it under the sequence for `dont` and
    then hand the user back a word with a mark they never typed and cannot delete in one
    press. English contractions are consequently missing from v1; the fix is an implicit
    apostrophe on key 1, the way Tegic did it, and it is a format change rather than a tweak.
    """
    sources = sorted(
        os.path.join(raw, name)
        for name in os.listdir(raw)
        if name.endswith(f"-{language}.txt")
    )
    if not sources:
        raise SystemExit(f"no raw text for {language}: run fetch.py first")

    counts = Counter()
    dropped = 0
    for path in sources:
        # Titles are a few percent of the text and the entire workload. startswith, not `in`:
        # "titles-" is a substring of "subtitles-", so a containment test weights everything
        # equally and silently changes no ratio at all.
        weight = title_weight if os.path.basename(path).startswith("titles-") else 1
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                for token in line.split():
                    if sequence_of(token) is None:
                        dropped += 1
                        continue
                    counts[token] += weight

    print(
        f"{language}: {len(counts):,} spellable types from {len(sources)} files"
        f" ({dropped:,} tokens dropped as unspellable)",
        file=sys.stderr,
    )
    return counts


def order(counts: Counter, limit: int) -> list[tuple[str, int]]:
    """Top words by count, then sorted by key sequence with the commonest first inside each.

    The sequence ordering is the format's whole trick: every word a sequence produces ends up
    contiguous, and so does every word it completes to, which turns both of T9's questions into
    a binary search. The count ordering inside a sequence is what makes the first candidate
    right most of the time without any runtime ranking at all.
    """
    chosen = counts.most_common(limit)
    return sorted(chosen, key=lambda item: (sequence_of(item[0]), -item[1], item[0]))


def encode(words: list[tuple[str, int]], alphabet: str) -> tuple[bytes, list[int]]:
    index_of = {letter: position + 1 for position, letter in enumerate(alphabet)}
    highest = max((count for _, count in words), default=1)
    scale = math.log1p(highest) or 1.0

    entries = bytearray()
    checkpoints = []
    previous = ""

    for position, (word, count) in enumerate(words):
        if position % INDEX_STEP == 0:
            checkpoints.append(len(entries))
            shared = 0
        else:
            shared = 0
            limit = min(len(previous), len(word))
            while shared < limit and previous[shared] == word[shared]:
                shared += 1

        suffix = word[shared:]
        if shared >= 15 or len(suffix) >= 15:
            entries.append(ESCAPE)
            entries.append(shared)
            entries.append(len(suffix))
        else:
            entries.append((shared << 4) | len(suffix))
        entries.extend(index_of[letter] for letter in suffix)

        # 0 is never written: a score of zero would be indistinguishable from a word the
        # encoder failed to score, and every word here occurred at least once.
        entries.append(max(1, min(255, 1 + int(254 * math.log1p(count) / scale))))
        previous = word

    return bytes(entries), checkpoints


def write(language: str, words: list[tuple[str, int]], out: str) -> str:
    alphabet = "".join(sorted({letter for word, _ in words for letter in word}))
    if len(alphabet) > 63:
        raise SystemExit(f"alphabet of {len(alphabet)} does not fit the format")

    entries, checkpoints = encode(words, alphabet)

    os.makedirs(out, exist_ok=True)
    target = os.path.join(out, f"dictionary-{language}.bin")
    with open(target, "wb") as handle:
        handle.write(MAGIC)
        handle.write(struct.pack(">BB", VERSION, len(alphabet)))
        for letter in alphabet:
            handle.write(struct.pack(">H", ord(letter)))
        handle.write(struct.pack(">IHI", len(words), INDEX_STEP, len(checkpoints)))
        for offset in checkpoints:
            handle.write(struct.pack(">I", offset))
        handle.write(struct.pack(">I", len(entries)))
        handle.write(entries)

    size = os.path.getsize(target)
    print(
        f"  {os.path.basename(target)}  {size:,} bytes"
        f"  ({len(words):,} words, {size / len(words):.2f} B/word,"
        f" alphabet {len(alphabet)})",
        file=sys.stderr,
    )
    return target


def report(words: list[tuple[str, int]]) -> None:
    """How ambiguous the dictionary is, weighted by how often each word actually occurs.

    Printed because it is the number that decides whether T9 is worth shipping, and it is
    cheap here and expensive to recover later.
    """
    buckets = {}
    for word, count in words:
        buckets.setdefault(sequence_of(word), []).append((word, count))

    total = sum(count for _, count in words)
    first = sum(max(entry[1] for entry in bucket) for bucket in buckets.values())
    collided = sum(len(bucket) for bucket in buckets.values() if len(bucket) > 1)

    print(
        f"  {len(buckets):,} distinct key sequences,"
        f" {collided:,} words share one with another"
        f" ({100 * first / total:.2f}% of weighted use is first choice)",
        file=sys.stderr,
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--language", choices=tuple(ALPHABETS), required=True)
    parser.add_argument("--raw", default=DEFAULT_RAW, help="directory of normalised text")
    parser.add_argument("--out", default=DEFAULT_ASSETS, help="where to write the dictionary")
    parser.add_argument(
        "--words",
        type=int,
        default=120_000,
        help="how many word types to keep, commonest first",
    )
    parser.add_argument(
        "--title-weight",
        type=int,
        default=20,
        help="how many times title text counts, to set the domain mix deliberately",
    )
    arguments = parser.parse_args()

    counts = count_words(arguments.language, arguments.raw, arguments.title_weight)
    words = order(counts, arguments.words)
    if not words:
        raise SystemExit("no words survived: check the raw text")
    report(words)
    write(arguments.language, words, arguments.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
