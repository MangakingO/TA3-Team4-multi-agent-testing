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
