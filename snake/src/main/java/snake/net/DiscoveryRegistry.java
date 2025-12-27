package snake.net;

import me.ippolitov.fit.snakes.SnakesProto;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class DiscoveryRegistry {
    public static final class GameInfo {
        public final String gameName;
        public final InetSocketAddress masterAddr;
        public final SnakesProto.GameConfig config;
        public final SnakesProto.GamePlayers players;
        public final boolean canJoin;
        public volatile long lastSeenAt;

        public GameInfo(String gameName, InetSocketAddress masterAddr,
                        SnakesProto.GameConfig config, SnakesProto.GamePlayers players, boolean canJoin, long lastSeenAt) {
            this.gameName = gameName;
            this.masterAddr = masterAddr;
            this.config = config;
            this.players = players;
            this.canJoin = canJoin;
            this.lastSeenAt = lastSeenAt;
        }
    }

    private final Map<String, GameInfo> games = new ConcurrentHashMap<>();

    public void upsert(String gameName, InetSocketAddress masterAddr,
                       SnakesProto.GameConfig config, SnakesProto.GamePlayers players, boolean canJoin) {
        long now = System.currentTimeMillis();
        games.put(gameName, new GameInfo(gameName, masterAddr, config, players, canJoin, now));
    }

    public List<GameInfo> snapshot() {
        ArrayList<GameInfo> list = new ArrayList<>(games.values());
        list.sort(Comparator.comparing(a -> a.gameName));
        return list;
    }

    public void reapOlderThan(long ms) {
        long now = System.currentTimeMillis();
        games.values().removeIf(g -> now - g.lastSeenAt > ms);
    }
}
