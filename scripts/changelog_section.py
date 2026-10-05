#!/usr/bin/env python3
"""Print one release's section of CHANGELOG.md, without its heading.

    python3 scripts/changelog_section.py 0.1.0 [--require-date] [--changelog FILE]

The section runs from its "## [0.1.0] - ..." heading to the next "## "
heading. The release workflow uses the output as the GitHub release's notes.

Exits 1, saying why on stderr, when the section is missing or empty, and with
--require-date also when its heading carries no YYYY-MM-DD date — still
"Unreleased" means the release commit (docs/RELEASING.md) was skipped.
"""
import argparse
import re
import sys


def section(text: str, version: str):
    """The heading line and the body of `version`'s section, or None."""
    lines = text.splitlines()
    heading = re.compile(r"^##\s*\[v?" + re.escape(version) + r"\](?:\s|$)")
    for i, line in enumerate(lines):
        if heading.match(line):
            body = []
            for rest in lines[i + 1:]:
                if rest.startswith("## "):
                    break
                body.append(rest)
            return line, "\n".join(body).strip("\n")
    return None


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("version", help="the version, with or without a leading v")
    ap.add_argument("--changelog", default="CHANGELOG.md")
    ap.add_argument("--require-date", action="store_true",
                    help="fail unless the heading carries a YYYY-MM-DD date")
    args = ap.parse_args()
    version = args.version[1:] if args.version.startswith("v") else args.version

    with open(args.changelog, encoding="utf-8") as f:
        found = section(f.read(), version)
    if found is None:
        print(f"{args.changelog} has no section for [{version}]", file=sys.stderr)
        return 1
    heading, body = found
    if not body.strip():
        print(f"{args.changelog}: the [{version}] section is empty", file=sys.stderr)
        return 1
    if args.require_date and not re.search(r"\b\d{4}-\d{2}-\d{2}\b", heading):
        print(f"{args.changelog}: '{heading}' has no release date; date it in the release commit",
              file=sys.stderr)
        return 1
    print(body)
    return 0


if __name__ == "__main__":
    sys.exit(main())
