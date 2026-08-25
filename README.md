# atv-t9

A T9 keyboard for Android TV. Number keys carry the letters, one press each, and a dictionary
decides which letters they were.

Third application in the programme, after `atv-letterwise` and `atv-h4`. Separate repository,
separate APK, no shared code — see `../docs/00-overview.md` for why.

---

## What is different about this one

Two things, and both come from the television rather than from T9.

**The Polish diacritics are free.** `ą ć ę ł ń ó ś ź ż` fold onto the key their base letter sits
on, so the user never reaches for one and the dictionary restores it. Under multitap `ż` is six
taps of `9` *and* the user has to know it is on `9`; here it is one press. Nothing is printed on
this remote, so a mapping that has to be taught is a mapping that will not be used.

**There is a second dictionary underneath, and it is not optional.** A TV search box is mostly
proper nouns, and a fixed dictionary answers a series title with nothing at all, every time, for
as long as the app is installed. So every committed word is remembered: a title costs its
multitap price once, and one press per letter after that. `docs/00-overview.md` §5 already
records that T9 "degrades badly out of dictionary, and the workload here is largely proper
nouns" — the user dictionary is the answer to that sentence, not a feature bolted on beside it.

What that buys, and what it costs, is measured rather than asserted:

```bash
./gradlew :core:bench
```

## How it types

| Key | What it does |
|---|---|
| `2`–`9` | One press per letter. The strip shows what the presses could mean. |
| `←` `→` | Walk the candidates. Only while a word is in progress — outside one the d-pad belongs to whatever is behind the keyboard. |
| `OK` | Finish the word. With nothing pending, submits the field. |
| `0` | Finish the word and add a space. |
| `1` | Cycle `. , - ' & : /`, replacing in place. |
| hold `2`–`9` | Spell the word out letter by letter, for anything the dictionary lacks. |
| `BACK` | Abandon the word in progress. Otherwise left alone. |

Four keys are assignable from settings, captured from the remote rather than chosen from a list:
show-the-keyboard, spell, delete and switch-language. Remotes disagree about which keys exist and
about what they report — the key this project most wanted turned out to be keycode 300.

**Only the trigger is listened for while the keyboard is hidden**, and it is unassigned by
default. Consuming d-pad events while hidden is what once left a television unnavigable.

## What is remembered, and what is not

The user dictionary holds words and a use count. Not the text they appeared in, not the field
they were typed into, not when. There is nowhere in the format to put anything else.

Nothing is learnt from a password field, a field that set `IME_FLAG_NO_PERSONALIZED_LEARNING`, a
no-suggestions field, or an email or URL field. Learning can also be turned off entirely, and the
field-level refusals apply regardless of that setting.

Everything learnt is listed in **Settings → Your words**, and any of it can be removed there. A
store that cannot be inspected or emptied would make the privacy claim unverifiable, and the
claim is the reason a keyboard is allowed to hold `INTERNET` at all.

## The network permission

`INTERNET` and `REQUEST_INSTALL_PACKAGES` are held for one screen: the updater, which runs in its
own process (`:updater`) so that the component handling keystrokes contains no networking code.
Nothing runs unless the user opens that screen and presses something — no background job, no boot
receiver, no poll at keyboard start.

They exist because sideloading has no update channel. They come out when this ships through a
store, and at that point on-demand language packs become a Play Store mechanism rather than a
socket this app owns. See `../docs/00-overview.md` §3.1.

## Building

```bash
docker compose -f ../docker/compose.yaml run --rm dev ./gradlew assembleDevDebug
```

Two flavours, `prod` and `dev`, and deliberately two *applications*: the dev build carries its own
`applicationId` and installs alongside the released one, so an experiment that misbehaves does not
take the working keyboard with it.

## The dictionaries

Committed as assets, built from a corpus that is not committed:

```bash
python3 corpus/fetch.py --language pl --megabytes 250
python3 corpus/build.py --language pl --words 120000 --title-weight 20
```

OpenSubtitles supplies running speech, Wikidata supplies the film, series and person names that
are the actual workload. `corpus/README.md` has the detail, including what the sizes came out at
and why the word count is where it is.

## Known gaps

- **English contractions are missing.** `don't` carries a mark the keypad cannot reach, so it is
  dropped rather than filed under the sequence for `dont` and handed back with a mark the user
  never typed. The fix is an implicit apostrophe on key `1`, the way Tegic did it, and it is a
  format change rather than a tweak.
- **No word-context ranking.** Candidates are ordered by frequency alone, with the user's own use
  on top. A bigram over the previous committed word would help and has not been measured yet.
- **`bench/queries-v1.tsv` is 26 real queries.** Small on purpose — see the header of that file —
  but small enough that a KSPC figure from it carries wide error bars.

## Licence

MIT. See `LICENSE`.
