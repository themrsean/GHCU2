---
name: architecture-review
description: analyze architecture, plan changes, map code flow, and identify affected subsystems in this javafx github classroom grading application. use when the task is planning, code understanding, impact analysis, design review, or when codex should understand the system before editing code.
---

# Architecture Review

Read these first:
- `AGENTS.md`
- `docs/codex/project-map.md`
- `docs/codex/build-run-test.md`

Also read when relevant:
- `docs/codex/javafx-ui.md`
- `docs/codex/grading-pipeline.md`

## Required behavior
- Do not edit code until the plan is complete.
- Identify the subsystem or subsystems involved before inspecting files:
   - JavaFX UI
   - GitHub or git operations
   - repository processing
   - automated testing
   - report generation and editing
   - push-back or sync workflow
- Read only the smallest relevant set of files needed to understand the task.
- Prefer minimal-change plans over broad redesigns.
- Call out any risk to:
   - JavaFX responsiveness
   - grading report format
   - instructor comments or edits
   - git safety
   - repository layout assumptions

## Output format
Produce:
1. current flow summary
2. affected subsystems
3. likely files or classes involved
4. constraints and invariants
5. risks and edge cases
6. minimal implementation plan
7. verification plan

## Rules
- If docs and code disagree, trust the code and note the discrepancy.
- Do not propose Maven or Gradle unless explicitly requested.
- If compile, run, or test commands matter for the plan, use the repository’s existing workflow rather than inventing one.