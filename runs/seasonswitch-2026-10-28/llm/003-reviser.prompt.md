<!-- role: reviser | model: Claude Opus 5.5 -->

# System

You maintain a JUnit 5 test suite designed with equivalence partitioning (EP) and boundary
value analysis (BVA). A human tester reviewed the suite and asked for changes.
Apply exactly the changes the tester asked for, and nothing else:
- "add" new test cases (use technique REVIEW unless the tester names EP or BVA, and new ids
  REVIEW-01, REVIEW-02, ...),
- "modify" test cases (same id as the one replaced, all fields filled in),
- "delete" test cases by id.
Follow the same rules as the rest of the suite: each test independent, check observable
behaviour rather than internal variables, "junit" holds only the statements of the method body.

# User

Class under test: SeasonSwitch

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

Current test suite (one JSON object per line):
{"id": "EP-01", "technique": "EP", "method": "SeasonSwitch()", "condition": "new object (valid)", "input": "new SeasonSwitch()", "expected": "AC and heater off, both timers stopped, room at 70, coolTemp 68, heatTemp 77", "junit": "SeasonSwitch s = new SeasonSwitch();\nassertFalse(s.ac.isOn);\nassertFalse(s.heater.isOn);\nassertFalse(s.timer.isRunning());\nassertFalse(s.room.timer.isRunning());\nassertEquals(70, s.room.temp);\nassertEquals(68, s.coolTemp);\nassertEquals(77, s.heatTemp);"}
{"id": "EP-02", "technique": "EP", "method": "cool()", "condition": "state OFF (valid)", "input": "new switch; cool()", "expected": "prints \"Cooling started. RmTemp=70\"; AC on, heater off, control timer running", "junit": "SeasonSwitch s = new SeasonSwitch();\nString out = TestSupport.captureOut(s::cool);\nassertEquals(List.of(\"Cooling started. RmTemp=70\"), TestSupport.lines(out));\nassertTrue(s.ac.isOn);\nassertFalse(s.heater.isOn);\nassertTrue(s.timer.isRunning());"}
{"id": "EP-03", "technique": "EP", "method": "cool()", "condition": "state COOL (valid)", "input": "cool(); cool()", "expected": "second call prints nothing; AC still on, heater off, timer running", "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\nString out = TestSupport.captureOut(s::cool);\nassertEquals(\"\", out);\nassertTrue(s.ac.isOn);\nassertFalse(s.heater.isOn);\nassertTrue(s.timer.isRunning());"}
{"id": "EP-04", "technique": "EP", "method": "cool()", "condition": "state HEAT (valid)", "input": "heat(); cool()", "expected": "prints \"Cooling started. RmTemp=70\"; heater off, AC on, timer running", "junit": "SeasonSwitch s = new SeasonSwitch();\ns.heat();\nString out = TestSupport.captureOut(s::cool);\nassertEquals(List.of(\"Cooling started. RmTemp=70\"), TestSupport.lines(out));\nassertTrue(s.ac.isOn);\nassertFalse(s.heater.isOn);\nassertTrue(s.timer.isRunning());"}
{"id": "EP-05", "technique": "EP", "method": "cool()", "condition": "state not OFF/COOL/HEAT (invalid)", "input": "state = 5; cool()", "expected": "nothing printed; AC and heater stay off; timer not started", "junit": "SeasonSwitch s = new SeasonSwitch();\nSeasonSwitch.state = 5;\nString out = TestSupport.captureOut(s::cool);\nassertEquals(\"\", out);\nassertFalse(s.ac.isOn);\nassertFalse(s.heater.isOn);\nassertFalse(s.timer.isRunning());"}
{"id": "EP-06", "technique": "EP", "method": "heat()", "condition": "state OFF (valid)", "input": "new switch; heat()", "expected": "prints \"Heating started. RmTemp=70\"; heater on, AC off, control timer running", "junit": "SeasonSwitch s = new SeasonSwitch();\nString out = TestSupport.captureOut(s::heat);\nassertEquals(List.of(\"Heating started. RmTemp=70\"), TestSupport.lines(out));\nassertTrue(s.heater.isOn);\nassertFalse(s.ac.isOn);\nassertTrue(s.timer.isRunning());"}
{"id": "EP-07", "technique": "EP", "method": "heat()", "condition": "state HEAT (valid)", "input": "heat(); heat()", "expected": "second call prints nothing; heater still on, AC off, timer running", "junit": "SeasonSwitch s = new SeasonSwitch();\ns.heat();\nString out = TestSupport.captureOut(s::heat);\nassertEquals(\"\", out);\nassertTrue(s.heater.isOn);\nassertFalse(s.ac.isOn);\nassertTrue(s.timer.isRunning());"}
{"id": "EP-08", "technique": "EP", "method": "heat()", "condition": "state COOL (valid)", "input": "cool(); heat()", "expected": "prints \"Heating started. RmTemp=70\"; AC off, heater on, timer running", "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\nString out = TestSupport.captureOut(s::heat);\nassertEquals(List.of(\"Heating started. RmTemp=70\"), TestSupport.lines(out));\nassertFalse(s.ac.isOn);\nassertTrue(s.heater.isOn);\nassertTrue(s.timer.isRunning());"}
{"id": "EP-09", "technique": "EP", "method": "heat()", "condition": "state not OFF/COOL/HEAT (invalid)", "input": "state = 5; heat()", "expected": "nothing printed; heater and AC stay off; timer not started", "junit": "SeasonSwitch s = new SeasonSwitch();\nSeasonSwitch.state = 5;\nString out = TestSupport.captureOut(s::heat);\nassertEquals(\"\", out);\nassertFalse(s.heater.isOn);\nassertFalse(s.ac.isOn);\nassertFalse(s.timer.isRunning());"}
{"id": "EP-10", "technique": "EP", "method": "off()", "condition": "state OFF (valid)", "input": "new switch; off()", "expected": "nothing printed; AC and heater off; timer not running", "junit": "SeasonSwitch s = new SeasonSwitch();\nString out = TestSupport.captureOut(s::off);\nassertEquals(\"\", out);\nassertFalse(s.ac.isOn);\nassertFalse(s.heater.isOn);\nassertFalse(s.timer.isRunning());"}
{"id": "EP-11", "technique": "EP", "method": "off()", "condition": "state COOL (valid)", "input": "cool(); off()", "expected": "prints \"Cooling stopped. RmTemp=70\"; AC off; timer stopped", "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\nString out = TestSupport.captureOut(s::off);\nassertEquals(List.of(\"Cooling stopped. RmTemp=70\"), TestSupport.lines(out));\nassertFalse(s.ac.isOn);\nassertFalse(s.timer.isRunning());"}
{"id": "EP-12", "technique": "EP", "method": "off()", "condition": "state HEAT (valid)", "input": "heat(); off()", "expected": "prints \"Heating stopped. RmTemp=70\"; heater off; timer stopped", "junit": "SeasonSwitch s = new SeasonSwitch();\ns.heat();\nString out = TestSupport.captureOut(s::off);\nassertEquals(List.of(\"Heating stopped. RmTemp=70\"), TestSupport.lines(out));\nassertFalse(s.heater.isOn);\nassertFalse(s.timer.isRunning());"}
{"id": "EP-13", "technique": "EP", "method": "off()", "condition": "state not OFF/COOL/HEAT (invalid)", "input": "cool(); state = 5; off()", "expected": "nothing printed; AC stays on and the timer keeps running", "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\nSeasonSwitch.state = 5;\nString out = TestSupport.captureOut(s::off);\nassertEquals(\"\", out);\nassertTrue(s.ac.isOn);\nassertTrue(s.timer.isRunning());"}
{"id": "EP-14", "technique": "EP", "method": "timerOff()", "condition": "state OFF (valid)", "input": "new switch; timerOff()", "expected": "nothing printed; AC and heater stay off", "junit": "SeasonSwitch s = new SeasonSwitch();\nString out = TestSupport.captureOut(s::timerOff);\nassertEquals(\"\", out);\nassertFalse(s.ac.isOn);\nassertFalse(s.heater.isOn);"}
{"id": "EP-15", "technique": "EP", "method": "timerOff()", "condition": "state COOL, temp > coolTemp (valid)", "input": "cool(); AC off; temp = 75; timerOff()", "expected": "prints \"Cooling started. RmTemp=75\"; AC on", "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\ns.ac.off();\ns.room.temp = 75;\nString out = TestSupport.captureOut(s::timerOff);\nassertEquals(List.of(\"Cooling started. RmTemp=75\"), TestSupport.lines(out));\nassertTrue(s.ac.isOn);"}
{"id": "EP-16", "technique": "EP", "method": "timerOff()", "condition": "state COOL, temp <= coolTemp (valid)", "input": "cool(); temp = 60; timerOff()", "expected": "prints \"Cooling stopped. RmTemp=60\"; AC off", "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\ns.room.temp = 60;\nString out = TestSupport.captureOut(s::timerOff);\nassertEquals(List.of(\"Cooling stopped. RmTemp=60\"), TestSupport.lines(out));\nassertFalse(s.ac.isOn);"}
{"id": "EP-17", "technique": "EP", "method": "timerOff()", "condition": "state HEAT, temp < heatTemp (valid)", "input": "heat(); heater off; temp = 60; timerOff()", "expected": "prints \"Heating started. RmTemp=60\"; heater on", "junit": "SeasonSwitch s = new SeasonSwitch();\ns.heat();\ns.heater.off();\ns.room.temp = 60;\nString out = TestSupport.captureOut(s::timerOff);\nassertEquals(List.of(\"Heating started. RmTemp=60\"), TestSupport.lines(out));\nassertTrue(s.heater.isOn);"}
{"id": "EP-18", "technique": "EP", "method": "timerOff()", "condition": "state HEAT, temp >= heatTemp (valid)", "input": "heat(); temp = 85; timerOff()", "expected": "prints \"Heating stopped. RmTemp=85\"; heater off", "junit": "SeasonSwitch s = new SeasonSwitch();\ns.heat();\ns.room.temp = 85;\nString out = TestSupport.captureOut(s::timerOff);\nassertEquals(List.of(\"Heating stopped. RmTemp=85\"), TestSupport.lines(out));\nassertFalse(s.heater.isOn);"}
{"id": "EP-19", "technique": "EP", "method": "timerOff()", "condition": "state not OFF/COOL/HEAT (invalid)", "input": "cool(); state = 5; temp = 60; timerOff()", "expected": "nothing printed; AC stays on", "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\nSeasonSwitch.state = 5;\ns.room.temp = 60;\nString out = TestSupport.captureOut(s::timerOff);\nassertEquals(\"\", out);\nassertTrue(s.ac.isOn);"}
{"id": "EP-20", "technique": "EP", "method": "actionPerformed(ActionEvent)", "condition": "control timer event (valid), state COOL, temp 75", "input": "cool(); AC off; temp = 75; actionPerformed(null)", "expected": "same as timerOff(): prints \"Cooling started. RmTemp=75\"; AC on", "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\ns.ac.off();\ns.room.temp = 75;\nString out = TestSupport.captureOut(() -> s.actionPerformed(null));\nassertEquals(List.of(\"Cooling started. RmTemp=75\"), TestSupport.lines(out));\nassertTrue(s.ac.isOn);"}
{"id": "EP-21", "technique": "EP", "method": "Room.actionPerformed(ActionEvent)", "condition": "room timer event, state OFF (valid)", "input": "new switch; room event", "expected": "room temperature stays 70", "junit": "SeasonSwitch s = new SeasonSwitch();\ns.room.actionPerformed(null);\nassertEquals(70, s.room.temp);"}
{"id": "EP-22", "technique": "EP", "method": "Room.actionPerformed(ActionEvent)", "condition": "room timer event, state COOL, AC on (valid)", "input": "cool(); room event", "expected": "room temperature falls to 69", "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\ns.room.actionPerformed(null);\nassertEquals(69, s.room.temp);"}
{"id": "EP-23", "technique": "EP", "method": "Room.actionPerformed(ActionEvent)", "condition": "room timer event, state COOL, AC off (valid)", "input": "cool(); AC off; room event", "expected": "room temperature rises to 71", "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\ns.ac.off();\ns.room.actionPerformed(null);\nassertEquals(71, s.room.temp);"}
{"id": "EP-24", "technique": "EP", "method": "Room.actionPerformed(ActionEvent)", "condition": "room timer event, state HEAT, heater on (valid)", "input": "heat(); room event", "expected": "room temperature rises to 71", "junit": "SeasonSwitch s = new SeasonSwitch();\ns.heat();\ns.room.actionPerformed(null);\nassertEquals(71, s.room.temp);"}
{"id": "EP-25", "technique": "EP", "method": "Room.actionPerformed(ActionEvent)", "condition": "room timer event, state HEAT, heater off (valid)", "input": "heat(); heater off; room event", "expected": "room temperature falls to 69", "junit": "SeasonSwitch s = new SeasonSwitch();\ns.heat();\ns.heater.off();\ns.room.actionPerformed(null);\nassertEquals(69, s.room.temp);"}
{"id": "EP-26", "technique": "EP", "method": "Room.actionPerformed(ActionEvent)", "condition": "room timer event, state not OFF/COOL/HEAT (invalid)", "input": "cool(); state = 5; room event", "expected": "room temperature stays 70", "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\nSeasonSwitch.state = 5;\ns.room.actionPerformed(null);\nassertEquals(70, s.room.temp);"}
{"id": "EP-27", "technique": "EP+BVA", "method": "main(String[])", "condition": "no argument (valid)", "input": "java SeasonSwitch", "expected": "prints the prompt, then \"Cooling started. RmTemp=70\"; keeps running", "junit": "TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000);\nassertTrue(r.stillRunning);\nList<String> lines = r.lines();\nassertEquals(\"Enter off, cool or heat to run. Press Ctrl-c to exit.\", lines.get(0));\nassertEquals(\"Cooling started. RmTemp=70\", lines.get(1));"}
{"id": "EP-28", "technique": "EP", "method": "main(String[])", "condition": "command \"cool\" (valid)", "input": "java SeasonSwitch cool", "expected": "prints the prompt, then \"Cooling started. RmTemp=70\"; keeps running", "junit": "TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000, \"cool\");\nassertTrue(r.stillRunning);\nList<String> lines = r.lines();\nassertEquals(\"Enter off, cool or heat to run. Press Ctrl-c to exit.\", lines.get(0));\nassertEquals(\"Cooling started. RmTemp=70\", lines.get(1));"}
{"id": "EP-29", "technique": "EP", "method": "main(String[])", "condition": "command \"heat\" (valid)", "input": "java SeasonSwitch heat", "expected": "prints the prompt, then \"Heating started. RmTemp=70\"; keeps running", "junit": "TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000, \"heat\");\nassertTrue(r.stillRunning);\nList<String> lines = r.lines();\nassertEquals(\"Enter off, cool or heat to run. Press Ctrl-c to exit.\", lines.get(0));\nassertEquals(\"Heating started. RmTemp=70\", lines.get(1));"}
{"id": "EP-30", "technique": "EP", "method": "main(String[])", "condition": "command \"off\" (valid)", "input": "java SeasonSwitch off", "expected": "prints the prompt and \"System off. System exits.\"; exits with code 0", "junit": "TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000, \"off\");\nassertFalse(r.stillRunning);\nassertEquals(0, r.exitCode);\nassertEquals(List.of(\"Enter off, cool or heat to run. Press Ctrl-c to exit.\", \"System off. System exits.\"), r.lines());"}
{"id": "EP-31", "technique": "EP", "method": "main(String[])", "condition": "unknown command (invalid)", "input": "java SeasonSwitch warm", "expected": "prints the prompt and \"Unknown command. System exits.\"; exits with code 1", "junit": "TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000, \"warm\");\nassertFalse(r.stillRunning);\nassertEquals(1, r.exitCode);\nassertEquals(List.of(\"Enter off, cool or heat to run. Press Ctrl-c to exit.\", \"Unknown command. System exits.\"), r.lines());"}
{"id": "BVA-01", "technique": "BVA", "method": "timerOff()", "condition": "temp = 69 = coolTemp + 1 (just above)", "input": "cool(); ac off; temp = 69; timerOff()", "expected": "prints \"Cooling started. RmTemp=69\"; ac on", "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\ns.ac.off();\ns.room.temp = 69;\nString out = TestSupport.captureOut(s::timerOff);\nassertEquals(List.of(\"Cooling started. RmTemp=69\"), TestSupport.lines(out));\nassertTrue(s.ac.isOn);"}
{"id": "BVA-02", "technique": "BVA", "method": "timerOff()", "condition": "temp = 68 = coolTemp (at the boundary)", "input": "cool(); temp = 68; timerOff()", "expected": "prints \"Cooling stopped. RmTemp=68\"; ac off", "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\ns.room.temp = 68;\nString out = TestSupport.captureOut(s::timerOff);\nassertEquals(List.of(\"Cooling stopped. RmTemp=68\"), TestSupport.lines(out));\nassertFalse(s.ac.isOn);"}
{"id": "BVA-03", "technique": "BVA", "method": "timerOff()", "condition": "temp = 67 = coolTemp - 1 (just below)", "input": "cool(); temp = 67; timerOff()", "expected": "prints \"Cooling stopped. RmTemp=67\"; ac off", "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\ns.room.temp = 67;\nString out = TestSupport.captureOut(s::timerOff);\nassertEquals(List.of(\"Cooling stopped. RmTemp=67\"), TestSupport.lines(out));\nassertFalse(s.ac.isOn);"}
{"id": "BVA-04", "technique": "BVA", "method": "timerOff()", "condition": "temp = 76 = heatTemp - 1 (just below)", "input": "heat(); heater off; temp = 76; timerOff()", "expected": "prints \"Heating started. RmTemp=76\"; heater on", "junit": "SeasonSwitch s = new SeasonSwitch();\ns.heat();\ns.heater.off();\ns.room.temp = 76;\nString out = TestSupport.captureOut(s::timerOff);\nassertEquals(List.of(\"Heating started. RmTemp=76\"), TestSupport.lines(out));\nassertTrue(s.heater.isOn);"}
{"id": "BVA-05", "technique": "BVA", "method": "timerOff()", "condition": "temp = 77 = heatTemp (at the boundary)", "input": "heat(); temp = 77; timerOff()", "expected": "prints \"Heating stopped. RmTemp=77\"; heater off", "junit": "SeasonSwitch s = new SeasonSwitch();\ns.heat();\ns.room.temp = 77;\nString out = TestSupport.captureOut(s::timerOff);\nassertEquals(List.of(\"Heating stopped. RmTemp=77\"), TestSupport.lines(out));\nassertFalse(s.heater.isOn);"}
{"id": "BVA-06", "technique": "BVA", "method": "timerOff()", "condition": "temp = 78 = heatTemp + 1 (just above)", "input": "heat(); temp = 78; timerOff()", "expected": "prints \"Heating stopped. RmTemp=78\"; heater off", "junit": "SeasonSwitch s = new SeasonSwitch();\ns.heat();\ns.room.temp = 78;\nString out = TestSupport.captureOut(s::timerOff);\nassertEquals(List.of(\"Heating stopped. RmTemp=78\"), TestSupport.lines(out));\nassertFalse(s.heater.isOn);"}
{"id": "BVA-07", "technique": "BVA", "method": "Room.actionPerformed(ActionEvent), actionPerformed(ActionEvent)", "condition": "1 room event = n-1 (n = 2 events reach coolTemp)", "input": "cool(); 1 room event(s); control event", "expected": "room at 69; prints \"Cooling started. RmTemp=69\"; ac stays on", "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\ns.room.actionPerformed(null);\nassertEquals(69, s.room.temp);\nString out = TestSupport.captureOut(() -> s.actionPerformed(null));\nassertEquals(List.of(\"Cooling started. RmTemp=69\"), TestSupport.lines(out));\nassertTrue(s.ac.isOn);"}
{"id": "BVA-08", "technique": "BVA", "method": "Room.actionPerformed(ActionEvent), actionPerformed(ActionEvent)", "condition": "2 room events = n (reaches coolTemp)", "input": "cool(); 2 room event(s); control event", "expected": "room at 68; prints \"Cooling stopped. RmTemp=68\"; ac off", "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\ns.room.actionPerformed(null);\ns.room.actionPerformed(null);\nassertEquals(68, s.room.temp);\nString out = TestSupport.captureOut(() -> s.actionPerformed(null));\nassertEquals(List.of(\"Cooling stopped. RmTemp=68\"), TestSupport.lines(out));\nassertFalse(s.ac.isOn);"}
{"id": "BVA-09", "technique": "BVA", "method": "timerOff(), Room.actionPerformed(ActionEvent)", "condition": "AC stopped at coolTemp; 1 room event moves just above it", "input": "cool(); temp = 68; timerOff(); room event; timerOff()", "expected": "ac stops at 68; room moves to 69; prints \"Cooling started. RmTemp=69\"; ac on again", "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\ns.room.temp = 68;\ns.timerOff();\nassertFalse(s.ac.isOn);\ns.room.actionPerformed(null);\nassertEquals(69, s.room.temp);\nString out = TestSupport.captureOut(s::timerOff);\nassertEquals(List.of(\"Cooling started. RmTemp=69\"), TestSupport.lines(out));\nassertTrue(s.ac.isOn);"}
{"id": "BVA-10", "technique": "BVA", "method": "Room.actionPerformed(ActionEvent), actionPerformed(ActionEvent)", "condition": "6 room events = n-1 (n = 7 events reach heatTemp)", "input": "heat(); 6 room event(s); control event", "expected": "room at 76; prints \"Heating started. RmTemp=76\"; heater stays on", "junit": "SeasonSwitch s = new SeasonSwitch();\ns.heat();\nfor (int i = 0; i < 6; i++) {\n    s.room.actionPerformed(null);\n}\nassertEquals(76, s.room.temp);\nString out = TestSupport.captureOut(() -> s.actionPerformed(null));\nassertEquals(List.of(\"Heating started. RmTemp=76\"), TestSupport.lines(out));\nassertTrue(s.heater.isOn);"}
{"id": "BVA-11", "technique": "BVA", "method": "Room.actionPerformed(ActionEvent), actionPerformed(ActionEvent)", "condition": "7 room events = n (reaches heatTemp)", "input": "heat(); 7 room event(s); control event", "expected": "room at 77; prints \"Heating stopped. RmTemp=77\"; heater off", "junit": "SeasonSwitch s = new SeasonSwitch();\ns.heat();\nfor (int i = 0; i < 7; i++) {\n    s.room.actionPerformed(null);\n}\nassertEquals(77, s.room.temp);\nString out = TestSupport.captureOut(() -> s.actionPerformed(null));\nassertEquals(List.of(\"Heating stopped. RmTemp=77\"), TestSupport.lines(out));\nassertFalse(s.heater.isOn);"}
{"id": "BVA-12", "technique": "BVA", "method": "timerOff(), Room.actionPerformed(ActionEvent)", "condition": "heater stopped at heatTemp; 1 room event moves just below it", "input": "heat(); temp = 77; timerOff(); room event; timerOff()", "expected": "heater stops at 77; room moves to 76; prints \"Heating started. RmTemp=76\"; heater on again", "junit": "SeasonSwitch s = new SeasonSwitch();\ns.heat();\ns.room.temp = 77;\ns.timerOff();\nassertFalse(s.heater.isOn);\ns.room.actionPerformed(null);\nassertEquals(76, s.room.temp);\nString out = TestSupport.captureOut(s::timerOff);\nassertEquals(List.of(\"Heating started. RmTemp=76\"), TestSupport.lines(out));\nassertTrue(s.heater.isOn);"}
{"id": "BVA-13", "technique": "BVA", "method": "cool()", "condition": "state = -1 = OFF - 1 (just below the valid range)", "input": "state = -1; cool()", "expected": "nothing printed; AC off; timer not started", "junit": "SeasonSwitch s = new SeasonSwitch();\nSeasonSwitch.state = -1;\nString out = TestSupport.captureOut(s::cool);\nassertEquals(\"\", out);\nassertFalse(s.ac.isOn);\nassertFalse(s.timer.isRunning());"}
{"id": "BVA-14", "technique": "BVA", "method": "heat()", "condition": "state = 3 = HEAT + 1 (just above the valid range)", "input": "state = 3; heat()", "expected": "nothing printed; heater off; timer not started", "junit": "SeasonSwitch s = new SeasonSwitch();\nSeasonSwitch.state = 3;\nString out = TestSupport.captureOut(s::heat);\nassertEquals(\"\", out);\nassertFalse(s.heater.isOn);\nassertFalse(s.timer.isRunning());"}
{"id": "BVA-16", "technique": "BVA", "method": "main(String[])", "condition": "2 arguments (one more than used)", "input": "java SeasonSwitch heat extra", "expected": "the extra argument is ignored: prints \"Heating started. RmTemp=70\"; keeps running", "junit": "TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000, \"heat\", \"extra\");\nassertTrue(r.stillRunning);\nassertEquals(\"Heating started. RmTemp=70\", r.lines().get(1));"}
{"id": "BVA-17", "technique": "BVA", "method": "main(String[])", "condition": "empty command (length 0)", "input": "java SeasonSwitch \"\"", "expected": "prints the prompt and \"Unknown command. System exits.\"; exits with code 1", "junit": "TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000, \"\");\nassertFalse(r.stillRunning);\nassertEquals(1, r.exitCode);\nassertEquals(\"Unknown command. System exits.\", r.lines().get(1));"}
{"id": "BVA-18", "technique": "BVA", "method": "main(String[])", "condition": "\"OFF\": differs from \"off\" only by case", "input": "java SeasonSwitch \"OFF\"", "expected": "prints the prompt and \"Unknown command. System exits.\"; exits with code 1", "junit": "TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000, \"OFF\");\nassertFalse(r.stillRunning);\nassertEquals(1, r.exitCode);\nassertEquals(\"Unknown command. System exits.\", r.lines().get(1));"}
{"id": "BVA-19", "technique": "BVA", "method": "main(String[])", "condition": "\"coo\": one character shorter than \"cool\"", "input": "java SeasonSwitch \"coo\"", "expected": "prints the prompt and \"Unknown command. System exits.\"; exits with code 1", "junit": "TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000, \"coo\");\nassertFalse(r.stillRunning);\nassertEquals(1, r.exitCode);\nassertEquals(\"Unknown command. System exits.\", r.lines().get(1));"}
{"id": "BVA-20", "technique": "BVA", "method": "main(String[])", "condition": "\"heats\": one character longer than \"heat\"", "input": "java SeasonSwitch \"heats\"", "expected": "prints the prompt and \"Unknown command. System exits.\"; exits with code 1", "junit": "TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000, \"heats\");\nassertFalse(r.stillRunning);\nassertEquals(1, r.exitCode);\nassertEquals(\"Unknown command. System exits.\", r.lines().get(1));"}

The tester's requested changes:
The suite never checks that two SeasonSwitch objects are independent. Add one test: create switch a and switch b; a.cool(); then b.off(). b was never turned on, so b.off() must print nothing, and a must keep cooling (a.ac.isOn true, a.timer running). Check only behaviour (output, AC, timer), not the state variable.

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
  "summary": {
   "description": "What was changed and why",
   "title": "Summary",
   "type": "string"
  },
  "add": {
   "description": "New test cases",
   "items": {
    "$ref": "#/$defs/TestCase"
   },
   "title": "Add",
   "type": "array"
  },
  "modify": {
   "description": "Replacements, same id as the test case they replace",
   "items": {
    "$ref": "#/$defs/TestCase"
   },
   "title": "Modify",
   "type": "array"
  },
  "delete": {
   "description": "Ids of test cases to remove",
   "items": {
    "type": "string"
   },
   "title": "Delete",
   "type": "array"
  }
 },
 "required": [
  "summary"
 ],
 "title": "SuiteRevision",
 "type": "object"
}
