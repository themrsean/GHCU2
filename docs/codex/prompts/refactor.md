Use the repo-local skill `safe-refactor`.

Read `AGENTS.md` first.
Read the Checkstyle file referenced by `AGENTS.md` before changing Java files.

Refactor target:
[PASTE CLASS / PACKAGE / AREA HERE]

Goals:
- preserve behavior
- improve readability and maintainability
- reduce duplication
- preserve subsystem boundaries
- keep business logic out of JavaFX controllers where practical

Process:
- identify the current maintenance problems first
- propose a staged refactor plan
- make only the smallest useful changes
- verify the changed path using the smallest relevant compile/test flow
- summarize each changed file and why

Do not do opportunistic rewrites.
Do not introduce Maven or Gradle.