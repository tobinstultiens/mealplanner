#!/usr/bin/env bash
# PreToolUse hook for Bash: nothing reaches `main` by a push without the owner being asked.
#
# `main` changes through a pull request. A permission rule cannot tell the destination of a push
# (`git push origin issue-1:main` starts like `git push origin issue-1`), so this hook reads it:
#
# - A push with any refspec whose destination is `main` (`src:main`, `HEAD:main`, `+src:main`,
#   `refs/heads/main`, `--force`, flags before the remote, inside a chain or quotes) always asks.
#   This is checked first, so nothing later can turn it into an allow.
# - The plain push of an `issue-*` branch to the branch of the same name is approved without a
#   prompt: `git push [-u | --force-with-lease] origin issue-<name>`, with `git -C <path>` and a
#   trailing `2>&1` allowed, after any number of `cd <path> &&`. Nothing else may be in the
#   command: no other part, no newline, no pipe, no substitution.
# - Every other command is left to the permission rules, which no longer allow any push, so a push
#   without a refspec, `--all`, `--mirror` and the like are asked about too.
#
# The owner can still push from their own terminal; the GitHub ruleset on `main` is the real lock.
set -euo pipefail

input=$(cat)
command=$(jq -r '.tool_input.command // ""' <<<"$input")

decide() {
  jq -n --arg d "$1" --arg r "$2" '{hookSpecificOutput: {hookEventName: "PreToolUse", permissionDecision: $d, permissionDecisionReason: $r}}'
  exit 0
}

# Does a push part of the command have a destination of `main`? Quotes and backslashes are dropped
# first, and the command is cut into parts at ; & | brackets, backticks and line breaks.
while IFS= read -r part; do
  read -r -a words <<<"$part" || continue
  [[ ${#words[@]} -gt 0 && "${words[0]}" == git ]] || continue
  pushing=false
  for word in "${words[@]:1}"; do
    if [[ "$word" == push ]]; then pushing=true; continue; fi
    $pushing || continue
    [[ "$word" == -* ]] && continue
    dest=${word#+}
    dest=${dest##*:}
    dest=${dest#refs/heads/}
    dest=${dest#heads/}
    if [[ "$dest" == main || "$dest" == *'*'* ]]; then
      decide ask "A push to main skips review and CI: main takes pull requests with green checks only"
    fi
  done
done < <(tr -d "\"'\\\\" <<<"$command" | tr ';&|(){}`' '\n')

# A path: plain characters, or in double quotes without anything the shell would expand.
path='("[^"$`\\]+"|[A-Za-z0-9._/~-]+)'
cd_part="^[[:space:]]*cd[[:space:]]+$path[[:space:]]*\$"
push_part="^[[:space:]]*git( -C $path)? push( -u| --force-with-lease)? origin (issue-[A-Za-z0-9._/-]+)( 2>&1)?[[:space:]]*\$"

# One line only, and every part between && is a cd or the plain push of an issue branch.
if [[ "$command" != *$'\n'* && "$command" != *$'\r'* ]]; then
  allowed=true
  pushes=0
  while IFS= read -r part; do
    if [[ "$part" =~ $cd_part ]]; then
      continue
    elif [[ "$part" =~ $push_part && "${BASH_REMATCH[4]}" != *..* ]]; then
      pushes=$((pushes + 1))
    else
      allowed=false
      break
    fi
  done < <(sed 's/&&/\n/g' <<<"$command")
  if $allowed && [[ $pushes -gt 0 ]]; then
    decide allow "A branch issue-* goes to the branch of the same name"
  fi
fi
exit 0
