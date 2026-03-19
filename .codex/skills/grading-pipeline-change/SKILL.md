---
name: grading-pipeline-change
description: plan and implement changes to the github classroom grading pipeline in this application, including repository processing, automated testing, report generation, report editing, and safe push-back behavior. use when codex should make a feature or behavior change in the grading workflow.
---

# Grading Pipeline Change

Read these first:
- `AGENTS.md`
- `docs/codex/project-map.md`
- `docs/codex/build-run-test.md`
- `docs/codex/grading-pipeline.md`

Also read when relevant:
- `docs/codex/javafx-ui.md`

## Required behavior
- Map the end-to-end flow before editing code.
- Identify all affected subsystems:
   - repository processing
   - automated testing
   - report generation
   - report editing
   - push-back or sync workflow
   - JavaFX UI if user interaction changes
   - GitHub or git operations if repository behavior changes
- State whether the change affects:
   - repository layout assumptions
   - automated test execution
   - report schema or format
   - instructor-authored comments or edits
   - push-back behavior
- Prefer additive, backward-compatible changes when practical.
- Keep write-path changes more conservative than read-path changes.

## Output format
Produce:
1. requested change
2. current affected flow
3. affected subsystems
4. likely files or classes involved
5. compatibility risks
6. minimal implementation plan
7. verification plan
8. changed-file summary after implementation

## Rules
- Preserve report format unless explicitly asked to change it.
- Preserve instructor-authored comments and edits.
- Be conservative with commit, push, overwrite, and sync logic.
- Separate report-generation logic from report-editing logic when possible.
- If docs and code disagree, trust the code and note the discrepancy.
- Do not invent new compile, run, or test commands when existing ones can be discovered in the repo.