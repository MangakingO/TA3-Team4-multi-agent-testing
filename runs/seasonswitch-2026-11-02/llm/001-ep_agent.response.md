{
 "analysis": "Inputs: the current state (OFF, COOL, HEAT; invalid = any other value), room.temp compared with coolTemp (68) and heatTemp (77), whether the AC or heater is on (room timer events), and main's command-line argument.\nSeasonSwitch(): new object (valid) - starts off.\ncool(): state OFF (valid); COOL (valid); HEAT (valid); any other state value (invalid).\nheat(): state OFF (valid); HEAT (valid); COOL (valid); any other state value (invalid).\noff(): state OFF (valid); COOL (valid); HEAT (valid); any other state value (invalid).\ntimerOff() (control timer event): OFF (valid); COOL with temp > coolTemp (valid); COOL with temp <= coolTemp (valid); HEAT with temp < heatTemp (valid); HEAT with temp >= heatTemp (valid); any other state value (invalid).\nactionPerformed(e): a control timer event (valid) - behaves as timerOff().\nRoom.actionPerformed(e) (room timer event): OFF (valid); COOL with AC on (valid); COOL with AC off (valid); HEAT with heater on (valid); HEAT with heater off (valid); any other state value (invalid).\nmain(args): no argument (valid); \"cool\" (valid); \"heat\" (valid); \"off\" (valid); any other command (invalid).",
 "test_cases": [
  {
   "id": "EP-01",
   "technique": "EP",
   "method": "SeasonSwitch()",
   "condition": "new object (valid)",
   "input": "new SeasonSwitch()",
   "expected": "AC and heater off, both timers stopped, room at 70, coolTemp 68, heatTemp 77",
   "junit": "SeasonSwitch s = new SeasonSwitch();\nassertFalse(s.ac.isOn);\nassertFalse(s.heater.isOn);\nassertFalse(s.timer.isRunning());\nassertFalse(s.room.timer.isRunning());\nassertEquals(70, s.room.temp);\nassertEquals(68, s.coolTemp);\nassertEquals(77, s.heatTemp);"
  },
  {
   "id": "EP-02",
   "technique": "EP",
   "method": "cool()",
   "condition": "state OFF (valid)",
   "input": "new switch; cool()",
   "expected": "prints \"Cooling started. RmTemp=70\"; AC on, heater off, control timer running",
   "junit": "SeasonSwitch s = new SeasonSwitch();\nString out = TestSupport.captureOut(s::cool);\nassertEquals(List.of(\"Cooling started. RmTemp=70\"), TestSupport.lines(out));\nassertTrue(s.ac.isOn);\nassertFalse(s.heater.isOn);\nassertTrue(s.timer.isRunning());"
  },
  {
   "id": "EP-03",
   "technique": "EP",
   "method": "cool()",
   "condition": "state COOL (valid)",
   "input": "cool(); cool()",
   "expected": "second call prints nothing; AC still on, heater off, timer running",
   "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\nString out = TestSupport.captureOut(s::cool);\nassertEquals(\"\", out);\nassertTrue(s.ac.isOn);\nassertFalse(s.heater.isOn);\nassertTrue(s.timer.isRunning());"
  },
  {
   "id": "EP-04",
   "technique": "EP",
   "method": "cool()",
   "condition": "state HEAT (valid)",
   "input": "heat(); cool()",
   "expected": "prints \"Cooling started. RmTemp=70\"; heater off, AC on, timer running",
   "junit": "SeasonSwitch s = new SeasonSwitch();\ns.heat();\nString out = TestSupport.captureOut(s::cool);\nassertEquals(List.of(\"Cooling started. RmTemp=70\"), TestSupport.lines(out));\nassertTrue(s.ac.isOn);\nassertFalse(s.heater.isOn);\nassertTrue(s.timer.isRunning());"
  },
  {
   "id": "EP-05",
   "technique": "EP",
   "method": "cool()",
   "condition": "state not OFF/COOL/HEAT (invalid)",
   "input": "state = 5; cool()",
   "expected": "nothing printed; AC and heater stay off; timer not started",
   "junit": "SeasonSwitch s = new SeasonSwitch();\nSeasonSwitch.state = 5;\nString out = TestSupport.captureOut(s::cool);\nassertEquals(\"\", out);\nassertFalse(s.ac.isOn);\nassertFalse(s.heater.isOn);\nassertFalse(s.timer.isRunning());"
  },
  {
   "id": "EP-06",
   "technique": "EP",
   "method": "heat()",
   "condition": "state OFF (valid)",
   "input": "new switch; heat()",
   "expected": "prints \"Heating started. RmTemp=70\"; heater on, AC off, control timer running",
   "junit": "SeasonSwitch s = new SeasonSwitch();\nString out = TestSupport.captureOut(s::heat);\nassertEquals(List.of(\"Heating started. RmTemp=70\"), TestSupport.lines(out));\nassertTrue(s.heater.isOn);\nassertFalse(s.ac.isOn);\nassertTrue(s.timer.isRunning());"
  },
  {
   "id": "EP-07",
   "technique": "EP",
   "method": "heat()",
   "condition": "state HEAT (valid)",
   "input": "heat(); heat()",
   "expected": "second call prints nothing; heater still on, AC off, timer running",
   "junit": "SeasonSwitch s = new SeasonSwitch();\ns.heat();\nString out = TestSupport.captureOut(s::heat);\nassertEquals(\"\", out);\nassertTrue(s.heater.isOn);\nassertFalse(s.ac.isOn);\nassertTrue(s.timer.isRunning());"
  },
  {
   "id": "EP-08",
   "technique": "EP",
   "method": "heat()",
   "condition": "state COOL (valid)",
   "input": "cool(); heat()",
   "expected": "prints \"Heating started. RmTemp=70\"; AC off, heater on, timer running",
   "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\nString out = TestSupport.captureOut(s::heat);\nassertEquals(List.of(\"Heating started. RmTemp=70\"), TestSupport.lines(out));\nassertFalse(s.ac.isOn);\nassertTrue(s.heater.isOn);\nassertTrue(s.timer.isRunning());"
  },
  {
   "id": "EP-09",
   "technique": "EP",
   "method": "heat()",
   "condition": "state not OFF/COOL/HEAT (invalid)",
   "input": "state = 5; heat()",
   "expected": "nothing printed; heater and AC stay off; timer not started",
   "junit": "SeasonSwitch s = new SeasonSwitch();\nSeasonSwitch.state = 5;\nString out = TestSupport.captureOut(s::heat);\nassertEquals(\"\", out);\nassertFalse(s.heater.isOn);\nassertFalse(s.ac.isOn);\nassertFalse(s.timer.isRunning());"
  },
  {
   "id": "EP-10",
   "technique": "EP",
   "method": "off()",
   "condition": "state OFF (valid)",
   "input": "new switch; off()",
   "expected": "nothing printed; AC and heater off; timer not running",
   "junit": "SeasonSwitch s = new SeasonSwitch();\nString out = TestSupport.captureOut(s::off);\nassertEquals(\"\", out);\nassertFalse(s.ac.isOn);\nassertFalse(s.heater.isOn);\nassertFalse(s.timer.isRunning());"
  },
  {
   "id": "EP-11",
   "technique": "EP",
   "method": "off()",
   "condition": "state COOL (valid)",
   "input": "cool(); off()",
   "expected": "prints \"Cooling stopped. RmTemp=70\"; AC off; timer stopped",
   "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\nString out = TestSupport.captureOut(s::off);\nassertEquals(List.of(\"Cooling stopped. RmTemp=70\"), TestSupport.lines(out));\nassertFalse(s.ac.isOn);\nassertFalse(s.timer.isRunning());"
  },
  {
   "id": "EP-12",
   "technique": "EP",
   "method": "off()",
   "condition": "state HEAT (valid)",
   "input": "heat(); off()",
   "expected": "prints \"Heating stopped. RmTemp=70\"; heater off; timer stopped",
   "junit": "SeasonSwitch s = new SeasonSwitch();\ns.heat();\nString out = TestSupport.captureOut(s::off);\nassertEquals(List.of(\"Heating stopped. RmTemp=70\"), TestSupport.lines(out));\nassertFalse(s.heater.isOn);\nassertFalse(s.timer.isRunning());"
  },
  {
   "id": "EP-13",
   "technique": "EP",
   "method": "off()",
   "condition": "state not OFF/COOL/HEAT (invalid)",
   "input": "cool(); state = 5; off()",
   "expected": "nothing printed; AC stays on and the timer keeps running",
   "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\nSeasonSwitch.state = 5;\nString out = TestSupport.captureOut(s::off);\nassertEquals(\"\", out);\nassertTrue(s.ac.isOn);\nassertTrue(s.timer.isRunning());"
  },
  {
   "id": "EP-14",
   "technique": "EP",
   "method": "timerOff()",
   "condition": "state OFF (valid)",
   "input": "new switch; timerOff()",
   "expected": "nothing printed; AC and heater stay off",
   "junit": "SeasonSwitch s = new SeasonSwitch();\nString out = TestSupport.captureOut(s::timerOff);\nassertEquals(\"\", out);\nassertFalse(s.ac.isOn);\nassertFalse(s.heater.isOn);"
  },
  {
   "id": "EP-15",
   "technique": "EP",
   "method": "timerOff()",
   "condition": "state COOL, temp > coolTemp (valid)",
   "input": "cool(); AC off; temp = 75; timerOff()",
   "expected": "prints \"Cooling started. RmTemp=75\"; AC on",
   "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\ns.ac.off();\ns.room.temp = 75;\nString out = TestSupport.captureOut(s::timerOff);\nassertEquals(List.of(\"Cooling started. RmTemp=75\"), TestSupport.lines(out));\nassertTrue(s.ac.isOn);"
  },
  {
   "id": "EP-16",
   "technique": "EP",
   "method": "timerOff()",
   "condition": "state COOL, temp <= coolTemp (valid)",
   "input": "cool(); temp = 60; timerOff()",
   "expected": "prints \"Cooling stopped. RmTemp=60\"; AC off",
   "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\ns.room.temp = 60;\nString out = TestSupport.captureOut(s::timerOff);\nassertEquals(List.of(\"Cooling stopped. RmTemp=60\"), TestSupport.lines(out));\nassertFalse(s.ac.isOn);"
  },
  {
   "id": "EP-17",
   "technique": "EP",
   "method": "timerOff()",
   "condition": "state HEAT, temp < heatTemp (valid)",
   "input": "heat(); heater off; temp = 60; timerOff()",
   "expected": "prints \"Heating started. RmTemp=60\"; heater on",
   "junit": "SeasonSwitch s = new SeasonSwitch();\ns.heat();\ns.heater.off();\ns.room.temp = 60;\nString out = TestSupport.captureOut(s::timerOff);\nassertEquals(List.of(\"Heating started. RmTemp=60\"), TestSupport.lines(out));\nassertTrue(s.heater.isOn);"
  },
  {
   "id": "EP-18",
   "technique": "EP",
   "method": "timerOff()",
   "condition": "state HEAT, temp >= heatTemp (valid)",
   "input": "heat(); temp = 85; timerOff()",
   "expected": "prints \"Heating stopped. RmTemp=85\"; heater off",
   "junit": "SeasonSwitch s = new SeasonSwitch();\ns.heat();\ns.room.temp = 85;\nString out = TestSupport.captureOut(s::timerOff);\nassertEquals(List.of(\"Heating stopped. RmTemp=85\"), TestSupport.lines(out));\nassertFalse(s.heater.isOn);"
  },
  {
   "id": "EP-19",
   "technique": "EP",
   "method": "timerOff()",
   "condition": "state not OFF/COOL/HEAT (invalid)",
   "input": "cool(); state = 5; temp = 60; timerOff()",
   "expected": "nothing printed; AC stays on",
   "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\nSeasonSwitch.state = 5;\ns.room.temp = 60;\nString out = TestSupport.captureOut(s::timerOff);\nassertEquals(\"\", out);\nassertTrue(s.ac.isOn);"
  },
  {
   "id": "EP-20",
   "technique": "EP",
   "method": "actionPerformed(ActionEvent)",
   "condition": "control timer event (valid), state COOL, temp 75",
   "input": "cool(); AC off; temp = 75; actionPerformed(null)",
   "expected": "same as timerOff(): prints \"Cooling started. RmTemp=75\"; AC on",
   "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\ns.ac.off();\ns.room.temp = 75;\nString out = TestSupport.captureOut(() -> s.actionPerformed(null));\nassertEquals(List.of(\"Cooling started. RmTemp=75\"), TestSupport.lines(out));\nassertTrue(s.ac.isOn);"
  },
  {
   "id": "EP-21",
   "technique": "EP",
   "method": "Room.actionPerformed(ActionEvent)",
   "condition": "room timer event, state OFF (valid)",
   "input": "new switch; room event",
   "expected": "room temperature stays 70",
   "junit": "SeasonSwitch s = new SeasonSwitch();\ns.room.actionPerformed(null);\nassertEquals(70, s.room.temp);"
  },
  {
   "id": "EP-22",
   "technique": "EP",
   "method": "Room.actionPerformed(ActionEvent)",
   "condition": "room timer event, state COOL, AC on (valid)",
   "input": "cool(); room event",
   "expected": "room temperature falls to 69",
   "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\ns.room.actionPerformed(null);\nassertEquals(69, s.room.temp);"
  },
  {
   "id": "EP-23",
   "technique": "EP",
   "method": "Room.actionPerformed(ActionEvent)",
   "condition": "room timer event, state COOL, AC off (valid)",
   "input": "cool(); AC off; room event",
   "expected": "room temperature rises to 71",
   "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\ns.ac.off();\ns.room.actionPerformed(null);\nassertEquals(71, s.room.temp);"
  },
  {
   "id": "EP-24",
   "technique": "EP",
   "method": "Room.actionPerformed(ActionEvent)",
   "condition": "room timer event, state HEAT, heater on (valid)",
   "input": "heat(); room event",
   "expected": "room temperature rises to 71",
   "junit": "SeasonSwitch s = new SeasonSwitch();\ns.heat();\ns.room.actionPerformed(null);\nassertEquals(71, s.room.temp);"
  },
  {
   "id": "EP-25",
   "technique": "EP",
   "method": "Room.actionPerformed(ActionEvent)",
   "condition": "room timer event, state HEAT, heater off (valid)",
   "input": "heat(); heater off; room event",
   "expected": "room temperature falls to 69",
   "junit": "SeasonSwitch s = new SeasonSwitch();\ns.heat();\ns.heater.off();\ns.room.actionPerformed(null);\nassertEquals(69, s.room.temp);"
  },
  {
   "id": "EP-26",
   "technique": "EP",
   "method": "Room.actionPerformed(ActionEvent)",
   "condition": "room timer event, state not OFF/COOL/HEAT (invalid)",
   "input": "cool(); state = 5; room event",
   "expected": "room temperature stays 70",
   "junit": "SeasonSwitch s = new SeasonSwitch();\ns.cool();\nSeasonSwitch.state = 5;\ns.room.actionPerformed(null);\nassertEquals(70, s.room.temp);"
  },
  {
   "id": "EP-27",
   "technique": "EP",
   "method": "main(String[])",
   "condition": "no argument (valid)",
   "input": "java SeasonSwitch",
   "expected": "prints the prompt, then \"Cooling started. RmTemp=70\"; keeps running",
   "junit": "TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000);\nassertTrue(r.stillRunning);\nList<String> lines = r.lines();\nassertEquals(\"Enter off, cool or heat to run. Press Ctrl-c to exit.\", lines.get(0));\nassertEquals(\"Cooling started. RmTemp=70\", lines.get(1));"
  },
  {
   "id": "EP-28",
   "technique": "EP",
   "method": "main(String[])",
   "condition": "command \"cool\" (valid)",
   "input": "java SeasonSwitch cool",
   "expected": "prints the prompt, then \"Cooling started. RmTemp=70\"; keeps running",
   "junit": "TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000, \"cool\");\nassertTrue(r.stillRunning);\nList<String> lines = r.lines();\nassertEquals(\"Enter off, cool or heat to run. Press Ctrl-c to exit.\", lines.get(0));\nassertEquals(\"Cooling started. RmTemp=70\", lines.get(1));"
  },
  {
   "id": "EP-29",
   "technique": "EP",
   "method": "main(String[])",
   "condition": "command \"heat\" (valid)",
   "input": "java SeasonSwitch heat",
   "expected": "prints the prompt, then \"Heating started. RmTemp=70\"; keeps running",
   "junit": "TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000, \"heat\");\nassertTrue(r.stillRunning);\nList<String> lines = r.lines();\nassertEquals(\"Enter off, cool or heat to run. Press Ctrl-c to exit.\", lines.get(0));\nassertEquals(\"Heating started. RmTemp=70\", lines.get(1));"
  },
  {
   "id": "EP-30",
   "technique": "EP",
   "method": "main(String[])",
   "condition": "command \"off\" (valid)",
   "input": "java SeasonSwitch off",
   "expected": "prints the prompt and \"System off. System exits.\"; exits with code 0",
   "junit": "TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000, \"off\");\nassertFalse(r.stillRunning);\nassertEquals(0, r.exitCode);\nassertEquals(List.of(\"Enter off, cool or heat to run. Press Ctrl-c to exit.\", \"System off. System exits.\"), r.lines());"
  },
  {
   "id": "EP-31",
   "technique": "EP",
   "method": "main(String[])",
   "condition": "unknown command (invalid)",
   "input": "java SeasonSwitch warm",
   "expected": "prints the prompt and \"Unknown command. System exits.\"; exits with code 1",
   "junit": "TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000, \"warm\");\nassertFalse(r.stillRunning);\nassertEquals(1, r.exitCode);\nassertEquals(List.of(\"Enter off, cool or heat to run. Press Ctrl-c to exit.\", \"Unknown command. System exits.\"), r.lines());"
  }
 ]
}