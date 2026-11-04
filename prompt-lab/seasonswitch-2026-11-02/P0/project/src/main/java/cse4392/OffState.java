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
