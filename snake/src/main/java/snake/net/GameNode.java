package snake.net;

import me.ippolitov.fit.snakes.SnakesProto;
import snake.core.Dir;
import snake.core.multi.MultiEngine;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class GameNode {

    public enum Mode { IDLE, MASTER, CLIENT }

    public static final class ClientView {
        public volatile boolean inGame = false;
        public volatile String gameName = "";
        public volatile int myId = 0;
        public volatile SnakesProto.NodeRole myRole = SnakesProto.NodeRole.NORMAL;

        public volatile InetSocketAddress masterAddr = null;
        public volatile InetSocketAddress deputyAddr = null;

        public volatile SnakesProto.GameConfig cfg = null;
        public volatile StateCodec.RenderState render = null;

        public volatile String lastError = null;
    }

    private final UdpSockets socks;
    private final DiscoveryRegistry registry;

    private final Thread unicastRxThread;
    private final Thread mcastRxThread;

    private final java.util.concurrent.ScheduledExecutorService sched;

    private volatile boolean running = true;
    private volatile Mode mode = Mode.IDLE;

    public final ClientView view = new ClientView();

    private final MsgSeq seq = new MsgSeq();
    private volatile int myId = 0;

    private volatile ReliableOutbox outbox = null;

    private final Map<InetSocketAddress, Long> lastHeardAt = new ConcurrentHashMap<>();

    private final Map<InetSocketAddress, Integer> joinAddrToId = new ConcurrentHashMap<>();

    private String gameName;
    private SnakesProto.GameConfig masterCfg;
    private MultiEngine eng;
    private final Map<Integer, InetSocketAddress> playerAddr = new ConcurrentHashMap<>();
    private final Map<Integer, SnakesProto.NodeRole> roles = new ConcurrentHashMap<>();
    private int deputyId = 0;

    private final Map<Integer, Dir> pendingSteer = new ConcurrentHashMap<>();

    public GameNode(UdpSockets socks, DiscoveryRegistry registry) {
        this.socks = socks;
        this.registry = registry;

        this.sched = java.util.concurrent.Executors.newScheduledThreadPool(1);

        this.unicastRxThread = new Thread(this::unicastRxLoop, "unicast-rx");
        this.mcastRxThread = new Thread(this::mcastRxLoop, "mcast-rx");
        unicastRxThread.setDaemon(true);
        mcastRxThread.setDaemon(true);

        unicastRxThread.start();
        mcastRxThread.start();

        sched.scheduleAtFixedRate(() -> registry.reapOlderThan(3500), 1500, 1500, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        running = false;
        try { sched.shutdownNow(); } catch (Exception ignored) {}
    }

    public DiscoveryRegistry registry() { return registry; }

    public synchronized void hostNewGame(String gameName, int w, int h, int foodStatic, int stateDelayMs, String myName) {
        this.mode = Mode.MASTER;
        this.gameName = gameName;
        this.masterCfg = SnakesProto.GameConfig.newBuilder()
                .setWidth(w).setHeight(h).setFoodStatic(foodStatic).setStateDelayMs(stateDelayMs)
                .build();

        this.outbox = new ReliableOutbox(socks.unicast, NetConst.resendPeriodMs(stateDelayMs));
        this.eng = new MultiEngine(w, h, foodStatic);

        this.myId = 1;

        eng.upsertPlayer(myId, myName);
        roles.put(myId, SnakesProto.NodeRole.MASTER);

        eng.placeNewSnakeForPlayer(myId);
        eng.st.players.get(myId).viewer = false;

        view.inGame = true;
        view.gameName = gameName;
        view.myId = myId;
        view.myRole = SnakesProto.NodeRole.MASTER;
        view.masterAddr = socks.localUnicast;
        view.deputyAddr = null;
        view.cfg = masterCfg;

        int tick = masterCfg.getStateDelayMs();
        sched.scheduleAtFixedRate(this::masterTickAndBroadcast, tick, tick, java.util.concurrent.TimeUnit.MILLISECONDS);
        sched.scheduleAtFixedRate(this::masterAnnounce, 0, NetConst.ANNOUNCE_PERIOD_MS, java.util.concurrent.TimeUnit.MILLISECONDS);

        int rp = NetConst.resendPeriodMs(stateDelayMs);
        sched.scheduleAtFixedRate(this::masterNetMaintenance, rp, rp, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public synchronized void joinGame(DiscoveryRegistry.GameInfo g, String myName, boolean viewerOnly) {
        this.mode = Mode.CLIENT;
        this.gameName = g.gameName;
        this.masterCfg = g.config;

        int stateDelayMs = masterCfg.getStateDelayMs();
        this.outbox = new ReliableOutbox(socks.unicast, NetConst.resendPeriodMs(stateDelayMs));

        this.myId = 0;
        view.inGame = true;
        view.gameName = g.gameName;
        view.myId = 0;
        view.myRole = viewerOnly ? SnakesProto.NodeRole.VIEWER : SnakesProto.NodeRole.NORMAL;
        view.masterAddr = g.masterAddr;
        view.deputyAddr = null;
        view.cfg = masterCfg;

        SnakesProto.GameMessage.JoinMsg jm = SnakesProto.GameMessage.JoinMsg.newBuilder()
                .setPlayerName(myName)
                .setGameName(g.gameName)
                .setRequestedRole(viewerOnly ? SnakesProto.NodeRole.VIEWER : SnakesProto.NodeRole.NORMAL)
                .setPlayerType(SnakesProto.PlayerType.HUMAN)
                .build();

        SnakesProto.GameMessage msg = SnakesProto.GameMessage.newBuilder()
                .setMsgSeq(seq.next())
                .setJoin(jm)
                .build();

        try {
            outbox.sendNow(g.masterAddr, msg, true);
        } catch (Exception e) {
            view.lastError = "Join send failed: " + e.getMessage();
        }

        int rp = NetConst.resendPeriodMs(stateDelayMs);
        sched.scheduleAtFixedRate(this::clientNetMaintenance, rp, rp, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void sendSteer(Dir d) {
    if (!view.inGame) return;
    if (d == null) return;

    if (mode == Mode.MASTER) {
        pendingSteer.put(myId, d);
        return;
    }

    if (mode != Mode.CLIENT) return;
    if (view.masterAddr == null) return;
    if (myId == 0) return;

    SnakesProto.GameMessage.SteerMsg sm = SnakesProto.GameMessage.SteerMsg.newBuilder()
            .setDirection(StateCodec.toProtoDir(d))
            .build();

    SnakesProto.GameMessage msg = SnakesProto.GameMessage.newBuilder()
            .setMsgSeq(seq.next())
            .setSenderId(myId)
            .setSteer(sm)
            .build();

    try {
        outbox.sendNow(view.masterAddr, msg, true);
    } catch (Exception e) {
        view.lastError = "Steer send failed: " + e.getMessage();
    }
}


    public void leaveToViewer() {
        if (!view.inGame) return;
        if (mode == Mode.MASTER) {
            view.inGame = false;
            mode = Mode.IDLE;
            return;
        }
        if (mode == Mode.CLIENT && view.masterAddr != null && myId != 0) {
            SnakesProto.GameMessage.RoleChangeMsg rc = SnakesProto.GameMessage.RoleChangeMsg.newBuilder()
                    .setSenderRole(SnakesProto.NodeRole.VIEWER)
                    .build();
            SnakesProto.GameMessage msg = SnakesProto.GameMessage.newBuilder()
                    .setMsgSeq(seq.next())
                    .setSenderId(myId)
                    .setReceiverId(0)
                    .setRoleChange(rc)
                    .build();
            try {
                outbox.sendNow(view.masterAddr, msg, true);
            } catch (Exception ignored) {}
        }
        view.inGame = false;
        mode = Mode.IDLE;
    }

    private void unicastRxLoop() {
        byte[] buf = new byte[65535];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socks.unicast.receive(p);

                InetSocketAddress from = new InetSocketAddress(p.getAddress(), p.getPort());
                lastHeardAt.put(from, System.currentTimeMillis());

                SnakesProto.GameMessage gm = Proto.parse(p.getData(), p.getLength());
                onUnicastMessage(from, gm);
            } catch (SocketTimeoutException ignored) {
            } catch (Exception ignored) {
            }
        }
    }

    private void mcastRxLoop() {
        byte[] buf = new byte[65535];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socks.mcastRecv.receive(p);

                InetSocketAddress from = new InetSocketAddress(p.getAddress(), p.getPort());
                SnakesProto.GameMessage gm = Proto.parse(p.getData(), p.getLength());
                onMulticastMessage(from, gm);
            } catch (SocketTimeoutException ignored) {
            } catch (Exception ignored) {
            }
        }
    }

    private void onMulticastMessage(InetSocketAddress from, SnakesProto.GameMessage gm) {
        if (!gm.hasAnnouncement()) return;
        for (SnakesProto.GameAnnouncement a : gm.getAnnouncement().getGamesList()) {
            InetSocketAddress masterAddr = new InetSocketAddress(from.getAddress(), from.getPort());
            registry.upsert(a.getGameName(), masterAddr, a.getConfig(), a.getPlayers(), a.getCanJoin());
        }
    }

    private void onUnicastMessage(InetSocketAddress from, SnakesProto.GameMessage gm) throws Exception {
        if (gm.hasAck()) {
            if (outbox != null) outbox.onAck(from, gm.getMsgSeq());
            if (mode == Mode.CLIENT && myId == 0 && gm.hasReceiverId()) {
                myId = gm.getReceiverId();
                view.myId = myId;
            }
            return;
        }

        boolean needsAck = !(gm.hasAnnouncement() || gm.hasDiscover() || gm.hasAck());
        if (needsAck) {
            int senderId = gm.hasSenderId() ? gm.getSenderId() : 0;
            SnakesProto.GameMessage ack = SnakesProto.GameMessage.newBuilder()
                    .setMsgSeq(gm.getMsgSeq())
                    .setSenderId(myId == 0 ? 0 : myId)
                    .setReceiverId(senderId)
                    .setAck(SnakesProto.GameMessage.AckMsg.newBuilder().build())
                    .build();
            try {
                if (outbox != null) outbox.sendNow(from, ack, false);
                else {
                    byte[] data = Proto.toBytes(ack);
                    socks.unicast.send(new DatagramPacket(data, data.length, from.getAddress(), from.getPort()));
                }
            } catch (Exception ignored) {}
        }

        if (mode == Mode.MASTER) {
            onMasterMessage(from, gm);
        } else if (mode == Mode.CLIENT) {
            onClientMessage(from, gm);
        }
    }

    private void onMasterMessage(InetSocketAddress from, SnakesProto.GameMessage gm) {
        if (gm.hasJoin()) {
            handleJoin(from, gm);
            return;
        }
        if (gm.hasSteer()) {
            int pid = gm.getSenderId();
            Dir d = StateCodec.fromProtoDir(gm.getSteer().getDirection());
            pendingSteer.put(pid, d);
            return;
        }
        if (gm.hasRoleChange()) {
            int pid = gm.getSenderId();
            if (pid != 0 && eng != null) {
                SnakesProto.NodeRole sr = gm.getRoleChange().hasSenderRole() ? gm.getRoleChange().getSenderRole() : null;
                if (sr == SnakesProto.NodeRole.VIEWER) {
                    roles.put(pid, SnakesProto.NodeRole.VIEWER);
                    var snake = eng.st.snakes.get(pid);
                    if (snake != null) snake.zombie = true;
                }
            }
        }
        if (gm.hasPing()) return;
        if (gm.hasDiscover()) {
            try {
                SnakesProto.GameMessage ann = buildAnnouncement();
                byte[] data = Proto.toBytes(ann);
                socks.unicast.send(new DatagramPacket(data, data.length, from.getAddress(), from.getPort()));
            } catch (Exception ignored) {}
        }
    }

    private void onClientMessage(InetSocketAddress from, SnakesProto.GameMessage gm) {
        if (gm.hasState()) {
            SnakesProto.GameState st = gm.getState().getState();
            if (view.render != null && st.getStateOrder() <= view.render.stateOrder) return;

            int w = view.cfg.getWidth();
            int h = view.cfg.getHeight();
            view.render = StateCodec.decodeToRender(st, w, h);

            InetSocketAddress deputy = null;
            InetSocketAddress master = null;
            for (SnakesProto.GamePlayer p : st.getPlayers().getPlayersList()) {
                if (p.getRole() == SnakesProto.NodeRole.MASTER && p.hasIpAddress() && p.hasPort()) {
                    master = new InetSocketAddress(p.getIpAddress(), p.getPort());
                }
                if (p.getRole() == SnakesProto.NodeRole.DEPUTY && p.hasIpAddress() && p.hasPort()) {
                    deputy = new InetSocketAddress(p.getIpAddress(), p.getPort());
                }
            }
            if (master != null) view.masterAddr = master;
            view.deputyAddr = deputy;
            return;
        }
        if (gm.hasError()) {
            view.lastError = gm.getError().getErrorMessage();
            return;
        }
        if (gm.hasRoleChange()) {
            SnakesProto.GameMessage.RoleChangeMsg rc = gm.getRoleChange();
            if (rc.hasReceiverRole() && myId != 0 && gm.hasReceiverId() && gm.getReceiverId() == myId) {
                view.myRole = rc.getReceiverRole();
            }
            if (rc.hasSenderRole()) {
                view.myRole = rc.getSenderRole();
            }
        }
    }

    private void handleJoin(InetSocketAddress from, SnakesProto.GameMessage gm) {
    if (eng == null) return;

    Integer existingId = joinAddrToId.get(from);
    if (existingId != null) {
        try {
            SnakesProto.GameMessage ack = SnakesProto.GameMessage.newBuilder()
                    .setMsgSeq(gm.getMsgSeq())
                    .setSenderId(myId)
                    .setReceiverId(existingId)
                    .setAck(SnakesProto.GameMessage.AckMsg.newBuilder().build())
                    .build();
            outbox.sendNow(from, ack, false);
        } catch (Exception ignored) {}
        return;
    }

    SnakesProto.GameMessage.JoinMsg j = gm.getJoin();

    int newId = 1;
    while (eng.st.players.containsKey(newId) || newId == myId) newId++;

    eng.upsertPlayer(newId, j.getPlayerName());
    playerAddr.put(newId, from);

    boolean viewer = (j.getRequestedRole() == SnakesProto.NodeRole.VIEWER);
    roles.put(newId, viewer ? SnakesProto.NodeRole.VIEWER : SnakesProto.NodeRole.NORMAL);

    if (!viewer) {
        boolean ok = eng.placeNewSnakeForPlayer(newId);
        if (!ok) {
            try {
                SnakesProto.GameMessage err = SnakesProto.GameMessage.newBuilder()
                        .setMsgSeq(seq.next())
                        .setSenderId(myId)
                        .setReceiverId(newId)
                        .setError(SnakesProto.GameMessage.ErrorMsg.newBuilder()
                                .setErrorMessage("No space for new snake (5x5 empty square not found)")
                                .build())
                        .build();
                outbox.sendNow(from, err, true);
            } catch (Exception ignored) {}
            eng.st.players.remove(newId);
            roles.remove(newId);
            playerAddr.remove(newId);
            return;
        }
    }

    joinAddrToId.put(from, newId);

    ensureDeputy();

    try {
        SnakesProto.GameMessage ack = SnakesProto.GameMessage.newBuilder()
                .setMsgSeq(gm.getMsgSeq())
                .setSenderId(myId)
                .setReceiverId(newId)
                .setAck(SnakesProto.GameMessage.AckMsg.newBuilder().build())
                .build();
        outbox.sendNow(from, ack, false);
    } catch (Exception ignored) {}
}


    private void masterTickAndBroadcast() {
        if (mode != Mode.MASTER || eng == null) return;

        for (var e : pendingSteer.entrySet()) {
            int pid = e.getKey();
            if (roles.getOrDefault(pid, SnakesProto.NodeRole.NORMAL) == SnakesProto.NodeRole.VIEWER) continue;
            var snake = eng.st.snakes.get(pid);
            if (snake != null && snake.zombie) continue;
            eng.applySteer(pid, e.getValue());
        }
        pendingSteer.clear();

        eng.tick();

        try {
            SnakesProto.GameState gs = StateCodec.encodeState(eng, roles, playerAddr);
            SnakesProto.GameMessage sm = SnakesProto.GameMessage.newBuilder()
                    .setMsgSeq(seq.next())
                    .setSenderId(myId)
                    .setState(SnakesProto.GameMessage.StateMsg.newBuilder().setState(gs).build())
                    .build();

            for (var e : playerAddr.entrySet()) {
                InetSocketAddress to = e.getValue();
                outbox.sendNow(to, sm, true);
            }

            view.render = StateCodec.decodeToRender(gs, masterCfg.getWidth(), masterCfg.getHeight());
        } catch (Exception ignored) {}
    }

    private void masterAnnounce() {
        if (mode != Mode.MASTER || eng == null) return;
        try {
            SnakesProto.GameMessage ann = buildAnnouncement();
            byte[] data = Proto.toBytes(ann);

            DatagramPacket p = new DatagramPacket(
                    data, data.length, InetAddress.getByName(NetConst.MCAST_ADDR), NetConst.MCAST_PORT
            );
            socks.unicast.send(p);
        } catch (Exception ignored) {}
    }

    private SnakesProto.GameMessage buildAnnouncement() {
        SnakesProto.GamePlayers gp = StateCodec.encodePlayers(eng.st, roles, playerAddr);
        boolean canJoin = true;

        SnakesProto.GameAnnouncement ga = SnakesProto.GameAnnouncement.newBuilder()
                .setPlayers(gp)
                .setConfig(masterCfg)
                .setCanJoin(canJoin)
                .setGameName(gameName)
                .build();

        return SnakesProto.GameMessage.newBuilder()
                .setMsgSeq(seq.next())
                .setAnnouncement(SnakesProto.GameMessage.AnnouncementMsg.newBuilder().addGames(ga).build())
                .build();
    }

    private void masterNetMaintenance() {
        if (mode != Mode.MASTER || outbox == null) return;

        Set<InetSocketAddress> peers = new HashSet<>(playerAddr.values());
        outbox.tickResendAndPing(myId, seq, peers);

        int timeout = NetConst.peerTimeoutMs(masterCfg.getStateDelayMs());
        long now = System.currentTimeMillis();

        List<Integer> toDrop = new ArrayList<>();
        for (var e : playerAddr.entrySet()) {
            int pid = e.getKey();
            InetSocketAddress a = e.getValue();
            long last = lastHeardAt.getOrDefault(a, 0L);
            if (now - last > timeout) {
                toDrop.add(pid);
            }
        }
        for (int pid : toDrop) {
            roles.put(pid, SnakesProto.NodeRole.VIEWER);
            var snake = eng.st.snakes.get(pid);
            if (snake != null) snake.zombie = true;
            playerAddr.remove(pid);

            if (pid == deputyId) {
                deputyId = 0;
                ensureDeputy();
            }
        }
    }

    private void ensureDeputy() {
        if (deputyId != 0) return;

        int pick = 0;
        for (var e : roles.entrySet()) {
            int pid = e.getKey();
            if (pid == myId) continue;
            if (e.getValue() == SnakesProto.NodeRole.NORMAL) { pick = pid; break; }
        }
        if (pick == 0) return;

        deputyId = pick;
        roles.put(deputyId, SnakesProto.NodeRole.DEPUTY);

        InetSocketAddress to = playerAddr.get(deputyId);
        if (to != null) {
            SnakesProto.GameMessage.RoleChangeMsg rc = SnakesProto.GameMessage.RoleChangeMsg.newBuilder()
                    .setReceiverRole(SnakesProto.NodeRole.DEPUTY)
                    .build();
            SnakesProto.GameMessage msg = SnakesProto.GameMessage.newBuilder()
                    .setMsgSeq(seq.next())
                    .setSenderId(myId)
                    .setReceiverId(deputyId)
                    .setRoleChange(rc)
                    .build();
            try { outbox.sendNow(to, msg, true); } catch (Exception ignored) {}
        }
    }

    private void clientNetMaintenance() {
        if (mode != Mode.CLIENT || outbox == null || view.cfg == null) return;

        Set<InetSocketAddress> peers = new HashSet<>();
        if (view.masterAddr != null) peers.add(view.masterAddr);

        if (myId != 0) {
            outbox.tickResendAndPing(myId, seq, peers);
        }

        int timeout = NetConst.peerTimeoutMs(view.cfg.getStateDelayMs());
        long now = System.currentTimeMillis();

        if (view.masterAddr != null) {
            long last = lastHeardAt.getOrDefault(view.masterAddr, 0L);
            if (now - last > timeout) {
                if (view.deputyAddr != null) {
                    view.masterAddr = view.deputyAddr;
                    view.lastError = "MASTER timeout, switching to DEPUTY as master";
                }
            }
        }
    }
}
