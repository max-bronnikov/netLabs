package snake.net;

import me.ippolitov.fit.snakes.SnakesProto;
import snake.core.Dir;
import snake.core.Pos;
import snake.core.multi.MultiEngine;
import snake.core.multi.MultiState;

import java.net.InetSocketAddress;
import java.util.*;

public final class StateCodec {
    private StateCodec() {}

    public static SnakesProto.Direction toProtoDir(Dir d) {
        return switch (d) {
            case UP -> SnakesProto.Direction.UP;
            case DOWN -> SnakesProto.Direction.DOWN;
            case LEFT -> SnakesProto.Direction.LEFT;
            case RIGHT -> SnakesProto.Direction.RIGHT;
        };
    }

    public static Dir fromProtoDir(SnakesProto.Direction d) {
        return switch (d) {
            case UP -> Dir.UP;
            case DOWN -> Dir.DOWN;
            case LEFT -> Dir.LEFT;
            case RIGHT -> Dir.RIGHT;
            default -> Dir.RIGHT;
        };
    }

    public static SnakesProto.GameState.Coord coord(Pos p) {
        return SnakesProto.GameState.Coord.newBuilder().setX(p.x()).setY(p.y()).build();
    }

    public static SnakesProto.GameState.Snake encodeSnake(MultiState.Snake s, boolean zombie) {
        List<Pos> cells = new ArrayList<>(s.body);
        if (cells.isEmpty()) {
            return SnakesProto.GameState.Snake.newBuilder()
                    .setPlayerId(s.playerId)
                    .addPoints(SnakesProto.GameState.Coord.newBuilder().setX(0).setY(0).build())
                    .setState(zombie ? SnakesProto.GameState.Snake.SnakeState.ZOMBIE : SnakesProto.GameState.Snake.SnakeState.ALIVE)
                    .setHeadDirection(toProtoDir(s.dir))
                    .build();
        }

        List<SnakesProto.GameState.Coord> points = new ArrayList<>();
        Pos head = cells.get(0);
        points.add(SnakesProto.GameState.Coord.newBuilder().setX(head.x()).setY(head.y()).build());

        int dxPrev = 0, dyPrev = 0;
        int segDx = 0, segDy = 0;

        for (int i = 1; i < cells.size(); i++) {
            Pos a = cells.get(i - 1);
            Pos b = cells.get(i);
            int dx = b.x() - a.x();
            int dy = b.y() - a.y();

            dx = Integer.compare(dx, 0);
            dy = Integer.compare(dy, 0);

            if (i == 1) {
                dxPrev = dx; dyPrev = dy;
                segDx = dx; segDy = dy;
            } else {
                if (dx == dxPrev && dy == dyPrev) {
                    segDx += dx;
                    segDy += dy;
                } else {
                    points.add(SnakesProto.GameState.Coord.newBuilder().setX(segDx).setY(segDy).build());
                    dxPrev = dx; dyPrev = dy;
                    segDx = dx; segDy = dy;
                }
            }
        }
        points.add(SnakesProto.GameState.Coord.newBuilder().setX(segDx).setY(segDy).build());

        return SnakesProto.GameState.Snake.newBuilder()
                .setPlayerId(s.playerId)
                .addAllPoints(points)
                .setState(zombie ? SnakesProto.GameState.Snake.SnakeState.ZOMBIE : SnakesProto.GameState.Snake.SnakeState.ALIVE)
                .setHeadDirection(toProtoDir(s.dir))
                .build();
    }

    public static SnakesProto.GamePlayers encodePlayers(MultiState st, Map<Integer, SnakesProto.NodeRole> roles,
                                                        Map<Integer, InetSocketAddress> addrs) {
        SnakesProto.GamePlayers.Builder gp = SnakesProto.GamePlayers.newBuilder();
        for (MultiState.Player p : st.players.values()) {
            SnakesProto.GamePlayer.Builder b = SnakesProto.GamePlayer.newBuilder()
                    .setName(p.name)
                    .setId(p.id)
                    .setRole(roles.getOrDefault(p.id, SnakesProto.NodeRole.NORMAL))
                    .setScore(p.score);

            InetSocketAddress a = addrs.get(p.id);
            if (a != null) {
                b.setIpAddress(a.getAddress().getHostAddress());
                b.setPort(a.getPort());
            }
            gp.addPlayers(b.build());
        }
        return gp.build();
    }

    public static SnakesProto.GameState encodeState(MultiEngine eng,
                                                    Map<Integer, SnakesProto.NodeRole> roles,
                                                    Map<Integer, InetSocketAddress> addrs) {
        MultiState st = eng.st;
        SnakesProto.GameState.Builder gs = SnakesProto.GameState.newBuilder();
        gs.setStateOrder((int)Math.min(Integer.MAX_VALUE, st.stateOrder));

        for (var e : st.snakes.entrySet()) {
            int pid = e.getKey();
            boolean zombie = e.getValue().zombie;
            gs.addSnakes(encodeSnake(e.getValue(), zombie));
        }

        for (Pos f : st.food) gs.addFoods(coord(f));

        gs.setPlayers(encodePlayers(st, roles, addrs));
        return gs.build();
    }

    public static RenderState decodeToRender(SnakesProto.GameState gs, int w, int h) {
        RenderState rs = new RenderState(w, h);
        rs.stateOrder = gs.getStateOrder();

        for (SnakesProto.GameState.Coord c : gs.getFoodsList()) {
            rs.food.add(new Pos(c.getX(), c.getY()));
        }

        for (SnakesProto.GamePlayer p : gs.getPlayers().getPlayersList()) {
            rs.players.put(p.getId(), new RenderState.PlayerInfo(p.getName(), p.getScore(), p.getRole()));
        }

        for (SnakesProto.GameState.Snake s : gs.getSnakesList()) {
            List<Pos> cells = expandSnake(s, w, h);
            rs.snakes.put(s.getPlayerId(), new RenderState.SnakeCells(cells, s.getHeadDirection(), s.getState()));
        }

        return rs;
    }

    private static List<Pos> expandSnake(SnakesProto.GameState.Snake s, int w, int h) {
        List<SnakesProto.GameState.Coord> pts = s.getPointsList();
        if (pts.isEmpty()) return List.of();

        int x = pts.get(0).getX();
        int y = pts.get(0).getY();
        List<Pos> out = new ArrayList<>();
        out.add(new Pos(mod(x, w), mod(y, h)));

        for (int i = 1; i < pts.size(); i++) {
            int dx = pts.get(i).getX();
            int dy = pts.get(i).getY();

            int steps = Math.abs(dx) + Math.abs(dy);
            int sx = Integer.compare(dx, 0);
            int sy = Integer.compare(dy, 0);
            for (int k = 0; k < steps; k++) {
                x += sx;
                y += sy;
                out.add(new Pos(mod(x, w), mod(y, h)));
            }
        }
        return out;
    }

    private static int mod(int a, int m) {
        int r = a % m;
        return r < 0 ? r + m : r;
    }

    public static final class RenderState {
        public final int w, h;
        public int stateOrder;

        public final Map<Integer, PlayerInfo> players = new HashMap<>();
        public final Map<Integer, SnakeCells> snakes = new HashMap<>();
        public final Set<Pos> food = new HashSet<>();

        public RenderState(int w, int h) { this.w = w; this.h = h; }

        public record PlayerInfo(String name, int score, SnakesProto.NodeRole role) {}
        public record SnakeCells(List<Pos> cells,
                                 SnakesProto.Direction headDir,
                                 SnakesProto.GameState.Snake.SnakeState snakeState) {}
    }
}
