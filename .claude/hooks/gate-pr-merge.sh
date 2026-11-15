#!/usr/bin/env bash
# PreToolUse hook for Bash: only the `reviewer` sub-agent may merge a pull request.
#
# - `gh pr merge` from anyone else (the main session, coders) is blocked.
# - From the reviewer, the plain form `gh pr merge <number> [--squash] [--delete-branch]`
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

if [[ "$agent" != "reviewer" ]]; then
  echo "Only the reviewer agent merges pull requests in this repo (.claude/hooks/gate-pr-merge.sh). Start a reviewer for the PR instead." >&2
  exit 2
fi

if grep -Eq '^[[:space:]]*gh pr merge [0-9]+( --squash| --delete-branch)*[[:space:]]*$' <<<"$command"; then
  jq -n '{hookSpecificOutput: {hookEventName: "PreToolUse", permissionDecision: "allow", permissionDecisionReason: "The reviewer agent may merge the pull request it reviewed"}}'
fi
exit 0
