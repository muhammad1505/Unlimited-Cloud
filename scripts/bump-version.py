#!/usr/bin/env python3
"""Bump the app version and start its CHANGELOG entry.

Usage:
    python3 scripts/bump-version.py 1.2.0
    python3 scripts/bump-version.py 1.2.0 --date "22 November 2026"
    python3 scripts/bump-version.py 1.2.0 --notes "Ringkasan perubahan"

The script only touches two human-editable files:

* ``gradle.properties`` - the single version source read by every Gradle module.
* ``CHANGELOG.md`` - the source bundled into the in-app changelog and the
  post-update dialog, and reused for GitHub release notes.

The Android versionCode is never stored: Gradle derives it as
``major * 10000 + minor * 100 + patch`` so it always grows with the version
name and cannot drift.
"""

from __future__ import annotations

import argparse
import re
import sys
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROPERTIES = ROOT / "gradle.properties"
CHANGELOG = ROOT / "CHANGELOG.md"
VERSION_PATTERN = re.compile(r"^unlimitedcloud\.versionName=(.+)$", re.MULTILINE)
SEMVER = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")
RELEASE_HEADING = re.compile(r"^##\s+(?:\[)?(\d+\.\d+\.\d+)(?:\])?")


def fail(message: str) -> "NoReturn":
    print(f"bump-version: {message}", file=sys.stderr)
    raise SystemExit(1)


def version_code(version: str) -> int:
    major, minor, patch = (int(part) for part in version.split("."))
    return major * 10_000 + minor * 100 + patch


def bump_properties(version: str) -> str:
    text = PROPERTIES.read_text(encoding="utf-8")
    match = VERSION_PATTERN.search(text)
    if not match:
        fail("gradle.properties has no unlimitedcloud.versionName entry")
    previous = match.group(1).strip()
    if SEMVER.match(previous):
        if tuple(int(p) for p in previous.split(".")) >= tuple(
            int(p) for p in version.split(".")
        ):
            fail(
                f"new version {version} must be greater than the current {previous}"
            )
    PROPERTIES.write_text(
        VERSION_PATTERN.sub(f"unlimitedcloud.versionName={version}", text),
        encoding="utf-8",
    )
    return previous


def bump_changelog(version: str, released: str, notes: str | None) -> None:
    text = CHANGELOG.read_text(encoding="utf-8")
    if any(match.group(1) == version for match in map(RELEASE_HEADING.match, text.splitlines()) if match):
        fail(f"CHANGELOG.md already has a {version} release")

    body = [f"## {version} — {released}", ""]
    if notes:
        body += ["### Baru", ""] + [f"- {line.strip()}" for line in notes.splitlines() if line.strip()]
    else:
        body += [
            "<!-- Tambahkan poin perubahan untuk pengguna, satu baris diawali \"- \". -->",
            "",
            "### Baru",
        ]
    body += ["", "### Perbaikan", "", ""]
    entry = "\n".join(body)

    lines = text.splitlines(keepends=True)
    insert_at = None
    in_fence = False
    for index, line in enumerate(lines):
        if line.strip().startswith("```"):
            in_fence = not in_fence
            continue
        # Skip the documented example block so a new release never lands inside it.
        if not in_fence and line.startswith("## "):
            insert_at = index
            break

    if insert_at is None:
        if lines and not lines[-1].endswith("\n"):
            lines[-1] += "\n"
        lines.append("\n" + entry)
    else:
        lines.insert(insert_at, entry)
    CHANGELOG.write_text("".join(lines), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version", help="MAJOR.MINOR.PATCH, e.g. 1.2.0")
    today = date.today()
    parser.add_argument(
        "--date",
        default=f"{today.day} {today.strftime('%B %Y')}",
        help="release date shown in the changelog (default: today)",
    )
    parser.add_argument(
        "--notes",
        default=None,
        help="extra notes; each line becomes a bullet under 'Baru'",
    )
    args = parser.parse_args()

    if not SEMVER.match(args.version):
        fail(f"'{args.version}' is not MAJOR.MINOR.PATCH")

    previous = bump_properties(args.version)
    bump_changelog(args.version, args.date, args.notes)

    print(f"version {previous} -> {args.version} (versionCode {version_code(args.version)})")
    print("next: edit the 'Baru' / 'Perbaikan' sections of CHANGELOG.md, then")
    print(f"      git tag v{args.version} after the release build on main succeeds")


if __name__ == "__main__":
    main()
