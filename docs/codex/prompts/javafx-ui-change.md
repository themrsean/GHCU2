Read `AGENTS.md` first.
Read `docs/codex/javafx-ui.md`.
Use `architecture-review` first if the UI flow is not already clear.

UI task:
[PASTE UI TASK HERE]

Before editing, explain:
- current user flow
- what changes
- what remains unchanged
- likely controllers/views/classes involved
- background-task and JavaFX-thread concerns
- verification steps

Constraints:
- preserve current workflow unless explicitly changing UX
- keep long-running work off the JavaFX application thread
- keep business logic outside controllers where practical

Do not edit code until the plan is complete.