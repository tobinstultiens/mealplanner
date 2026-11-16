#!/usr/bin/env bash
# PreToolUse hook for Bash: nothing reaches `main` by a push without the owner being asked.
#
# `main` changes through a pull request. A permission rule cannot tell the destination of a push
# (`git push origin issue-1:main` starts like `git push origin issue-1`), so this hook reads it:
#
# - The plain push of an `issue-*` branch to the branch of the same name, alone in the command,
#   is approved without a prompt: `git push [-u | --force-with-lease] origin issue-<name>`.
# - A push with any refspec whose destination is `main` (`src:main`, `HEAD:main`, `+src:main`,
#   `refs/heads/main`, `--force`, flags before the remote, inside a chain or quotes) always asks.
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

# The plain form, nothing else in the command.
if grep -Eq '^[[:space:]]*git push( -u| --force-with-lease)? origin issue-[A-Za-z0-9._/-]+[[:space:]]*$' <<<"$command"; then
  decide allow "A branch issue-* goes to the branch of the same name"
fi

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
exit 0
