#!/usr/bin/env python3
"""A GitHub release's notes: its highlights, then its CHANGELOG.md section.

    python3 scripts/release_notes.py 0.5.0 [--changelog FILE] [--highlights-dir DIR]

docs/releases/<version>.md, when there is one, opens the notes: the release's
main points for a reader, in English with the Russian under a "🇷🇺 Что нового"
<details> block (docs/RELEASING.md). Under it, "📋 Changelog" and the section
scripts/changelog_section.py prints. With no highlights file the notes are the
section alone. Exits 1 when the section is missing or empty.
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from changelog_section import section  # noqa: E402


def notes(version: str, changelog: str, highlights: str) -> str:
    found = section(changelog, version)
    if found is None or not found[1].strip():
        raise ValueError(f"no [{version}] section, or an empty one")
    body = found[1].strip()
    if not highlights.strip():
        return body
    return highlights.strip() + "\n\n## 📋 Changelog\n\n" + body


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("version")
    ap.add_argument("--changelog", default="CHANGELOG.md")
    ap.add_argument("--highlights-dir", default=os.path.join("docs", "releases"))
    args = ap.parse_args()
    version = args.version.lstrip("v")
    with open(args.changelog, encoding="utf-8") as f:
        changelog = f.read()
    path = os.path.join(args.highlights_dir, version + ".md")
    highlights = open(path, encoding="utf-8").read() if os.path.exists(path) else ""
    try:
        print(notes(version, changelog, highlights))
    except ValueError as e:
        print(f"{args.changelog}: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
