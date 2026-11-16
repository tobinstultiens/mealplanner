#!/usr/bin/env bash
# Freezes the API types of a release for ReleasedAppTest: copies the shared API types as they were
# at TAG into server/src/test/.../server/released/, so the test can read every response of the
# current server the way that release's app does (README, "API compatibility").
#
# What is copied: shared/.../api/*.kt, Health.kt and the two files the API types refer to
# (recipes/ParsedRecipe.kt for IngredientLine, units/UnitTable.kt for Dimension). Each file gets
# the package dev.tobins.mealplanner.released[.sub] and a header naming the tag and commit. The
# folder is emptied first, so a type removed since the last freeze goes away too.
#
# Usage: scripts/freeze-api.sh TAG      (e.g. v0.1.3; run it after every app release, then commit)
set -euo pipefail

tag="${1:?usage: scripts/freeze-api.sh TAG}"
cd "$(git rev-parse --show-toplevel)"
commit=$(git rev-parse --verify --quiet "$tag^{commit}") || { echo "no such tag: $tag" >&2; exit 1; }

base=shared/src/commonMain/kotlin/dev/tobins/mealplanner
out=server/src/test/kotlin/dev/tobins/mealplanner/server/released

files=$(git ls-tree -r --name-only "$tag" -- "$base/api" | grep '\.kt$')
files="$files
$base/Health.kt
$base/recipes/ParsedRecipe.kt
$base/units/UnitTable.kt"

rm -rf "$out"
for path in $files; do
  rel=${path#"$base"/}
  mkdir -p "$out/$(dirname "$rel")"
  {
    echo "// Frozen from $tag ($commit) by scripts/freeze-api.sh. Do not edit: freeze again after a release."
    git show "$tag:$path" | sed -E \
      -e 's/^package dev\.tobins\.mealplanner(\..*)?$/package dev.tobins.mealplanner.released\1/' \
      -e 's/^import dev\.tobins\.mealplanner\./import dev.tobins.mealplanner.released./'
  } > "$out/$rel"
done
echo "Froze $(echo "$files" | wc -l) files of $tag ($commit) into $out"
