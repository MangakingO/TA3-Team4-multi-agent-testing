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
