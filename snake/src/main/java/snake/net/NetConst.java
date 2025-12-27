package snake.net;

import java.net.InetAddress;

public final class NetConst {
    private NetConst() {}

    public static final String MCAST_ADDR = "239.192.0.4";
    public static final int MCAST_PORT = 9192;

    public static final int ANNOUNCE_PERIOD_MS = 1000;

    public static int resendPeriodMs(int stateDelayMs) { return Math.max(10, stateDelayMs / 10); }
    public static int peerTimeoutMs(int stateDelayMs) { return (int)Math.max(50, Math.floor(stateDelayMs * 0.8)); }

    public static InetAddress multicastGroup() {
        try {
            return InetAddress.getByName(MCAST_ADDR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
