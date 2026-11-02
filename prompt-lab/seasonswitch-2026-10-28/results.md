# Prompt lab seasonswitch-2026-10-28

Refactoring prompt versions scored on the tested original code of run `seasonswitch-2026-10-28`.

| Version | Files | Compiles | State pattern checks | Tests compile unchanged | Tests pass | Branch coverage |
|---|---|---|---|---|---|---|
| P0 | 5 | yes | 6/8 | 10/51 | 10/10 | 14/16 (88%) |
| P1 | 5 | yes | 7/8 | 44/51 | 44/44 | 16/16 (100%) |
| P2 | 5 | yes | 8/8 | 44/51 | 44/44 | 16/16 (100%) |
| P3 | 5 | yes | 8/8 | 44/51 | 44/44 | 16/16 (100%) |

## Problems found per version

- **P0**: pattern: Subject keeps exactly the original public interface (all original public members present; added: public cse4392.SeasonState getState(); public cse4392.SeasonSwitch$AC getAc(); public cse4392.SeasonSwitch$Heater getHeater(); public cse4392.SeasonSwitch$Room getRoom(); public int getCoolTemp(); public int getHeatTemp(); public javax.swing.Timer getTimer(); public void setState(cse4392.SeasonState)); pattern: Members the tests use are still there (non-private fields, methods, nested classes) (missing: AC: boolean isOn; Heater: boolean isOn; Room: javax.swing.Timer timer; Room: public int temp; SeasonSwitch: AC ac; SeasonSwitch: Heater heater; SeasonSwitch: Room room; SeasonSwitch: int coolTemp; SeasonSwitch: int heatTemp; SeasonSwitch: javax.swing.Timer timer); 41 test(s) no longer compile: EP-01, EP-02, EP-03, EP-04, EP-05, EP-06, EP-07, EP-08, EP-09, EP-10, EP-11, EP-12, EP-13, EP-14, EP-15, EP-16, EP-17, EP-18, EP-19, EP-20, BVA-01, BVA-02, BVA-03, BVA-04, BVA-05, BVA-06, BVA-07, BVA-08, BVA-09, BVA-10, BVA-11, BVA-12, BVA-13, BVA-14, EP-21, EP-22, EP-23, EP-24, EP-25, EP-26, REVIEW-01
- **P1**: pattern: Subject keeps exactly the original public interface (all original public members present; added: public void setState(cse4392.SeasonState)); 7 test(s) no longer compile: BVA-13, BVA-14, EP-05, EP-09, EP-13, EP-19, EP-26
- **P2**: 7 test(s) no longer compile: BVA-13, BVA-14, EP-05, EP-09, EP-13, EP-19, EP-26
- **P3**: 7 test(s) no longer compile: BVA-13, BVA-14, EP-05, EP-09, EP-13, EP-19, EP-26
