#!/usr/bin/env python3
"""Print where a permission string sits inside a packaged APK manifest.

Binary XML keeps its strings in a pool, so a permission that survives the
manifest merger can be found by offset even when the merger report says
nothing. Used by CI's failure path in the APK permission check.

Usage: scripts/apk-permission-context.py <apk> [...] [--permission NAME]
"""

from __future__ import annotations

import sys
import zipfile
from pathlib import Path

DEFAULT_PERMISSION = "android.permission.DUMP"


def main(argv: list[str]) -> int:
    permission = DEFAULT_PERMISSION
    if "--permission" in argv:
        index = argv.index("--permission")
        permission = argv[index + 1]
        del argv[index:index + 2]
    if not argv:
        print("[FAILED] no APK given", file=sys.stderr)
        return 1

    needle = permission.encode()
    for raw in argv:
        apk = Path(raw)
        if not apk.exists():
            print(f"[FAILED] {apk} does not exist", file=sys.stderr)
            continue
        with zipfile.ZipFile(apk) as archive:
            blob = archive.read("AndroidManifest.xml")
        offset = blob.find(needle)
        print(f"{apk.name}: {len(blob)} bytes, {permission} at {offset}")
        if offset >= 0:
            print(blob[max(0, offset - 60):offset + 80])
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
