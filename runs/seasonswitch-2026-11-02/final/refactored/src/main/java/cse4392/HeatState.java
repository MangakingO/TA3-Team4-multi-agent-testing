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
