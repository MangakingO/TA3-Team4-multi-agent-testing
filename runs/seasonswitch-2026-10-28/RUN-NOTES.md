# How this run was made

- **Agent system:** this repository, unchanged during the run (`python -m agent_system run ...`, then `resume`).
- **Language model:** `LLM_PROVIDER=manual`. Each agent's prompt was written to `llm/NNN-<agent>.prompt.md`
  and answered by **Claude Opus 5.5**. The answer was saved as the matching `.response.md`. The prompts
  are exactly what the system generated; the agents' JSON outputs were validated by the system.
- **Human-in-the-loop decisions** (test review rounds 1-2, refactoring review round 1) were made by Claude
  on the team's behalf, at the team's request. The answers are kept next to the review files
  (`*.answer.used.txt`). Re-run with your own reviews to make these decisions yourselves.
- **Build:** Maven 3.9.11 and JDK 21 in a sandbox without access to Maven Central, so the projects were
  built with an offline build file (Ubuntu-packaged Surefire 2.22, JUnit 5.10, JaCoCo 0.8.11 agent and
  report) that has the same layout and outputs as `season-switch/pom.xml`. The `pom.xml` files kept in
  this folder are the real one (JUnit 5.14.4, Surefire 3.5.5, JaCoCo 0.8.15), so `mvn test` reruns
  any phase.
- **Unanswered prompt:** none. Every `*.prompt.md` in `llm/` has a response.

Timeline: setup and observation; EP and BVA agents in parallel; curation (1 duplicate); test review
(1 test added); test run 1 (REVIEW-01 failed); repair 1 (code: `state` made an instance field);
test run 2 (7 tests did not compile); repair 2 (tests set the state on the object); test run 3 (51/51);
coverage 36/36; refactoring (final prompt P3); refactoring review (accepted); test adapter (7 obsolete
tests removed); refactored test run 1 (44/44); coverage 16/16.
