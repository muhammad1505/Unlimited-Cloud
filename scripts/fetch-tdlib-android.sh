#!/usr/bin/env bash
# Fetches prebuilt TDLib JSON natives (libtdjson.so) for Android.
#
# TDLib is not published for Android on Maven Central and building it locally
# needs the NDK plus hours of CPU, so the release build downloads a pinned,
# checksum-verified archive instead. The binaries come from the up9cloud /
# android-libtdjson "libs" asset (TDLib built with the JSON C interface only,
# statically linked STL, 16 KB page aligned for Android 15+).
#
# Usage: scripts/fetch-tdlib-android.sh [abi...]
#   Default ABI: arm64-v8a (the only one the release APK ships)
#   Pass x86_64 to also support 64-bit emulators.
set -euo pipefail

TDLIB_VERSION="1.8.65"
ARCHIVE_URL="https://github.com/up9cloud/android-libtdjson/releases/download/v${TDLIB_VERSION}/libs.tar.gz"
ARCHIVE_SHA256="cb92b2dd62489f016c92e26f604c1658d87b0c3bbb56ca56ced6870def05f401"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/androidApp/src/main/jniLibs"
CACHE="${TDLIB_CACHE_DIR:-${TMPDIR:-/tmp}/unlimited-cloud-tdlib}"
ARCHIVE="$CACHE/libs-${TDLIB_VERSION}.tar.gz"

if [ "$#" -gt 0 ]; then
    ABIS=("$@")
else
    ABIS=(arm64-v8a)
fi

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    else
        shasum -a 256 "$1" | cut -d' ' -f1
    fi
}

mkdir -p "$CACHE"
if [ ! -f "$ARCHIVE" ]; then
    echo "Downloading TDLib ${TDLIB_VERSION} Android natives..."
    curl -fsSL -o "$ARCHIVE.part" "$ARCHIVE_URL"
    mv "$ARCHIVE.part" "$ARCHIVE"
fi

actual_sha="$(sha256_of "$ARCHIVE")"
if [ "$actual_sha" != "$ARCHIVE_SHA256" ]; then
    echo "::error::Checksum mismatch for $ARCHIVE (got $actual_sha, expected $ARCHIVE_SHA256)" >&2
    rm -f "$ARCHIVE"
    exit 1
fi
echo "Checksum OK: $actual_sha"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

for abi in "${ABIS[@]}"; do
    tar -xzf "$ARCHIVE" -C "$TMP" "libs/${abi}/libtdjson.so"
    mkdir -p "$DEST/$abi"
    cp "$TMP/libs/${abi}/libtdjson.so" "$DEST/$abi/libtdjson.so"
    chmod 644 "$DEST/$abi/libtdjson.so"
    echo "installed jniLibs/$abi/libtdjson.so ($(du -h "$DEST/$abi/libtdjson.so" | cut -f1))"
done

echo "TDLib Android natives ready in androidApp/src/main/jniLibs"
