<!-- role: bva_agent | model: Claude Opus 5.5 -->

# System

You are a senior software test engineer. You design functional (black-box) test cases with the
BOUNDARY VALUE ANALYSIS technique and write them as JUnit 5 tests.

Boundary value analysis:
- Faults cluster at the edges of equivalence classes, so test at and around every boundary.
- For an ordered input range or a threshold, test the value just below, at, and just above each
  boundary (for a range [min, max]: min-1, min, min+1, max-1, max, max+1; for a comparison such
  as x > limit: limit-1, limit, limit+1). Leave out values the type cannot hold.
- Thresholds compared with fields count as boundaries too, even when the value comes from a field
  the test sets rather than from a parameter.
- For behaviour that depends on the object's state, the boundaries are where the behaviour
  changes: the first and the last state of a sequence, the transition that wraps around, and the
  smallest and largest numbers of repeated events (0, 1, n-1, n and n+1 events).
- For strings, collections and command-line arguments: empty, one element, the shortest and the
  longest accepted value, and values that differ from an accepted one by one character or by case.
- Each boundary value gets its own test case. Name the boundary in "condition",
  for example "temp = 68 = coolTemp (at the boundary)".

Rules for every test case:
- Make each test independent: create a new object, bring it into the needed state with calls,
  then check every observable result: return values, console output, exit codes, and the objects
  the class controls.
- Check behaviour, not implementation: do not read internal bookkeeping variables (such as a state
  code) when the behaviour shows the same thing, so the tests still work after a refactoring.
  Write such a variable only when no call can reach the input you need (an invalid state).
- Take the expected result from the intended behaviour (comments, names and the observed program
  output), not from what a line of code happens to do. If the code contradicts its documented
  behaviour, expect the documented behaviour.
- JUnit 5 assertions are statically imported (assertEquals, assertTrue, assertThrows, ...);
  java.util.* and java.io.* are imported. The helper class TestSupport is in the same package.
- "junit" holds only the statements of the test method body: no @Test, no method header, no imports.
- Ids are BVA-01, BVA-02, ... "technique" is BVA.

# User

Class under test: SeasonSwitch (package cse4392)

```java
package cse4392;

import javax.swing.Timer;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.concurrent.CountDownLatch;

public class SeasonSwitch implements ActionListener {
   static int state=0;
   static final int OFF=0, COOL=1, HEAT=2;
   AC ac=new AC();
   Heater heater=new Heater();
   Timer timer=new Timer(1200, this);
   Room room=new Room();
   int coolTemp=68;
   int heatTemp=77;
   public void cool() {
      switch (state) {
         case OFF:
            ac.on();
            timer.start();
            state=COOL;
            System.out.println("Cooling started. RmTemp="+room.temp);
            break;
         case COOL: break;
         case HEAT:
            heater.off();
            ac.on();
            timer.start();
            state=COOL;
            System.out.println("Cooling started. RmTemp="+room.temp);
            break;
      }
   }
   public void heat() {
      switch (state) {
         case OFF:
            heater.on();
            timer.start();
            state=HEAT;
            System.out.println("Heating started. RmTemp="+room.temp);
            break;
         case HEAT: break;
         case COOL:
            ac.off();
            heater.on();
            timer.start();
            state=HEAT;
            System.out.println("Heating started. RmTemp="+room.temp);
            break;
      }
   }
   public void off() {
      switch (state) {
         case OFF: break;
         case HEAT:
            heater.off();
            timer.stop();
            state=OFF;
            System.out.println("Heating stopped. RmTemp="+room.temp);
            break;
         case COOL:
            ac.off();
            timer.stop();
            state=OFF;
            System.out.println("Cooling stopped. RmTemp="+room.temp);
            break;
      }
   }
   public void actionPerformed(ActionEvent e) {
      timerOff();
   }
   public void timerOff() {
      switch (state) {
         case OFF: break;
         case COOL:
            if (room.temp>coolTemp) {
               ac.on();
               System.out.println("Cooling started. RmTemp="+room.temp);
            } else {
               ac.off();
               System.out.println("Cooling stopped. RmTemp="+room.temp);
            }
            break;
         case HEAT:
            if (room.temp<heatTemp) {
               heater.on();
               System.out.println("Heating started. RmTemp="+room.temp);
            } else {
               heater.off();
               System.out.println("Heating stopped. RmTemp="+room.temp);
            }
            break;
      }
   }    
   public static void main(String[] args) {
      System.out.println("Enter off, cool or heat to run. "+
         "Press Ctrl-c to exit.");
      SeasonSwitch s=new SeasonSwitch();
      s.room.timer.start();
      if (args.length==0) s.cool(); else
      if ("off".compareTo(args[0])==0) {
         s.off(); 
         System.out.println("System off. System exits.");
         System.exit(0);
      } else
      if ("cool".compareTo(args[0])==0) s.cool(); else
      if ("heat".compareTo(args[0])==0) s.heat(); else {
         System.out.println("Unknown command. System exits.");
         System.exit(1);
      }
      try {
         new CountDownLatch(5).await();
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         s.timer.stop();
         s.room.timer.stop();
      }
   }
class AC {
   boolean isOn=false;
   public void on() { isOn=true; }
   public void off() { isOn=false; }
}
class Heater {
   boolean isOn=false;
   public void on() { isOn=true; }
   public void off() { isOn=false; }
}
class Room implements ActionListener {
   public int temp=70;
   Timer timer=new Timer(400, this);
   public void actionPerformed(ActionEvent ae) {
      switch (state) {
         case OFF: return;
         case COOL: 
            if (ac.isOn) temp--; else temp++;
            break;
         case HEAT:
            if (heater.isOn) temp++; else temp--;
            break;
      } 
   }   
} // end of Room class
}
```

Observed behaviour (the program run from the command line):
```
$ java SeasonSwitch (no arguments)   -> still running after 4 s, stopped
Enter off, cool or heat to run. Press Ctrl-c to exit.
Cooling started. RmTemp=70
Cooling stopped. RmTemp=68
Cooling started. RmTemp=71
Cooling stopped. RmTemp=68

$ java SeasonSwitch cool   -> still running after 4 s, stopped
Enter off, cool or heat to run. Press Ctrl-c to exit.
Cooling started. RmTemp=70
Cooling stopped. RmTemp=68
Cooling started. RmTemp=71
Cooling stopped. RmTemp=68

$ java SeasonSwitch heat   -> still running after 4 s, stopped
Enter off, cool or heat to run. Press Ctrl-c to exit.
Heating started. RmTemp=70
Heating started. RmTemp=72
Heating started. RmTemp=75
Heating stopped. RmTemp=78

$ java SeasonSwitch off   -> exit code 0
Enter off, cool or heat to run. Press Ctrl-c to exit.
System off. System exits.

$ java SeasonSwitch warm   -> exit code 1
Enter off, cool or heat to run. Press Ctrl-c to exit.
Unknown command. System exits.
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

Task: list the boundaries of every operation of SeasonSwitch in "analysis" (one line each),
then write one test case for each boundary value in "test_cases".

Reply with only a JSON object that follows this JSON Schema:
{
 "$defs": {
  "TestCase": {
   "properties": {
    "id": {
     "description": "Unique id, e.g. EP-01, BVA-07 or COV-02",
     "title": "Id",
     "type": "string"
    },
    "technique": {
     "description": "EP, BVA, COVERAGE or REVIEW",
     "title": "Technique",
     "type": "string"
    },
    "method": {
     "description": "Public method(s) or constructor under test, e.g. next()",
     "title": "Method",
     "type": "string"
    },
    "condition": {
     "description": "The equivalence class or boundary this test exercises",
     "title": "Condition",
     "type": "string"
    },
    "input": {
     "description": "Concrete inputs: starting state plus the calls and argument values",
     "title": "Input",
     "type": "string"
    },
    "expected": {
     "description": "Expected observable result (return value, state, output or exception)",
     "title": "Expected",
     "type": "string"
    },
    "junit": {
     "description": "Java statements for the body of one JUnit 5 test method",
     "title": "Junit",
     "type": "string"
    }
   },
   "required": [
    "id",
    "technique",
    "method",
    "condition",
    "input",
    "expected",
    "junit"
   ],
   "title": "TestCase",
   "type": "object"
  }
 },
 "properties": {
  "analysis": {
   "description": "Short list of the equivalence classes or boundaries identified",
   "title": "Analysis",
   "type": "string"
  },
  "test_cases": {
   "items": {
    "$ref": "#/$defs/TestCase"
   },
   "title": "Test Cases",
   "type": "array"
  }
 },
 "required": [
  "analysis",
  "test_cases"
 ],
 "title": "TestSuiteDraft",
 "type": "object"
}
