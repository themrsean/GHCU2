# JavaFX UI Guidance

## Goals
- keep UI responsive
- keep business logic outside controllers where practical
- preserve current user workflow unless explicitly changing UX

## Rules
- Do not place long-running git or grading work on the JavaFX application thread.
- Prefer controllers coordinating services instead of containing core processing logic.
- Preserve current editing flows for grading reports unless the task explicitly changes them.
- Be careful with shared mutable state between UI controls and report models.

## When changing UI behavior
Document:
1. what the user currently does
2. what changes
3. what remains unchanged
4. what should be regression-tested manually

## Additional expectations
- Prefer incremental UI changes over broad controller rewrites.
- If a UI action triggers clone, grading, testing, or push-back work, explicitly identify the background execution path and UI state updates.
- Preserve report-editing affordances unless the task explicitly changes them.