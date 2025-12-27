package snake.net;

import me.ippolitov.fit.snakes.SnakesProto;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ReliableOutbox {
    private static final class Pending {
        final InetSocketAddress to;
        final SnakesProto.GameMessage msg;
        volatile long lastSendAt;
        Pending(InetSocketAddress to, SnakesProto.GameMessage msg, long lastSendAt) {
            this.to = to; this.msg = msg; this.lastSendAt = lastSendAt;
        }
    }

    private final DatagramSocket sock;
    private final int resendPeriodMs;

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private final Map<InetSocketAddress, Long> lastAnyUnicastSentAt = new ConcurrentHashMap<>();

    public ReliableOutbox(DatagramSocket sock, int resendPeriodMs) {
        this.sock = sock;
        this.resendPeriodMs = resendPeriodMs;
    }

    private static String key(InetSocketAddress to, long seq) {
        return to.toString() + "|" + seq;
    }

    public void sendNow(InetSocketAddress to, SnakesProto.GameMessage msg, boolean needsAck) throws Exception {
        byte[] data = Proto.toBytes(msg);
        DatagramPacket p = new DatagramPacket(data, data.length, to.getAddress(), to.getPort());
        sock.send(p);
        long now = System.currentTimeMillis();
        lastAnyUnicastSentAt.put(to, now);
        if (needsAck) {
            pending.put(key(to, msg.getMsgSeq()), new Pending(to, msg, now));
        }
    }

    public void onAck(InetSocketAddress from, long msgSeq) {
        pending.remove(key(from, msgSeq));
    }

    public void tickResendAndPing(int myId, MsgSeq seq, Set<InetSocketAddress> peers) {
        long now = System.currentTimeMillis();

        for (Pending p : pending.values()) {
            if (now - p.lastSendAt >= resendPeriodMs) {
                try {
                    byte[] data = Proto.toBytes(p.msg);
                    DatagramPacket dp = new DatagramPacket(data, data.length, p.to.getAddress(), p.to.getPort());
                    sock.send(dp);
                    p.lastSendAt = now;
                    lastAnyUnicastSentAt.put(p.to, now);
                } catch (Exception ignored) {}
            }
        }

        for (InetSocketAddress peer : peers) {
            long last = lastAnyUnicastSentAt.getOrDefault(peer, 0L);
            if (now - last >= resendPeriodMs) {
                try {
                    SnakesProto.GameMessage ping = SnakesProto.GameMessage.newBuilder()
                            .setMsgSeq(seq.next())
                            .setSenderId(myId)
                            .setPing(SnakesProto.GameMessage.PingMsg.newBuilder().build())
                            .build();
                    sendNow(peer, ping, true);
                } catch (Exception ignored) {}
            }
        }
    }
}
