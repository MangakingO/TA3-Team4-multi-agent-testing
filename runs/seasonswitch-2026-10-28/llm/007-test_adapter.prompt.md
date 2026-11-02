<!-- role: test_adapter | model: Claude Opus 5.5 -->

# System

You adapt an existing JUnit 5 test suite to refactored code. The class SeasonSwitch was
refactored with the State pattern and keeps its original public interface, so most tests run
unchanged. You only get the tests that no longer compile against the refactored code.

For each one:
- If it checks behaviour that still exists, change as little as possible so it compiles against
  the refactored classes while checking exactly the same behaviour as before ("modify").
- If it only checked an implementation detail that the refactoring removed on purpose (for
  example forcing an int state field to a value that matches no state constant, to reach a switch
  branch that no longer exists), "delete" it and say in "reason" what was removed.
Leave "code_edits" empty.

# User

Refactored source files:
// ===== CoolState.java =====
```java
 1 | package cse4392;
 2 | 
 3 | class CoolState extends SeasonState {
 4 |     @Override
 5 |     void cool(SeasonSwitch s) {
 6 |         // already cooling
 7 |     }
 8 | 
 9 |     @Override
10 |     void heat(SeasonSwitch s) {
11 |         s.ac.off();
12 |         s.heater.on();
13 |         s.timer.start();
14 |         s.setState(HEAT);
15 |         System.out.println("Heating started. RmTemp=" + s.room.temp);
16 |     }
17 | 
18 |     @Override
19 |     void off(SeasonSwitch s) {
20 |         s.ac.off();
21 |         s.timer.stop();
22 |         s.setState(OFF);
23 |         System.out.println("Cooling stopped. RmTemp=" + s.room.temp);
24 |     }
25 | 
26 |     @Override
27 |     void timerOff(SeasonSwitch s) {
28 |         if (s.room.temp > s.coolTemp) {
29 |             s.ac.on();
30 |             System.out.println("Cooling started. RmTemp=" + s.room.temp);
31 |         } else {
32 |             s.ac.off();
33 |             System.out.println("Cooling stopped. RmTemp=" + s.room.temp);
34 |         }
35 |     }
36 | 
37 |     @Override
38 |     void roomTick(SeasonSwitch s) {
39 |         if (s.ac.isOn) {
40 |             s.room.temp--;
41 |         } else {
42 |             s.room.temp++;
43 |         }
44 |     }
45 | }
```

// ===== HeatState.java =====
```java
 1 | package cse4392;
 2 | 
 3 | class HeatState extends SeasonState {
 4 |     @Override
 5 |     void cool(SeasonSwitch s) {
 6 |         s.heater.off();
 7 |         s.ac.on();
 8 |         s.timer.start();
 9 |         s.setState(COOL);
10 |         System.out.println("Cooling started. RmTemp=" + s.room.temp);
11 |     }
12 | 
13 |     @Override
14 |     void heat(SeasonSwitch s) {
15 |         // already heating
16 |     }
17 | 
18 |     @Override
19 |     void off(SeasonSwitch s) {
20 |         s.heater.off();
21 |         s.timer.stop();
22 |         s.setState(OFF);
23 |         System.out.println("Heating stopped. RmTemp=" + s.room.temp);
24 |     }
25 | 
26 |     @Override
27 |     void timerOff(SeasonSwitch s) {
28 |         if (s.room.temp < s.heatTemp) {
29 |             s.heater.on();
30 |             System.out.println("Heating started. RmTemp=" + s.room.temp);
31 |         } else {
32 |             s.heater.off();
33 |             System.out.println("Heating stopped. RmTemp=" + s.room.temp);
34 |         }
35 |     }
36 | 
37 |     @Override
38 |     void roomTick(SeasonSwitch s) {
39 |         if (s.heater.isOn) {
40 |             s.room.temp++;
41 |         } else {
42 |             s.room.temp--;
43 |         }
44 |     }
45 | }
```

// ===== OffState.java =====
```java
 1 | package cse4392;
 2 | 
 3 | class OffState extends SeasonState {
 4 |     @Override
 5 |     void cool(SeasonSwitch s) {
 6 |         s.ac.on();
 7 |         s.timer.start();
 8 |         s.setState(COOL);
 9 |         System.out.println("Cooling started. RmTemp=" + s.room.temp);
10 |     }
11 | 
12 |     @Override
13 |     void heat(SeasonSwitch s) {
14 |         s.heater.on();
15 |         s.timer.start();
16 |         s.setState(HEAT);
17 |         System.out.println("Heating started. RmTemp=" + s.room.temp);
18 |     }
19 | 
20 |     @Override
21 |     void off(SeasonSwitch s) {
22 |         // already off
23 |     }
24 | 
25 |     @Override
26 |     void timerOff(SeasonSwitch s) {
27 |         // nothing to control
28 |     }
29 | 
30 |     @Override
31 |     void roomTick(SeasonSwitch s) {
32 |         // the room temperature does not change
33 |     }
34 | }
```

// ===== SeasonState.java =====
```java
 1 | package cse4392;
 2 | 
 3 | abstract class SeasonState {
 4 |     static final SeasonState OFF = new OffState();
 5 |     static final SeasonState COOL = new CoolState();
 6 |     static final SeasonState HEAT = new HeatState();
 7 | 
 8 |     abstract void cool(SeasonSwitch s);
 9 | 
10 |     abstract void heat(SeasonSwitch s);
11 | 
12 |     abstract void off(SeasonSwitch s);
13 | 
14 |     /** The control timer fired. */
15 |     abstract void timerOff(SeasonSwitch s);
16 | 
17 |     /** The room timer fired: how the room temperature changes in this state. */
18 |     abstract void roomTick(SeasonSwitch s);
19 | }
```

// ===== SeasonSwitch.java =====
```java
 1 | package cse4392;
 2 | 
 3 | import javax.swing.Timer;
 4 | import java.awt.event.ActionEvent;
 5 | import java.awt.event.ActionListener;
 6 | import java.util.concurrent.CountDownLatch;
 7 | 
 8 | /** Subject (context) of the State pattern: delegates every state-dependent request to its current state. */
 9 | public class SeasonSwitch implements ActionListener {
10 |    private SeasonState state = SeasonState.OFF;
11 |    AC ac=new AC();
12 |    Heater heater=new Heater();
13 |    Timer timer=new Timer(1200, this);
14 |    Room room=new Room();
15 |    int coolTemp=68;
16 |    int heatTemp=77;
17 | 
18 |    void setState(SeasonState next) {
19 |       state = next;
20 |    }
21 | 
22 |    public void cool() {
23 |       state.cool(this);
24 |    }
25 |    public void heat() {
26 |       state.heat(this);
27 |    }
28 |    public void off() {
29 |       state.off(this);
30 |    }
31 |    public void actionPerformed(ActionEvent e) {
32 |       timerOff();
33 |    }
34 |    public void timerOff() {
35 |       state.timerOff(this);
36 |    }
37 |    public static void main(String[] args) {
38 |       System.out.println("Enter off, cool or heat to run. "+
39 |          "Press Ctrl-c to exit.");
40 |       SeasonSwitch s=new SeasonSwitch();
41 |       s.room.timer.start();
42 |       if (args.length==0) s.cool(); else
43 |       if ("off".compareTo(args[0])==0) {
44 |          s.off();
45 |          System.out.println("System off. System exits.");
46 |          System.exit(0);
47 |       } else
48 |       if ("cool".compareTo(args[0])==0) s.cool(); else
49 |       if ("heat".compareTo(args[0])==0) s.heat(); else {
50 |          System.out.println("Unknown command. System exits.");
51 |          System.exit(1);
52 |       }
53 |       try {
54 |          new CountDownLatch(5).await();
55 |       } catch (InterruptedException e) {
56 |          Thread.currentThread().interrupt();
57 |          s.timer.stop();
58 |          s.room.timer.stop();
59 |       }
60 |    }
61 | class AC {
62 |    boolean isOn=false;
63 |    public void on() { isOn=true; }
64 |    public void off() { isOn=false; }
65 | }
66 | class Heater {
67 |    boolean isOn=false;
68 |    public void on() { isOn=true; }
69 |    public void off() { isOn=false; }
70 | }
71 | class Room implements ActionListener {
72 |    public int temp=70;
73 |    Timer timer=new Timer(400, this);
74 |    public void actionPerformed(ActionEvent ae) {
75 |       state.roomTick(SeasonSwitch.this);
76 |    }
77 | }
78 | }
```

Facts about SeasonSwitch that every test must respect (from the testability analysis):
1. The test class is in package cse4392, so tests may use package-private members. Non-private members:
   - SeasonSwitch: AC ac; Heater heater; javax.swing.Timer timer; Room room; int coolTemp; int heatTemp; public SeasonSwitch(); void setState(SeasonState); public void cool(); public void heat(); public void off(); public void actionPerformed(ActionEvent); public void timerOff(); public static void main(String[])
   - SeasonSwitch.AC: boolean isOn; public void on(); public void off()
   - SeasonSwitch.Heater: boolean isOn; public void on(); public void off()
   - SeasonSwitch.Room: public int temp; javax.swing.Timer timer; public void actionPerformed(ActionEvent)
2. Timers: SeasonSwitch.timer, SeasonSwitch.Room.timer. Never wait for a timer (no Thread.sleep). Simulate one timer event by calling the listener directly: `s.actionPerformed(null)` (where s is the SeasonSwitch under test), `s.room.actionPerformed(null)`. Every SeasonSwitch created in a test is tracked and its timers are stopped after the test.
3. Console output: SeasonSwitch prints with System.out. Check it with `String out = TestSupport.captureOut(() -> s.someMethod());` which returns the printed text with "\n" line endings; `TestSupport.lines(out)` splits it into lines.
4. main(): it calls System.exit and may never return (await()). Never call SeasonSwitch.main directly. Use `TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000, "arg");` which runs main in a separate JVM for at most 3000 ms and gives r.exitCode (the System.exit code, 0 if main returned or was stopped), r.stillRunning (true if main was still running and was stopped), r.out and r.lines(). Each call takes about a second, so use it only for main().

Tests that do not compile (id, code, compiler messages):
### EP-05
```java
SeasonSwitch s = new SeasonSwitch();
s.state = 5;
String out = TestSupport.captureOut(s::cool);
assertEquals("", out);
assertFalse(s.ac.isOn);
assertFalse(s.heater.isOn);
assertFalse(s.timer.isRunning());
```
- line 77: state has private access in cse4392.SeasonSwitch
- line 77: incompatible types: int cannot be converted to cse4392.SeasonState
### EP-09
```java
SeasonSwitch s = new SeasonSwitch();
s.state = 5;
String out = TestSupport.captureOut(s::heat);
assertEquals("", out);
assertFalse(s.heater.isOn);
assertFalse(s.ac.isOn);
assertFalse(s.timer.isRunning());
```
- line 124: state has private access in cse4392.SeasonSwitch
- line 124: incompatible types: int cannot be converted to cse4392.SeasonState
### EP-13
```java
SeasonSwitch s = new SeasonSwitch();
s.cool();
s.state = 5;
String out = TestSupport.captureOut(s::off);
assertEquals("", out);
assertTrue(s.ac.isOn);
assertTrue(s.timer.isRunning());
```
- line 170: state has private access in cse4392.SeasonSwitch
- line 170: incompatible types: int cannot be converted to cse4392.SeasonState
### EP-19
```java
SeasonSwitch s = new SeasonSwitch();
s.cool();
s.state = 5;
s.room.temp = 60;
String out = TestSupport.captureOut(s::timerOff);
assertEquals("", out);
assertTrue(s.ac.isOn);
```
- line 238: state has private access in cse4392.SeasonSwitch
- line 238: incompatible types: int cannot be converted to cse4392.SeasonState
### EP-26
```java
SeasonSwitch s = new SeasonSwitch();
s.cool();
s.state = 5;
s.room.actionPerformed(null);
assertEquals(70, s.room.temp);
```
- line 308: state has private access in cse4392.SeasonSwitch
- line 308: incompatible types: int cannot be converted to cse4392.SeasonState
### BVA-13
```java
SeasonSwitch s = new SeasonSwitch();
s.state = -1;
String out = TestSupport.captureOut(s::cool);
assertEquals("", out);
assertFalse(s.ac.isOn);
assertFalse(s.timer.isRunning());
```
- line 516: state has private access in cse4392.SeasonSwitch
- line 516: incompatible types: int cannot be converted to cse4392.SeasonState
### BVA-14
```java
SeasonSwitch s = new SeasonSwitch();
s.state = 3;
String out = TestSupport.captureOut(s::heat);
assertEquals("", out);
assertFalse(s.heater.isOn);
assertFalse(s.timer.isRunning());
```
- line 527: state has private access in cse4392.SeasonSwitch
- line 527: incompatible types: int cannot be converted to cse4392.SeasonState

Reply with only a JSON object that follows this JSON Schema:
{
 "$defs": {
  "CodeEdit": {
   "properties": {
    "file": {
     "description": "Source file name, e.g. SeasonSwitch.java",
     "title": "File",
     "type": "string"
    },
    "content": {
     "description": "The complete new content of that file",
     "title": "Content",
     "type": "string"
    },
    "reason": {
     "title": "Reason",
     "type": "string"
    }
   },
   "required": [
    "file",
    "content",
    "reason"
   ],
   "title": "CodeEdit",
   "type": "object"
  },
  "TestEdit": {
   "properties": {
    "id": {
     "description": "Id of the test case to change",
     "title": "Id",
     "type": "string"
    },
    "action": {
     "enum": [
      "modify",
      "delete"
     ],
     "title": "Action",
     "type": "string"
    },
    "expected": {
     "default": "",
     "description": "New expected result (modify only)",
     "title": "Expected",
     "type": "string"
    },
    "junit": {
     "default": "",
     "description": "New JUnit method body (modify only)",
     "title": "Junit",
     "type": "string"
    },
    "reason": {
     "title": "Reason",
     "type": "string"
    }
   },
   "required": [
    "id",
    "action",
    "reason"
   ],
   "title": "TestEdit",
   "type": "object"
  }
 },
 "properties": {
  "diagnosis": {
   "description": "Root cause of each failure: is the code wrong or the test wrong, and why",
   "title": "Diagnosis",
   "type": "string"
  },
  "test_edits": {
   "items": {
    "$ref": "#/$defs/TestEdit"
   },
   "title": "Test Edits",
   "type": "array"
  },
  "code_edits": {
   "items": {
    "$ref": "#/$defs/CodeEdit"
   },
   "title": "Code Edits",
   "type": "array"
  }
 },
 "required": [
  "diagnosis"
 ],
 "title": "RepairPlan",
 "type": "object"
}
