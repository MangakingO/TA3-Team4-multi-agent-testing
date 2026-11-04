# Testability analysis

Facts about SeasonSwitch that every test must respect (from the testability analysis):
1. The test class is in package cse4392, so tests may use package-private members. Non-private members:
   - SeasonSwitch: AC ac; Heater heater; javax.swing.Timer timer; Room room; int coolTemp; int heatTemp; public SeasonSwitch(); void setState(SeasonState); public void cool(); public void heat(); public void off(); public void actionPerformed(ActionEvent); public void timerOff(); public static void main(String[])
   - SeasonSwitch.AC: boolean isOn; public void on(); public void off()
   - SeasonSwitch.Heater: boolean isOn; public void on(); public void off()
   - SeasonSwitch.Room: public int temp; javax.swing.Timer timer; public void actionPerformed(ActionEvent)
2. Timers: SeasonSwitch.timer, SeasonSwitch.Room.timer. Never wait for a timer (no Thread.sleep). Simulate one timer event by calling the listener directly: `s.actionPerformed(null)` (where s is the SeasonSwitch under test), `s.room.actionPerformed(null)`. Every SeasonSwitch created in a test is tracked and its timers are stopped after the test.
3. Console output: SeasonSwitch prints with System.out. Check it with `String out = TestSupport.captureOut(() -> s.someMethod());` which returns the printed text with "\n" line endings; `TestSupport.lines(out)` splits it into lines.
4. main(): it calls System.exit and may never return (await()). Never call SeasonSwitch.main directly. Use `TestSupport.MainRun r = TestSupport.runMain(SeasonSwitch.class, 3000, "arg");` which runs main in a separate JVM for at most 3000 ms and gives r.exitCode (the System.exit code, 0 if main returned or was stopped), r.stillRunning (true if main was still running and was stopped), r.out and r.lines(). Each call takes about a second, so use it only for main().
