#!/usr/bin/env python3
"""Verify the version chain stays consistent before CI builds a release.

Checks:
1. ``unlimitedcloud.versionName`` is MAJOR.MINOR.PATCH.
2. The newest release heading in CHANGELOG.md matches that version.
3. No screen or TDLib parameter hardcodes a version any more; both read the
   generated ``AppBuildInfo`` so a bump cannot leave stale strings behind.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROPERTIES = ROOT / "gradle.properties"
CHANGELOG = ROOT / "CHANGELOG.md"
SETTINGS_SCREEN = (
    ROOT / "composeApp/src/commonMain/kotlin/com/unlimitedcloud/ui/SettingsScreen.kt"
)
TDLIB_ENGINE = (
    ROOT / "composeApp/src/commonMain/kotlin/com/unlimitedcloud/tdjson/TdLibTelegramEngine.kt"
)
VERSION_PATTERN = re.compile(r"^unlimitedcloud\.versionName=(.+)$", re.MULTILINE)
SEMVER = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")
RELEASE_HEADING = re.compile(r"^##\s+(?:\[)?(\d+\.\d+\.\d+)(?:\])?")

errors: list[str] = []


def read_version() -> str:
    match = VERSION_PATTERN.search(PROPERTIES.read_text(encoding="utf-8"))
    if not match:
        errors.append("gradle.properties has no unlimitedcloud.versionName entry")
        return ""
    version = match.group(1).strip()
    if not SEMVER.match(version):
        errors.append(f"versionName '{version}' is not MAJOR.MINOR.PATCH")
    return version


def read_newest_changelog_version() -> str | None:
    for line in CHANGELOG.read_text(encoding="utf-8").splitlines():
        match = RELEASE_HEADING.match(line)
        if match:
            return match.group(1)
    return None


def check_no_hardcoded_versions() -> None:
    settings = SETTINGS_SCREEN.read_text(encoding="utf-8")
    if re.search(r"Versi\s+\d+\.\d+\.\d+", settings):
        errors.append(
            "SettingsScreen.kt hardcodes a version; use AppBuildInfo.VERSION_NAME"
        )
    engine = TDLIB_ENGINE.read_text(encoding="utf-8")
    if re.search(r'"application_version"\s*,\s*"\d', engine):
        errors.append(
            "TdLibTelegramEngine.kt hardcodes application_version; use AppBuildInfo.VERSION_NAME"
        )


def main() -> None:
    version = read_version()
    if version:
        newest = read_newest_changelog_version()
        if newest is None:
            errors.append("CHANGELOG.md has no '## MAJOR.MINOR.PATCH' release heading")
        elif newest != version:
            errors.append(
                f"CHANGELOG.md newest release is {newest} but gradle.properties has {version}"
            )
        else:
            major, minor, patch = (int(part) for part in version.split("."))
            code = major * 10_000 + minor * 100 + patch
            print(f"version {version} -> versionCode {code}")

    check_no_hardcoded_versions()

    if errors:
        print("[FAILED] version check:", file=sys.stderr)
        for error in errors:
            print(f" - {error}", file=sys.stderr)
        raise SystemExit(1)

    print("[PASSED] versions, changelog and generated build info agree")


if __name__ == "__main__":
    main()
