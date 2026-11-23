#!/usr/bin/env python3
"""Assert the packaged APK requests only the permissions the app actually needs.

Play Protect flags privileged or unnecessary permissions, so this reads the
*built* APK rather than the source manifest and looks only at what the app
requests: the `uses-permission` and `uses-permission-sdk-23` elements.

Scanning the raw manifest bytes for permission names is not good enough: a
receiver can carry `android:permission="android.permission.DUMP"` to restrict
who may trigger it (WorkManager's diagnostics receiver and profileinstaller
both do), which requests nothing yet reads exactly like a privileged request.

Usage: scripts/check-apk-permissions.py <apk> [...]
"""

from __future__ import annotations

import struct
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

# Chunk types from Android's binary XML format (frameworks/base/libs/androidfw).
RES_STRING_POOL_TYPE = 0x0001
RES_XML_TYPE = 0x0003
RES_XML_START_ELEMENT_TYPE = 0x0102

TYPE_STRING = 0x03
UTF8_FLAG = 0x00000100

PERMISSION_ELEMENTS = {"uses-permission", "uses-permission-sdk-23"}


def _u16(data: bytes, offset: int) -> int:
    return struct.unpack_from("<H", data, offset)[0]


def _u32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def _string_pool(data: bytes, start: int) -> list[str]:
    header_size = _u16(data, start + 2)
    count = _u32(data, start + 8)
    flags = _u32(data, start + 16)
    strings_start = _u32(data, start + 20)
    offsets_at = start + header_size
    base = start + strings_start
    utf8 = bool(flags & UTF8_FLAG)

    strings: list[str] = []
    for index in range(count):
        cursor = base + _u32(data, offsets_at + 4 * index)
        if utf8:
            # One or two length bytes; the second length counts UTF-16 units and
            # is only present for backwards compatibility.
            length = data[cursor]
            cursor += 1
            if length & 0x80:
                length = ((length & 0x7F) << 8) | data[cursor]
                cursor += 1
            cursor += 1 if not data[cursor] & 0x80 else 2
            strings.append(data[cursor:cursor + length].decode("utf-8", "replace"))
        else:
            length = _u16(data, cursor)
            cursor += 2
            if length & 0x8000:
                length = ((length & 0x7FFF) << 16) | _u16(data, cursor)
                cursor += 2
            strings.append(data[cursor:cursor + length * 2].decode("utf-16-le", "replace"))
    return strings


def apk_permissions(apk: Path) -> set[str]:
    with zipfile.ZipFile(apk) as archive:
        data = archive.read("AndroidManifest.xml")

    if _u16(data, 0) != RES_XML_TYPE:
        raise ValueError("AndroidManifest.xml is not Android binary XML")

    header_size = _u16(data, 2)
    strings: list[str] = []
    permissions: set[str] = set()

    offset = header_size
    while offset + 8 <= len(data):
        chunk_type = _u16(data, offset)
        chunk_header = _u16(data, offset + 2)
        chunk_size = _u32(data, offset + 4)
        if chunk_size < 8 or offset + chunk_size > len(data):
            break
        if chunk_type == RES_STRING_POOL_TYPE and not strings:
            strings = _string_pool(data, offset)
        elif chunk_type == RES_XML_START_ELEMENT_TYPE:
            element = strings[_u32(data, offset + 20)]
            if element in PERMISSION_ELEMENTS:
                attr_start = _u16(data, offset + 24)
                attr_size = _u16(data, offset + 26)
                attr_count = _u16(data, offset + 28)
                cursor = offset + chunk_header + attr_start
                for _ in range(attr_count):
                    name = strings[_u32(data, cursor + 4)]
                    data_type = data[cursor + 15]
                    value_index = _u32(data, cursor + 16)
                    if name == "name" and data_type == TYPE_STRING:
                        permissions.add(strings[value_index].rsplit(".", 1)[-1])
                    cursor += attr_size
        offset += chunk_size

    return permissions


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

        try:
            permissions = apk_permissions(apk)
        except (KeyError, ValueError, struct.error) as error:
            print(f"[FAILED] cannot read permissions from {apk.name}: {error}", file=sys.stderr)
            failed = True
            continue

        if not permissions:
            print(f"[FAILED] no requested permissions found in {apk.name}", file=sys.stderr)
            failed = True
            continue

        unexpected = sorted(permissions - ALLOWED)
        print(f"{apk.name}: {', '.join(sorted(permissions)) or '(none)'}")
        if unexpected:
            print(
                f"  [FAILED] unexpected permissions: {', '.join(unexpected)}",
                file=sys.stderr,
            )
            failed = True

    if failed:
        return 1
    print("[PASSED] APK permissions match the allow list")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
