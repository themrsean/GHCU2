# Grading Pipeline Guidance

## Main concerns
- repository layout assumptions
- test execution reliability
- result-to-report mapping
- preserving instructor edits and comments
- safe push-back behavior

## Rules
- Treat report format as stable unless explicitly changing it.
- Treat instructor-authored comments as durable data that should not be overwritten accidentally.
- Distinguish between:
    - raw automated results
    - derived grading decisions
    - instructor edits
- Preserve existing semantics around when a report is generated, updated, or pushed.

## When changing grading logic
Always identify:
1. input assumptions
2. output format impact
3. backward compatibility risks
4. verification steps

## Safety expectations
- Be conservative when changes affect both report generation and report editing.
- Avoid changes that could erase or regenerate instructor-edited content without an explicit migration or merge strategy.
- Treat push-back behavior as high risk and verify the write path separately from the generation path when practical.