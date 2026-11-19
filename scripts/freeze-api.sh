#!/usr/bin/env bash
# Freezes the API types of a release for ReleasedAppTest: copies the shared API types as they were
# at TAG into server/src/test/.../server/released/, so the test can read every response of the
# current server the way that release's app does (README, "API compatibility").
#
# What is copied: shared/.../api/*.kt, Health.kt and the files the API types refer to
# (recipes/ParsedRecipe.kt for IngredientLine, units/UnitTable.kt for Dimension,
# planner/PortionRange.kt for the defaults in Profile). Each file gets the package
# dev.tobins.mealplanner.released[.sub] and a header naming the tag and commit. The folder is
# emptied first, so a type removed since the last freeze goes away too.
#
# Afterwards the script checks that every dev.tobins.mealplanner import of a frozen file names
# something that was frozen, and fails otherwise: when an API type starts to need another file,
# add it to the list below instead of finding out from a compile error in the freeze PR.
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
$base/units/UnitTable.kt
$base/planner/PortionRange.kt"

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
# Every project import of a frozen file must name a declaration (or, for a wildcard, a package) that
# is frozen too. Checked on the frozen copies, whose imports all start with ...mealplanner.released.
missing=0
while IFS= read -r line; do
  file=${line%%:*}
  import=${line#*:}
  import=${import#import dev.tobins.mealplanner.released.}
  name=${import##*.}
  if [ "$name" = "*" ]; then
    pkg="package dev.tobins.mealplanner.released.${import%.*}"
    grep -rqxF "$pkg" "$out" && continue
  else
    grep -rqE "^(public |internal |private )?((data |enum |sealed |value |annotation )?class|object|interface|typealias|fun|val) ([A-Za-z<>. ]+\\.)?$name\\b" "$out" && continue
  fi
  echo "$file imports dev.tobins.mealplanner.$import, which was not frozen: add its file to the list in $0" >&2
  missing=1
done < <(grep -rE '^import dev\.tobins\.mealplanner\.released\.' "$out")
[ "$missing" = 0 ] || exit 1
echo "Froze $(echo "$files" | wc -l) files of $tag ($commit) into $out"
