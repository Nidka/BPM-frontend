#!/bin/bash
set -euo pipefail

# Genera el host y conserva las versiones resueltas; solo construye para simulador.
bpm_frontend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bpm_xcodegen="${XCODEGEN_BIN:-xcodegen}"
"$bpm_xcodegen" generate --spec "$bpm_frontend_root/iosApp/project.yml"
bpm_package_directory="$bpm_frontend_root/iosApp/BPMApp.xcodeproj/project.xcworkspace/xcshareddata/swiftpm"
mkdir -p "$bpm_package_directory"
cp "$bpm_frontend_root/iosApp/Package.resolved" "$bpm_package_directory/Package.resolved"
bpm_derived_data="${BPM_IOS_DERIVED_DATA:-$bpm_frontend_root/iosApp/build/DerivedData}"
xcodebuild -project "$bpm_frontend_root/iosApp/BPMApp.xcodeproj" -scheme BPMApp \
    -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
    -derivedDataPath "$bpm_derived_data" \
    -onlyUsePackageVersionsFromResolvedFile CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- ARCHS=arm64 build

# Compilar no basta: Compose comprueba esta propiedad al iniciar la aplicación.
python3 - "$bpm_derived_data/Build/Products/Debug-iphonesimulator/BPMApp.app/Info.plist" <<'PY'
import plistlib, sys
with open(sys.argv[1], 'rb') as file:
    info = plistlib.load(file)
assert info.get('CADisableMinimumFrameDurationOnPhone') is True, 'Falta la propiedad de arranque exigida por Compose iOS'
assert info.get('CFBundleIdentifier') == 'pe.bpm.app', 'El bundle iOS no coincide con el registro Firebase'
PY
