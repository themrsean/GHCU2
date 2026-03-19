# TEST Mode (MODE=test)

## Purpose

Use **test mode** to create or modify unit tests under controlled conditions.

This mode is for:

- Adding new test coverage
- Encoding expected behavior into tests
- Creating regression tests
- Adding edge case validation
- Expanding coverage for a specific class or workflow

This mode **must not modify production source files**.

---

## Capabilities

In `MODE=test`, the agent can:

- Inspect repository structure (`list_files`)
- Locate relevant code (`search_repo`)
- Read source/test files (`read_file`)
- Compile production sources (`compile_prod`)
- Compile test sources (`compile_tests`)
- Run tests (`run_tests`)
- Create new test files (`write_file`)
- Patch existing test files (`apply_patch`)

The agent **cannot**:

- Modify production source files under `src/main/java`
- Modify files outside:
    - `src/test/java`
    - `src/test/resources`

---

## Required Workflow (Strict Order)

When writing or modifying tests, the agent must:

1) Inspect the target production class:
    - `search_repo`
    - `read_file`

2) Design the test strategy:
    - Identify behaviors to validate
    - Identify edge cases
    - Identify failure modes
    - Identify required fixtures or temp directories
    - Identify private classes that will need Reflection to access

3) Create or patch test files:
    - Use `write_file` for new test classes
    - Use `apply_patch` for modifications to existing test files
    - Do not create new test files for classes if there is an existing test file

4) Validate:
    - `compile_prod`
    - `compile_tests`
    - `run_tests`

5) Report:
    - Number of tests discovered
    - Number passed/failed
    - Any compilation issues
    - If failures occur, stop and report

---

## File Location Rules

All new test files must be placed under:

- `src/test/java/<matching package>/`

Test fixtures must be placed under:

- `src/test/resources/`

The package of the test must match the production package structure.

Example:

Production:
```
src/main/java/persistence/SettingsStore.java
```

Test:
```
src/test/java/persistence/SettingsStoreTest.java
```

---

## Test Design Requirements

Tests must:

- Use JUnit 6 annotations (`@Test`)
- Avoid relying on external filesystem state
- Prefer `@TempDir` for file-based testing
- Avoid modifying real user directories
- Be deterministic (no random data unless seeded)
- Avoid sleep-based timing checks

---

## Types of Tests to Prefer

### 1) Behavior Tests
Validate public method behavior.

### 2) Edge Case Tests
- Empty input
- Null handling (if applicable)
- Invalid format
- Missing file
- Boundary values

### 3) Regression Tests
Capture previously broken behavior so it cannot reoccur.

### 4) Artifact Tests
If a method writes files:
- Verify file exists
- Verify content structure
- Verify expected values

---

## Patch Rules

When modifying an existing test file:

- Use `apply_patch`
- Do not rewrite the entire file unless necessary
- Preserve existing tests
- Do not remove tests unless explicitly instructed

---

## Output Requirements

After running tests, report:

- Compilation status
- Test discovery count
- Tests started
- Tests passed
- Tests failed
- Any stack traces

Example format:

```
Compilation:
- Production: success (49 files)
- Tests: success (3 files)

Execution:
- Tests found: 12
- Tests passed: 12
- Tests failed: 0
- Tests aborted: 0
```

---

## When to Refuse

The agent must refuse if:

- The prompt attempts to modify production source
- The prompt attempts to write outside allowed directories
- `CONFIRM_WRITE=true` is not present
- The requested change requires architectural modification (switch to `MODE=plan`)

---

## Prompt Templates

### Add Coverage to Existing Class

```
MODE=test
CONFIRM_WRITE=true
Add comprehensive tests for persistence.SettingsStore.
Cover edge cases and file persistence behavior.
```

---

### Create New Test File

```
MODE=test
CONFIRM_WRITE=true
Create a new test class for service.WorkflowEngine.
Focus on step sequencing and failure handling.
```

---

### Add Regression Test

```
MODE=test
Add a regression test for the bug where reports were skipped if the mapping file was empty.
```

---

## Transition Back to Debug

If tests fail after writing:

Switch to:

```
MODE=debug
```

Reproduce and isolate before attempting further changes.