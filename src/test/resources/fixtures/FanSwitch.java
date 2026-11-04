/**
 * Stand-in class used only to test the agent system (it is not SeasonSwitch).
 *
 * A ceiling fan with a pull chain. Each pull moves the fan to the next setting:
 * OFF -> LOW -> HIGH -> OFF. setSpeed(int) selects a setting directly:
 * 0 = OFF, 1 = LOW, 2 = HIGH; any other value is rejected with IllegalArgumentException.
 *
 * It contains one deliberate fault: pulling the chain at HIGH goes to LOW instead of OFF.
 */
public class FanSwitch {
    private int speed = 0; // 0 = OFF, 1 = LOW, 2 = HIGH

    public void pull() {
        if (speed == 2) {
            speed = 1;
        } else {
            speed = speed + 1;
        }
    }

    public void setSpeed(int s) {
        if (s < 0 || s > 2) {
            throw new IllegalArgumentException("speed must be 0, 1 or 2: " + s);
        }
        speed = s;
    }

    public String getSetting() {
        if (speed == 0) {
            return "OFF";
        }
        if (speed == 1) {
            return "LOW";
        }
        return "HIGH";
    }

    public static void main(String[] args) {
        FanSwitch fan = new FanSwitch();
        for (int i = 0; i < 4; i++) {
            System.out.println(fan.getSetting());
            fan.pull();
        }
    }
}
