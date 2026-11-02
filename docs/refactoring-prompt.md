# The State-pattern refactoring prompt (step 8)

The assignment asks each team to try variations of a refactoring prompt with a web-based LLM until
the result is satisfactory, and then use the final prompt in the agent system. We kept every
version in `agent_system/prompts/refactor_versions/` and scored them with the prompt lab:

    python -m agent_system lab <run-id>

The lab renders each version for the tested SeasonSwitch code and takes the LLM's reply. With
`LLM_PROVIDER=manual` it writes `prompt.md`; you paste that into the web LLM and save the answer as
`reply.md`. It then measures:

- does the refactored code compile?
- does it pass the 8 State pattern checks (javap)?
- how many of the 51 accepted test cases compile unchanged against it, and how many pass?
- the branch coverage of the refactored code by those tests.

## Versions

| Version | What it adds | Why |
|---|---|---|
| P0 | Only the task and the code ("Refactor ... using the State design pattern") | Baseline |
| P1 | A role, and the participants of the State pattern with their responsibilities | Get the full pattern (abstract State, one class per state, delegation) |
| P2 | Hard constraints: keep the class name, package and exact public interface; no new public members (helpers package-private); no `switch`/`if` chain or `instanceof` on the state; same behaviour; Java 17; keep `main` | The assignment's hint: the existing tests must run unchanged |
| P3 | The exact public interface from javap, and an output format: a design section with a transition table, then each file in its own ` ```java ` block with a `// File:` line | Gives the agent a parseable reply, and gives the reviewers the design |

## Results (run seasonswitch-2026-10-28; the replies came from Claude Opus 5.5)

| Version | Compiles | State pattern checks | Tests compile unchanged | Tests pass | Branch coverage |
|---|---|---|---|---|---|
| P0 | yes | 6/8 | 10/51 | 10/10 | 14/16 (88%) |
| P1 | yes | 7/8 | 44/51 | 44/44 | 16/16 (100%) |
| P2 | yes | 8/8 | 44/51 | 44/44 | 16/16 (100%) |
| P3 | yes | 8/8 | 44/51 | 44/44 | 16/16 (100%) |

- **P0** produced a clean State pattern, but it also "tidied up" the class. It made the fields
  private and added eight public getters/setters, which broke the public-interface check and 41 of
  the 51 tests (the tests use the package-private fields `ac`, `heater`, `room`, `timer`).
- **P1** kept the fields but added a public `setState(...)`. The interface was no longer exactly the
  same.
- **P2 and P3** passed all 8 checks. The only tests that no longer compile are the 7 that put an
  invalid int into `state` to reach the hidden `default` branch of each old `switch`. After the
  refactoring, `state` is a `SeasonState` object and there is no `switch` left, so these tests are
  obsolete. In step 10 the test adapter removed them and logged the reason.

**Final prompt: P3** (`agent_system/prompts/refactor_state_pattern.md`). It scores the same as P2.
It adds the exact API and an output format the agent parses reliably, plus a transition table for
the step-9 review. In the run, the team accepted the refactoring in the first review round.

These are one reply per version from one model. The team can score the same prompts with their own
web LLM (`lab <run-id> --lab-id <name>`, paste each `prompt.md`, save `reply.md`, run the lab again) or
with Ollama (`LLM_PROVIDER=ollama`, also with a new `--lab-id`, because existing replies are reused).
