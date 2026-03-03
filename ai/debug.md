# DEBUG Mode (MODE=debug)

## Purpose
Use **debug mode** to reproduce failures, isolate root cause, and propose fixes **without modifying files**. This mode is for:
- Test failures or “0 tests discovered”
- Compilation errors
- Runtime errors during workflow steps
- “Works on my machine” inconsistencies (classpath, output dirs, etc.)

## Capabilities
In `MODE=debug`, the agent can:
- Inspect the repository (`list_files`, `search_repo`, `read_file`)
- Compile production sources (`compile_prod`)
- Compile tests (`compile_tests`)
- Run tests (`run_tests`)

The agent **cannot**:
- Write or patch files (no `write_file` / `apply_patch`)
- Modify production/test sources in any way

## Required debug workflow
When debugging anything test/build related, follow this standard sequence:

1) `compile_prod`
2) `compile_tests`
3) `run_tests`
4) If failure persists:
    - `search_repo` for the failing symbol / stack trace
    - `read_file` the referenced files
    - Propose a fix plan (file + change summary)

## What to include in your prompt
Provide at least one of:
- Exact command you ran
- Full error output / stack trace
- Expected behavior vs actual behavior
- Relevant context: OS, JDK version, IntelliJ configuration changes (test root, output root)

If you can, include:
- The *first failing test name*
- The *first error line*
- The *full javac/java command lines* (your agent prints these if you log them)

## Behavior & Nonfunctional Debugging (No Failing Tests)

Use this section when **nothing fails**, but behavior is wrong or a **nonfunctional requirement** is not met.

### Examples
- Feature completes without errors, but output is incorrect/incomplete
- Workflow finishes, but expected artifacts (reports/mappings/files) are missing
- UI feels laggy; performance/memory expectations are not met
- “It works” but violates an expectation (ordering, formatting, edge-case handling)

---

### Required inputs in your prompt

Provide **observable** details (avoid “it seems broken”):

- **Expectation** (what should happen, in measurable terms)
- **Actual** (what actually happens, in measurable terms)
- **Reproduction steps** (click path and/or commands)
- **Inputs/config** (paths, JSON files, settings values)
- **Acceptance criteria** (how we’ll know it’s fixed)

## Investigation workflow

Before forming conclusions, the agent must:

1) Inspect relevant files using `search_repo` and `read_file`
2) If behavior depends on runtime output, run:
    - `compile_prod`
    - `compile_tests`
    - `run_tests`
3) Base conclusions only on observed code or runtime output


### 1) Identify the system boundary and artifacts

Determine what “correct” output looks like:
- Generated files (HTML reports, extracted folders, JSON)
- Console/log output
- UI state changes (status text, list contents)
- Side effects (git clones, checkstyle output, junit results)

Use repo inspection tools to locate where artifacts are created/updated.

### 2) Trace the entry point and execution path

Find where the behavior originates:
- UI action handler (often in ui/*Controller.java)
- Workflow orchestration (service/WorkflowEngine, RunAllService, steps)
- Persistence (persistence/*Store.java)
- Report generation (service/ReportService.java)

Preferred approach:

- search_repo for UI label text / menu item / method name
- read_file around the event handler
- trace which services are invoked and what inputs they use

### 3) Form hypotheses and verify by reading code

Common root causes when “nothing fails”:

- Wrong or stale path (settings, mapping, output directory)
- Silent skip due to empty list/filter/condition
- Exception swallowed/logged but not surfaced in UI
- State not refreshed (cached objects, outdated mappings)
- Threading/timing issue (JavaFX thread vs background task ordering)

Verify by identifying:
- conditional branches that skip work
- where inputs are loaded (settings/JSON)
- where output is written and under which conditions

### 4) Produce a fix plan (no edits in debug mode)

Deliverable in `MODE=debug` must include:

- Root cause statement (single sentence)
- Evidence (file path + line numbers)
- Minimal fix plan:
    - File:
    - Method:
    - Change summary:
- Validation steps (exact commands to run)

Do not propose architectural rewrites in debug mode.
Only suggest the smallest change necessary to satisfy the expectation.

### 5) Convert the expectation into an executable check (preferred)

After root cause is understood, capture the expectation as a test when feasible:

- Functional expectations → unit/integration-ish tests using @TempDir
- Missing artifacts → tests that assert files exist with expected content
- JSON/schema behavior → tests using fixtures under src/test/resources

Hand-off to test writing mode:
```
MODE=test
CONFIRM_WRITE=true
Create a new test that encodes the expectation using @TempDir and/or fixtures under src/test/resources.
After writing, run compile_prod, compile_tests, run_tests and report results.
```
#### Performance and other nonfunctional requirements

For performance/latency/memory:

- First, identify the likely hot path and inputs.
- Prefer deterministic checks (e.g., “does not perform repeated IO” / “does not re-parse JSON N times”).
- If adding time-based thresholds, keep them conservative and environment-aware.

If the requirement is too environment-dependent for a unit test, produce:

- a benchmark plan (how to measure)
- instrumentation plan (what to log, where)
- a regression guard (e.g., “avoids O(N^2) loop” via structural assertions)

#### Output format expected from the agent

A good response for “wrong behavior but no failures” includes:

- What was inspected (files and key lines/conditions)
- The execution path (entry point → services → artifacts)
- The most likely root cause(s) with evidence
- Minimal fix plan + validation steps
- Optional: how to encode as tests (file path + test idea)

## When Debug Mode Is Not Enough

Switch modes if:

- A code change is clearly required → use `MODE=test`
- A new regression guard should be written → use `MODE=test`
- You want a design-level analysis → use `MODE=plan`
- You want architectural explanation → use `MODE=explain`

## Prompt templates

### Reproduce a failing test
```text
MODE=debug
Reproduce the failing test run and identify the root cause.
Run compile_prod, compile_tests, run_tests.
Here is the failure output:
<PASTE OUTPUT>
```

### No failing test - Nonfunctional
```text
MODE=debug
Expectation:
- ...

Actual:
- ...

Repro steps:
1) ...
2) ...

Inputs/Config:
- ...

Acceptance criteria:
- ...
```
