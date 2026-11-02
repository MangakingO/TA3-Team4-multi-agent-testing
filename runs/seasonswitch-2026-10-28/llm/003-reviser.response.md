{
 "summary": "Added REVIEW-01: two SeasonSwitch objects must not affect each other (a cools, b is turned off).",
 "add": [
  {
   "id": "REVIEW-01",
   "technique": "REVIEW",
   "method": "off(), cool()",
   "condition": "two SeasonSwitch objects are independent",
   "input": "a = new SeasonSwitch(); b = new SeasonSwitch(); a.cool(); b.off()",
   "expected": "b.off() prints nothing (b was never on); a keeps cooling: AC on, timer running",
   "junit": "SeasonSwitch a = new SeasonSwitch();\nSeasonSwitch b = new SeasonSwitch();\na.cool();\nString out = TestSupport.captureOut(b::off);\nassertEquals(\"\", out);\nassertTrue(a.ac.isOn);\nassertTrue(a.timer.isRunning());"
  }
 ],
 "modify": [],
 "delete": []
}