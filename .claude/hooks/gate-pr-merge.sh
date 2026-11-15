#!/usr/bin/env bash
# PreToolUse hook for Bash: only the `reviewer` and `merger` sub-agents may merge a pull request.
#
# The reviewer merges what it approved; the merger lands, on the owner's approval, what
# reviewers approved but left for the owner.
#
# - `gh pr merge` from anyone else (the main session, coders) is blocked.
# - From those two, the plain form `gh pr merge <number> [--squash] [--delete-branch]`
#   is approved without a prompt; anything fancier goes through the normal permission flow.
# - Every other command is left alone.
#
# The owner can still merge by hand from their own terminal.
set -euo pipefail

input=$(cat)
command=$(jq -r '.tool_input.command // ""' <<<"$input")
agent=$(jq -r '.agent_type // ""' <<<"$input")

# `gh pr merge` at the start of the command or of a chained part (after ; & | or an opening bracket).
if ! grep -Eq '(^|[;&|(])[[:space:]]*gh[[:space:]]+pr[[:space:]]+merge([[:space:]]|$)' <<<"$command"; then
  exit 0
fi

if [[ "$agent" != "reviewer" && "$agent" != "merger" ]]; then
  echo "Only the reviewer and merger agents merge pull requests in this repo (.claude/hooks/gate-pr-merge.sh). Start a reviewer for the PR, or the merger once the owner approved merging." >&2
  exit 2
fi

if grep -Eq '^[[:space:]]*gh pr merge [0-9]+( --squash| --delete-branch)*[[:space:]]*$' <<<"$command"; then
  jq -n '{hookSpecificOutput: {hookEventName: "PreToolUse", permissionDecision: "allow", permissionDecisionReason: "The reviewer and merger agents may merge pull requests"}}'
fi
exit 0
