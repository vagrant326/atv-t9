#!/usr/bin/env python3
"""Fetches the training text. Nothing here is committed: only this script is.

Two sources, for two different jobs. OpenSubtitles supplies running speech, which is what
teaches the model ordinary letter sequences. Wikidata supplies film, series and person
names, which is the workload the keyboard actually faces and the place where statistics
from running text generalise worst.

The subtitle download is bounded by default, and here the bound could be far lower than it
is. This project needs single-character frequencies over some fifty symbols, which settle
after a few hundred thousand characters; the budget is generous only so the same raw text can
serve a higher-order model if one is ever wanted.

    python3 fetch.py --language pl --megabytes 40
"""

import argparse
import gzip
import json
import os
import sys
import urllib.parse
import urllib.request

from alphabet import normalise

RAW = os.path.join(os.path.dirname(__file__), "raw")

SUBTITLES = "https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/mono/{language}.txt.gz"

WIKIDATA = "https://query.wikidata.org/sparql"

# One entity kind per request, paged. Two things learned the hard way: a union of kinds
# times out, and so does walking the subclass tree with `wdt:P31/wdt:P279*` - the endpoint
# then returns a truncated body, which arrives as a JSON parse error rather than an HTTP
# error. Direct `P31` costs a few niche films and finishes.
TITLES_QUERY = """
SELECT ?label WHERE {{
  {selector}
  ?item rdfs:label ?label .
  FILTER(LANG(?label) = "{language}")
}}
LIMIT {limit}
OFFSET {offset}
"""

# People are selected by occupation rather than by being human: Q5 has millions of members
# and none of the filtering that makes the result relevant to a TV search box.
SELECTORS = {
    "film": "?item wdt:P31 wd:Q11424 .",
    "series": "?item wdt:P31 wd:Q5398426 .",
    "actor": "?item wdt:P106 wd:Q33999 .",
    "musician": "?item wdt:P106 wd:Q639669 .",
}

USER_AGENT = "atv-t9-corpus/0.1 (https://github.com/vagrant326/atv-t9)"


def fetch_subtitles(language: str, megabytes: int) -> str:
    """Streams the mono corpus and stops at the byte budget, normalising as it goes."""
    url = SUBTITLES.format(language=language)
    budget = megabytes * 1024 * 1024
    written = 0
    target = os.path.join(RAW, f"subtitles-{language}.txt")

    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request) as response:
        with gzip.GzipFile(fileobj=response) as stream:
            with open(target, "w", encoding="utf-8") as out:
                for raw in stream:
                    line = normalise(raw.decode("utf-8", "replace"), language)
                    if len(line) < 2:
                        continue
                    out.write(line + "\n")
                    written += len(line) + 1
                    if written >= budget:
                        break
    print(f"subtitles-{language}.txt  {written / 1024 / 1024:.1f} MB", file=sys.stderr)
    return target


def fetch_page(selector: str, language: str, limit: int, offset: int) -> list[str]:
    query = TITLES_QUERY.format(
        selector=selector, language=language, limit=limit, offset=offset
    )
    url = f"{WIKIDATA}?{urllib.parse.urlencode({'query': query, 'format': 'json'})}"
    request = urllib.request.Request(
        url,
        headers={"User-Agent": USER_AGENT, "Accept": "application/sparql-results+json"},
    )
    with urllib.request.urlopen(request, timeout=180) as response:
        payload = json.load(response)
    return [row["label"]["value"] for row in payload["results"]["bindings"]]


def fetch_titles(language: str, per_page: int, pages: int) -> str:
    """Film, series, actor and musician labels. A failed page is skipped, not fatal."""
    target = os.path.join(RAW, f"titles-{language}.txt")
    total = 0
    with open(target, "w", encoding="utf-8") as out:
        for name, selector in SELECTORS.items():
            kept = 0
            for page in range(pages):
                try:
                    labels = fetch_page(selector, language, per_page, page * per_page)
                except Exception as failure:  # noqa: BLE001 - reported, then skipped
                    print(f"titles {name} {language} page {page}: {failure}", file=sys.stderr)
                    continue
                if not labels:
                    break
                for label in labels:
                    line = normalise(label, language)
                    if len(line) < 2:
                        continue
                    out.write(line + "\n")
                    kept += 1
            total += kept
            print(f"titles {name} {language}: {kept}", file=sys.stderr)
    print(f"titles-{language}.txt  {total} labels", file=sys.stderr)
    return target


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--language", choices=("pl", "en"), required=True)
    parser.add_argument("--megabytes", type=int, default=40)
    parser.add_argument("--titles", type=int, default=10000, help="labels per page")
    parser.add_argument("--pages", type=int, default=3)
    parser.add_argument("--skip-subtitles", action="store_true")
    parser.add_argument("--skip-titles", action="store_true")
    arguments = parser.parse_args()

    os.makedirs(RAW, exist_ok=True)
    if not arguments.skip_subtitles:
        fetch_subtitles(arguments.language, arguments.megabytes)
    if not arguments.skip_titles:
        fetch_titles(arguments.language, arguments.titles, arguments.pages)
    return 0


if __name__ == "__main__":
    sys.exit(main())
