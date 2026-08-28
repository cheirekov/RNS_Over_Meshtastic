#!/usr/bin/env bash
set -euo pipefail

required=(
  ANDROID_KEYSTORE_FILE
  ANDROID_KEYSTORE_PASSWORD
  ANDROID_KEY_ALIAS
  ANDROID_KEY_PASSWORD
)
for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    printf 'error: %s is required for a signed release\n' "$name" >&2
    exit 2
  fi
done

if [[ ! -f "$ANDROID_KEYSTORE_FILE" ]]; then
  printf 'error: keystore does not exist: %s\n' "$ANDROID_KEYSTORE_FILE" >&2
  exit 2
fi
keystore_host_path=$(realpath "$ANDROID_KEYSTORE_FILE")

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
artifact_dir="$script_dir/release-artifacts"
apk_path="$script_dir/app/build/outputs/apk/release/app-release.apk"
mkdir -p "$artifact_dir"
chmod 700 "$artifact_dir"

docker compose -f "$script_dir/compose.yaml" run --rm \
  -v "$keystore_host_path:/run/secrets/release.jks:ro" \
  -e ANDROID_KEYSTORE_FILE=/run/secrets/release.jks \
  -e ANDROID_KEYSTORE_PASSWORD \
  -e ANDROID_KEY_ALIAS \
  -e ANDROID_KEY_PASSWORD \
  android-build gradle --no-daemon clean testDebugUnitTest assembleRelease

if [[ ! -f "$apk_path" ]]; then
  printf 'error: Gradle did not create %s\n' "$apk_path" >&2
  exit 1
fi

docker compose -f "$script_dir/compose.yaml" run --rm \
  android-build /opt/android-sdk/build-tools/35.0.0/apksigner \
  verify --verbose --print-certs /workspace/android/app/build/outputs/apk/release/app-release.apk \
  | tee "$artifact_dir/app-release-certificates.txt"

cp "$apk_path" "$artifact_dir/app-release.apk"
(cd "$artifact_dir" && sha256sum app-release.apk | tee app-release.apk.sha256)
chmod 600 "$artifact_dir"/*
printf 'Signed release artifacts: %s\n' "$artifact_dir"
