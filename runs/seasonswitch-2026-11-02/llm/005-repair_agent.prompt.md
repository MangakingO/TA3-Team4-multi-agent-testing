<!-- role: repair_agent | model: Claude Opus 5.5 -->

# System

You are a debugging agent on a test team. Some JUnit 5 tests failed or did not compile.
For every problem decide whether the CODE or the TEST is wrong, explain why in "diagnosis",
then fix the faulty side with the smallest change.

Phase policy (original code): the test cases were designed from the intended behaviour of the class. If the code does not do what its comments, names and observed behaviour say it should, the CODE is wrong: fix it with the smallest change. If a test expects the wrong result, sets up the object wrongly, or has a Java mistake, the TEST is wrong: fix the test.

Rules for fixes:
- A code edit gives the COMPLETE new content of one source file (no ellipses).
- A test edit gives the complete new method body in "junit" (statements only) and the new
  "expected"; use "delete" only for a test that is invalid or duplicates another one.
- Never weaken a correct test (removing assertions, catching the failure) just to make it pass.
- When you change the code, also fix every test that relied on the old, wrong behaviour.
- Do not touch anything that is not related to a listed problem.

# User

Phase: original. Class under test: SeasonSwitch

Source files:
// ===== SeasonSwitch.java =====
```java
  1 | package cse4392;
  2 | 
  3 | import javax.swing.Timer;
  4 | import java.awt.event.ActionEvent;
  5 | import java.awt.event.ActionListener;
  6 | import java.util.concurrent.CountDownLatch;
  7 | 
  8 | public class SeasonSwitch implements ActionListener {
  9 |    int state=0;
 10 |    static final int OFF=0, COOL=1, HEAT=2;
 11 |    AC ac=new AC();
 12 |    Heater heater=new Heater();
 13 |    Timer timer=new Timer(1200, this);
 14 |    Room room=new Room();
 15 |    int coolTemp=68;
 16 |    int heatTemp=77;
 17 |    public void cool() {
 18 |       switch (state) {
 19 |          case OFF:
 20 |             ac.on();
 21 |             timer.start();
 22 |             state=COOL;
 23 |             System.out.println("Cooling started. RmTemp="+room.temp);
 24 |             break;
 25 |          case COOL: break;
 26 |          case HEAT:
 27 |             heater.off();
 28 |             ac.on();
 29 |             timer.start();
 30 |             state=COOL;
 31 |             System.out.println("Cooling started. RmTemp="+room.temp);
 32 |             break;
 33 |       }
 34 |    }
 35 |    public void heat() {
 36 |       switch (state) {
 37 |          case OFF:
 38 |             heater.on();
 39 |             timer.start();
 40 |             state=HEAT;
 41 |             System.out.println("Heating started. RmTemp="+room.temp);
 42 |             break;
 43 |          case HEAT: break;
 44 |          case COOL:
 45 |             ac.off();
 46 |             heater.on();
 47 |             timer.start();
 48 |             state=HEAT;
 49 |             System.out.println("Heating started. RmTemp="+room.temp);
 50 |             break;
 51 |       }
 52 |    }
 53 |    public void off() {
 54 |       switch (state) {
 55 |          case OFF: break;
 56 |          case HEAT:
 57 |             heater.off();
 58 |             timer.stop();
 59 |             state=OFF;
 60 |             System.out.println("Heating stopped. RmTemp="+room.temp);
 61 |             break;
 62 |          case COOL:
 63 |             ac.off();
 64 |             timer.stop();
 65 |             state=OFF;
 66 |             System.out.println("Cooling stopped. RmTemp="+room.temp);
 67 |             break;
 68 |       }
 69 |    }
 70 |    public void actionPerformed(ActionEvent e) {
 71 |       timerOff();
 72 |    }
 73 |    public void timerOff() {
 74 |       switch (state) {
 75 |          case OFF: break;
 76 |          case COOL:
 77 |             if (room.temp>coolTemp) {
 78 |                ac.on();
 79 |                System.out.println("Cooling started. RmTemp="+room.temp);
 80 |             } else {
 81 |                ac.off();
 82 |                System.out.println("Cooling stopped. RmTemp="+room.temp);
 83 |             }
 84 |             break;
 85 |          case HEAT:
 86 |             if (room.temp<heatTemp) {
 87 |                heater.on();
 88 |                System.out.println("Heating started. RmTemp="+room.temp);
 89 |             } else {
 90 |                heater.off();
 91 |                System.out.println("Heating stopped. RmTemp="+room.temp);
 92 |             }
 93 |             break;
 94 |       }
 95 |    }    
 96 |    public static void main(String[] args) {
 97 |       System.out.println("Enter off, cool or heat to run. "+
 98 |          "Press Ctrl-c to exit.");
 99 |       SeasonSwitch s=new SeasonSwitch();
100 |       s.room.timer.start();
101 |       if (args.length==0) s.cool(); else
102 |       if ("off".compareTo(args[0])==0) {
103 |          s.off(); 
104 |          System.out.println("System off. System exits.");
105 |          System.exit(0);
106 |       } else
107 |       if ("cool".compareTo(args[0])==0) s.cool(); else
108 |       if ("heat".compareTo(args[0])==0) s.heat(); else {
109 |          System.out.println("Unknown command. System exits.");
110 |          System.exit(1);
111 |       }
112 |       try {
113 |          new CountDownLatch(5).await();
114 |       } catch (InterruptedException e) {
115 |          Thread.currentThread().interrupt();
116 |          s.timer.stop();
117 |          s.room.timer.stop();
118 |       }
119 |    }
120 | class AC {
121 |    boolean isOn=false;
122 |    public void on() { isOn=true; }
123 |    public void off() { isOn=false; }
124 | }
125 | class Heater {
126 |    boolean isOn=false;
127 |    public void on() { isOn=true; }
128 |    public void off() { isOn=false; }
129 | }
130 | class Room implements ActionListener {
131 |    public int temp=70;
132 |    Timer timer=new Timer(400, this);
133 |    public void actionPerformed(ActionEvent ae) {
134 |       switch (state) {
135 |          case OFF: return;
136 |          case COOL: 
137 |             if (ac.isOn) temp--; else temp++;
138 |             break;
139 |          case HEAT:
140 |             if (heater.isOn) temp++; else temp--;
141 |             break;
142 |       } 
143 |    }   
144 | } // end of Room class
145 | }
```

Facts about SeasonSwitch that every test must respect (from the testability analysis):
1. The test class is in package cse4392, so tests may use package-private members. Non-private members:
   - SeasonSwitch: static int state; static final int OFF = 0; static final int COOL = 1; static final int HEAT = 2; AC ac; Heater heater; javax.swing.Timer timer; Room room; int coolTemp; int heatTemp; public SeasonSwitch(); public void cool(); public void heat(); public void off(); public void actionPerformed(ActionEvent); public void timerOff(); public static void main(String[])
   - SeasonSwitch.AC: boolean isOn; public void on(); public void off()
   - SeasonSwitch.Heater: boolean isOn; public void on(); public void off()
   - SeasonSwitch.Room: public int temp; javax.swing.Timer timer; public void actionPerformed(ActionEvent)
2. Static state: SeasonSwitch.state is static, so shared by all SeasonSwitch objects. The test class puts static fields back to their initial values before each test.
3. Timers: SeasonSwitch.timer, SeasonSwitch.Room.timer. Never wait for a timer (no Thread.sleep). Simulate one timer event by calling the listener directly: `s.actionPerformed(null)` (where s is the SeasonSwitch under test), `s.room.actionPerformed(null)`. Every SeasonSwitch created in a test is tracked and its timers are stopped after the test.
4. Console output: SeasonSwitch prints with System.out. Check it with `String out = TestSupport.captureOut(() -> s.someMethod());` which returns the printed text with "\n" line endings; `TestSupport.lines(out)` splits it into lines.
5. main(): it calls System.exit and may never return (await()). Never call SeasonSwitch.main directly. Use `TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000, "arg");` which runs main in a separate JVM for at most 3000 ms and gives r.exitCode (the System.exit code, 0 if main returned or was stopped), r.stillRunning (true if main was still running and was stopped), r.out and r.lines(). Each call takes about a second, so use it only for main().

Failing tests:
(none)

Compile errors:
- [test] SeasonSwitchTest.java line 77 (inside test EP-05): non-static variable state cannot be referenced from a static context
- [test] SeasonSwitchTest.java line 124 (inside test EP-09): non-static variable state cannot be referenced from a static context
- [test] SeasonSwitchTest.java line 170 (inside test EP-13): non-static variable state cannot be referenced from a static context
- [test] SeasonSwitchTest.java line 238 (inside test EP-19): non-static variable state cannot be referenced from a static context
- [test] SeasonSwitchTest.java line 308 (inside test EP-26): non-static variable state cannot be referenced from a static context
- [test] SeasonSwitchTest.java line 516 (inside test BVA-13): non-static variable state cannot be referenced from a static context
- [test] SeasonSwitchTest.java line 527 (inside test BVA-14): non-static variable state cannot be referenced from a static context

BVA-13 code:
```java
SeasonSwitch s = new SeasonSwitch();
SeasonSwitch.state = -1;
String out = TestSupport.captureOut(s::cool);
assertEquals("", out);
assertFalse(s.ac.isOn);
assertFalse(s.timer.isRunning());
```

BVA-14 code:
```java
SeasonSwitch s = new SeasonSwitch();
SeasonSwitch.state = 3;
String out = TestSupport.captureOut(s::heat);
assertEquals("", out);
assertFalse(s.heater.isOn);
assertFalse(s.timer.isRunning());
```

EP-05 code:
```java
SeasonSwitch s = new SeasonSwitch();
SeasonSwitch.state = 5;
String out = TestSupport.captureOut(s::cool);
assertEquals("", out);
assertFalse(s.ac.isOn);
assertFalse(s.heater.isOn);
assertFalse(s.timer.isRunning());
```

EP-09 code:
```java
SeasonSwitch s = new SeasonSwitch();
SeasonSwitch.state = 5;
String out = TestSupport.captureOut(s::heat);
assertEquals("", out);
assertFalse(s.heater.isOn);
assertFalse(s.ac.isOn);
assertFalse(s.timer.isRunning());
```

EP-13 code:
```java
SeasonSwitch s = new SeasonSwitch();
s.cool();
SeasonSwitch.state = 5;
String out = TestSupport.captureOut(s::off);
assertEquals("", out);
assertTrue(s.ac.isOn);
assertTrue(s.timer.isRunning());
```

EP-19 code:
```java
SeasonSwitch s = new SeasonSwitch();
s.cool();
SeasonSwitch.state = 5;
s.room.temp = 60;
String out = TestSupport.captureOut(s::timerOff);
assertEquals("", out);
assertTrue(s.ac.isOn);
```

EP-26 code:
```java
SeasonSwitch s = new SeasonSwitch();
s.cool();
SeasonSwitch.state = 5;
s.room.actionPerformed(null);
assertEquals(70, s.room.temp);
```

Tests that pass: 0

Reply with only a JSON object that follows this JSON Schema:
{
  "type": "object",
  "properties": {
    "diagnosis": {
      "type": "string",
      "description": "Root cause of each failure: is the code wrong or the test wrong, and why"
    },
    "test_edits": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "id": {
            "type": "string",
            "description": "Id of the test case to change"
          },
          "action": {
            "type": "string",
            "enum": [
              "modify",
              "delete"
            ]
          },
          "expected": {
            "type": "string",
            "description": "New expected result (modify only)"
          },
          "junit": {
            "type": "string",
            "description": "New JUnit method body (modify only)"
          },
          "reason": {
            "type": "string"
          }
        },
        "required": [
          "id",
          "action",
          "reason"
        ]
      }
    },
    "code_edits": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "file": {
            "type": "string",
            "description": "Source file name, e.g. SeasonSwitch.java"
          },
          "content": {
            "type": "string",
            "description": "The complete new content of that file"
          },
          "reason": {
            "type": "string"
          }
        },
        "required": [
          "file",
          "content",
          "reason"
        ]
      }
    }
  },
  "required": [
    "diagnosis"
  ]
}
