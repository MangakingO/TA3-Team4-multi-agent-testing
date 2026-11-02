# Observed behaviour

The program compiled with `mvn compile` and run with `java` (each run stopped after 4 s if it was still running).

```
$ java SeasonSwitch (no arguments)   -> still running after 4 s, stopped
Enter off, cool or heat to run. Press Ctrl-c to exit.
Cooling started. RmTemp=70
Cooling stopped. RmTemp=68
Cooling started. RmTemp=71
Cooling stopped. RmTemp=68

$ java SeasonSwitch cool   -> still running after 4 s, stopped
Enter off, cool or heat to run. Press Ctrl-c to exit.
Cooling started. RmTemp=70
Cooling stopped. RmTemp=68
Cooling started. RmTemp=71
Cooling stopped. RmTemp=68

$ java SeasonSwitch heat   -> still running after 4 s, stopped
Enter off, cool or heat to run. Press Ctrl-c to exit.
Heating started. RmTemp=70
Heating started. RmTemp=72
Heating started. RmTemp=75
Heating stopped. RmTemp=78

$ java SeasonSwitch off   -> exit code 0
Enter off, cool or heat to run. Press Ctrl-c to exit.
System off. System exits.

$ java SeasonSwitch warm   -> exit code 1
Enter off, cool or heat to run. Press Ctrl-c to exit.
Unknown command. System exits.
```
