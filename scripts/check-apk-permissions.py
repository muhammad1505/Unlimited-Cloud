#!/usr/bin/env python3
"""Assert the packaged APK declares only the permissions the app actually needs.

Play Protect flags privileged or unnecessary permissions, and `tools:node="remove"`
quietly stops working when a dependency starts declaring the permission through a
different element (``uses-permission-sdk-23`` did exactly that for ``DUMP``). This
reads the *built* APK, so the check cannot drift from what users install.

Usage: scripts/check-apk-permissions.py <apk> [...]
"""

from __future__ import annotations

import re
import sys
import zipfile
from pathlib import Path

# INTERNET/ACCESS_NETWORK_STATE: TDLib. The rest come from WorkManager's own
# manifest and are required for the opt-in background gallery sync.
ALLOWED = {
    "INTERNET",
    "ACCESS_NETWORK_STATE",
    "WAKE_LOCK",
    "RECEIVE_BOOT_COMPLETED",
    "FOREGROUND_SERVICE",
    "FOREGROUND_SERVICE_DATA_SYNC",
    "BIND_JOB_SERVICE",
    "POST_NOTIFICATIONS",
}

PERMISSION = re.compile(r"android\.permission\.([A-Z_]+)")


def apk_permissions(apk: Path) -> set[str]:
    with zipfile.ZipFile(apk) as archive:
        blob = archive.read("AndroidManifest.xml")
    text = blob.decode("latin-1") + blob.decode("utf-16-le", errors="ignore")
    return set(PERMISSION.findall(text))


def main(paths: list[str]) -> int:
    if not paths:
        print("[FAILED] no APK given", file=sys.stderr)
        return 1

    failed = False
    for raw in paths:
        apk = Path(raw)
        if not apk.exists():
            print(f"[FAILED] {apk} does not exist", file=sys.stderr)
            failed = True
            continue

        permissions = apk_permissions(apk)
        unexpected = sorted(permissions - ALLOWED)
        print(f"{apk.name}: {', '.join(sorted(permissions)) or '(none)'}")
        if unexpected:
            print(
                f"  [FAILED] unexpected permissions: {', '.join(unexpected)}",
                file=sys.stderr,
            )
            failed = True
        if "DUMP" in permissions:
            print("  [FAILED] DUMP is still merged in; extend the manifest removal", file=sys.stderr)
            failed = True

    if failed:
        return 1
    print("[PASSED] APK permissions match the allow list")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
