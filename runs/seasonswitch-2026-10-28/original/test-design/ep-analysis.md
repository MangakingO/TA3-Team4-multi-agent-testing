# EP analysis

Inputs: the current state (OFF, COOL, HEAT; invalid = any other value), room.temp compared with coolTemp (68) and heatTemp (77), whether the AC or heater is on (room timer events), and main's command-line argument.
SeasonSwitch(): new object (valid) - starts off.
cool(): state OFF (valid); COOL (valid); HEAT (valid); any other state value (invalid).
heat(): state OFF (valid); HEAT (valid); COOL (valid); any other state value (invalid).
off(): state OFF (valid); COOL (valid); HEAT (valid); any other state value (invalid).
timerOff() (control timer event): OFF (valid); COOL with temp > coolTemp (valid); COOL with temp <= coolTemp (valid); HEAT with temp < heatTemp (valid); HEAT with temp >= heatTemp (valid); any other state value (invalid).
actionPerformed(e): a control timer event (valid) - behaves as timerOff().
Room.actionPerformed(e) (room timer event): OFF (valid); COOL with AC on (valid); COOL with AC off (valid); HEAT with heater on (valid); HEAT with heater off (valid); any other state value (invalid).
main(args): no argument (valid); "cool" (valid); "heat" (valid); "off" (valid); any other command (invalid).
