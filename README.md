# atv-t9

A T9 keyboard for Android TV. Number keys carry the letters, one press each, and a dictionary
decides which letters they were.

Third application in the programme, after
[`atv-letterwise`](https://github.com/vagrant326/atv-letterwise) and
[`atv-h4`](https://github.com/vagrant326/atv-h4), and since joined by
[`atv-multitap`](https://github.com/vagrant326/atv-multitap). Separate repository, separate APK,
no shared code: each one is a whole input method rather than a variation on one, and the part
they would share is the part that differs.

**To install it:** AFTVnews Downloader code **9874066**. That is the dev channel, which is all
there is so far — there is no production release yet. Seven digits on the remote beats typing a
URL with a grid keyboard, which is the problem this project exists to solve.
[Details and the direct link below.](#installing)

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
multitap price once, and one press per letter after that. T9 degrades badly out of dictionary, and
out of dictionary is where this workload lives — so the user dictionary is the answer to that, not
a feature bolted on beside it.

That is also why the benchmark prints two columns. **Cold**, on a query it has never seen, this
method loses to LetterWise; **warm**, once the title has been typed once, it beats everything else
in the programme. Neither figure describes the keyboard on its own, which is why both are printed:

```bash
./gradlew :core:bench
```

## How it types

| Key | What it does |
|---|---|
| `2`–`9` | One press per letter. The strip shows what the presses could mean. |
| `◀` `▶` | Walk the candidates, back and forward. Only while a word is in progress — outside one they fall through to the editor, which moves the caret. |
| `▼` `CH▾` / `CH▴` | The same walk, forward and back, for a remote whose d-pad is awkward. Consumed either way: a one-line field has no caret to move downwards, and a stray `CH` press must not change channel mid-word. |
| `▲` | Delete. Always, and it is the only delete that is never conditional on anything. |
| `OK` | Finish the word. With nothing pending, submits the field. |
| `0` | Finish the word and add a space. |
| `1` | Cycle `. , - ' & : /`, replacing in place. |
| hold `0` | Capitals: `abc` → `Abc` → `ABC`. Word-scoped, because a whole word is what is in flight. |
| hold `1` | Inside a word, spell it out letter by letter, for anything the dictionary lacks. Outside one, all thirty-two QWERTY marks on `2`–`9`, four to a key, for one mark. |
| hold `◀` `▶` | The caret, a word at a time. Outside a word only — inside one the arrows are the candidate walk. |
| hold `▲` | Delete back to the start of the word. |
| `BACK` | Abandon the word in progress. Otherwise left alone. |

`◀`, `▶` and `BACK` fall through to whatever is behind the keyboard when no word is in progress,
and that passthrough is the escape hatch: a keyboard that ate the whole d-pad on a television
leaves the device unnavigable, which is not a hypothetical.

**It types a password** — `Tv!2026` and the like. A password field starts in spelling rather than
in prediction: a password is in no dictionary, so every candidate offered against one is wrong and
the user would pay a hold of `1` per run to escape them. The strip shows one dot per character
instead of the letters, because the field's own masking is worth little while the keyboard prints
the same word across the television. Capitals come from the held `0` and every QWERTY mark from
the held `1`; the digits come from the digit mode, which a numeric field turns on by itself and
which a password field does not, so that is the one function here that wants a button of its own.

Five keys are assignable from settings, captured from the remote rather than chosen from a list:
show-the-keyboard, spell, delete, switch-language and digit mode. The trigger cannot be reached any
other way, because the keyboard is not on screen at the moment it is needed; the middle three are
comforts, since spelling is also a held `1`, `▲` already deletes and the language switch does
nothing with one language enabled. Remotes disagree about which keys exist and about what they
report — the key this project most wanted turned out to be keycode 300.

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
socket this app owns.

## Installing

In the AFTVnews Downloader app, enter code **9874066**. Seven digits on the remote beats entering
a URL with a grid keyboard, which is the problem this project exists to solve.

That code is the **dev channel**, and at the moment it is the whole list: this keyboard has no
production release yet, so there is nothing for a production code to resolve to. When the first
one lands it gets its own code, and it installs *alongside* this build rather than over it — the
two are separate applications.

The address works directly as well. It is permanent and always serves the newest build:

```
https://github.com/vagrant326/atv-t9/releases/download/latest-dev/atv-t9-dev.apk
```

Dev builds are published as prereleases, so they never show up as "Latest" on the releases page —
`latest-dev` always points at the newest one. The asset name deliberately carries no version
number, which is what keeps both the URL and the Downloader code valid across releases; rename the
asset and the code breaks silently.

Then Settings → System → Keyboard, select it, and enable it. Android requires that step manually
for every IME.

**If the keyboard ever leaves the TV unnavigable**, press `HOME` — an IME cannot intercept it —
and switch keyboards or uninstall from there. A USB mouse also always works, because pointer
events never reach the keyboard's key handling.

## Updating

Settings → Check for updates. It compares the installed version against the latest release of its
own channel, downloads the APK and hands it to the system installer. The first time, Android will
ask you to allow this app to install packages; the screen links straight there.

Nothing checks on its own — no background job, no boot receiver, no poll when the keyboard starts.
It happens when you press the button and not otherwise. A dev build will never offer to install a
production APK over itself, or the other way round: each channel matches its own tag prefix.

## Building

```bash
docker compose -f ../docker/compose.yaml run --rm dev ./gradlew assembleDevDebug
```

Two flavours, `prod` and `dev`, and deliberately two *applications*: the dev build carries its own
`applicationId` and installs alongside the released one, so an experiment that misbehaves does not
take the working keyboard with it.

| Branch | What runs | Result |
|---|---|---|
| `feature/**`, `fix/**`, pull requests | CI — tests, lint, both debug APKs | artifacts only |
| `develop` | Release dev | `dev-x.y.z`, installs as **atv-t9 dev** |
| `main` | Release | `vx.y.z`, installs as **atv-t9** |

Production owns the major and the minor; a dev build keeps them and counts the patch from the
last production release, so `dev-0.2.7` is the seventh dev build past `v0.2.0`. Nothing has
shipped to production here yet, so the dev builds are still counting from `0.0`.

Day to day: work on `develop`, which publishes a dev build on every push. To ship, open a pull
request from `develop` to `main` and merge it — `main` does not exist yet in this repository, and
that merge is what creates it. **Do not delete `develop`**; it is long-lived. After merging, bring
it back in line so the next dev release contains the merge:

```bash
git switch develop && git merge --ff-only main && git push
```

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
