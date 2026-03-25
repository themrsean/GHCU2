# REFACTOR Mode (MODE=refactor)

## Purpose

Use **refactor mode** to improve internal structure without changing external behavior.

This mode is for:

- Reducing duplication
- Improving readability
- Extracting methods/classes
- Improving naming
- Simplifying control flow
- Improving cohesion
- Reducing complexity
- Improving testability (without changing functionality)

Refactor mode must preserve:

- Public behavior
- Public APIs
- File formats
- Output structure
- Test outcomes

No functional changes are allowed.

---

## Capabilities

In `MODE=refactor`, the agent can:

- Inspect repository structure (`list_files`)
- Locate relevant code (`search_repo`)
- Read source and test files (`read_file`)
- Compile production sources (`compile_prod`)
- Compile test sources (`compile_tests`)
- Run tests (`run_tests`)
- Patch production and test files (`apply_patch`)
- Create new source or test files if required for structural changes (`write_file`)

---

## Strict Constraints

The agent must not:

- Change observable behavior
- Change method signatures unless explicitly approved
- Remove existing tests
- Change JSON schema or output formats
- Introduce new dependencies
- Perform architectural rewrites unless explicitly requested

If the change would alter behavior, switch to `MODE=plan`.

---

## Required Workflow (Strict Order)

1) Understand the current implementation
    - `search_repo`
    - `read_file`

2) Identify structural problems
   Examples:
    - Long methods
    - Duplicate logic
    - Mixed responsibilities
    - Deep nesting
    - Poor naming
    - Tight coupling

3) Produce a refactor plan summary
    - What is wrong structurally
    - Why it is problematic
    - Minimal structural improvement

4) Apply changes using `apply_patch` or `write_file`

5) Validate:
    - `compile_prod`
    - `compile_tests`
    - `run_tests`

6) Report:
    - Compilation status
    - Test results
    - Confirmation that behavior is unchanged

---

## Refactor Plan Format

Before making changes, the agent must describe:

```
Structural Issue:
...

Current Location:
- File:
- Class:
- Method:

Refactor Strategy:
...

Expected Behavioral Impact:
None (behavior preserved)
```

---

## Allowed Refactor Types

### 1) Extract Method
Split long methods into smaller private helpers.

### 2) Extract Class
Move cohesive responsibilities into a new class.

### 3) Rename for Clarity
Improve method/variable names without changing logic.

### 4) Remove Duplication
Consolidate repeated logic.

### 5) Simplify Control Flow
Reduce nesting or condition complexity.

---

## Not Allowed Without Plan Mode

- Changing method signatures
- Changing constructor behavior
- Introducing new architectural layers
- Modifying workflow orchestration
- Changing data formats
- Changing UI behavior

If any of the above are required, switch to:

```
MODE=plan
```

---

## Validation Requirements

After refactoring:

- All existing tests must pass.
- No new test failures.
- No new warnings related to missing symbols.
- No change in test discovery count.

Report results clearly.

Example:

```
Compilation:
- Production: success
- Tests: success

Execution:
- Tests found: 18
- Tests passed: 18
- Tests failed: 0

Behavior: unchanged
```

---

## Example Prompts

### Simplify a Class

```
MODE=refactor
Refactor service.WorkflowEngine to reduce method length and improve readability.
Preserve all behavior.
```

---

### Remove Duplication

```
MODE=refactor
Remove duplicated JSON loading logic across persistence classes.
Do not change behavior.
```

---

### Improve Testability

```
MODE=refactor
Refactor persistence.SettingsStore to improve separation of file IO and parsing logic.
Do not change external behavior.
```

---

## When to Switch Modes

- If behavior must change → `MODE=plan`
- If tests must be added → `MODE=test`
- If diagnosing incorrect behavior → `MODE=debug`
- If explaining architecture → `MODE=explain`

Refactor mode is for structural improvement only.