Here's a refactoring of `SeasonSwitch` using the **State design pattern**. Each mode (Off, Cool, Heat) becomes its own state class, and `SeasonSwitch` delegates to its current state instead of switching on an `int`. I also tidied up encapsulation (private fields with accessors) and replaced `compareTo(...) == 0` with `equals`.

### SeasonState.java

```java
package cse4392;

/** State interface: one method per event the season switch reacts to. */
public interface SeasonState {
    void cool(SeasonSwitch context);
    void heat(SeasonSwitch context);
    void off(SeasonSwitch context);
    void timerTick(SeasonSwitch context);
    void roomTick(SeasonSwitch context);
}
```

### OffState.java

```java
package cse4392;

public class OffState implements SeasonState {
    public static final OffState INSTANCE = new OffState();

    private OffState() {
    }

    @Override
    public void cool(SeasonSwitch context) {
        context.getAc().on();
        context.getTimer().start();
        context.setState(CoolState.INSTANCE);
        System.out.println("Cooling started. RmTemp=" + context.getRoom().getTemp());
    }

    @Override
    public void heat(SeasonSwitch context) {
        context.getHeater().on();
        context.getTimer().start();
        context.setState(HeatState.INSTANCE);
        System.out.println("Heating started. RmTemp=" + context.getRoom().getTemp());
    }

    @Override
    public void off(SeasonSwitch context) {
        // already off
    }

    @Override
    public void timerTick(SeasonSwitch context) {
        // nothing to control while off
    }

    @Override
    public void roomTick(SeasonSwitch context) {
        // room temperature is not affected while off
    }
}
```

### CoolState.java

```java
package cse4392;

public class CoolState implements SeasonState {
    public static final CoolState INSTANCE = new CoolState();

    private CoolState() {
    }

    @Override
    public void cool(SeasonSwitch context) {
        // already cooling
    }

    @Override
    public void heat(SeasonSwitch context) {
        context.getAc().off();
        context.getHeater().on();
        context.getTimer().start();
        context.setState(HeatState.INSTANCE);
        System.out.println("Heating started. RmTemp=" + context.getRoom().getTemp());
    }

    @Override
    public void off(SeasonSwitch context) {
        context.getAc().off();
        context.getTimer().stop();
        context.setState(OffState.INSTANCE);
        System.out.println("Cooling stopped. RmTemp=" + context.getRoom().getTemp());
    }

    @Override
    public void timerTick(SeasonSwitch context) {
        SeasonSwitch.Room room = context.getRoom();
        if (room.getTemp() > context.getCoolTemp()) {
            context.getAc().on();
            System.out.println("Cooling started. RmTemp=" + room.getTemp());
        } else {
            context.getAc().off();
            System.out.println("Cooling stopped. RmTemp=" + room.getTemp());
        }
    }

    @Override
    public void roomTick(SeasonSwitch context) {
        context.getRoom().changeTemp(context.getAc().isOn() ? -1 : 1);
    }
}
```

### HeatState.java

```java
package cse4392;

public class HeatState implements SeasonState {
    public static final HeatState INSTANCE = new HeatState();

    private HeatState() {
    }

    @Override
    public void cool(SeasonSwitch context) {
        context.getHeater().off();
        context.getAc().on();
        context.getTimer().start();
        context.setState(CoolState.INSTANCE);
        System.out.println("Cooling started. RmTemp=" + context.getRoom().getTemp());
    }

    @Override
    public void heat(SeasonSwitch context) {
        // already heating
    }

    @Override
    public void off(SeasonSwitch context) {
        context.getHeater().off();
        context.getTimer().stop();
        context.setState(OffState.INSTANCE);
        System.out.println("Heating stopped. RmTemp=" + context.getRoom().getTemp());
    }

    @Override
    public void timerTick(SeasonSwitch context) {
        SeasonSwitch.Room room = context.getRoom();
        if (room.getTemp() < context.getHeatTemp()) {
            context.getHeater().on();
            System.out.println("Heating started. RmTemp=" + room.getTemp());
        } else {
            context.getHeater().off();
            System.out.println("Heating stopped. RmTemp=" + room.getTemp());
        }
    }

    @Override
    public void roomTick(SeasonSwitch context) {
        context.getRoom().changeTemp(context.getHeater().isOn() ? 1 : -1);
    }
}
```

### SeasonSwitch.java

```java
package cse4392;

import javax.swing.Timer;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.concurrent.CountDownLatch;

public class SeasonSwitch implements ActionListener {
    private SeasonState state = OffState.INSTANCE;
    private final AC ac = new AC();
    private final Heater heater = new Heater();
    private final Timer timer = new Timer(1200, this);
    private final Room room = new Room();
    private final int coolTemp = 68;
    private final int heatTemp = 77;

    public void cool() {
        state.cool(this);
    }

    public void heat() {
        state.heat(this);
    }

    public void off() {
        state.off(this);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        timerOff();
    }

    public void timerOff() {
        state.timerTick(this);
    }

    public SeasonState getState() {
        return state;
    }

    public void setState(SeasonState state) {
        this.state = state;
    }

    public AC getAc() {
        return ac;
    }

    public Heater getHeater() {
        return heater;
    }

    public Timer getTimer() {
        return timer;
    }

    public Room getRoom() {
        return room;
    }

    public int getCoolTemp() {
        return coolTemp;
    }

    public int getHeatTemp() {
        return heatTemp;
    }

    public static void main(String[] args) {
        System.out.println("Enter off, cool or heat to run. Press Ctrl-c to exit.");
        SeasonSwitch s = new SeasonSwitch();
        s.getRoom().getTimer().start();
        if (args.length == 0) {
            s.cool();
        } else if ("off".equals(args[0])) {
            s.off();
            System.out.println("System off. System exits.");
            System.exit(0);
        } else if ("cool".equals(args[0])) {
            s.cool();
        } else if ("heat".equals(args[0])) {
            s.heat();
        } else {
            System.out.println("Unknown command. System exits.");
            System.exit(1);
        }
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            s.getTimer().stop();
            s.getRoom().getTimer().stop();
        }
    }

    public static class AC {
        private boolean on;

        public void on() {
            on = true;
        }

        public void off() {
            on = false;
        }

        public boolean isOn() {
            return on;
        }
    }

    public static class Heater {
        private boolean on;

        public void on() {
            on = true;
        }

        public void off() {
            on = false;
        }

        public boolean isOn() {
            return on;
        }
    }

    public class Room implements ActionListener {
        private int temp = 70;
        private final Timer timer = new Timer(400, this);

        public int getTemp() {
            return temp;
        }

        void changeTemp(int delta) {
            temp += delta;
        }

        public Timer getTimer() {
            return timer;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            state.roomTick(SeasonSwitch.this);
        }
    }
}
```

**What changed**

- The `int state` and the `switch` statements are gone; behaviour for each mode lives in `OffState`, `CoolState` and `HeatState`, and each state decides the next one via `setState`.
- The room's temperature change (previously a `switch` inside `Room`) is also delegated to the state (`roomTick`).
- Fields are private with accessors, and the states are singletons because they hold no data.
