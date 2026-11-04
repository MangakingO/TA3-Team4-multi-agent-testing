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
