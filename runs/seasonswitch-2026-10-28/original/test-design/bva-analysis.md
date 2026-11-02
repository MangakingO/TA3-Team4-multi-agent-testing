# BVA analysis

timerOff() in COOL: AC runs while room.temp > coolTemp (68) - boundary 68: test 69, 68, 67.
timerOff() in HEAT: heater runs while room.temp < heatTemp (77) - boundary 77: test 76, 77, 78.
Room events while cooling from 70 with the AC on: n = 2 events reach coolTemp - test n-1 = 1 and n = 2 events, then a control event.
Room events while heating from 70 with the heater on: n = 7 events reach heatTemp - test n-1 = 6 and n = 7 events, then a control event.
Restart after the setpoint: one room event after the device stopped moves the temperature back across the setpoint (68 -> 69 cooling, 77 -> 76 heating).
State value range OFF (0) .. HEAT (2): just below (-1) and just above (3) are invalid.
main(args): argument count 0 (default command), 2 (extra argument ignored); command text: empty string, wrong case "OFF", one character short "coo", one character too many "heats".
