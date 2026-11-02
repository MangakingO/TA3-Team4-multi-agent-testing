# Test case review, round 1

Check that the test cases cover every equivalence class and boundary, that each expected result is right, and that nothing is missing. Request changes in plain words, or edit `/home/claude/ta3-multi-agent-testing/runs/seasonswitch-2026-10-28/original/test-cases.json` directly.

EP agent 31 + BVA agent 20 test cases; duplicates removed: 1 (see test-design/curation.md).

## Test cases

50 distinct test cases: BVA 19, EP 30, EP+BVA 1

| Id | Technique | Method | Condition | Input | Expected |
|---|---|---|---|---|---|
| EP-01 | EP | SeasonSwitch() | new object (valid) | new SeasonSwitch() | AC and heater off, both timers stopped, room at 70, coolTemp 68, heatTemp 77 |
| EP-02 | EP | cool() | state OFF (valid) | new switch; cool() | prints "Cooling started. RmTemp=70"; AC on, heater off, control timer running |
| EP-03 | EP | cool() | state COOL (valid) | cool(); cool() | second call prints nothing; AC still on, heater off, timer running |
| EP-04 | EP | cool() | state HEAT (valid) | heat(); cool() | prints "Cooling started. RmTemp=70"; heater off, AC on, timer running |
| EP-05 | EP | cool() | state not OFF/COOL/HEAT (invalid) | state = 5; cool() | nothing printed; AC and heater stay off; timer not started |
| EP-06 | EP | heat() | state OFF (valid) | new switch; heat() | prints "Heating started. RmTemp=70"; heater on, AC off, control timer running |
| EP-07 | EP | heat() | state HEAT (valid) | heat(); heat() | second call prints nothing; heater still on, AC off, timer running |
| EP-08 | EP | heat() | state COOL (valid) | cool(); heat() | prints "Heating started. RmTemp=70"; AC off, heater on, timer running |
| EP-09 | EP | heat() | state not OFF/COOL/HEAT (invalid) | state = 5; heat() | nothing printed; heater and AC stay off; timer not started |
| EP-10 | EP | off() | state OFF (valid) | new switch; off() | nothing printed; AC and heater off; timer not running |
| EP-11 | EP | off() | state COOL (valid) | cool(); off() | prints "Cooling stopped. RmTemp=70"; AC off; timer stopped |
| EP-12 | EP | off() | state HEAT (valid) | heat(); off() | prints "Heating stopped. RmTemp=70"; heater off; timer stopped |
| EP-13 | EP | off() | state not OFF/COOL/HEAT (invalid) | cool(); state = 5; off() | nothing printed; AC stays on and the timer keeps running |
| EP-14 | EP | timerOff() | state OFF (valid) | new switch; timerOff() | nothing printed; AC and heater stay off |
| EP-15 | EP | timerOff() | state COOL, temp > coolTemp (valid) | cool(); AC off; temp = 75; timerOff() | prints "Cooling started. RmTemp=75"; AC on |
| EP-16 | EP | timerOff() | state COOL, temp <= coolTemp (valid) | cool(); temp = 60; timerOff() | prints "Cooling stopped. RmTemp=60"; AC off |
| EP-17 | EP | timerOff() | state HEAT, temp < heatTemp (valid) | heat(); heater off; temp = 60; timerOff() | prints "Heating started. RmTemp=60"; heater on |
| EP-18 | EP | timerOff() | state HEAT, temp >= heatTemp (valid) | heat(); temp = 85; timerOff() | prints "Heating stopped. RmTemp=85"; heater off |
| EP-19 | EP | timerOff() | state not OFF/COOL/HEAT (invalid) | cool(); state = 5; temp = 60; timerOff() | nothing printed; AC stays on |
| EP-20 | EP | actionPerformed(ActionEvent) | control timer event (valid), state COOL, temp 75 | cool(); AC off; temp = 75; actionPerformed(null) | same as timerOff(): prints "Cooling started. RmTemp=75"; AC on |
| EP-21 | EP | Room.actionPerformed(ActionEvent) | room timer event, state OFF (valid) | new switch; room event | room temperature stays 70 |
| EP-22 | EP | Room.actionPerformed(ActionEvent) | room timer event, state COOL, AC on (valid) | cool(); room event | room temperature falls to 69 |
| EP-23 | EP | Room.actionPerformed(ActionEvent) | room timer event, state COOL, AC off (valid) | cool(); AC off; room event | room temperature rises to 71 |
| EP-24 | EP | Room.actionPerformed(ActionEvent) | room timer event, state HEAT, heater on (valid) | heat(); room event | room temperature rises to 71 |
| EP-25 | EP | Room.actionPerformed(ActionEvent) | room timer event, state HEAT, heater off (valid) | heat(); heater off; room event | room temperature falls to 69 |
| EP-26 | EP | Room.actionPerformed(ActionEvent) | room timer event, state not OFF/COOL/HEAT (invalid) | cool(); state = 5; room event | room temperature stays 70 |
| EP-27 (also BVA-15) | EP+BVA | main(String[]) | no argument (valid) | java SeasonSwitch | prints the prompt, then "Cooling started. RmTemp=70"; keeps running |
| EP-28 | EP | main(String[]) | command "cool" (valid) | java SeasonSwitch cool | prints the prompt, then "Cooling started. RmTemp=70"; keeps running |
| EP-29 | EP | main(String[]) | command "heat" (valid) | java SeasonSwitch heat | prints the prompt, then "Heating started. RmTemp=70"; keeps running |
| EP-30 | EP | main(String[]) | command "off" (valid) | java SeasonSwitch off | prints the prompt and "System off. System exits."; exits with code 0 |
| EP-31 | EP | main(String[]) | unknown command (invalid) | java SeasonSwitch warm | prints the prompt and "Unknown command. System exits."; exits with code 1 |
| BVA-01 | BVA | timerOff() | temp = 69 = coolTemp + 1 (just above) | cool(); ac off; temp = 69; timerOff() | prints "Cooling started. RmTemp=69"; ac on |
| BVA-02 | BVA | timerOff() | temp = 68 = coolTemp (at the boundary) | cool(); temp = 68; timerOff() | prints "Cooling stopped. RmTemp=68"; ac off |
| BVA-03 | BVA | timerOff() | temp = 67 = coolTemp - 1 (just below) | cool(); temp = 67; timerOff() | prints "Cooling stopped. RmTemp=67"; ac off |
| BVA-04 | BVA | timerOff() | temp = 76 = heatTemp - 1 (just below) | heat(); heater off; temp = 76; timerOff() | prints "Heating started. RmTemp=76"; heater on |
| BVA-05 | BVA | timerOff() | temp = 77 = heatTemp (at the boundary) | heat(); temp = 77; timerOff() | prints "Heating stopped. RmTemp=77"; heater off |
| BVA-06 | BVA | timerOff() | temp = 78 = heatTemp + 1 (just above) | heat(); temp = 78; timerOff() | prints "Heating stopped. RmTemp=78"; heater off |
| BVA-07 | BVA | Room.actionPerformed(ActionEvent), actionPerformed(ActionEvent) | 1 room event = n-1 (n = 2 events reach coolTemp) | cool(); 1 room event(s); control event | room at 69; prints "Cooling started. RmTemp=69"; ac stays on |
| BVA-08 | BVA | Room.actionPerformed(ActionEvent), actionPerformed(ActionEvent) | 2 room events = n (reaches coolTemp) | cool(); 2 room event(s); control event | room at 68; prints "Cooling stopped. RmTemp=68"; ac off |
| BVA-09 | BVA | timerOff(), Room.actionPerformed(ActionEvent) | AC stopped at coolTemp; 1 room event moves just above it | cool(); temp = 68; timerOff(); room event; timerOff() | ac stops at 68; room moves to 69; prints "Cooling started. RmTemp=69"; ac on again |
| BVA-10 | BVA | Room.actionPerformed(ActionEvent), actionPerformed(ActionEvent) | 6 room events = n-1 (n = 7 events reach heatTemp) | heat(); 6 room event(s); control event | room at 76; prints "Heating started. RmTemp=76"; heater stays on |
| BVA-11 | BVA | Room.actionPerformed(ActionEvent), actionPerformed(ActionEvent) | 7 room events = n (reaches heatTemp) | heat(); 7 room event(s); control event | room at 77; prints "Heating stopped. RmTemp=77"; heater off |
| BVA-12 | BVA | timerOff(), Room.actionPerformed(ActionEvent) | heater stopped at heatTemp; 1 room event moves just below it | heat(); temp = 77; timerOff(); room event; timerOff() | heater stops at 77; room moves to 76; prints "Heating started. RmTemp=76"; heater on again |
| BVA-13 | BVA | cool() | state = -1 = OFF - 1 (just below the valid range) | state = -1; cool() | nothing printed; AC off; timer not started |
| BVA-14 | BVA | heat() | state = 3 = HEAT + 1 (just above the valid range) | state = 3; heat() | nothing printed; heater off; timer not started |
| BVA-16 | BVA | main(String[]) | 2 arguments (one more than used) | java SeasonSwitch heat extra | the extra argument is ignored: prints "Heating started. RmTemp=70"; keeps running |
| BVA-17 | BVA | main(String[]) | empty command (length 0) | java SeasonSwitch "" | prints the prompt and "Unknown command. System exits."; exits with code 1 |
| BVA-18 | BVA | main(String[]) | "OFF": differs from "off" only by case | java SeasonSwitch "OFF" | prints the prompt and "Unknown command. System exits."; exits with code 1 |
| BVA-19 | BVA | main(String[]) | "coo": one character shorter than "cool" | java SeasonSwitch "coo" | prints the prompt and "Unknown command. System exits."; exits with code 1 |
| BVA-20 | BVA | main(String[]) | "heats": one character longer than "heat" | java SeasonSwitch "heats" | prints the prompt and "Unknown command. System exits."; exits with code 1 |

## JUnit code

### EP-01

```java
SeasonSwitch s = new SeasonSwitch();
assertFalse(s.ac.isOn);
assertFalse(s.heater.isOn);
assertFalse(s.timer.isRunning());
assertFalse(s.room.timer.isRunning());
assertEquals(70, s.room.temp);
assertEquals(68, s.coolTemp);
assertEquals(77, s.heatTemp);
```

### EP-02

```java
SeasonSwitch s = new SeasonSwitch();
String out = TestSupport.captureOut(s::cool);
assertEquals(List.of("Cooling started. RmTemp=70"), TestSupport.lines(out));
assertTrue(s.ac.isOn);
assertFalse(s.heater.isOn);
assertTrue(s.timer.isRunning());
```

### EP-03

```java
SeasonSwitch s = new SeasonSwitch();
s.cool();
String out = TestSupport.captureOut(s::cool);
assertEquals("", out);
assertTrue(s.ac.isOn);
assertFalse(s.heater.isOn);
assertTrue(s.timer.isRunning());
```

### EP-04

```java
SeasonSwitch s = new SeasonSwitch();
s.heat();
String out = TestSupport.captureOut(s::cool);
assertEquals(List.of("Cooling started. RmTemp=70"), TestSupport.lines(out));
assertTrue(s.ac.isOn);
assertFalse(s.heater.isOn);
assertTrue(s.timer.isRunning());
```

### EP-05

```java
SeasonSwitch s = new SeasonSwitch();
SeasonSwitch.state = 5;
String out = TestSupport.captureOut(s::cool);
assertEquals("", out);
assertFalse(s.ac.isOn);
assertFalse(s.heater.isOn);
assertFalse(s.timer.isRunning());
```

### EP-06

```java
SeasonSwitch s = new SeasonSwitch();
String out = TestSupport.captureOut(s::heat);
assertEquals(List.of("Heating started. RmTemp=70"), TestSupport.lines(out));
assertTrue(s.heater.isOn);
assertFalse(s.ac.isOn);
assertTrue(s.timer.isRunning());
```

### EP-07

```java
SeasonSwitch s = new SeasonSwitch();
s.heat();
String out = TestSupport.captureOut(s::heat);
assertEquals("", out);
assertTrue(s.heater.isOn);
assertFalse(s.ac.isOn);
assertTrue(s.timer.isRunning());
```

### EP-08

```java
SeasonSwitch s = new SeasonSwitch();
s.cool();
String out = TestSupport.captureOut(s::heat);
assertEquals(List.of("Heating started. RmTemp=70"), TestSupport.lines(out));
assertFalse(s.ac.isOn);
assertTrue(s.heater.isOn);
assertTrue(s.timer.isRunning());
```

### EP-09

```java
SeasonSwitch s = new SeasonSwitch();
SeasonSwitch.state = 5;
String out = TestSupport.captureOut(s::heat);
assertEquals("", out);
assertFalse(s.heater.isOn);
assertFalse(s.ac.isOn);
assertFalse(s.timer.isRunning());
```

### EP-10

```java
SeasonSwitch s = new SeasonSwitch();
String out = TestSupport.captureOut(s::off);
assertEquals("", out);
assertFalse(s.ac.isOn);
assertFalse(s.heater.isOn);
assertFalse(s.timer.isRunning());
```

### EP-11

```java
SeasonSwitch s = new SeasonSwitch();
s.cool();
String out = TestSupport.captureOut(s::off);
assertEquals(List.of("Cooling stopped. RmTemp=70"), TestSupport.lines(out));
assertFalse(s.ac.isOn);
assertFalse(s.timer.isRunning());
```

### EP-12

```java
SeasonSwitch s = new SeasonSwitch();
s.heat();
String out = TestSupport.captureOut(s::off);
assertEquals(List.of("Heating stopped. RmTemp=70"), TestSupport.lines(out));
assertFalse(s.heater.isOn);
assertFalse(s.timer.isRunning());
```

### EP-13

```java
SeasonSwitch s = new SeasonSwitch();
s.cool();
SeasonSwitch.state = 5;
String out = TestSupport.captureOut(s::off);
assertEquals("", out);
assertTrue(s.ac.isOn);
assertTrue(s.timer.isRunning());
```

### EP-14

```java
SeasonSwitch s = new SeasonSwitch();
String out = TestSupport.captureOut(s::timerOff);
assertEquals("", out);
assertFalse(s.ac.isOn);
assertFalse(s.heater.isOn);
```

### EP-15

```java
SeasonSwitch s = new SeasonSwitch();
s.cool();
s.ac.off();
s.room.temp = 75;
String out = TestSupport.captureOut(s::timerOff);
assertEquals(List.of("Cooling started. RmTemp=75"), TestSupport.lines(out));
assertTrue(s.ac.isOn);
```

### EP-16

```java
SeasonSwitch s = new SeasonSwitch();
s.cool();
s.room.temp = 60;
String out = TestSupport.captureOut(s::timerOff);
assertEquals(List.of("Cooling stopped. RmTemp=60"), TestSupport.lines(out));
assertFalse(s.ac.isOn);
```

### EP-17

```java
SeasonSwitch s = new SeasonSwitch();
s.heat();
s.heater.off();
s.room.temp = 60;
String out = TestSupport.captureOut(s::timerOff);
assertEquals(List.of("Heating started. RmTemp=60"), TestSupport.lines(out));
assertTrue(s.heater.isOn);
```

### EP-18

```java
SeasonSwitch s = new SeasonSwitch();
s.heat();
s.room.temp = 85;
String out = TestSupport.captureOut(s::timerOff);
assertEquals(List.of("Heating stopped. RmTemp=85"), TestSupport.lines(out));
assertFalse(s.heater.isOn);
```

### EP-19

```java
SeasonSwitch s = new SeasonSwitch();
s.cool();
SeasonSwitch.state = 5;
s.room.temp = 60;
String out = TestSupport.captureOut(s::timerOff);
assertEquals("", out);
assertTrue(s.ac.isOn);
```

### EP-20

```java
SeasonSwitch s = new SeasonSwitch();
s.cool();
s.ac.off();
s.room.temp = 75;
String out = TestSupport.captureOut(() -> s.actionPerformed(null));
assertEquals(List.of("Cooling started. RmTemp=75"), TestSupport.lines(out));
assertTrue(s.ac.isOn);
```

### EP-21

```java
SeasonSwitch s = new SeasonSwitch();
s.room.actionPerformed(null);
assertEquals(70, s.room.temp);
```

### EP-22

```java
SeasonSwitch s = new SeasonSwitch();
s.cool();
s.room.actionPerformed(null);
assertEquals(69, s.room.temp);
```

### EP-23

```java
SeasonSwitch s = new SeasonSwitch();
s.cool();
s.ac.off();
s.room.actionPerformed(null);
assertEquals(71, s.room.temp);
```

### EP-24

```java
SeasonSwitch s = new SeasonSwitch();
s.heat();
s.room.actionPerformed(null);
assertEquals(71, s.room.temp);
```

### EP-25

```java
SeasonSwitch s = new SeasonSwitch();
s.heat();
s.heater.off();
s.room.actionPerformed(null);
assertEquals(69, s.room.temp);
```

### EP-26

```java
SeasonSwitch s = new SeasonSwitch();
s.cool();
SeasonSwitch.state = 5;
s.room.actionPerformed(null);
assertEquals(70, s.room.temp);
```

### EP-27

```java
TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000);
assertTrue(r.stillRunning);
List<String> lines = r.lines();
assertEquals("Enter off, cool or heat to run. Press Ctrl-c to exit.", lines.get(0));
assertEquals("Cooling started. RmTemp=70", lines.get(1));
```

### EP-28

```java
TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000, "cool");
assertTrue(r.stillRunning);
List<String> lines = r.lines();
assertEquals("Enter off, cool or heat to run. Press Ctrl-c to exit.", lines.get(0));
assertEquals("Cooling started. RmTemp=70", lines.get(1));
```

### EP-29

```java
TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000, "heat");
assertTrue(r.stillRunning);
List<String> lines = r.lines();
assertEquals("Enter off, cool or heat to run. Press Ctrl-c to exit.", lines.get(0));
assertEquals("Heating started. RmTemp=70", lines.get(1));
```

### EP-30

```java
TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000, "off");
assertFalse(r.stillRunning);
assertEquals(0, r.exitCode);
assertEquals(List.of("Enter off, cool or heat to run. Press Ctrl-c to exit.", "System off. System exits."), r.lines());
```

### EP-31

```java
TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000, "warm");
assertFalse(r.stillRunning);
assertEquals(1, r.exitCode);
assertEquals(List.of("Enter off, cool or heat to run. Press Ctrl-c to exit.", "Unknown command. System exits."), r.lines());
```

### BVA-01

```java
SeasonSwitch s = new SeasonSwitch();
s.cool();
s.ac.off();
s.room.temp = 69;
String out = TestSupport.captureOut(s::timerOff);
assertEquals(List.of("Cooling started. RmTemp=69"), TestSupport.lines(out));
assertTrue(s.ac.isOn);
```

### BVA-02

```java
SeasonSwitch s = new SeasonSwitch();
s.cool();
s.room.temp = 68;
String out = TestSupport.captureOut(s::timerOff);
assertEquals(List.of("Cooling stopped. RmTemp=68"), TestSupport.lines(out));
assertFalse(s.ac.isOn);
```

### BVA-03

```java
SeasonSwitch s = new SeasonSwitch();
s.cool();
s.room.temp = 67;
String out = TestSupport.captureOut(s::timerOff);
assertEquals(List.of("Cooling stopped. RmTemp=67"), TestSupport.lines(out));
assertFalse(s.ac.isOn);
```

### BVA-04

```java
SeasonSwitch s = new SeasonSwitch();
s.heat();
s.heater.off();
s.room.temp = 76;
String out = TestSupport.captureOut(s::timerOff);
assertEquals(List.of("Heating started. RmTemp=76"), TestSupport.lines(out));
assertTrue(s.heater.isOn);
```

### BVA-05

```java
SeasonSwitch s = new SeasonSwitch();
s.heat();
s.room.temp = 77;
String out = TestSupport.captureOut(s::timerOff);
assertEquals(List.of("Heating stopped. RmTemp=77"), TestSupport.lines(out));
assertFalse(s.heater.isOn);
```

### BVA-06

```java
SeasonSwitch s = new SeasonSwitch();
s.heat();
s.room.temp = 78;
String out = TestSupport.captureOut(s::timerOff);
assertEquals(List.of("Heating stopped. RmTemp=78"), TestSupport.lines(out));
assertFalse(s.heater.isOn);
```

### BVA-07

```java
SeasonSwitch s = new SeasonSwitch();
s.cool();
s.room.actionPerformed(null);
assertEquals(69, s.room.temp);
String out = TestSupport.captureOut(() -> s.actionPerformed(null));
assertEquals(List.of("Cooling started. RmTemp=69"), TestSupport.lines(out));
assertTrue(s.ac.isOn);
```

### BVA-08

```java
SeasonSwitch s = new SeasonSwitch();
s.cool();
s.room.actionPerformed(null);
s.room.actionPerformed(null);
assertEquals(68, s.room.temp);
String out = TestSupport.captureOut(() -> s.actionPerformed(null));
assertEquals(List.of("Cooling stopped. RmTemp=68"), TestSupport.lines(out));
assertFalse(s.ac.isOn);
```

### BVA-09

```java
SeasonSwitch s = new SeasonSwitch();
s.cool();
s.room.temp = 68;
s.timerOff();
assertFalse(s.ac.isOn);
s.room.actionPerformed(null);
assertEquals(69, s.room.temp);
String out = TestSupport.captureOut(s::timerOff);
assertEquals(List.of("Cooling started. RmTemp=69"), TestSupport.lines(out));
assertTrue(s.ac.isOn);
```

### BVA-10

```java
SeasonSwitch s = new SeasonSwitch();
s.heat();
for (int i = 0; i < 6; i++) {
    s.room.actionPerformed(null);
}
assertEquals(76, s.room.temp);
String out = TestSupport.captureOut(() -> s.actionPerformed(null));
assertEquals(List.of("Heating started. RmTemp=76"), TestSupport.lines(out));
assertTrue(s.heater.isOn);
```

### BVA-11

```java
SeasonSwitch s = new SeasonSwitch();
s.heat();
for (int i = 0; i < 7; i++) {
    s.room.actionPerformed(null);
}
assertEquals(77, s.room.temp);
String out = TestSupport.captureOut(() -> s.actionPerformed(null));
assertEquals(List.of("Heating stopped. RmTemp=77"), TestSupport.lines(out));
assertFalse(s.heater.isOn);
```

### BVA-12

```java
SeasonSwitch s = new SeasonSwitch();
s.heat();
s.room.temp = 77;
s.timerOff();
assertFalse(s.heater.isOn);
s.room.actionPerformed(null);
assertEquals(76, s.room.temp);
String out = TestSupport.captureOut(s::timerOff);
assertEquals(List.of("Heating started. RmTemp=76"), TestSupport.lines(out));
assertTrue(s.heater.isOn);
```

### BVA-13

```java
SeasonSwitch s = new SeasonSwitch();
SeasonSwitch.state = -1;
String out = TestSupport.captureOut(s::cool);
assertEquals("", out);
assertFalse(s.ac.isOn);
assertFalse(s.timer.isRunning());
```

### BVA-14

```java
SeasonSwitch s = new SeasonSwitch();
SeasonSwitch.state = 3;
String out = TestSupport.captureOut(s::heat);
assertEquals("", out);
assertFalse(s.heater.isOn);
assertFalse(s.timer.isRunning());
```

### BVA-16

```java
TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000, "heat", "extra");
assertTrue(r.stillRunning);
assertEquals("Heating started. RmTemp=70", r.lines().get(1));
```

### BVA-17

```java
TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000, "");
assertFalse(r.stillRunning);
assertEquals(1, r.exitCode);
assertEquals("Unknown command. System exits.", r.lines().get(1));
```

### BVA-18

```java
TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000, "OFF");
assertFalse(r.stillRunning);
assertEquals(1, r.exitCode);
assertEquals("Unknown command. System exits.", r.lines().get(1));
```

### BVA-19

```java
TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000, "coo");
assertFalse(r.stillRunning);
assertEquals(1, r.exitCode);
assertEquals("Unknown command. System exits.", r.lines().get(1));
```

### BVA-20

```java
TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000, "heats");
assertFalse(r.stillRunning);
assertEquals(1, r.exitCode);
assertEquals("Unknown command. System exits.", r.lines().get(1));
```


---
Answer in the console (a = accept, r = request changes, e = I edited test-cases.json). Without the console, write your answer into `round-1.md.answer.txt` next to this file, then run `python -m agent_system resume seasonswitch-2026-10-28`.
