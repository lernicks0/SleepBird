"""Generate the checked-in Xcode project and simple original icon. No XcodeGen needed.
Run from the repository root. Python standard library; Pillow only for icon generation.
"""
from pathlib import Path
import hashlib
import json
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
def oid(label):
    return hashlib.sha256(label.encode()).hexdigest()[:24].upper()

objects = []
def obj(label, isa, properties):
    key = oid(label)
    objects.append(f"\t\t{key} = {{isa = {isa}; {properties}}};")
    return key

def array(values):
    return "(" + ", ".join(values) + ")"

swift = sorted((ROOT / "SleepBird").rglob("*.swift"))
test_swift = sorted((ROOT / "SleepBirdTests").rglob("*.swift"))
source_refs, source_builds, test_refs, test_builds = [], [], [], []
for files, refs, builds in [(swift, source_refs, source_builds), (test_swift, test_refs, test_builds)]:
    for file in files:
        relative = file.relative_to(ROOT).as_posix()
        ref = obj("ref:" + relative, "PBXFileReference", f'lastKnownFileType = sourcecode.swift; path = "{relative}"; sourceTree = SOURCE_ROOT; ')
        build = obj("build:" + relative, "PBXBuildFile", f"fileRef = {ref}; ")
        refs.append(ref)
        builds.append(build)

resources = []
resource_refs = []
for path, kind in [("SleepBird/Assets.xcassets", "folder.assetcatalog"), ("SleepBird/PrivacyInfo.xcprivacy", "text.xml")]:
    ref = obj("ref:" + path, "PBXFileReference", f'lastKnownFileType = {kind}; path = "{path}"; sourceTree = SOURCE_ROOT; ')
    resource_refs.append(ref)
    resources.append(obj("build:" + path, "PBXBuildFile", f"fileRef = {ref}; "))
app_product = obj("app-product", "PBXFileReference", 'explicitFileType = wrapper.application; includeInIndex = 0; path = SleepBird.app; sourceTree = BUILT_PRODUCTS_DIR; ')
tests_product = obj("tests-product", "PBXFileReference", 'explicitFileType = wrapper.cfbundle; includeInIndex = 0; path = SleepBirdTests.xctest; sourceTree = BUILT_PRODUCTS_DIR; ')
app_sources = obj("app-sources", "PBXSourcesBuildPhase", f"buildActionMask = 2147483647; files = {array(source_builds)}; runOnlyForDeploymentPostprocessing = 0; ")
test_sources = obj("test-sources", "PBXSourcesBuildPhase", f"buildActionMask = 2147483647; files = {array(test_builds)}; runOnlyForDeploymentPostprocessing = 0; ")
app_resources = obj("app-resources", "PBXResourcesBuildPhase", f"buildActionMask = 2147483647; files = {array(resources)}; runOnlyForDeploymentPostprocessing = 0; ")
test_resources = obj("test-resources", "PBXResourcesBuildPhase", "buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0; ")
app_frameworks = obj("app-frameworks", "PBXFrameworksBuildPhase", "buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0; ")
test_frameworks = obj("test-frameworks", "PBXFrameworksBuildPhase", "buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0; ")

def config_list(label, settings):
    configs = []
    for mode in ["Debug", "Release"]:
        values = dict(settings)
        values["SWIFT_OPTIMIZATION_LEVEL"] = '"-Onone"' if mode == "Debug" else '"-O"'
        values["DEBUG_INFORMATION_FORMAT"] = 'dwarf' if mode == "Debug" else '"dwarf-with-dsym"'
        if mode == "Debug":
            values["SWIFT_ACTIVE_COMPILATION_CONDITIONS"] = '"DEBUG $(inherited)"'
            values["ENABLE_TESTABILITY"] = "YES"
        props = " ".join(f"{k} = {v};" for k, v in values.items())
        configs.append(obj(label + "-" + mode, "XCBuildConfiguration", f"buildSettings = {{{props}}}; name = {mode}; "))
    return obj(label + "-configs", "XCConfigurationList", f"buildConfigurations = {array(configs)}; defaultConfigurationIsVisible = 0; defaultConfigurationName = Release; ")

project_config = config_list("project", {
    "CLANG_ENABLE_MODULES": "YES", "CLANG_ENABLE_OBJC_ARC": "YES",
    "IPHONEOS_DEPLOYMENT_TARGET": "18.0", "SDKROOT": "iphoneos", "SWIFT_VERSION": "5.0",
    "SWIFT_STRICT_CONCURRENCY": "minimal", "GCC_C_LANGUAGE_STANDARD": "gnu17",
})
common = {
    "CODE_SIGN_STYLE": "Automatic", "GENERATE_INFOPLIST_FILE": "YES",
    "IPHONEOS_DEPLOYMENT_TARGET": "18.0", "SWIFT_VERSION": "5.0",
    "PRODUCT_NAME": '"$(TARGET_NAME)"', "TARGETED_DEVICE_FAMILY": '"1,2"',
    "SUPPORTED_PLATFORMS": '"iphoneos iphonesimulator"', "SUPPORTS_MACCATALYST": "NO",
}
app_config = config_list("app", dict(common, **{
    "PRODUCT_BUNDLE_IDENTIFIER": "com.example.SleepBird", "MARKETING_VERSION": "1.0", "CURRENT_PROJECT_VERSION": "1",
    "ASSETCATALOG_COMPILER_APPICON_NAME": "AppIcon",
    "INFOPLIST_KEY_CFBundleDisplayName": "SleepBird",
    "INFOPLIST_KEY_LSApplicationCategoryType": '"public.app-category.lifestyle"',
    "INFOPLIST_KEY_UIApplicationSceneManifest_Generation": "YES",
    "INFOPLIST_KEY_UIApplicationSupportsIndirectInputEvents": "YES",
    "INFOPLIST_KEY_UILaunchScreen_Generation": "YES",
    "INFOPLIST_KEY_UISupportedInterfaceOrientations_iPhone": '"UIInterfaceOrientationPortrait UIInterfaceOrientationLandscapeLeft UIInterfaceOrientationLandscapeRight"',
    "INFOPLIST_KEY_UISupportedInterfaceOrientations_iPad": '"UIInterfaceOrientationPortrait UIInterfaceOrientationPortraitUpsideDown UIInterfaceOrientationLandscapeLeft UIInterfaceOrientationLandscapeRight"',
    "LD_RUNPATH_SEARCH_PATHS": '"$(inherited) @executable_path/Frameworks"',
}))
test_config = config_list("tests", dict(common, **{
    "PRODUCT_BUNDLE_IDENTIFIER": "com.example.SleepBirdTests",
    "TEST_HOST": '"$(BUILT_PRODUCTS_DIR)/SleepBird.app/$(BUNDLE_EXECUTABLE_FOLDER_PATH)/SleepBird"',
    "BUNDLE_LOADER": '"$(TEST_HOST)"',
    "LD_RUNPATH_SEARCH_PATHS": '"$(inherited) @executable_path/Frameworks @loader_path/Frameworks"',
}))
proxy = obj("test-proxy", "PBXContainerItemProxy", f"containerPortal = {oid('project')}; proxyType = 1; remoteGlobalIDString = {oid('app-target')}; remoteInfo = SleepBird; ")
dependency = obj("test-dependency", "PBXTargetDependency", f"target = {oid('app-target')}; targetProxy = {proxy}; ")
app_target = obj("app-target", "PBXNativeTarget", f'buildConfigurationList = {app_config}; buildPhases = {array([app_sources, app_frameworks, app_resources])}; buildRules = (); dependencies = (); name = SleepBird; productName = SleepBird; productReference = {app_product}; productType = "com.apple.product-type.application"; ')
test_target = obj("test-target", "PBXNativeTarget", f'buildConfigurationList = {test_config}; buildPhases = {array([test_sources, test_frameworks, test_resources])}; buildRules = (); dependencies = {array([dependency])}; name = SleepBirdTests; productName = SleepBirdTests; productReference = {tests_product}; productType = "com.apple.product-type.bundle.unit-test"; ')
sources_group = obj("sources-group", "PBXGroup", f"children = {array(source_refs + resource_refs)}; name = SleepBird; sourceTree = \"<group>\"; ")
tests_group = obj("tests-group", "PBXGroup", f"children = {array(test_refs)}; name = SleepBirdTests; sourceTree = \"<group>\"; ")
product_group = obj("products-group", "PBXGroup", f"children = {array([app_product, tests_product])}; name = Products; sourceTree = \"<group>\"; ")
main_group = obj("main-group", "PBXGroup", f"children = {array([sources_group, tests_group, product_group])}; sourceTree = \"<group>\"; ")
project = obj("project", "PBXProject", f'attributes = {{BuildIndependentTargetsInParallel = YES; LastUpgradeCheck = 1600; TargetAttributes = {{{app_target} = {{CreatedOnToolsVersion = 16.0; }}; {test_target} = {{CreatedOnToolsVersion = 16.0; TestTargetID = {app_target}; }}; }}; }}; buildConfigurationList = {project_config}; compatibilityVersion = "Xcode 14.0"; developmentRegion = zh-Hans; hasScannedForEncodings = 0; knownRegions = (en, Base, "zh-Hans"); mainGroup = {main_group}; productRefGroup = {product_group}; projectDirPath = ""; projectRoot = ""; targets = {array([app_target, test_target])}; ')
folder = ROOT / "SleepBird.xcodeproj"
folder.mkdir(exist_ok=True)
(folder / "project.pbxproj").write_text("// !$*UTF8*$!\n{\n\tarchiveVersion = 1;\n\tclasses = {};\n\tobjectVersion = 56;\n\tobjects = {\n" + "\n".join(objects) + f"\n\t}};\n\trootObject = {project};\n}}\n", encoding="utf-8")
workspace = folder / "project.xcworkspace"
workspace.mkdir(exist_ok=True)
(workspace / "contents.xcworkspacedata").write_text('<?xml version="1.0" encoding="UTF-8"?>\n<Workspace version="1.0"><FileRef location="self:"></FileRef></Workspace>\n', encoding="utf-8")
scheme_folder = folder / "xcshareddata" / "xcschemes"
scheme_folder.mkdir(parents=True, exist_ok=True)
ref = f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{app_target}" BuildableName="SleepBird.app" BlueprintName="SleepBird" ReferencedContainer="container:SleepBird.xcodeproj"/>'
test_ref = f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{test_target}" BuildableName="SleepBirdTests.xctest" BlueprintName="SleepBirdTests" ReferencedContainer="container:SleepBird.xcodeproj"/>'
scheme = f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="1600" version="1.3">
 <BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">{ref}</BuildActionEntry></BuildActionEntries></BuildAction>
 <TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv="YES"><Testables><TestableReference skipped="NO" parallelizable="NO">{test_ref}</TestableReference></Testables></TestAction>
 <LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugServiceExtension="internal" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0">{ref}</BuildableProductRunnable></LaunchAction>
 <ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO"><BuildableProductRunnable runnableDebuggingMode="0">{ref}</BuildableProductRunnable></ProfileAction>
 <AnalyzeAction buildConfiguration="Debug"/>
 <ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>
'''
(scheme_folder / "SleepBird.xcscheme").write_text(scheme, encoding="utf-8")
ET.fromstring(scheme)

assets = ROOT / "SleepBird" / "Assets.xcassets"
icon_folder = assets / "AppIcon.appiconset"
icon_folder.mkdir(parents=True, exist_ok=True)
(assets / "Contents.json").write_text(json.dumps({"info": {"author": "xcode", "version": 1}}, indent=2) + "\n")
(icon_folder / "Contents.json").write_text(json.dumps({"images": [{"filename": "AppIcon.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"}], "info": {"author": "xcode", "version": 1}}, indent=2) + "\n")
if not (icon_folder / "AppIcon.png").exists():
    from PIL import Image, ImageDraw
    size = 3072
    image = Image.new("RGB", (size, size), "#292555")
    draw = ImageDraw.Draw(image)
    def ellipse(box, color):
        draw.ellipse(tuple(int(v * 3) for v in box), fill=color)
    def polygon(points, color):
        draw.polygon([(int(x * 3), int(y * 3)) for x, y in points], fill=color)
    # Original sleeping songbird silhouette, crescent, and a closed eye.
    ellipse((614, 128, 874, 388), "#FDE6A4")
    ellipse((674, 106, 890, 322), "#292555")
    ellipse((210, 365, 764, 836), "#B6AEF9")
    ellipse((543, 317, 805, 587), "#B6AEF9")
    polygon([(230, 655), (123, 529), (178, 782), (351, 764)], "#B6AEF9")
    polygon([(787, 423), (887, 474), (787, 511)], "#FDE6A4")
    ellipse((325, 514, 654, 778), "#8D82D6")
    draw.arc(tuple(v * 3 for v in (635, 404, 729, 491)), 15, 165, fill="#292555", width=19)
    image.resize((1024, 1024), Image.Resampling.LANCZOS).save(icon_folder / "AppIcon.png")
print(f"Generated Xcode project: {len(swift)} app Swift files, {len(test_swift)} test file(s).")
