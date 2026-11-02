"""A scripted stand-in for the language model, so the whole workflow can be tested without Ollama.

The replies are written for tests/fixtures/FanSwitch.java. They are canned answers, not model output.
"""
from __future__ import annotations

import json
from collections import defaultdict

from agent_system.llm import LLM


def case(cid, tech, method, condition, inp, expected, junit):
    return {"id": cid, "technique": tech, "method": method, "condition": condition, "input": inp,
            "expected": expected, "junit": junit}


EP = {"analysis": "pull(): state OFF, LOW, HIGH (valid). setSpeed(s): 0..2 valid; s<0 invalid; s>2 invalid.",
      "test_cases": [
          case("EP-01", "EP", "FanSwitch()", "new fan", "new FanSwitch()", "OFF",
               'FanSwitch fan = new FanSwitch();\nassertEquals("OFF", fan.getSetting());'),
          case("EP-02", "EP", "pull()", "state OFF (valid)", "pull once", "LOW",
               'FanSwitch fan = new FanSwitch();\nfan.pull();\nassertEquals("LOW", fan.getSetting());'),
          case("EP-03", "EP", "setSpeed(int)", "0 <= s <= 2 (valid)", "setSpeed(1)", "LOW",
               'FanSwitch fan = new FanSwitch();\nfan.setSpeed(1);\nassertEquals("LOW", fan.getSetting());'),
          case("EP-04", "EP", "setSpeed(int)", "s < 0 (invalid)", "setSpeed(-5)", "IllegalArgumentException",
               'FanSwitch fan = new FanSwitch();\nassertThrows(IllegalArgumentException.class, () -> fan.setSpeed(-5));'),
          case("EP-05", "EP", "setSpeed(int)", "s > 2 (invalid)", "setSpeed(9)", "IllegalArgumentException",
               'FanSwitch fan = new FanSwitch();\nassertThrows(IllegalArgumentException.class, () -> fan.setSpeed(9));'),
      ]}

BVA = {"analysis": "setSpeed: -1, 0, 2, 3. pull: 1, 2, 3 events (wrap-around).",
       "test_cases": [
           case("BVA-01", "BVA", "setSpeed(int)", "s = -1 (below min)", "setSpeed(-1)", "IllegalArgumentException",
                'FanSwitch f = new FanSwitch();\nassertThrows(IllegalArgumentException.class, () -> f.setSpeed(-1));'),
           case("BVA-02", "BVA", "setSpeed(int)", "s = 0 (min)", "setSpeed(0)", "OFF",
                'FanSwitch f = new FanSwitch();\nf.setSpeed(0);\nassertEquals("OFF", f.getSetting());'),
           case("BVA-03", "BVA", "setSpeed(int)", "s = 2 (max)", "setSpeed(2)", "HIGH",
                'FanSwitch f = new FanSwitch();\nf.setSpeed(2);\nassertEquals("HIGH", f.getSetting());'),
           case("BVA-04", "BVA", "setSpeed(int)", "s = 3 (above max)", "setSpeed(3)", "IllegalArgumentException",
                'FanSwitch f = new FanSwitch();\nassertThrows(IllegalArgumentException.class, () -> f.setSpeed(3));'),
           # same code as EP-02 except the variable name: the curator must drop it
           case("BVA-05", "BVA", "pull()", "1 event", "pull once", "LOW",
                'FanSwitch f = new FanSwitch();\nf.pull();\nassertEquals("LOW", f.getSetting());'),
           case("BVA-06", "BVA", "pull()", "3 events: wrap-around HIGH -> OFF", "pull 3 times", "OFF",
                'FanSwitch f = new FanSwitch();\nf.pull();\nf.pull();\nf.pull();\nassertEquals("OFF", f.getSetting());'),
       ]}

REVISION = {"summary": "added the test the reviewer asked for",
            "add": [case("REVIEW-01", "REVIEW", "pull()", "HIGH then pull", "setSpeed(2); pull()", "OFF",
                         'FanSwitch fan = new FanSwitch();\nfan.setSpeed(2);\nfan.pull();\nassertEquals("OFF", fan.getSetting());')],
            "modify": [], "delete": []}

FIXED = open(__file__.replace("fakes.py", "fixtures/FanSwitch.java"), encoding="utf-8").read().replace(
    "            speed = 1;\n        } else", "            speed = 0;\n        } else")

REPAIR = {"diagnosis": "BVA-06 and REVIEW-01 expect OFF after HIGH, as the documentation says; pull() goes to LOW. "
                       "The code is wrong.",
          "test_edits": [],
          "code_edits": [{"file": "FanSwitch.java", "content": FIXED,
                          "reason": "pull() at HIGH must wrap around to OFF (documented cycle OFF -> LOW -> HIGH -> OFF)"}]}

COVERAGE = {"analysis": "main() never ran",
            "test_cases": [case("COV-01", "COVERAGE", "main(String[])", "line 39: loop of main()", "main(new String[0])",
                                "prints OFF, LOW, HIGH, OFF",
                                'PrintStream old = System.out;\nByteArrayOutputStream out = new ByteArrayOutputStream();\n'
                                'System.setOut(new PrintStream(out));\ntry {\n    FanSwitch.main(new String[0]);\n} finally {\n'
                                '    System.setOut(old);\n}\nString[] lines = out.toString().trim().split("\\\\R");\n'
                                'assertArrayEquals(new String[] {"OFF", "LOW", "HIGH", "OFF"}, lines);')]}

REFACTOR = '''Design

| current state | event | next state | action |
|---|---|---|---|
| Off | pull | Low | - |
| Low | pull | High | - |
| High | pull | Off | - |

```java
// File: FanState.java
abstract class FanState {
    abstract void pull(FanSwitch fan);
    abstract String name();
}
```

```java
// File: Off.java
class Off extends FanState {
    void pull(FanSwitch fan) { fan.setState(FanSwitch.LOW); }
    String name() { return "OFF"; }
}
```

```java
// File: Low.java
class Low extends FanState {
    void pull(FanSwitch fan) { fan.setState(FanSwitch.HIGH); }
    String name() { return "LOW"; }
}
```

```java
// File: High.java
class High extends FanState {
    void pull(FanSwitch fan) { fan.setState(FanSwitch.OFF); }
    String name() { return "HIGH"; }
}
```

```java
// File: FanSwitch.java
public class FanSwitch {
    static final FanState OFF = new Off();
    static final FanState LOW = new Low();
    static final FanState HIGH = new High();
    private static final FanState[] BY_SPEED = {OFF, LOW, HIGH};

    private FanState state = OFF;

    void setState(FanState next) { state = next; }

    public void pull() { state.pull(this); }

    public void setSpeed(int s) {
        if (s < 0 || s > 2) {
            throw new IllegalArgumentException("speed must be 0, 1 or 2: " + s);
        }
        state = BY_SPEED[s];
    }

    public String getSetting() { return state.name(); }

    public static void main(String[] args) {
        FanSwitch fan = new FanSwitch();
        for (int i = 0; i < 4; i++) {
            System.out.println(fan.getSetting());
            fan.pull();
        }
    }
}
```
'''

SCRIPT = {
    "ep_agent": [json.dumps(EP)],
    "bva_agent": [json.dumps(BVA)],
    "reviser": [json.dumps(REVISION)],
    "repair_agent": [json.dumps(REPAIR)],
    "coverage_agent": [json.dumps(COVERAGE)],
    "refactor_agent": [REFACTOR],
}


class ScriptedLLM(LLM):
    """Returns the scripted reply for each role, in order; records every prompt it was given."""

    def __init__(self, settings, tracer, script=None):
        super().__init__(settings, tracer)
        self.script = defaultdict(list, {k: list(v) for k, v in (script or SCRIPT).items()})
        self.calls: list[tuple[str, str]] = []

    def _complete(self, role, model_name, system, user, schema, prompt_file, response_file) -> str:
        self.calls.append((role, user))
        if not self.script[role]:
            raise AssertionError(f"no scripted reply left for {role}")
        return self.script[role].pop(0)
