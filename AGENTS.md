# AGENTS.md

## Project purpose
This repository is a desktop Java application with a JavaFX user interface.

The application:
- clones student repositories from GitHub Classroom assignments
- processes the repositories locally
- runs automated checks and tests
- generates grading reports
- allows instructors to edit reports and add comments
- pushes report changes back to student repositories when requested

## Instruction precedence
Use these instructions in this order:
1. direct user task requirements
2. repository reality as shown by the code and existing scripts
3. this `AGENTS.md` file
4. supporting docs under `docs/codex/`

If documentation and code disagree, trust the current codebase and note the discrepancy.

## Working style
- Prefer minimal, safe, reviewable changes.
- Do not rewrite large areas of the codebase unless the task explicitly asks for it.
- Before changing code, first identify the subsystem being changed:
    - JavaFX UI
    - GitHub / git operations
    - repository processing
    - automated testing
    - report generation and editing
    - push-back / sync workflow
- Avoid mixing unrelated subsystem changes in one pass.
- For debugging tasks, explain the root-cause hypothesis before applying a fix.
- For refactors, preserve behavior unless a behavior change is explicitly requested.

## Coding standards
- Follow the Checkstyle configuration located at:
    - `lib/MSOE_checkStyle.xml`
- If the repository later includes a local Checkstyle file, prefer the repo-local file over the remote URL.
- If the task changes Java source files, read the Checkstyle file before editing.
- Prefer changes that satisfy both the existing project style and the Checkstyle rules.
- If style guidance in this file and Checkstyle appear to conflict, treat Checkstyle as authoritative for formatting and naming, and this file as authoritative for workflow and architecture expectations.
- When using the remote Checkstyle file, summarize the most relevant applicable rules before making non-trivial Java edits.

## Build and run
This repository does not use Maven or Gradle.

Before making changes, locate and use the project’s documented compile and run commands.
If they are missing, identify the current manual compile/run workflow from the repository before editing code.
Prefer existing scripts, README instructions, and project docs over inventing new commands.

Expected environment concerns:
- JavaFX module path may need to be supplied manually
- tests may be launched through project-specific scripts or direct Java commands
- some workflows may depend on local git availability and authenticated GitHub access

When a task requires verification:
- compile the changed code if possible
- run the smallest relevant verification command first
- prefer targeted verification over broad expensive runs

## Architecture expectations
Preserve separation of concerns:

- UI layer:
    - JavaFX views, controllers, dialog logic, user interactions
- GitHub / git layer:
    - clone, fetch, branch, commit, push, pull, authentication-related integration
- Processing layer:
    - repository scanning, submission analysis, orchestration
- Testing layer:
    - invoking automated checks, capturing results, handling failures, and timeouts
- Reporting layer:
    - grading report generation, loading, editing, serialization, comments
- Sync layer:
    - writing report changes back to repositories safely

Do not move business logic into JavaFX controllers unless the task explicitly requires it.
Prefer extracting reusable logic out of controllers into plain Java classes.
Do not place long-running clone, grading, or test execution work on the JavaFX application thread.

## Data and behavior safety
- Preserve grading report format unless the task explicitly changes the format.
- Preserve existing comment and edit behavior unless the task explicitly changes it.
- Be careful with destructive git operations.
- Do not delete student work, overwrite repositories, or force-push unless the task explicitly requires it and the implementation clearly scopes the risk.
- Prefer dry-run style logic for git write operations when adding new functionality.

## Debugging expectations
When debugging:
1. identify reproduction steps
2. identify the failing subsystem
3. state the likely root cause
4. propose the smallest safe fix
5. describe the regression check

## Refactoring expectations
When refactoring:
- preserve public behavior
- preserve report output unless explicitly changing it
- keep refactors staged and easy to review
- summarize why each changed file was touched

## Planning expectations
For planning or architecture tasks, do not write code first.
Produce:
1. current flow summary
2. files or classes likely involved
3. constraints and risks
4. proposed change plan
5. verification plan

## Helpful project docs
When relevant, also read:
- `docs/codex/project-map.md`
- `docs/codex/build-run-test.md`
- `docs/codex/javafx-ui.md`
- `docs/codex/grading-pipeline.md`