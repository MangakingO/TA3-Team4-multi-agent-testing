# State pattern checks

Compiles: yes

| Check | Result | Detail |
|---|---|---|
| Subject class keeps the original name | PASS | cse4392.SeasonSwitch |
| Subject keeps exactly the original public interface | PASS | all original public members present |
| An abstract State type (interface or abstract class) | PASS | cse4392.SeasonState (abstract class) |
| One concrete class per state | PASS | CoolState, HeatState, OffState |
| Members the tests use are still there (non-private fields, methods, nested classes) | PASS | all present |
| Subject holds a reference to its current State | PASS | private SeasonState state |
| No instanceof tests on states in the Subject family | PASS | 0 instanceof instruction(s) in SeasonSwitch$AC, SeasonSwitch$Heater, SeasonSwitch$Room, SeasonSwitch |
| No switch on the state left in the Subject family | PASS | switch statements 5 -> 0; conditional branch instructions 13 -> 4 in SeasonSwitch$AC, SeasonSwitch$Heater, SeasonSwitch$Room, SeasonSwitch; all concrete states together 4 |
| Who decides the next state | INFO | the State classes (CoolState, HeatState, OffState) receive the Subject and set its next state |

| Class | Kind | Extends / implements | Branch instructions |
|---|---|---|---|
| cse4392.CoolState | class | cse4392.SeasonState | 2 |
| cse4392.HeatState | class | cse4392.SeasonState | 2 |
| cse4392.OffState | class | cse4392.SeasonState | 0 |
| cse4392.SeasonState | abstract class | - | 0 |
| cse4392.SeasonSwitch$AC | class | - | 0 |
| cse4392.SeasonSwitch$Heater | class | - | 0 |
| cse4392.SeasonSwitch$Room | class | java.awt.event.ActionListener | 0 |
| cse4392.SeasonSwitch | class | java.awt.event.ActionListener | 4 |
