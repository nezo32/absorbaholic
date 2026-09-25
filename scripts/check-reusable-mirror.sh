#!/usr/bin/env bash
# check-reusable-mirror.sh — verify that .github/workflows/reusable-*.yml are byte-identical to the shared pipeline
# in nezo32/enchantaholic at the pinned revision below. Absorbaholic calls these mirrors by local path; changes go
# upstream first, then get re-mirrored here together with a new UPSTREAM_SHA (docs/ci/REUSABLE_RELEASE_PIPELINE.md).
# Usage: scripts/check-reusable-mirror.sh   (from anywhere inside the repo; needs curl and network access)
# Env:   UPSTREAM_REPO / UPSTREAM_SHA override the pinned values (e.g. to preview a re-mirror).
set -euo pipefail

UPSTREAM_REPO="${UPSTREAM_REPO:-nezo32/enchantaholic}"
UPSTREAM_SHA="${UPSTREAM_SHA:-a3e3f7d4160e692a64845c196922c780d1608f9f}"

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

shopt -s nullglob
files=( "$root"/.github/workflows/reusable-*.yml )
[[ ${#files[@]} -gt 0 ]] || { echo "::error::no .github/workflows/reusable-*.yml found" >&2; exit 1; }

failed=0
for f in "${files[@]}"; do
  rel="${f#"$root"/}"
  url="https://raw.githubusercontent.com/$UPSTREAM_REPO/$UPSTREAM_SHA/$rel"
  if ! curl -fsSL --retry 3 --retry-connrefused -o "$tmp/upstream" "$url"; then
    echo "::error::could not fetch $url (is $rel in $UPSTREAM_REPO at $UPSTREAM_SHA?)" >&2
    failed=1; continue
  fi
  if diff -u --label "$UPSTREAM_REPO@${UPSTREAM_SHA:0:12}:$rel" --label "$rel" "$tmp/upstream" "$f"; then
    echo "ok   - $rel"
  else
    echo "::error file=$rel::$rel differs from $UPSTREAM_REPO@$UPSTREAM_SHA. Change the shared workflow upstream first, then re-mirror it and bump UPSTREAM_SHA in scripts/check-reusable-mirror.sh." >&2
    failed=1
  fi
done

[[ $failed -eq 0 ]] || exit 1
echo "OK: ${#files[@]} reusable workflows match $UPSTREAM_REPO@$UPSTREAM_SHA"
