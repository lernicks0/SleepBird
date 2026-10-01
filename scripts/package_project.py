"""Create a portable source archive; no local tools, caches, or Git metadata."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parent.parent
files = [ROOT / "README.md", ROOT / "WINDOWS.md", ROOT / "VALIDATION.md", ROOT / "LICENSE", ROOT / ".gitignore"]
for name in ["SleepBird", "SleepBirdTests", "SleepBird.xcodeproj", "scripts", ".github"]:
    files.extend(p for p in (ROOT / name).rglob("*") if p.is_file() and "__pycache__" not in p.parts and "xcuserdata" not in p.parts)
destination = ROOT / "SleepBird-MVP.zip"
with ZipFile(destination, "w", ZIP_DEFLATED) as archive:
    for file in sorted(files):
        archive.write(file, "SleepBird-MVP/" + file.relative_to(ROOT).as_posix())
with ZipFile(destination) as archive:
    assert archive.testzip() is None
    print(f"Archive verified: {len(archive.namelist())} files, {destination.stat().st_size:,} bytes.")
