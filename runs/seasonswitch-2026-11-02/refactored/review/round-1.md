# Refactoring review, round 1

Prompt: `/home/claude/ta3-multi-agent-testing/prompts/refactor_state_pattern.md` (version 1), attempt 1.

Review the code against the State pattern: Subject with the original interface, an abstract State, one concrete class per state, delegation, transitions, no conditionals on the state.

**Compiles:** yes


## Automated checks

| Check | Result | Detail |
|---|---|---|
| Subject class keeps the original name | PASS | cse4392.SeasonSwitch |
| Subject keeps exactly the original public interface | PASS | all original public members present |
| An abstract State type (interface or abstract class) | PASS | cse4392.SeasonState (abstract class) |
| One concrete class per state | PASS | CoolState, HeatState, OffState |
| Members the tests use are still there (non-private fields, methods, nested classes) | PASS | all present |
| Subject holds a reference to its current State | PASS | private SeasonState state |
| No instanceof tests on states in the Subject family | PASS | 0 instanceof instruction(s) in SeasonSwitch$AC, SeasonSwitch$Heater, SeasonSwitch$Room, SeasonSwitch |
| No switch on the state left in the Subject family | PASS | switch statements 5 -> 0; conditional branch instructions 13 -> 4 in SeasonSwitch$AC, SeasonSwitch$Heater, SeasonSwitch$Room, SeasonSwitch; all concrete states together 4 |
| Who decides the next state | INFO | the State classes (CoolState, HeatState, OffState) receive the Subject and set its next state |

| Class | Kind | Extends / implements | Branch instructions |
|---|---|---|---|
| cse4392.CoolState | class | cse4392.SeasonState | 2 |
| cse4392.HeatState | class | cse4392.SeasonState | 2 |
| cse4392.OffState | class | cse4392.SeasonState | 0 |
| cse4392.SeasonState | abstract class | - | 0 |
| cse4392.SeasonSwitch$AC | class | - | 0 |
| cse4392.SeasonSwitch$Heater | class | - | 0 |
| cse4392.SeasonSwitch$Room | class | java.awt.event.ActionListener | 0 |
| cse4392.SeasonSwitch | class | java.awt.event.ActionListener | 4 |


## The agent's design notes

## Design

States: **Off**, **Cool**, **Heat** (classes `OffState`, `CoolState`, `HeatState`, extending the abstract `SeasonState`).
Events: `cool()`, `heat()`, `off()`, the control timer (`actionPerformed` -> `timerOff()`, every 1.2 s) and the room timer (`Room.actionPerformed`, every 0.4 s).

| current state | event | next state | action |
|---|---|---|---|
| Off | cool() | Cool | AC on, start control timer, print "Cooling started. RmTemp=t" |
| Off | heat() | Heat | heater on, start control timer, print "Heating started. RmTemp=t" |
| Off | off(), control timer, room timer | Off | none |
| Cool | cool() | Cool | none |
| Cool | heat() | Heat | AC off, heater on, start control timer, print "Heating started. RmTemp=t" |
| Cool | off() | Off | AC off, stop control timer, print "Cooling stopped. RmTemp=t" |
| Cool | control timer | Cool | t > coolTemp: AC on, print "Cooling started. RmTemp=t"; else AC off, print "Cooling stopped. RmTemp=t" |
| Cool | room timer | Cool | t - 1 if the AC is on, else t + 1 |
| Heat | heat() | Heat | none |
| Heat | cool() | Cool | heater off, AC on, start control timer, print "Cooling started. RmTemp=t" |
| Heat | off() | Off | heater off, stop control timer, print "Heating stopped. RmTemp=t" |
| Heat | control timer | Heat | t < heatTemp: heater on, print "Heating started. RmTemp=t"; else heater off, print "Heating stopped. RmTemp=t" |
| Heat | room timer | Heat | t + 1 if the heater is on, else t - 1 |

`SeasonSwitch` keeps its class name, package and exact public interface; it holds the current `SeasonState` in a private field and delegates. The states choose the next state through the package-private `setState`. Every non-private field and nested class of the original (`ac`, `heater`, `timer`, `room`, `coolTemp`, `heatTemp`, `AC`, `Heater`, `Room`) is kept unchanged; only the int `state` field and its constants were replaced.

## Files

### CoolState.java

```java
package cse4392;

class CoolState extends SeasonState {
    @Override
    void cool(SeasonSwitch s) {
        // already cooling
    }

    @Override
    void heat(SeasonSwitch s) {
        s.ac.off();
        s.heater.on();
        s.timer.start();
        s.setState(HEAT);
        System.out.println("Heating started. RmTemp=" + s.room.temp);
    }

    @Override
    void off(SeasonSwitch s) {
        s.ac.off();
        s.timer.stop();
        s.setState(OFF);
        System.out.println("Cooling stopped. RmTemp=" + s.room.temp);
    }

    @Override
    void timerOff(SeasonSwitch s) {
        if (s.room.temp > s.coolTemp) {
            s.ac.on();
            System.out.println("Cooling started. RmTemp=" + s.room.temp);
        } else {
            s.ac.off();
            System.out.println("Cooling stopped. RmTemp=" + s.room.temp);
        }
    }

    @Override
    void roomTick(SeasonSwitch s) {
        if (s.ac.isOn) {
            s.room.temp--;
        } else {
            s.room.temp++;
        }
    }
}
```

### HeatState.java

```java
package cse4392;

class HeatState extends SeasonState {
    @Override
    void cool(SeasonSwitch s) {
        s.heater.off();
        s.ac.on();
        s.timer.start();
        s.setState(COOL);
        System.out.println("Cooling started. RmTemp=" + s.room.temp);
    }

    @Override
    void heat(SeasonSwitch s) {
        // already heating
    }

    @Override
    void off(SeasonSwitch s) {
        s.heater.off();
        s.timer.stop();
        s.setState(OFF);
        System.out.println("Heating stopped. RmTemp=" + s.room.temp);
    }

    @Override
    void timerOff(SeasonSwitch s) {
        if (s.room.temp < s.heatTemp) {
            s.heater.on();
            System.out.println("Heating started. RmTemp=" + s.room.temp);
        } else {
            s.heater.off();
            System.out.println("Heating stopped. RmTemp=" + s.room.temp);
        }
    }

    @Override
    void roomTick(SeasonSwitch s) {
        if (s.heater.isOn) {
            s.room.temp++;
        } else {
            s.room.temp--;
        }
    }
}
```

### OffState.java

```java
package cse4392;

class OffState extends SeasonState {
    @Override
    void cool(SeasonSwitch s) {
        s.ac.on();
        s.timer.start();
        s.setState(COOL);
        System.out.println("Cooling started. RmTemp=" + s.room.temp);
    }

    @Override
    void heat(SeasonSwitch s) {
        s.heater.on();
        s.timer.start();
        s.setState(HEAT);
        System.out.println("Heating started. RmTemp=" + s.room.temp);
    }

    @Override
    void off(SeasonSwitch s) {
        // already off
    }

    @Override
    void timerOff(SeasonSwitch s) {
        // nothing to control
    }

    @Override
    void roomTick(SeasonSwitch s) {
        // the room temperature does not change
    }
}
```

### SeasonState.java

```java
package cse4392;

abstract class SeasonState {
    static final SeasonState OFF = new OffState();
    static final SeasonState COOL = new CoolState();
    static final SeasonState HEAT = new HeatState();

    abstract void cool(SeasonSwitch s);

    abstract void heat(SeasonSwitch s);

    abstract void off(SeasonSwitch s);

    /** The control timer fired. */
    abstract void timerOff(SeasonSwitch s);

    /** The room timer fired: how the room temperature changes in this state. */
    abstract void roomTick(SeasonSwitch s);
}
```

### SeasonSwitch.java

```java
package cse4392;

import javax.swing.Timer;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.concurrent.CountDownLatch;

/** Subject (context) of the State pattern: delegates every state-dependent request to its current state. */
public class SeasonSwitch implements ActionListener {
   private SeasonState state = SeasonState.OFF;
   AC ac=new AC();
   Heater heater=new Heater();
   Timer timer=new Timer(1200, this);
   Room room=new Room();
   int coolTemp=68;
   int heatTemp=77;

   void setState(SeasonState next) {
      state = next;
   }

   public void cool() {
      state.cool(this);
   }
   public void heat() {
      state.heat(this);
   }
   public void off() {
      state.off(this);
   }
   public void actionPerformed(ActionEvent e) {
      timerOff();
   }
   public void timerOff() {
      state.timerOff(this);
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
      state.roomTick(SeasonSwitch.this);
   }
}
}
```

---
Answer in the console (a = accept, f = feedback (regenerate), p = I edited the prompt, c = I edited the code). Without the console, write your answer into `round-1.md.answer.txt` next to this file, then run `java -jar target/ta3-agents.jar resume seasonswitch-2026-11-02`.
