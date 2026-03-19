---
name: safe-refactor
description: refactor this java and javafx codebase in small behavior-preserving steps while keeping ui, grading, reporting, and git responsibilities separated. use when codex should clean up code, extract logic, reduce duplication, or simplify structure without intentionally changing behavior.
---

# Safe Refactor

Read these first:
- `AGENTS.md`
- `docs/codex/project-map.md`
- `docs/codex/javafx-ui.md`

Read this before changing Java files:
- the Checkstyle file referenced by `AGENTS.md`

Also read when relevant:
- `docs/codex/grading-pipeline.md`
- `docs/codex/build-run-test.md`

## Required behavior
- Identify the current pain points before editing.
- Preserve subsystem boundaries:
    - JavaFX UI
    - GitHub or git operations
    - repository processing
    - automated testing
    - report generation and editing
    - push-back or sync workflow
- Prefer extracting business logic out of JavaFX controllers when practical.
- Make the smallest useful behavior-preserving changes.
- Keep refactors staged and easy to review.
- Summarize each changed file and why it changed.

## Output format
Produce:
1. current smell or maintenance problem
2. subsystem boundary to preserve
3. staged refactor plan
4. files to touch
5. behavior-preservation notes
6. verification plan
7. changed-file summary after implementation

## Rules
- No opportunistic rewrites.
- No new build system.
- No report format changes unless explicitly requested.
- No risky git behavior changes unless explicitly requested.
- Prefer existing project patterns over introducing new abstractions without need.
- If docs and code disagree, trust the code and note the discrepancy.