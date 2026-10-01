"""Static validation only. This does not compile Swift or run XCTest.
Optional syntax parsing: pip install --target .validation tree-sitter tree-sitter-swift
"""
from pathlib import Path
import json
import plistlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / ".validation"))
checks = []
def check(condition, description):
    checks.append((bool(condition), description))
    print(("PASS " if condition else "FAIL ") + description)

pbx = (ROOT / "SleepBird.xcodeproj/project.pbxproj").read_text(encoding="utf-8")
definitions = re.findall(r"^\s*([A-F0-9]{24}) = \{isa =", pbx, re.M)
references = set(re.findall(r"\b[A-F0-9]{24}\b", pbx))
check(len(definitions) == len(set(definitions)), "Xcode object IDs are unique")
check(references == set(definitions), "All Xcode object references resolve")
source_files = sorted((ROOT / "SleepBird").rglob("*.swift")) + sorted((ROOT / "SleepBirdTests").rglob("*.swift"))
for file in source_files:
    check(file.relative_to(ROOT).as_posix() in pbx, f"Xcode source membership: {file.relative_to(ROOT).as_posix()}")
check('IPHONEOS_DEPLOYMENT_TARGET = 18.0' in pbx, "Deployment target: iOS/iPadOS 18.0")
check('TARGETED_DEVICE_FAMILY = "1,2"' in pbx, "iPhone and iPad supported")
check('SUPPORTS_MACCATALYST = NO' in pbx, "No accidental Catalyst target")
ET.parse(ROOT / "SleepBird.xcodeproj/xcshareddata/xcschemes/SleepBird.xcscheme")
ET.parse(ROOT / "SleepBird.xcodeproj/project.xcworkspace/contents.xcworkspacedata")
check(True, "Shared scheme and workspace XML parse")
privacy = plistlib.loads((ROOT / "SleepBird/PrivacyInfo.xcprivacy").read_bytes())
check(privacy["NSPrivacyAccessedAPITypes"][0]["NSPrivacyAccessedAPITypeReasons"] == ["CA92.1"], "UserDefaults privacy manifest")
for file in (ROOT / "SleepBird/Assets.xcassets").rglob("*.json"):
    json.loads(file.read_text())
check(True, "Asset catalogs parse")
from PIL import Image
icon = Image.open(ROOT / "SleepBird/Assets.xcassets/AppIcon.appiconset/AppIcon.png")
check(icon.size == (1024, 1024) and icon.mode == "RGB", "App icon: 1024x1024, opaque RGB")

provider = (ROOT / "SleepBird/Services/NotificationMessageProvider.swift").read_text(encoding="utf-8")
for level in ["one", "two", "three", "four", "five"]:
    block = re.search(r"\." + level + r": \[(.*?)\n\s*\]", provider, re.S).group(1)
    messages = re.findall(r'"([^"\n]+)"', block)
    check(len(messages) >= 15 and len(messages) == len(set(messages)), f"Level {level}: {len(messages)} unique messages")
manager = (ROOT / "SleepBird/Services/NotificationManager.swift").read_text(encoding="utf-8")
check('options: [.alert, .sound, .badge]' in manager, "Explicit native notification permission request")
check('title: "我睡了 💤", options: []' in manager, "Background notification action declared")
check('removePendingNotificationRequests(withIdentifiers:' in manager, "Targeted pending cancellation present")
check('id == tracker.night.id' in manager, "Stale notification actions cannot complete a new night")
check('repeats: false' in manager and 'prefix(max(0, 60 - tests.count))' in manager, "Finite one-shot queue with 60-request budget")
check('needsReconcile = true' in manager and 'while needsReconcile' in manager, "Reentrant scheduling uses a dirty loop")
check('.trigger?.nextTriggerDate()' not in manager, "Trigger date queried on supported concrete subclasses")
tests = (ROOT / "SleepBirdTests/SleepBirdTests.swift").read_text(encoding="utf-8")
test_count = len(re.findall(r"func test\w+\(", tests))
check(test_count >= 15, f"{test_count} XCTest cases supplied (not executed here)")

try:
    from tree_sitter import Language, Parser
    import tree_sitter_swift
except ImportError:
    print("SKIP Swift syntax parser unavailable; install optional packages into .validation.")
else:
    parser = Parser(Language(tree_sitter_swift.language()))
    for file in source_files:
        tree = parser.parse(file.read_bytes())
        errors = []
        def walk(node):
            if node.type == "ERROR" or node.is_missing:
                errors.append(f"{node.type} at {node.start_point.row + 1}:{node.start_point.column + 1}")
            for child in node.children:
                walk(child)
        walk(tree.root_node)
        check(not errors, f"Swift syntax: {file.relative_to(ROOT).as_posix()}" + (" — " + ", ".join(errors) if errors else ""))

failed = [description for passed, description in checks if not passed]
print(f"\n{len(checks) - len(failed)}/{len(checks)} static checks passed.")
print("Swift type checking, Apple SDK linking, XCTest execution and device UI/notifications remain unverified.")
sys.exit(1 if failed else 0)
