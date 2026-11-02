# TA3: a multi-agent system that tests and refactors SeasonSwitch

CSE 4392/5320 Software Engineering with Generative AI, Team Assignment 3 (Team 4).

A LangGraph workflow of LLM agents and deterministic tools that:

1. designs **equivalence partitioning** and **boundary value analysis** test cases with two agents running in parallel (steps 1-2),
2. joins them and removes duplicates (step 3),
3. asks the **tester** to review them (step 4, human in the loop),
4. runs them with Maven and JUnit 5, repairing the code or the tests until all pass, and logs every change (steps 5-6),
5. measures **branch coverage** with JaCoCo and adds tests until it is 100% (step 7),
6. refactors SeasonSwitch with the **State pattern** using the team's final prompt (step 8),
7. asks the **team** to review the refactored code (step 9, human in the loop),
8. reuses and adapts the tests on the refactored code, repairs, and reaches 100% branch coverage again (steps 10-12).

Python agent system; Java tests and refactored code. The language model runs locally with **Ollama**.

## Deliverables (what to submit)

`R` = `runs/seasonswitch-2026-10-28/final` (the recorded run).

| Deliverable | Where |
|---|---|
| Presentation | `deck/TA3-Multi-Agent-Testing-Team4.pptx` (regenerate with `node deck/build_deck.js`) |
| Agent system code | `agent_system/`, with `tests/`, `requirements.txt`, `.env.example` and this README |
| Test cases | `R/original/test-cases.md` and `R/refactored/test-cases.md` (also `.json`; the JUnit class is in `src/test/java`) |
| Test results | `R/original/test-results/` and `R/refactored/test-results/` (`summary.md`, Surefire XML) |
| Coverage | `R/original/coverage/` and `R/refactored/coverage/` (`summary.md`; open `jacoco/index.html`) |
| Architecture diagrams | `docs/diagrams/`: `architecture.png` (components), `architecture-flow.png` (runtime flow), `langgraph.png` (the compiled graph), `datamodel.png` (data model); see [Architecture](#architecture) |
| UML activity diagram | `docs/diagrams/activity.png` (and the two halves `activity-part1.png`, `activity-part2.png`) |
| Prompt that generated the system | `docs/agent-system-generation-prompt.md` |
| Refactoring prompt (step 8) | final: `agent_system/prompts/refactor_state_pattern.md`; versions and scores: `docs/refactoring-prompt.md`, `prompt-lab/` |
| Every change to code or tests | `R/changes.md` |

## Requirements

| Tool | Version | Check |
|---|---|---|
| Python | 3.10 or later (3.11+ recommended) | `python --version` |
| JDK | 17 or later | `java -version` and `javac -version` |
| Maven | 3.9 or later | `mvn -v` |
| Ollama | 0.5 or later | `ollama --version` |

```bash
ollama pull qwen2.5-coder:7b      # 4.7 GB; any model with good Java works (set LLM_MODEL)
```

## Install

```bash
python -m venv .venv
# Windows: .venv\Scripts\activate      macOS/Linux: source .venv/bin/activate
pip install -r requirements.txt
copy .env.example .env               # macOS/Linux: cp .env.example .env
```

The first Maven build downloads JUnit, JaCoCo and the Maven plugins, so it needs internet once.

## Observe the program (What To Do, item 2)

```bash
cd season-switch
mvn compile exec:java                          # no argument = cool; stop with Ctrl+C
mvn compile exec:java -Dexec.args="heat"       # also: cool, off, or any other word
```

The agent system observes the program the same way in its setup step (each run stops after 4 s).

## Run the agent system

```bash
python -m agent_system run season-switch/src/main/java/cse4392/SeasonSwitch.java \
    --observe "" --observe cool --observe heat --observe off --observe warm
```

The run prints each step. It **pauses** at every human-in-the-loop point:

- **Step 4, test review:** read `runs/<run-id>/original/review/round-N.md`, then answer `a` (accept),
  `r` (request changes in plain words), or `e` (you edited `original/test-cases.json` yourself).
- **Step 9, refactoring review:** read `runs/<run-id>/refactored/review/round-N.md`, which has the
  code and the automated State pattern checks. Answer `a` (accept), `f` (feedback, regenerate), `p`
  (you edited the prompt file, regenerate), or `c` (you edited the code, re-check).
- **A loop does not converge** (repair or coverage limit reached): `c` continue or `s` stop.

You can stop at a pause and continue later, even from another terminal:

```bash
python -m agent_system status <run-id>    # where the run is and what it waits for
python -m agent_system resume <run-id>    # continue (answers can also go in round-N.md.answer.txt)
python -m agent_system report <run-id>    # rewrite summary.md and final/
python -m agent_system list               # all runs
```

`--no-wait` never prompts; it uses answer files if present and otherwise pauses and exits.
`--auto-approve` accepts both reviews (unattended demo only).

### Using a web LLM instead of Ollama

Set `LLM_PROVIDER=manual` (and optionally `MANUAL_MODEL=<name>` so the run records which LLM you used).
Each agent's prompt is written to `runs/<run-id>/llm/NNN-<agent>.prompt.md`. Paste it into the web
LLM, save the whole answer as the matching `NNN-<agent>.response.md`, then `resume`. The recorded
run in `runs/seasonswitch-2026-10-28` was made this way, with Claude Opus 5.5 answering. Run it
again with Ollama to get your own results.

## The refactoring prompt (step 8)

The versions are in `agent_system/prompts/refactor_versions/` (P0 to P3). The final prompt is
`agent_system/prompts/refactor_state_pattern.md` (= P3). Score the versions on a run's tested code:

```bash
python -m agent_system lab <run-id>                    # writes prompt-lab/<run-id>/<version>/prompt.md
python -m agent_system lab <run-id> --lab-id my-llm    # a new lab folder, so new replies are collected
```

With `LLM_PROVIDER=manual`, paste each `prompt.md` into the web LLM, save the answer as `reply.md`
next to it, and run the lab again. It reports, for each version: compiles, State pattern checks,
tests that compile unchanged, tests that pass, and branch coverage. A version that already has a
`reply.md` is only rescored, so use a new `--lab-id` to try another LLM. See `docs/refactoring-prompt.md`.

## What a run produces (`runs/<run-id>/`)

| Path | Contents |
|---|---|
| `original/observe.md`, `testability.md` | what the program printed; what makes it hard to test |
| `original/test-design/` | the EP and BVA agents' test cases and analysis, `curation.md` (duplicates) |
| `original/review/` | the test reviews and the answers |
| `original/test-cases.json` / `.md` | the test cases (also in `refactored/`) |
| `<phase>/test-results/run-NN/` | `SeasonSwitchTest.java`, Surefire XML reports, `summary.md`, the Maven log |
| `<phase>/coverage/run-NN/` | JaCoCo HTML, XML and CSV reports, `summary.md` |
| `refactored/` | the refactoring agent's replies, `pattern-check.md`, the reviews |
| `changes.md` | every change to code or tests: phase, agent, reason, unified diff |
| `trace.log`, `trace.jsonl`, `llm/` | every step with timings and threads; every prompt and reply |
| `summary.md`, `final/` | the summary, and both phases as complete Maven projects (`mvn test` reruns them) |

## Architecture

![Architecture: components and connections](docs/diagrams/architecture.png)

The main design idea: **the graph decides, the agents create, the tools check.** The 12 steps are
fixed by the assignment, so a LangGraph `StateGraph` routes every step with deterministic
conditional edges. Language models do only the creative work: designing tests, diagnosing failures,
refactoring and adapting tests. Deterministic tools check everything the models produce. Maven and
JUnit run the tests, JaCoCo measures the branches, and javap inspects the refactored bytecode.

Colours in every diagram: purple is an LLM agent or the model, gray is deterministic code or a file,
and amber is a person.

### Runtime flow

![Runtime flow: what each part sends and gets back](docs/diagrams/architecture-flow.png)

1. **Start.** The command line reads `SeasonSwitch.java`, the `.env` settings and the prompt files,
   and streams the first input into the graph.
2. **Nodes.** The orchestrator hands the shared `AgentState` to each node in turn and merges the
   update the node returns.
    - **LLM agent nodes** send a prompt plus the JSON Schema of their answer through LangChain
      `ChatOllama` to Ollama. They get back JSON (test cases, repair plans) that Pydantic validates,
      or Java files from the refactoring agent.
    - **Tool nodes** run `mvn test`, `javap -c` and `java`, and read the Surefire and JaCoCo XML reports.
3. **People.** At step 4, at step 9, and when a loop reaches its limit, the node calls
   `interrupt()`. The command line shows the review file and waits for an answer. The answer resumes
   the graph (`Command(resume=...)`), now or later from another terminal.
4. **Memory.** The `SqliteSaver` checkpointer stores the `AgentState` after every step in
   `checkpoints.sqlite`. That is what `resume`, `status` and `report` read.
5. **Files.** Everything a run produces lands in `runs/<run-id>/`: test cases, test results, coverage,
   the refactored code, `changes.md`, every prompt and reply, and `final/`.

### The LangGraph graph

![The compiled LangGraph graph: 16 nodes and their edges](docs/diagrams/langgraph.png)

This diagram is drawn from the compiled graph itself (`make_langgraph.py` reads its nodes and edges),
so it always matches `graph.py`. Solid arrows are fixed edges. Dashed arrows are conditional edges,
each labelled with the condition its routing function checks. The same `run_tests`, `repair`,
`measure_coverage` and `coverage_gap` nodes serve both phases: the state's `phase` says whether
they work on the original or the refactored code.

### Data model

![Data model: agent outputs, the LangGraph state, and the files a run saves](docs/diagrams/datamodel.png)

The agents answer in the Pydantic models of `schemas.py`. Their JSON Schema is part of every prompt
and is Ollama's `format`, so even a 7B model returns valid JSON. The graph carries one `AgentState`
(`state.py`). Every result is also saved as a file in the run folder.

### Components and code

| Component | Code |
|---|---|
| Command line: `run`, `resume`, `status`, `report`, `list`, `lab` | `agent_system/cli.py` |
| Orchestrator: nodes, conditional edges, checkpoints | `agent_system/graph.py`, `agent_system/state.py` |
| Settings and run context (model, tracer, change log) | `agent_system/config.py`, `agent_system/context.py`, `.env.example` |
| Model access: Ollama, or a web LLM in manual mode | `agent_system/llm.py` |
| Agent output schemas | `agent_system/schemas.py` |
| Agent prompts | `agent_system/prompts/*.md` (rendered by `agent_system/prompts.py`; refactoring prompt versions in `refactor_versions/`) |
| EP, BVA, reviser and coverage agents | `agent_system/agents/test_designers.py` |
| Repair agent | `agent_system/agents/repair.py` |
| Refactoring agent | `agent_system/agents/refactorer.py`, `agent_system/tools/refactor_parse.py` |
| Test adapter | `agent_system/agents/adapter.py` |
| Graph nodes, one per step | `agent_system/nodes/`: `setup`, `design`, `review`, `testing`, `coverage`, `refactoring`, `adapt`, `finalize` |
| Setup and testability analysis | `agent_system/tools/testability.py`, `runjava.py`, `javap.py` |
| Curator (removes duplicates) | `agent_system/tools/dedup.py` |
| JUnit renderer and test helper | `agent_system/tools/junit.py`, `agent_system/templates/TestSupport.java` |
| Test runner (Maven, Surefire) | `agent_system/tools/maven.py` |
| Coverage reader (JaCoCo) | `agent_system/tools/jacoco.py` |
| State pattern checker | `agent_system/tools/pattern_check.py` |
| Change log and execution trace | `agent_system/tools/changelog.py`, `agent_system/tracing.py` |
| Prompt lab (step 8) | `agent_system/prompt_lab.py` |
| Class under test | `season-switch/` (`pom.xml`: JUnit 5.14.4, Surefire 3.5.5, JaCoCo 0.8.15) |
| Tests of the agent system | `tests/`: pytest, including end-to-end runs of the whole graph with a scripted model on `tests/fixtures/FanSwitch.java` through real Maven, JUnit and JaCoCo |

**Testability support.** SeasonSwitch keeps its state in a static field, uses Swing timers, shows its
results only as printed text, and ends `main()` with `System.exit` or an endless wait.
`TestSupport.java`, added to every test class, handles each of these:

- it captures `System.out`;
- it stops the timers after each test;
- it restores the static fields;
- it runs `main()` in a separate JVM that carries the JaCoCo agent, so neither `System.exit` nor the
  wait can stop the tests, and `main()`'s coverage still counts.

**More diagrams and their sources.** `docs/diagrams/activity.png` is the UML activity diagram of the
12 steps; `activity-part1.png` and `activity-part2.png` are its two halves, used in the slides. Each
diagram has an SVG next to its PNG. To redraw them:

```bash
python docs/diagrams/make_diagrams.py     # activity, architecture, runtime flow, data model
python docs/diagrams/make_langgraph.py    # the compiled graph (langgraph.mmd; PNG and SVG need the Mermaid CLI)
```

## Results of the recorded run (`runs/seasonswitch-2026-10-28`)

| | Original code | Refactored code |
|---|---|---|
| Test cases | 51 (EP 30, BVA 19, EP+BVA 1, review 1) | 44 (7 removed as obsolete) |
| Test runs | 3 (1 failure found a defect; 7 tests fixed after the code fix) | 1 |
| Result | 51/51 pass | 44/44 pass |
| Branch coverage | 36/36 = 100% | 16/16 = 100% |
| Line coverage | 94/98 = 96% (`System.exit` and `await()` lines) | 112/116 = 97% (the same lines) |

The tester's review added a test showing that two `SeasonSwitch` objects shared one `static state`.
The repair agent made `state` an instance field. See `runs/seasonswitch-2026-10-28/changes.md`.

## Troubleshooting

- *Ollama is not reachable*: start the Ollama app (or `ollama serve`); check `OLLAMA_BASE_URL`.
- *Model not pulled*: `ollama pull qwen2.5-coder:7b`, or set `LLM_MODEL` to a model you have.
- *Maven not on the PATH*: install Maven and check `mvn -v`, or set `MVN` to the full path of `mvn.cmd`.
- *A model reply is not valid JSON*: the agent asks once more with the error; if it still fails,
  `resume` retries the step. Smaller models do better with a bigger `LLM_NUM_CTX`.
- *Slow on a laptop without a GPU*: one test-design reply can take many minutes on a 7B model;
  `LLM_TIMEOUT` (default 3600 s) bounds each call.

Note: the recorded run was built in a sandbox without access to Maven Central, using an offline build
file with the same layout. The projects in `runs/` and `final/` use `season-switch/pom.xml`.
