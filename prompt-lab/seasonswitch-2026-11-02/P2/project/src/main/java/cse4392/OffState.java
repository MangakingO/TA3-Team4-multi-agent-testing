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
