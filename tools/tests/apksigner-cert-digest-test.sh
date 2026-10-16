#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=../apksigner-cert-digest.sh
source "$REPO_ROOT/tools/apksigner-cert-digest.sh"

expected="62EFEC3DC24D05B9AEC7DE3A713610BE8A0B7C57D75EC2F041D90032F58794CD"
legacy_output='Signer #1 certificate SHA-256 digest: 62:ef:ec:3d:c2:4d:05:b9:ae:c7:de:3a:71:36:10:be:8a:0b:7c:57:d7:5e:c2:f0:41:d9:00:32:f5:87:94:cd'
current_output='V2 Signer: certificate SHA-256 digest: 62efec3dc24d05b9aec7de3a713610be8a0b7c57d75ec2f041d90032f58794cd'

assert_digest() {
  local label="$1"
  local output="$2"
  local actual
  actual="$(printf '%s\n' "$output" | extract_apksigner_certificate_sha256)"
  if [[ "$actual" != "$expected" ]]; then
    printf 'FAIL: %s format expected %s, got %s\n' "$label" "$expected" "$actual" >&2
    exit 1
  fi
}

assert_digest "legacy" "$legacy_output"
assert_digest "current V2" "$current_output"

if [[ -n "$(printf 'No certificate digest here\n' | extract_apksigner_certificate_sha256)" ]]; then
  echo "FAIL: parser returned a fingerprint when none was present" >&2
  exit 1
fi

echo "apksigner certificate digest parser tests passed"
