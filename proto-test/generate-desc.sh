#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/schemas"
mkdir -p "$OUT"

protoc \
  --descriptor_set_out="$OUT/service.desc" \
  --include_imports \
  --proto_path="$ROOT/proto-test" \
  "$ROOT/proto-test/user.proto"

echo "Generated: $OUT/service.desc"
