# EXPLAIN Mode (MODE=explain)

## Purpose

Use **explain mode** to understand how the system works without changing or executing anything.

This mode is for:

- Understanding architecture
- Explaining a specific class or subsystem
- Tracing data flow
- Clarifying how a feature works
- Understanding why something behaves a certain way
- Onboarding or documentation purposes

This mode is strictly read-only and non-executing.

---

## Capabilities

In `MODE=explain`, the agent can:

- Inspect repository structure (`list_files`)
- Locate relevant code (`search_repo`)
- Read source and test files (`read_file`)

The agent **cannot**:

- Compile sources
- Run tests
- Write or patch files
- Modify any source or test code

---

## Required Workflow

When explaining behavior or architecture, the agent must:

1) Identify relevant files:
    - Use `search_repo` to locate entry points or symbols.
    - Use `read_file` to inspect the implementation.

2) Trace execution flow:
    - Identify the entry method.
    - Follow method calls across classes.
    - Identify side effects (file writes, JSON persistence, service calls).

3) Explain using evidence:
    - Reference file paths.
    - Reference method names.
    - Reference class responsibilities.
    - Cite relevant control flow or decision logic.

The explanation must be grounded in actual code, not assumptions.

---

## Explanation Structure

Responses in explain mode should follow this structure:

### Summary
Short description of what the component does.

### Entry Points
Where execution begins.

### Core Logic
Main responsibilities and control flow.

### Dependencies
Other classes or services used.

### Data Flow
What inputs come in and what outputs/artifacts are produced.

### Edge Cases / Constraints
Important conditional logic or limitations.

---

## Example Prompts

### Explain a Class

```
MODE=explain
Explain how persistence.SettingsStore works.
Focus on load/save behavior and file handling.
```

---

### Explain a Feature

```
MODE=explain
Explain how the "Run All" workflow operates from UI click to report generation.
Trace the full execution path.
```

---

### Explain an Error Mechanism

```
MODE=explain
Explain how errors are propagated during workflow execution.
Where are they logged?
Where are they surfaced to the UI?
```

---

## Output Expectations

A valid explanation must:

- Reference actual files and methods
- Avoid speculation
- Avoid proposing changes
- Avoid running compilation or tests
- Avoid suggesting refactors (use MODE=plan for that)

---

## When to Switch Modes

Switch to:

- `MODE=debug` if investigating incorrect behavior
- `MODE=plan` if proposing architectural changes
- `MODE=test` if adding coverage

Explain mode is for understanding only.