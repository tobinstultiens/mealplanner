---
name: work-issues
description: Work through one or more GitHub issues of the meal planner with sub-agents - a coder per issue in its own worktree, a pull request each, an independent reviewer that merges. Use when asked to work on, implement or finish issues by number.
argument-hint: "<issue numbers>"
---

Issues to work on: $ARGUMENTS

You direct the work; you do not write the code or review it yourself. Keep your own reading to issues, briefs and reports, so the expensive session stays small.

## 1. Plan
- Read each issue (`gh issue view <n> --json title,body,labels,state,comments`). Skip and report any that is closed, labelled `postponed`, or too vague to have acceptance criteria.
- **Who codes it:** `coder` (Sonnet) by default. `coder-deep` (Opus) only when the issue touches database schema or migrations, login/OIDC or security, how accounts' data is kept apart, concurrency or transactions, or refactors across modules.
- **What may run together:** only work whose files don't overlap. Issues that touch the same screens, the same module's schema, or build on each other's result (e.g. a theme after the layouts it styles) run one after the other, the later one started after the earlier one is merged. At most three coders at a time: every one runs Gradle.
- **Splitting one issue** is worth it only when it falls into slices with disjoint files (server endpoint / shared logic / app screen). Then create and push the issue branch, give each slice its own coder and a branch off it, merge the slice branches into the issue branch yourself, and open one PR for the issue. Otherwise one coder does the issue in sequence.
- Say in two or three lines what will run in parallel, what in sequence and why, then start.

## 2. Code
Start each coder with the Agent tool, `isolation: "worktree"`, in the background, with a brief that stands on its own: the issue number, its acceptance criteria, the files or module it owns, what other agents are changing at the same time, and decisions already made. Don't paste the issue; tell it to read it.

## 3. Look (flows the screenshot tests cannot show)
A change in how a screen looks is shown by the screenshot tests: the coder re-records the reference pictures and the reviewer reads them in the PR's diff, so nothing needs attaching. Run `emulator-tester` on the branch before review only for what those tests can't show (a flow such as login or an update, a platform dialog), one PR at a time (there is one emulator), and add its findings to the PR as a comment.

## 4. Review
For each PR start a fresh `reviewer` with only the PR number; pass `model: "opus"` when `coder-deep` wrote it. It comments its verdict on the PR and merges when everything is met.
- **Changes needed:** send the findings to the same coder (SendMessage), then start a new reviewer, not the old one.
- After two rounds without approval, stop on that issue and report why, rather than loop.
- **Approved but left for the owner** (a release is required, or it touches workflows, signing or `.claude/`): when more than one such PR waits, run `merger` in plan mode and give the owner its order and what they have to do. Only when the owner says which ones to merge, start `merger` in merge mode with the PR numbers and the owner's words quoted; when the owner also asked for an app release after the merges, say so in the brief and it starts the Release workflow once CI on `main` is green, without a further prompt. That makes the owner's request the only approval: when they asked to merge PRs labelled `needs-app-release` and said nothing about a release, ask them once, before starting the merger, whether it should release afterwards. Never infer either approval.
- A failing CI run nobody understands: `ci-triage` with the run id.
- After a merge, have the coders of the PRs still open rebase on `main` before their review.

## 5. Report
First, before anything else: whether the Android app needs a release, taken from the reviewers' "App release" lines. **Required** (the merged server change breaks the installed app; the PR is left for the owner to merge, since the server deploys itself the following night) or **wanted** (app changes are merged but not on the phone yet), naming the PRs, with the reminder that a release is Actions → Release → Run workflow. Say nothing about it when no PR needs one.

Then per issue: the PR, the verdict, merged or not, and what is left. Then check that nothing was left behind: `git worktree list` and `gh pr list`.

## 6. Milestone check
After the report, see whether an audit is due: about twenty pull requests have merged since the last audit (the newest folder in `build/audit/`; its `report.md` names the commit). A release alone does not make one due. If it is due, and no coder is still running and no pull request of this run is open, start the `architect` agent once (when a release is about to be made, after it, not before) and pass on its summary and the paths of its drafts; the owner decides which drafts become issues. Otherwise don't start it: it is expensive, and it is never part of working on an issue. The owner can always ask for it directly.

## Limits
- Never merge a PR yourself, and never push to `main`, tag, release or run workflows: the reviewer merges, the merger merges what the owner approved and releases when the owner asked for it (only the merger can start a workflow).
- Don't raise an agent's model or effort beyond the rules above without the owner asking.
