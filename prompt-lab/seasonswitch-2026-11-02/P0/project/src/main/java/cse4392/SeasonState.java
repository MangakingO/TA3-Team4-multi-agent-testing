package cse4392;

/** State interface: one method per event the season switch reacts to. */
public interface SeasonState {
    void cool(SeasonSwitch context);
    void heat(SeasonSwitch context);
    void off(SeasonSwitch context);
    void timerTick(SeasonSwitch context);
    void roomTick(SeasonSwitch context);
}
