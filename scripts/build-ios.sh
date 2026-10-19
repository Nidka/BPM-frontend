#!/bin/bash
set -euo pipefail

# Genera el host y conserva las versiones resueltas; solo construye para simulador.
bpm_frontend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bpm_xcodegen="${XCODEGEN_BIN:-xcodegen}"
"$bpm_xcodegen" generate --spec "$bpm_frontend_root/iosApp/project.yml"
bpm_package_directory="$bpm_frontend_root/iosApp/BPMApp.xcodeproj/project.xcworkspace/xcshareddata/swiftpm"
mkdir -p "$bpm_package_directory"
cp "$bpm_frontend_root/iosApp/Package.resolved" "$bpm_package_directory/Package.resolved"
xcodebuild -project "$bpm_frontend_root/iosApp/BPMApp.xcodeproj" -scheme BPMApp \
    -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
    -derivedDataPath "${BPM_IOS_DERIVED_DATA:-$bpm_frontend_root/iosApp/build/DerivedData}" \
    -onlyUsePackageVersionsFromResolvedFile CODE_SIGNING_ALLOWED=NO ARCHS=arm64 build
