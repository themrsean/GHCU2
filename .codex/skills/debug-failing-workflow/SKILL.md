---
name: debug-failing-workflow
description: investigate bugs, failing grading runs, report issues, javafx problems, and git or github classroom workflow failures in this application. use when codex should diagnose a failure, identify root cause, propose the smallest safe fix, and define regression checks before or during implementation.
---

# Debug Failing Workflow

Read these first:
- `AGENTS.md`
- `docs/codex/project-map.md`
- `docs/codex/build-run-test.md`

Also read when relevant:
- `docs/codex/javafx-ui.md`
- `docs/codex/grading-pipeline.md`

## Required behavior
- Start by restating the failure clearly.
- Identify reproduction steps before proposing a fix.
- Classify the failing subsystem:
   - JavaFX UI
   - GitHub or git operations
   - repository processing
   - automated testing
   - report generation and editing
   - push-back or sync workflow
- Inspect the smallest relevant code path first.
- State the root-cause hypothesis before editing.
- Prefer the smallest safe fix over a broad rewrite.
- If verification is possible, run the smallest relevant compile or test path first.

## Output format
Produce:
1. reproduction path
2. failing subsystem
3. root-cause hypothesis
4. likely files, classes, or functions involved
5. smallest safe fix
6. regression check
7. remaining risks or unknowns

## Rules
- Preserve report format unless the bug is in report formatting.
- Preserve instructor-authored comments unless the bug is in comment handling.
- Be conservative around clone, commit, push, overwrite, and force-push behavior.
- For UI bugs, explicitly note any JavaFX application thread or background task concerns.
- If docs and code disagree, trust the code and note the discrepancy.