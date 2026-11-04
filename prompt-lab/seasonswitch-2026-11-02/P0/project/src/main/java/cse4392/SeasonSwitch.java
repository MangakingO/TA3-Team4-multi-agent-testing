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
