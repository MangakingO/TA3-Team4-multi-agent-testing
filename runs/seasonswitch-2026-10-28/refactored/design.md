# Design notes from the refactoring agent

## Design

States: **Off**, **Cool**, **Heat** (classes `OffState`, `CoolState`, `HeatState`, extending the abstract `SeasonState`).
Events: `cool()`, `heat()`, `off()`, the control timer (`actionPerformed` -> `timerOff()`, every 1.2 s) and the room timer (`Room.actionPerformed`, every 0.4 s).

| current state | event | next state | action |
|---|---|---|---|
| Off | cool() | Cool | AC on, start control timer, print "Cooling started. RmTemp=t" |
| Off | heat() | Heat | heater on, start control timer, print "Heating started. RmTemp=t" |
| Off | off(), control timer, room timer | Off | none |
| Cool | cool() | Cool | none |
| Cool | heat() | Heat | AC off, heater on, start control timer, print "Heating started. RmTemp=t" |
| Cool | off() | Off | AC off, stop control timer, print "Cooling stopped. RmTemp=t" |
| Cool | control timer | Cool | t > coolTemp: AC on, print "Cooling started. RmTemp=t"; else AC off, print "Cooling stopped. RmTemp=t" |
| Cool | room timer | Cool | t - 1 if the AC is on, else t + 1 |
| Heat | heat() | Heat | none |
| Heat | cool() | Cool | heater off, AC on, start control timer, print "Cooling started. RmTemp=t" |
| Heat | off() | Off | heater off, stop control timer, print "Heating stopped. RmTemp=t" |
| Heat | control timer | Heat | t < heatTemp: heater on, print "Heating started. RmTemp=t"; else heater off, print "Heating stopped. RmTemp=t" |
| Heat | room timer | Heat | t + 1 if the heater is on, else t - 1 |

`SeasonSwitch` keeps its class name, package and exact public interface; it holds the current `SeasonState` in a private field and delegates. The states choose the next state through the package-private `setState`. Every non-private field and nested class of the original (`ac`, `heater`, `timer`, `room`, `coolTemp`, `heatTemp`, `AC`, `Heater`, `Room`) is kept unchanged; only the int `state` field and its constants were replaced.
