The refactoring keeps `SeasonSwitch` as the Subject with exactly the same public interface (constructor, `cool()`, `heat()`, `off()`, `actionPerformed(ActionEvent)`, `timerOff()`, `main(String[])`) and the same output, exit codes and timers. All other members keep their names and package-private access, because code in the same package may use them.

- `SeasonState` (abstract) declares one method per state-dependent operation, including the room-timer event that `Room` used to `switch` on.
- `OffState`, `CoolState`, `HeatState` implement each mode and choose the next state through the package-private `SeasonSwitch.setState(...)`; no public members were added.
- No `switch` or `if`/`else` chain on the state is left in `SeasonSwitch` or its nested classes; the remaining `if`s compare temperatures or check whether a device is on.
- `state` is now a private `SeasonState` field. The int constants `OFF`, `COOL`, `HEAT` were only used by the old `switch` statements, so they were removed.

```java
// SeasonState.java
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

```java
// OffState.java
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

```java
// CoolState.java
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

```java
// HeatState.java
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

```java
// SeasonSwitch.java
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
