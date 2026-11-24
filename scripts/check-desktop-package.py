#!/usr/bin/env python3
"""Verify that desktop installers really contain TDLib and the brand icon.

jpackage can produce a valid-looking installer while the app still cannot start
TDLib (missing libtdjson) or still shows the stock Kotlin icon. This check looks
inside the finished deb/rpm/msi instead of trusting the build steps:

1. Extract the artifact with the platform tool (dpkg-deb, rpm2cpio + cpio, or
   7z/msiexec, which also has to open the cab an msi hides its payload in).
2. Find `libtdjson.so` (Linux) or `tdjson.dll` (Windows) inside the app jars and
   compare it byte-for-byte with the native fetched for this build.
3. Find `icon.png` inside the app jars and compare it with the committed brand
   icon. Linux packages must also carry the launcher PNG copied by jpackage and
   their desktop entry must point at it.

Usage:
  python3 scripts/check-desktop-package.py path/to/*.deb path/to/*.rpm
  python scripts/check-desktop-package.py path\\to\\*.msi
"""

from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path
from typing import NoReturn

ROOT = Path(__file__).resolve().parent.parent
RESOURCES = ROOT / "composeApp" / "src" / "desktopMain" / "resources"
SOURCE_ICON = RESOURCES / "icon.png"
SUPPORTED_FORMATS = {".deb", ".rpm", ".msi"}


def fail(message: str) -> NoReturn:
    print(f"[FAILED] {message}", file=sys.stderr)
    raise SystemExit(1)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def require_tool(name: str) -> str:
    path = shutil.which(name)
    if path is None:
        fail(f"{name} tidak ditemukan; pasang alat ekstraksi paket terlebih dahulu")
    return path


def find_7zip() -> str | None:
    """Locates 7-Zip; the Windows runners ship it outside PATH on some images."""
    for name in ("7z", "7za", "7zr"):
        path = shutil.which(name)
        if path is not None:
            return path
    for variable in ("ProgramFiles", "ProgramFiles(x86)"):
        base = os.environ.get(variable)
        if not base:
            continue
        candidate = Path(base) / "7-Zip" / "7z.exe"
        if candidate.is_file():
            return str(candidate)
    return None


def run(command: list[str]) -> subprocess.CompletedProcess[bytes]:
    print("+ " + " ".join(command))
    return subprocess.run(command, check=False)


def extract_nested_cabs(root: Path, seven: str, rounds: int = 3) -> None:
    """Unpacks the cabinet an msi keeps its app image in.

    7z reads the msi container but leaves the payload as a single ``Data.cab``,
    so the app jar only appears once that cab is opened as well.
    """
    for _ in range(rounds):
        cabs = [entry for entry in root.rglob("*.cab") if entry.is_file()]
        if not cabs:
            return
        for cab in cabs:
            result = run([seven, "x", "-y", f"-o{root.resolve()}", str(cab.resolve())])
            if result.returncode != 0:
                fail(f"{cab.name}: 7z gagal mengekstrak cab msi (exit {result.returncode})")
            # Removing the cab keeps the next round from unpacking it again.
            cab.unlink(missing_ok=True)


def extract(package: Path, destination: Path) -> str:
    """Unpacks one installer into [destination] and returns its format."""
    kind = package.suffix.lower()
    destination.mkdir(parents=True, exist_ok=True)

    if kind == ".deb":
        result = run([require_tool("dpkg-deb"), "-x", str(package), str(destination)])
        if result.returncode != 0:
            fail(f"{package.name}: gagal mengekstrak deb (exit {result.returncode})")
        return kind

    if kind == ".rpm":
        rpm2cpio = require_tool("rpm2cpio")
        cpio = require_tool("cpio")
        with subprocess.Popen([rpm2cpio, str(package)], stdout=subprocess.PIPE) as producer:
            consumer = subprocess.run(
                [cpio, "-idm", "--quiet"],
                stdin=producer.stdout,
                cwd=destination,
                check=False,
            )
            producer.stdout.close()
            producer_status = producer.wait()
        if producer_status != 0 or consumer.returncode != 0:
            fail(
                f"{package.name}: gagal mengekstrak rpm "
                f"(rpm2cpio={producer_status}, cpio={consumer.returncode})",
            )
        return kind

    if kind == ".msi":
        # 7-Zip can read the MSI's embedded cabinet directly and is present on
        # the Windows CI image. The jar search is recursive, so the extraction
        # layout does not matter.
        seven = find_7zip()
        if seven is not None:
            result = run(
                [
                    seven,
                    "x",
                    "-y",
                    f"-o{destination.resolve()}",
                    str(package.resolve()),
                ],
            )
            if result.returncode != 0:
                fail(f"{package.name}: 7z gagal mengekstrak msi (exit {result.returncode})")
            extract_nested_cabs(destination, seven)
            return kind

        msiexec = shutil.which("msiexec")
        if msiexec is None:
            system_root = Path(os.environ.get("SystemRoot", r"C:\Windows"))
            candidate = system_root / "System32" / "msiexec.exe"
            if candidate.is_file():
                msiexec = str(candidate)
        if msiexec is None:
            fail("7z/msiexec tidak ditemukan; jalankan pemeriksaan msi di Windows")

        # Administrative install extracts the app image without installing it.
        result = run(
            [
                msiexec,
                "/a",
                str(package.resolve()),
                "/qn",
                f"TARGETDIR={destination.resolve()}",
            ],
        )
        if result.returncode not in (0, 3010):
            fail(f"{package.name}: msiexec gagal mengekstrak msi (exit {result.returncode})")
        return kind

    fail(f"{package.name}: format tidak didukung (pakai .deb, .rpm, atau .msi)")


def desktop_icon_names(root: Path) -> list[str]:
    """Returns the file names referenced by `Icon=` in packaged desktop entries."""
    names: list[str] = []
    for desktop in root.rglob("*.desktop"):
        if not desktop.is_file():
            continue
        for line in desktop.read_text(encoding="utf-8", errors="replace").splitlines():
            if line.startswith("Icon="):
                value = line.removeprefix("Icon=").strip()
                if value:
                    names.append(Path(value).name)
    return names


def app_archives(root: Path) -> list[Path]:
    """Returns the files that can be opened as jars.

    An msi stores its payload under the installer's short file names, so the app
    jar is not guaranteed to keep its `.jar` suffix once extracted. Anything that
    really is a zip archive counts.
    """
    archives: list[Path] = []
    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        if path.suffix.lower() == ".jar" or zipfile.is_zipfile(path):
            archives.append(path)
    return archives


def jar_entries(root: Path, names: list[str]) -> dict[str, list[tuple[Path, bytes]]]:
    """Collects named entries from every jar in the extracted app image."""
    jars = app_archives(root)
    if not jars:
        extracted = [path for path in sorted(root.rglob("*")) if path.is_file()]
        sample = ", ".join(str(path.relative_to(root)) for path in extracted[:15])
        fail(
            f"{root}: tidak ada jar aplikasi di dalam paket "
            f"({len(extracted)} berkas terekstrak: {sample})",
        )

    found: dict[str, list[tuple[Path, bytes]]] = {name: [] for name in names}
    for jar in jars:
        try:
            with zipfile.ZipFile(jar) as archive:
                for name in names:
                    try:
                        found[name].append((jar, archive.read(name)))
                    except KeyError:
                        continue
        except (OSError, zipfile.BadZipFile) as exc:
            fail(f"{jar}: bukan jar yang valid ({exc})")
    return found


def verify(package: Path, icon: bytes) -> None:
    if not package.is_file():
        fail(f"{package}: file tidak ada")

    kind = package.suffix.lower()
    native_name = "tdjson.dll" if kind == ".msi" else "libtdjson.so"
    native_path = RESOURCES / native_name
    if not native_path.is_file():
        fail(f"{native_path} tidak ada; jalankan scripts/fetch-tdlib-desktop.sh dulu")
    native = native_path.read_bytes()

    launcher: Path | None = None
    with tempfile.TemporaryDirectory(prefix="uc-package-") as temporary:
        root = Path(temporary)
        extract(package, root)
        entries = jar_entries(root, [native_name, "icon.png"])

        if not any(data == native for _, data in entries[native_name]):
            fail(
                f"{package.name}: {native_name} tidak ada atau berbeda dari native "
                "yang diunduh untuk build ini",
            )
        if not any(data == icon for _, data in entries["icon.png"]):
            fail(f"{package.name}: icon.png di paket bukan ikon merek yang di-commit")

        if kind in (".deb", ".rpm"):
            # jpackage copies the custom PNG next to the launcher. The app jar
            # above covers the window/tray icon; this covers the desktop entry.
            referenced = desktop_icon_names(root)
            if not referenced:
                fail(f"{package.name}: desktop entry tidak punya Icon=")

            matching = [
                path
                for path in root.rglob("*.png")
                if path.is_file()
                and path.name in referenced
                and path.read_bytes() == icon
            ]
            if not matching:
                fail(
                    f"{package.name}: ikon launcher yang dirujuk desktop entry "
                    "bukan icon.png",
                )
            launcher = matching[0].relative_to(root)

    print(f"[OK] {package.name}: {native_name} [{digest(native)[:12]}] + icon.png [brand]")
    if launcher is not None:
        print(f"      launcher: {launcher}")


def main(argv: list[str]) -> int:
    if not argv:
        print(
            "usage: python3 scripts/check-desktop-package.py "
            "<package.deb|rpm|msi> [...]",
            file=sys.stderr,
        )
        return 2
    if not SOURCE_ICON.is_file():
        fail(f"{SOURCE_ICON} tidak ada")

    packages = [Path(argument) for argument in argv]
    for package in packages:
        if package.suffix.lower() not in SUPPORTED_FORMATS:
            fail(f"{package}: format tidak didukung (pakai .deb, .rpm, atau .msi)")
        verify(package, SOURCE_ICON.read_bytes())

    print(f"[PASSED] {len(packages)} paket desktop memuat native TDLib dan ikon merek")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
