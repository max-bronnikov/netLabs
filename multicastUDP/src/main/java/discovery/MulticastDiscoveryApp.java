package discovery;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class MulticastDiscoveryApp {

    private static final int PORT = 50000;

    private static final long HEARTBEAT_INTERVAL_MS = 1000;

    private static final long INSTANCE_TIMEOUT_MS = 5000;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: java -jar <гредл кладет жар в build/libs> <address>");
            System.exit(1);
        }

        String groupStr = args[0];
        InetAddress group = InetAddress.getByName(groupStr);
        if (!group.isMulticastAddress()) {
            System.err.println("Not multicast addr " + groupStr);
            System.exit(1);
        }

        StandardProtocolFamily family;
        if (group instanceof Inet4Address) {
            family = StandardProtocolFamily.INET;
        } else if (group instanceof Inet6Address) {
            family = StandardProtocolFamily.INET6;
        } else {
            System.exit(1);
            return;
        }

        NetworkInterface iface = findMulticastInterface(group);
        if (iface == null) {
            System.err.println("Not found interface " + groupStr);
            System.exit(1);
        }

        DatagramChannel channel = DatagramChannel.open(family);
        channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);

        channel.bind(new InetSocketAddress(PORT));
        channel.configureBlocking(false);
        channel.join(group, iface);

        Selector selector = Selector.open();
        channel.register(selector, SelectionKey.OP_READ);

        String selfId = UUID.randomUUID().toString();
        InstanceTracker tracker = new InstanceTracker(selfId);
        Set<String> lastPrinted = new LinkedHashSet<>();

        System.out.println("Multicast discovery started.");
        System.out.println("Group: " + groupStr + ":" + PORT + " via interface " + iface.getName());
        System.out.println("Instance ID: " + selfId);
        System.out.println("Waiting for other instances");

        ByteBuffer recvBuf = ByteBuffer.allocate(1024);
        long lastHeartbeat = 0;

        while (true) {
            long selectTimeout = HEARTBEAT_INTERVAL_MS;
            selector.select(selectTimeout);

            boolean changed = false;

            var iter = selector.selectedKeys().iterator();
            while (iter.hasNext()) {
                SelectionKey key = iter.next();
                iter.remove();
                if (!key.isValid()) {
                    continue;
                }
                if (key.isReadable()) {
                    changed |= handleReceive(channel, recvBuf, tracker);
                }
            }

            long now = System.currentTimeMillis();

            if (now - lastHeartbeat >= HEARTBEAT_INTERVAL_MS) {
                sendHeartbeat(channel, group, selfId);
                lastHeartbeat = now;
            }

            changed |= tracker.removeExpired(now, INSTANCE_TIMEOUT_MS);

            if (changed) {
                Set<String> addresses = tracker.currentAddresses();
                if (!addresses.equals(lastPrinted)) {
                    printAlive(addresses);
                    lastPrinted = new LinkedHashSet<>(addresses);
                }
            }
        }
    }

    private static NetworkInterface findMulticastInterface(InetAddress group) throws SocketException {
        boolean wantV4 = group instanceof Inet4Address;

        Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
        while (en.hasMoreElements()) {
            NetworkInterface ni = en.nextElement();
            if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) {
                continue;
            }
            for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                InetAddress addr = ia.getAddress();
                if (addr == null) {
                    continue;
                }
                if (wantV4 && addr instanceof Inet4Address) {
                    return ni;
                }
                if (!wantV4 && addr instanceof Inet6Address) {
                    return ni;
                }
            }
        }
        return null;
    }

    private static boolean handleReceive(
            DatagramChannel channel,
            ByteBuffer buf,
            InstanceTracker tracker
    ) throws IOException {
        boolean changed = false;
        while (true) {
            buf.clear();
            SocketAddress sa = channel.receive(buf);
            if (sa == null) {
                break;
            }
            if (!(sa instanceof InetSocketAddress addr)) {
                continue;
            }
            buf.flip();
            if (!buf.hasRemaining()) {
                continue;
            }

            String msg = StandardCharsets.UTF_8.decode(buf).toString().trim();
            if (msg.isEmpty()) {
                continue;
            }

            long now = System.currentTimeMillis();
            changed |= tracker.updateInstance(msg, addr, now);
        }
        return changed;
    }

    private static void sendHeartbeat(
            DatagramChannel channel,
            InetAddress group,
            String selfId
    ) throws IOException {
        byte[] data = selfId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.wrap(data);
        channel.send(buf, new InetSocketAddress(group, PORT));
    }

    private static void printAlive(Set<String> addresses) {
        System.out.println("Alive instances:");
        if (addresses.isEmpty()) {
            System.out.println("  (none)");
        } else {
            for (String ip : addresses) {
                System.out.println("  " + ip);
            }
        }
        System.out.println();
    }
}
