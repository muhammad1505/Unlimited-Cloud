#!/usr/bin/env bash
# Fetches the prebuilt TDLib JSON native used by the desktop packages.
#
# The desktop app talks to TDLib through its JSON C API (`libtdjson`). TDLib is
# not published as a jar on Maven Central, so the native is downloaded from a
# pinned, checksum-verified release and placed on the desktop resources
# classpath. `DesktopTdJsonClient` extracts it next to the app at runtime.
#
# Supported hosts: Linux x86_64 (glibc) and Windows x86_64. The CI desktop jobs
# package on those same hosts, so the deb/rpm/msi include the matching native.
#
# Usage: scripts/fetch-tdlib-desktop.sh
set -euo pipefail

TDLIB_VERSION="1.8.71"
RELEASE_TAG="0.1.8.71-f10611e"
BASE_URL="https://github.com/AlexXanderGrib/prebuilt-tdlib/releases/download/${RELEASE_TAG}"

case "$(uname -m)" in
    x86_64|amd64) ;;
    *)
        echo "::error::TDLib desktop natives are only pinned for x86_64 hosts (got $(uname -m))" >&2
        exit 1
        ;;
esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/composeApp/src/desktopMain/resources"
CACHE="${TDLIB_CACHE_DIR:-${TMPDIR:-/tmp}/unlimited-cloud-tdlib-desktop}"

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | cut -d' ' -f1
    else
        echo "::error::no SHA-256 tool (sha256sum or shasum) found" >&2
        exit 1
    fi
}

case "$(uname -s)" in
    Linux)
        ASSET="libtdjson-x64-glibc.so"
        OUTPUT="libtdjson.so"
        SHA256="4b180e09545569ab03db92b5d154d073a45e5ac83a816d50f8c74b655e394c11"
        ;;
    MINGW*|MSYS*|CYGWIN*)
        ASSET="tdjson-x64.dll"
        OUTPUT="tdjson.dll"
        SHA256="13671640557c05426d22f4486ef2aa4924205229fb454c0a782aedb1422ce5ba"
        ;;
    *)
        echo "::error::TDLib desktop natives are only pinned for Linux x86_64 and Windows x86_64" >&2
        echo "Set -Djna.library.path to a local libtdjson instead." >&2
        exit 1
        ;;
esac

mkdir -p "$CACHE" "$DEST"
ARCHIVE="$CACHE/$ASSET"

if [ ! -f "$ARCHIVE" ]; then
    echo "Downloading TDLib ${TDLIB_VERSION} desktop native (${ASSET})..."
    curl -fsSL --retry 3 -o "$ARCHIVE.part" "$BASE_URL/$ASSET"
    mv "$ARCHIVE.part" "$ARCHIVE"
fi

actual_sha="$(sha256_of "$ARCHIVE")"
if [ "$actual_sha" != "$SHA256" ]; then
    echo "::error::Checksum mismatch for $ARCHIVE (got $actual_sha, expected $SHA256)" >&2
    rm -f "$ARCHIVE"
    exit 1
fi
echo "Checksum OK: $actual_sha"

cp "$ARCHIVE" "$DEST/$OUTPUT"
chmod 644 "$DEST/$OUTPUT"
echo "installed composeApp/src/desktopMain/resources/$OUTPUT ($(du -h "$DEST/$OUTPUT" | cut -f1))"
