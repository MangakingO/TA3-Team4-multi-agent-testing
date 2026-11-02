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
