Read AGENTS.md first.

Implement the approved fix for the selected issue only.

Constraints:
- minimal change
- preserve subsystem boundaries
- preserve report format unless explicitly required
- preserve instructor-authored comments
- do not introduce risky git behavior
- follow the Checkstyle rules referenced by AGENTS.md
- do not invent Maven, Gradle, or new build commands

After implementation:
1. run the smallest relevant verification path
2. report exactly what was verified
3. summarize each changed file and why
4. list remaining risks or unverified assumptions