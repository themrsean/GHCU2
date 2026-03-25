# PLAN Mode (MODE=plan)

## Purpose

Use **plan mode** to design solutions before writing or modifying code.

This mode is for:

- New features
- Structural changes
- Refactors
- Architectural decisions
- Large test additions
- Workflow changes
- Nonfunctional improvements (performance, scalability, reliability)

Plan mode is **read-only**. No code is modified.

## Capabilities

In `MODE=plan`, the agent can:

- Inspect repository structure (`list_files`)
- Locate relevant code (`search_repo`)
- Read specific files (`read_file`)

The agent **cannot**:

- Compile sources
- Run tests
- Write or patch files
- Modify any source or test code

## Required Planning Workflow

The agent must follow this sequence:

### 1) Scope Clarification

Identify:

- What is being built/changed
- What constraints exist (tooling, directories, write limits)
- Whether the change affects:
  - UI
  - Services
  - Persistence
  - Reports
  - Workflow engine
  - Tests

If unclear, ask clarifying questions before proceeding.

### 2) Repository Analysis

Before proposing a solution, the agent must:

- `search_repo` for relevant classes/interfaces
- `read_file` core entry points
- Identify existing patterns to follow

Plans must align with current architecture — not invent new patterns unless justified.

### 3) Proposed Design

Deliver a structured plan containing:
- Summary (1–2 paragraphs)
- Files to modify or create
- Methods/classes impacted
- Data flow changes (if any)
- Dependency implications
- Risk assessment

Format:
```
Summary:
...

Files:
- src/main/java/...
- src/test/java/...

Changes:
- Class:
    - Method:
    - Responsibility:

Data Flow:
...

Risks:
...

Validation Strategy:
...
```
### 4) Test Strategy

Every plan must include one of:

- New unit tests
- Integration-style tests
- Regression tests
- Explanation of why no new tests are required

Tests must be located under:

- src/test/java
- src/test/resources (fixtures)

### 5) Migration / Compatibility Considerations (if applicable)

If the change affects:

- JSON formats
- Settings files
- Output directory structure
- Serialized data
- UI expectations

The plan must describe:

- Backward compatibility impact
- Required migration steps
- Versioning considerations

## Prompt Templates
### New Feature
```
MODE=plan
Design a feature that allows ...
Constraints:
- ...
  Existing behavior:
- ...
  Output requirements:
- ...
```
  ### Refactor
```
  MODE=plan
  Refactor <class or subsystem> to improve ...
  Do not change external behavior.
  Identify minimal structural improvements.
  ```
  ### Add Test Coverage
```
  MODE=plan
  Design a test suite for <class>.
  Identify edge cases, failure modes, and integration boundaries.
  ```
  ### Architectural Evaluation
```
  MODE=plan
  Evaluate the current design of <subsystem>.
  Identify structural weaknesses and propose improvements.
```
## Output Expectations

A valid plan response must:

- Reference existing files
- Align with repository structure
- Avoid speculative redesigns
- Propose minimal viable changes
- Include a test strategy
- Be implementation-ready

## Transition to Implementation

After approving a plan:

- For test-only changes → switch to `MODE=test`
- For production edits (if enabled later) → switch to a write-enabled mode
- After implementation → run:
  - `compile_prod`
  - `compile_tests`
  - `run_tests`

Example:
```
MODE=test
CONFIRM_WRITE=true
Implement the approved test additions from the plan.
```