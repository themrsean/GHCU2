# Build, Run, and Test

## Important
This repository does not use Maven or Gradle.

Codex should discover and use the project’s real compile, run, and test commands from this repository.
Do not invent a new build workflow unless explicitly asked.

## Command discovery order
When compile, run, or test commands are needed, check in this order:
1. existing scripts in the repository
2. README or developer documentation
3. IDE project metadata if it clearly defines the workflow
4. current source layout and import/module structure

If multiple valid workflows exist, prefer the one already documented for contributors.

## Expected verification approach

### For source changes
- compile only the relevant source set first
- compile the whole app only if needed

### For UI changes
- prefer compile verification plus targeted manual-path reasoning
- avoid broad rewrites of JavaFX controller behavior

### For grading pipeline changes
- verify using the smallest representative repository, fixture, or test path available

### For git or push logic changes
- prefer dry-run or non-destructive validation where possible

## JavaFX note
The runtime may require explicit JavaFX module configuration such as:
- module path
- add-modules flags

Use the project’s existing conventions if present.
Do not invent new JavaFX launch commands if the repository already defines them.

## Testing note
If there is no single automated test command, prefer the smallest available verification that exercises the changed path.
State clearly what was and was not verified.