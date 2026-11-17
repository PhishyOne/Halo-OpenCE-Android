"""Fail exact-head Android release if the selected helmet icon was not packaged."""
from pathlib import Path
from zipfile import ZipFile
import hashlib
import sys

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "port/android/app/src/main/res/drawable-nodpi/halo_selected_icon.webp"
EXPECTED_SHA256 = "937b3876cf0da75724878158b2e604268bfdd20d2cd59c82984dddbd0a348ee8"


def verify(apk: Path):
    if len(sys.argv) != 2:
        raise ValueError("Usage: verify_android_icon_v40.py signed.apk")
    source = SOURCE.read_bytes()
    source_sha = hashlib.sha256(source).hexdigest()
    if source_sha != EXPECTED_SHA256:
        raise ValueError("The approved source artwork changed unexpectedly")
    with ZipFile(apk) as z:
        matches = []
        for item in z.infolist():
            if not item.filename.lower().endswith(".webp"):
                continue
            if hashlib.sha256(z.read(item)).hexdigest() == EXPECTED_SHA256:
                matches.append(item.filename)
        if len(matches) != 1:
            raise ValueError(f"Approved helmet icon not byte-exact in APK: {matches}")
        print("PASS v40 selected icon packaged unchanged:", matches[0])


if __name__ == "__main__":
    verify(Path(sys.argv[1]))
