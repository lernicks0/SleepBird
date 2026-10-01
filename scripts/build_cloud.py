"""Run on a macOS CI runner; make a device IPA for subsequent Windows signing.
No Apple credentials, provisioning profile, or certificate is used in this script.
"""
from pathlib import Path
import json
import os
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / ".ci-output"
BUILD = ROOT / ".ci-build"

def run(command, log_name):
    print("Running " + log_name, flush=True)
    log = OUTPUT / log_name
    with log.open("w", encoding="utf-8") as stream:
        result = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, text=True)
    if result.returncode:
        print(log.read_text(encoding="utf-8", errors="replace")[-18000:], flush=True)
        raise RuntimeError(f"Command failed ({result.returncode}); see {log_name}")

def test_destination():
    data = json.loads(subprocess.check_output(["xcrun", "simctl", "list", "devices", "available", "--json"], text=True))
    for runtime, devices in sorted(data["devices"].items(), reverse=True):
        if ".iOS-" not in runtime:
            continue
        major = int(runtime.split(".iOS-", 1)[1].split("-", 1)[0])
        if major < 18:
            continue
        for device in devices:
            if device.get("isAvailable") and device["name"].startswith("iPhone"):
                return "platform=iOS Simulator,id=" + device["udid"]
    raise RuntimeError("No available iPhone simulator with iOS 18 or newer on this runner.")

def main():
    OUTPUT.mkdir(exist_ok=True)
    if sys.platform != "darwin":
        raise RuntimeError("This script requires macOS with Xcode. Run the GitHub Actions workflow, not this script on Windows.")
    version = subprocess.check_output(["xcodebuild", "-version"], text=True)
    (OUTPUT / "BUILD-INFO.txt").write_text(version + "\nUnsigned device build. Must be signed before installation.\n", encoding="utf-8")
    base = ["xcodebuild", "-project", "SleepBird.xcodeproj", "-scheme", "SleepBird"]
    if os.environ.get("RUN_TESTS", "true").lower() == "true":
        run(base + ["-configuration", "Debug", "-destination", test_destination(),
                    "-derivedDataPath", str(BUILD / "tests"), "-resultBundlePath", str(OUTPUT / "SleepBirdTests.xcresult"),
                    "CODE_SIGNING_ALLOWED=NO", "test"], "tests.log")
        print("XCTest succeeded. Last test log lines:", flush=True)
        print("\n".join((OUTPUT / "tests.log").read_text(encoding="utf-8", errors="replace").splitlines()[-65:]), flush=True)
        with (OUTPUT / "BUILD-INFO.txt").open("a", encoding="utf-8") as stream:
            stream.write("XCTest command succeeded.\n")
    else:
        with (OUTPUT / "BUILD-INFO.txt").open("a", encoding="utf-8") as stream:
            stream.write("XCTest skipped by workflow input.\n")
    run(base + ["-configuration", "Release", "-sdk", "iphoneos", "-destination", "generic/platform=iOS",
                "-derivedDataPath", str(BUILD / "device"), "CODE_SIGNING_ALLOWED=NO",
                "CODE_SIGNING_REQUIRED=NO", "CODE_SIGN_IDENTITY=", "build"], "build.log")
    app = BUILD / "device/Build/Products/Release-iphoneos/SleepBird.app"
    if not (app / "SleepBird").is_file() or not (app / "Info.plist").is_file():
        raise RuntimeError("The expected built SleepBird.app is missing.")
    payload = BUILD / "package/Payload"
    payload.mkdir(parents=True, exist_ok=True)
    shutil.copytree(app, payload / "SleepBird.app", dirs_exist_ok=True)
    run(["ditto", "-c", "-k", "--keepParent", str(payload), str(OUTPUT / "SleepBird-unsigned.ipa")], "package.log")
    with (OUTPUT / "BUILD-INFO.txt").open("a", encoding="utf-8") as stream:
        stream.write("Device build succeeded. Package contains Payload/SleepBird.app.\n")
    print("IPA ready: .ci-output/SleepBird-unsigned.ipa", flush=True)

if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        OUTPUT.mkdir(exist_ok=True)
        (OUTPUT / "FAILED.txt").write_text(str(error) + "\n", encoding="utf-8")
        print(str(error), file=sys.stderr)
        sys.exit(1)
