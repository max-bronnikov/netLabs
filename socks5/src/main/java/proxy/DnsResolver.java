package proxy;

import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.ResolverConfig;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class DnsResolver {

    private static final int DNS_MAX_PACKET = 512;

    private final DatagramChannel channel;
    private final InetSocketAddress dnsServer;
    private final Map<Integer, PendingQuery> pending = new HashMap<>();
    private int nextId = 1;

    private static class PendingQuery {
        final String hostname;
        final ClientSession session;

        PendingQuery(String hostname, ClientSession session) {
            this.hostname = hostname;
            this.session = session;
        }
    }

    DnsResolver(DatagramChannel channel) {
        this.channel = channel;

        ResolverConfig cfg = ResolverConfig.getCurrentConfig();
        InetSocketAddress serverAddr = null;

        if (cfg != null) {
            List<InetSocketAddress> servers = cfg.servers();
            if (servers != null && !servers.isEmpty()) {
                serverAddr = servers.getFirst();
            }
        }

        if (serverAddr == null) {
            // dns google
            serverAddr = new InetSocketAddress("8.8.8.8", 53);
        }

        this.dnsServer = serverAddr;
    }

    void resolve(String hostname, ClientSession session) throws IOException {
        int id = nextId & 0xFFFF;
        nextId = (nextId + 1) & 0xFFFF;

        Name name = Name.fromString(hostname.endsWith(".") ? hostname : hostname + ".");
        org.xbill.DNS.Record q =
                org.xbill.DNS.Record.newRecord(name, Type.A, DClass.IN);
        Message query = Message.newQuery(q);
        query.getHeader().setID(id);

        byte[] wire = query.toWire();
        ByteBuffer buf = ByteBuffer.wrap(wire);
        pending.put(id, new PendingQuery(hostname, session));
        channel.send(buf, dnsServer);
    }

    void handleRead() {
        ByteBuffer buf = ByteBuffer.allocate(DNS_MAX_PACKET);
        try {
            while (true) {
                buf.clear();
                var from = channel.receive(buf);
                if (from == null) {
                    break;
                }
                buf.flip();
                byte[] data = new byte[buf.remaining()];
                buf.get(data);

                Message resp = new Message(data);
                int id = resp.getHeader().getID();
                PendingQuery pq = pending.remove(id);
                if (pq == null) {
                    continue;
                }

                InetAddress addr = null;
                org.xbill.DNS.Record[] answers = resp.getSectionArray(Section.ANSWER);

                for (org.xbill.DNS.Record r : answers) {
                    if (r instanceof ARecord a) {
                        addr = a.getAddress();
                        break;
                    }
                }

                if (addr != null) {
                    pq.session.onDnsResolved(addr);
                } else {
                    pq.session.onDnsFailed();
                }
            }
        } catch (IOException | RuntimeException e) {
            for (PendingQuery pq : pending.values()) {
                pq.session.onDnsFailed();
            }
            pending.clear();
        }
    }
}
