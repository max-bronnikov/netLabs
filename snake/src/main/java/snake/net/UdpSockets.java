package snake.net;

import java.io.Closeable;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;

public final class UdpSockets implements Closeable {
    public final MulticastSocket mcastRecv;
    public final DatagramSocket unicast;

    public final InetSocketAddress localUnicast;

    public UdpSockets() throws Exception {
        // recv multicast
        mcastRecv = new MulticastSocket(NetConst.MCAST_PORT);
        mcastRecv.setReuseAddress(true);
        mcastRecv.joinGroup(NetConst.multicastGroup());
        mcastRecv.setSoTimeout(200);

        unicast = new DatagramSocket(0);
        unicast.setSoTimeout(200);

        localUnicast = new InetSocketAddress(InetAddress.getLocalHost().getHostAddress(), unicast.getLocalPort());
    }

    @Override public void close() {
        try { mcastRecv.leaveGroup(NetConst.multicastGroup()); } catch (Exception ignored) {}
        try { mcastRecv.close(); } catch (Exception ignored) {}
        try { unicast.close(); } catch (Exception ignored) {}
    }
}
