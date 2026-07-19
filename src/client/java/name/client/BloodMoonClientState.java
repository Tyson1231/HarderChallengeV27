package name.client;

public final class BloodMoonClientState {

    private static boolean active;

    private BloodMoonClientState() {
    }

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean value) {
        active = value;
    }

    public static void reset() {
        active = false;
    }
}
