package name.client;

public final class HunterClientState {

    private static boolean active;

    private HunterClientState() {
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
