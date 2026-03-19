# Project Map

## Purpose
This application manages instructor-side grading workflows for GitHub Classroom repositories.

## Main end-to-end flow
1. Instructor selects or configures an assignment.
2. Application clones or updates student repositories.
3. Application processes repository contents.
4. Application runs automated tests or grading checks.
5. Application generates grading reports.
6. Instructor reviews and edits reports and adds comments.
7. Application commits and pushes report changes back to repositories when requested.

## Architectural invariants
- Long-running clone, processing, grading, and push-back work should not block the JavaFX application thread.
- UI code should coordinate workflows, not contain core grading or git business logic.
- Report generation and report editing must preserve instructor-authored content unless a task explicitly changes that behavior.
- Git write operations are high risk and should remain isolated and reviewable.

## Subsystems

### JavaFX UI
Responsible for:
- screens
- dialogs
- table views
- edit and report interactions
- user-triggered workflow actions

### GitHub / Git
Responsible for:
- clone, fetch, and pull
- branch and working tree state
- commit and push of generated reports
- repository-level safety checks

### Submission Processing
Responsible for:
- locating relevant files
- normalizing student repository contents
- coordinating grading and test steps

### Automated Testing
Responsible for:
- launching checks
- capturing output
- mapping results into report data
- handling failures, timeouts, and partial results

### Report Generation and Editing
Responsible for:
- creating report models
- serializing report content
- preserving editable comments
- presenting report data in the UI

### Push-Back Workflow
Responsible for:
- turning report edits into repository changes
- safe commit creation
- safe push logic and conflict handling

## Risk hotspots
- UI code that also performs file or git operations directly
- shared mutable report state between UI and processing code
- assumptions about repository layout across different assignments
- destructive git behavior
- long-running operations on the JavaFX application thread