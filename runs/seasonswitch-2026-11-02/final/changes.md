# Change log

Every change the agents made to the code or the test cases, with the reason and a unified diff.

## 1. [original] reviser: add test case REVIEW-01 (round 1)

*2026-11-02T20:49:48*

**Reason:** The suite never checks that two SeasonSwitch objects are independent. Add one test: create switch a and switch b; a.cool(); then b.off(). b was never turned on, so b.off() must print nothing, and a must keep cooling (a.ac.isOn true, a.timer running). Check only behaviour (output, AC, timer), not the state variable.

```diff
--- a/REVIEW-01
+++ b/REVIEW-01
@@ -0,0 +1,10 @@
+{
+  "id": "REVIEW-01",
+  "technique": "REVIEW",
+  "method": "off(), cool()",
+  "condition": "two SeasonSwitch objects are independent",
+  "input": "a = new SeasonSwitch(); b = new SeasonSwitch(); a.cool(); b.off()",
+  "expected": "b.off() prints nothing (b was never on); a keeps cooling: AC on, timer running",
+  "junit": "SeasonSwitch a = new SeasonSwitch();\nSeasonSwitch b = new SeasonSwitch();\na.cool();\nString out = TestSupport.captureOut(b::off);\nassertEquals(\"\", out);\nassertTrue(a.ac.isOn);\nassertTrue(a.timer.isRunning());",
+  "also": []
+}
```

## 2. [original] repair agent: modify code SeasonSwitch.java (round 1)

*2026-11-02T20:50:25*

**Reason:** state was static and shared by all SeasonSwitch objects; it is now an instance field, so each switch keeps its own state (OFF, COOL or HEAT)

```diff
--- a/SeasonSwitch.java
+++ b/SeasonSwitch.java
@@ -6,7 +6,7 @@
 import java.util.concurrent.CountDownLatch;
 
 public class SeasonSwitch implements ActionListener {
-   static int state=0;
+   int state=0;
    static final int OFF=0, COOL=1, HEAT=2;
    AC ac=new AC();
    Heater heater=new Heater();
```

## 3. [original] repair agent: modify test case EP-05 (round 2)

*2026-11-04T12:23:59*

**Reason:** state is now an instance field (repair round 1), so the invalid state is set on the switch under test (s.state) instead of through the class

```diff
--- a/EP-05
+++ b/EP-05
@@ -5,6 +5,6 @@
   "condition": "state not OFF/COOL/HEAT (invalid)",
   "input": "state = 5; cool()",
   "expected": "nothing printed; AC and heater stay off; timer not started",
-  "junit": "SeasonSwitch s = new SeasonSwitch();\nSeasonSwitch.state = 5;\nString out = TestSupport.captureOut(s::cool);\nassertEquals(\"\", out);\nassertFalse(s.ac.isOn);\nassertFalse(s.heater.isOn);\nassertFalse(s.timer.isRunning());",
+  "junit": "SeasonSwitch s = new SeasonSwitch();\ns.state = 5;\nString out = TestSupport.captureOut(s::cool);\nassertEquals(\"\", out);\nassertFalse(s.ac.isOn);\nassertFalse(s.heater.isOn);\nassertFalse(s.timer.isRunning());",
   "also": []
 }
```

## 4. [original] repair agent: modify test case EP-09 (round 2)

*2026-11-04T12:23:59*

**Reason:** state is now an instance field (repair round 1), so the invalid state is set on the switch under test (s.state) instead of through the class

```diff
--- a/EP-09
+++ b/EP-09
@@ -5,6 +5,6 @@
   "condition": "state not OFF/COOL/HEAT (invalid)",
   "input": "state = 5; heat()",
   "expected": "nothing printed; heater and AC stay off; timer not started",
-  "junit": "SeasonSwitch s = new SeasonSwitch();\nSeasonSwitch.state = 5;\nString out = TestSupport.captureOut(s::heat);\nassertEquals(\"\", out);\nassertFalse(s.heater.isOn);\nassertFalse(s.ac.isOn);\nassertFalse(s.timer.isRunning());",
+  "junit": "SeasonSwitch s = new SeasonSwitch();\ns.state = 5;\nString out = TestSupport.captureOut(s::heat);\nassertEquals(\"\", out);\nassertFalse(s.heater.isOn);\nassertFalse(s.ac.isOn);\nassertFalse(s.timer.isRunning());",
   "also": []
 }
```

## 5. [original] repair agent: modify test case EP-13 (round 2)

*2026-11-04T12:23:59*

**Reason:** state is now an instance field (repair round 1), so the invalid state is set on the switch under test (s.state) instead of through the class

```diff
--- a/EP-13
+++ b/EP-13
@@ -5,6 +5,6 @@
   "condition": "state not OFF/COOL/HEAT (invalid)",
   "input": "cool(); state = 5; off()",
   "expected": "nothing printed; AC stays on and the timer keeps running",
-  "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\nSeasonSwitch.state = 5;\nString out = TestSupport.captureOut(s::off);\nassertEquals(\"\", out);\nassertTrue(s.ac.isOn);\nassertTrue(s.timer.isRunning());",
+  "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\ns.state = 5;\nString out = TestSupport.captureOut(s::off);\nassertEquals(\"\", out);\nassertTrue(s.ac.isOn);\nassertTrue(s.timer.isRunning());",
   "also": []
 }
```

## 6. [original] repair agent: modify test case EP-19 (round 2)

*2026-11-04T12:23:59*

**Reason:** state is now an instance field (repair round 1), so the invalid state is set on the switch under test (s.state) instead of through the class

```diff
--- a/EP-19
+++ b/EP-19
@@ -5,6 +5,6 @@
   "condition": "state not OFF/COOL/HEAT (invalid)",
   "input": "cool(); state = 5; temp = 60; timerOff()",
   "expected": "nothing printed; AC stays on",
-  "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\nSeasonSwitch.state = 5;\ns.room.temp = 60;\nString out = TestSupport.captureOut(s::timerOff);\nassertEquals(\"\", out);\nassertTrue(s.ac.isOn);",
+  "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\ns.state = 5;\ns.room.temp = 60;\nString out = TestSupport.captureOut(s::timerOff);\nassertEquals(\"\", out);\nassertTrue(s.ac.isOn);",
   "also": []
 }
```

## 7. [original] repair agent: modify test case EP-26 (round 2)

*2026-11-04T12:23:59*

**Reason:** state is now an instance field (repair round 1), so the invalid state is set on the switch under test (s.state) instead of through the class

```diff
--- a/EP-26
+++ b/EP-26
@@ -5,6 +5,6 @@
   "condition": "room timer event, state not OFF/COOL/HEAT (invalid)",
   "input": "cool(); state = 5; room event",
   "expected": "room temperature stays 70",
-  "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\nSeasonSwitch.state = 5;\ns.room.actionPerformed(null);\nassertEquals(70, s.room.temp);",
+  "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\ns.state = 5;\ns.room.actionPerformed(null);\nassertEquals(70, s.room.temp);",
   "also": []
 }
```

## 8. [original] repair agent: modify test case BVA-13 (round 2)

*2026-11-04T12:23:59*

**Reason:** state is now an instance field (repair round 1), so the invalid state is set on the switch under test (s.state) instead of through the class

```diff
--- a/BVA-13
+++ b/BVA-13
@@ -5,6 +5,6 @@
   "condition": "state = -1 = OFF - 1 (just below the valid range)",
   "input": "state = -1; cool()",
   "expected": "nothing printed; AC off; timer not started",
-  "junit": "SeasonSwitch s = new SeasonSwitch();\nSeasonSwitch.state = -1;\nString out = TestSupport.captureOut(s::cool);\nassertEquals(\"\", out);\nassertFalse(s.ac.isOn);\nassertFalse(s.timer.isRunning());",
+  "junit": "SeasonSwitch s = new SeasonSwitch();\ns.state = -1;\nString out = TestSupport.captureOut(s::cool);\nassertEquals(\"\", out);\nassertFalse(s.ac.isOn);\nassertFalse(s.timer.isRunning());",
   "also": []
 }
```

## 9. [original] repair agent: modify test case BVA-14 (round 2)

*2026-11-04T12:23:59*

**Reason:** state is now an instance field (repair round 1), so the invalid state is set on the switch under test (s.state) instead of through the class

```diff
--- a/BVA-14
+++ b/BVA-14
@@ -5,6 +5,6 @@
   "condition": "state = 3 = HEAT + 1 (just above the valid range)",
   "input": "state = 3; heat()",
   "expected": "nothing printed; heater off; timer not started",
-  "junit": "SeasonSwitch s = new SeasonSwitch();\nSeasonSwitch.state = 3;\nString out = TestSupport.captureOut(s::heat);\nassertEquals(\"\", out);\nassertFalse(s.heater.isOn);\nassertFalse(s.timer.isRunning());",
+  "junit": "SeasonSwitch s = new SeasonSwitch();\ns.state = 3;\nString out = TestSupport.captureOut(s::heat);\nassertEquals(\"\", out);\nassertFalse(s.heater.isOn);\nassertFalse(s.timer.isRunning());",
   "also": []
 }
```

## 10. [refactored] test adapter: delete test case EP-05 (round 1)

*2026-11-04T12:25:37*

**Reason:** Obsolete after the State pattern refactoring: the test forced an int value that matches no state constant into the state field, to reach the hidden default branch of a switch (state). The refactoring replaced the int state with a private SeasonState object that can only be OffState, CoolState or HeatState, and removed every switch, so this invalid state can no longer exist.

```diff
--- a/EP-05
+++ b/EP-05
@@ -1,10 +0,0 @@
-{
-  "id": "EP-05",
-  "technique": "EP",
-  "method": "cool()",
-  "condition": "state not OFF/COOL/HEAT (invalid)",
-  "input": "state = 5; cool()",
-  "expected": "nothing printed; AC and heater stay off; timer not started",
-  "junit": "SeasonSwitch s = new SeasonSwitch();\ns.state = 5;\nString out = TestSupport.captureOut(s::cool);\nassertEquals(\"\", out);\nassertFalse(s.ac.isOn);\nassertFalse(s.heater.isOn);\nassertFalse(s.timer.isRunning());",
-  "also": []
-}
```

## 11. [refactored] test adapter: delete test case EP-09 (round 1)

*2026-11-04T12:25:37*

**Reason:** Obsolete after the State pattern refactoring: the test forced an int value that matches no state constant into the state field, to reach the hidden default branch of a switch (state). The refactoring replaced the int state with a private SeasonState object that can only be OffState, CoolState or HeatState, and removed every switch, so this invalid state can no longer exist.

```diff
--- a/EP-09
+++ b/EP-09
@@ -1,10 +0,0 @@
-{
-  "id": "EP-09",
-  "technique": "EP",
-  "method": "heat()",
-  "condition": "state not OFF/COOL/HEAT (invalid)",
-  "input": "state = 5; heat()",
-  "expected": "nothing printed; heater and AC stay off; timer not started",
-  "junit": "SeasonSwitch s = new SeasonSwitch();\ns.state = 5;\nString out = TestSupport.captureOut(s::heat);\nassertEquals(\"\", out);\nassertFalse(s.heater.isOn);\nassertFalse(s.ac.isOn);\nassertFalse(s.timer.isRunning());",
-  "also": []
-}
```

## 12. [refactored] test adapter: delete test case EP-13 (round 1)

*2026-11-04T12:25:37*

**Reason:** Obsolete after the State pattern refactoring: the test forced an int value that matches no state constant into the state field, to reach the hidden default branch of a switch (state). The refactoring replaced the int state with a private SeasonState object that can only be OffState, CoolState or HeatState, and removed every switch, so this invalid state can no longer exist.

```diff
--- a/EP-13
+++ b/EP-13
@@ -1,10 +0,0 @@
-{
-  "id": "EP-13",
-  "technique": "EP",
-  "method": "off()",
-  "condition": "state not OFF/COOL/HEAT (invalid)",
-  "input": "cool(); state = 5; off()",
-  "expected": "nothing printed; AC stays on and the timer keeps running",
-  "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\ns.state = 5;\nString out = TestSupport.captureOut(s::off);\nassertEquals(\"\", out);\nassertTrue(s.ac.isOn);\nassertTrue(s.timer.isRunning());",
-  "also": []
-}
```

## 13. [refactored] test adapter: delete test case EP-19 (round 1)

*2026-11-04T12:25:37*

**Reason:** Obsolete after the State pattern refactoring: the test forced an int value that matches no state constant into the state field, to reach the hidden default branch of a switch (state). The refactoring replaced the int state with a private SeasonState object that can only be OffState, CoolState or HeatState, and removed every switch, so this invalid state can no longer exist.

```diff
--- a/EP-19
+++ b/EP-19
@@ -1,10 +0,0 @@
-{
-  "id": "EP-19",
-  "technique": "EP",
-  "method": "timerOff()",
-  "condition": "state not OFF/COOL/HEAT (invalid)",
-  "input": "cool(); state = 5; temp = 60; timerOff()",
-  "expected": "nothing printed; AC stays on",
-  "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\ns.state = 5;\ns.room.temp = 60;\nString out = TestSupport.captureOut(s::timerOff);\nassertEquals(\"\", out);\nassertTrue(s.ac.isOn);",
-  "also": []
-}
```

## 14. [refactored] test adapter: delete test case EP-26 (round 1)

*2026-11-04T12:25:37*

**Reason:** Obsolete after the State pattern refactoring: the test forced an int value that matches no state constant into the state field, to reach the hidden default branch of a switch (state). The refactoring replaced the int state with a private SeasonState object that can only be OffState, CoolState or HeatState, and removed every switch, so this invalid state can no longer exist.

```diff
--- a/EP-26
+++ b/EP-26
@@ -1,10 +0,0 @@
-{
-  "id": "EP-26",
-  "technique": "EP",
-  "method": "Room.actionPerformed(ActionEvent)",
-  "condition": "room timer event, state not OFF/COOL/HEAT (invalid)",
-  "input": "cool(); state = 5; room event",
-  "expected": "room temperature stays 70",
-  "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\ns.state = 5;\ns.room.actionPerformed(null);\nassertEquals(70, s.room.temp);",
-  "also": []
-}
```

## 15. [refactored] test adapter: delete test case BVA-13 (round 1)

*2026-11-04T12:25:37*

**Reason:** Obsolete after the State pattern refactoring: the test forced an int value that matches no state constant into the state field, to reach the hidden default branch of a switch (state). The refactoring replaced the int state with a private SeasonState object that can only be OffState, CoolState or HeatState, and removed every switch, so this invalid state can no longer exist.

```diff
--- a/BVA-13
+++ b/BVA-13
@@ -1,10 +0,0 @@
-{
-  "id": "BVA-13",
-  "technique": "BVA",
-  "method": "cool()",
-  "condition": "state = -1 = OFF - 1 (just below the valid range)",
-  "input": "state = -1; cool()",
-  "expected": "nothing printed; AC off; timer not started",
-  "junit": "SeasonSwitch s = new SeasonSwitch();\ns.state = -1;\nString out = TestSupport.captureOut(s::cool);\nassertEquals(\"\", out);\nassertFalse(s.ac.isOn);\nassertFalse(s.timer.isRunning());",
-  "also": []
-}
```

## 16. [refactored] test adapter: delete test case BVA-14 (round 1)

*2026-11-04T12:25:37*

**Reason:** Obsolete after the State pattern refactoring: the test forced an int value that matches no state constant into the state field, to reach the hidden default branch of a switch (state). The refactoring replaced the int state with a private SeasonState object that can only be OffState, CoolState or HeatState, and removed every switch, so this invalid state can no longer exist.

```diff
--- a/BVA-14
+++ b/BVA-14
@@ -1,10 +0,0 @@
-{
-  "id": "BVA-14",
-  "technique": "BVA",
-  "method": "heat()",
-  "condition": "state = 3 = HEAT + 1 (just above the valid range)",
-  "input": "state = 3; heat()",
-  "expected": "nothing printed; heater off; timer not started",
-  "junit": "SeasonSwitch s = new SeasonSwitch();\ns.state = 3;\nString out = TestSupport.captureOut(s::heat);\nassertEquals(\"\", out);\nassertFalse(s.heater.isOn);\nassertFalse(s.timer.isRunning());",
-  "also": []
-}
```
