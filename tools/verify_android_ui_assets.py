"""Verify release APK carries the original OpenCE menu fonts and game data.

Run after Android release packaging, before signing/release artifact upload.
No modification, extraction to disk, or network access required.
"""
from pathlib import Path
from zipfile import ZipFile
import argparse
import hashlib

ROOT = Path(__file__).resolve().parents[1]
FONTS = ROOT / "port/android/app/src/main/assets/fonts"
REQUIRED = ("Overpass-750.ttf", "Overpass-900.ttf", "Overpass-OFL.txt")


def check(apk: Path) -> None:
    if not apk.is_file():
        raise ValueError(f"No signed APK at {apk}")
    with ZipFile(apk) as z:
        names = set(z.namelist())
        for name in ("assets/halo_guest.elf", "assets/brokers.txt"):
            if name not in names:
                raise ValueError(f"Native guest asset missing: {name}")
        for name in REQUIRED:
            target = "assets/fonts/" + name
            if target not in names:
                raise ValueError(f"Android native-menu font asset missing: {target}")
            expected = hashlib.sha256((FONTS / name).read_bytes()).digest()
            received = hashlib.sha256(z.read(target)).digest()
            if expected != received:
                raise ValueError(f"APK font asset differs from source: {name}")
        if z.testzip() is not None:
            raise ValueError("APK ZIP integrity check failed")
        print(f"PASS UI font assets: {len(REQUIRED)} byte-exact; native guest ELF present")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    check(parser.parse_args().apk)


if __name__ == "__main__":
    main()
