"""Alphabets and text normalisation shared by the corpus scripts.

Wider than a keypad keyboard's alphabet, and deliberately so. Under a base-4 code every
character is a code of its own, so punctuation and digits cost a slightly longer code and
nothing else — there is no ambiguous group for them to inflate. That is why they are counted
here rather than being treated as a special case at the far end of the pipeline.

The Polish alphabet keeps its diacritics. Stripping them is the obvious cleanup and it would
be a mistake: `ó` is a character with its own code, and a table trained on text without it
would leave it unreachable.
"""

SPACE = " "

EN_LETTERS = "abcdefghijklmnopqrstuvwxyz"
PL_EXTRA = "ąćęłńóśźż"
DIGITS = "0123456789"

# The same seven marks the other keyboards in the programme cycle on their `1` key. Kept
# identical so the shared query corpus is typable, character for character, in every app.
PUNCTUATION = ".,-'&:/"

ALPHABETS = {
    "en": SPACE + EN_LETTERS + DIGITS + PUNCTUATION,
    "pl": SPACE + EN_LETTERS + PL_EXTRA + DIGITS + PUNCTUATION,
}

# Typographic characters that appear throughout scraped subtitle text. Folding them keeps a
# word whole; leaving them would turn one word into two.
FOLD = {
    " ": " ",  # non-breaking space
    "‘": "'",
    "’": "'",
    "“": '"',
    "”": '"',
    "–": "-",  # en dash
    "—": "-",  # em dash
    "…": "...",
}


def normalise(line: str, language: str) -> str:
    """Lowercase, fold lookalikes, and reduce to the alphabet plus single spaces.

    Characters outside the alphabet become a space rather than vanishing. Dropping them
    would join the letters either side into a sequence that never occurs in real text.
    """
    alphabet = set(ALPHABETS[language])
    folded = "".join(FOLD.get(character, character) for character in line)
    kept = [character if character in alphabet else SPACE for character in folded.lower()]

    out = []
    for character in kept:
        if character == SPACE and (not out or out[-1] == SPACE):
            continue
        out.append(character)
    text = "".join(out).strip()

    # Subtitles mark a change of speaker with a leading dash. Counted as ordinary text it
    # would hand `-` a code far shorter than anyone typing a film title deserves to pay for.
    while text.startswith("- ") or text.startswith("-"):
        text = text[1:].lstrip()
    return text
