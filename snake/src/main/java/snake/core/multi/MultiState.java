package snake.core.multi;

import snake.core.Dir;
import snake.core.Pos;

import java.util.*;

public final class MultiState {
    public final int w, h;
    public long stateOrder = 0;

    public final Map<Integer, Snake> snakes = new HashMap<>(); // playerId -> snake
    public final Set<Pos> food = new HashSet<>();

    public final Map<Integer, Player> players = new HashMap<>(); // playerId -> player

    public MultiState(int w, int h) { this.w = w; this.h = h; }

    public Pos wrap(Pos p) {
        int x = p.x() % w; if (x < 0) x += w;
        int y = p.y() % h; if (y < 0) y += h;
        return new Pos(x, y);
    }

    public Pos step(Pos from, Dir d) {
        return wrap(new Pos(from.x() + d.dx, from.y() + d.dy));
    }

    public static final class Player {
        public final int id;
        public String name;
        public int score = 0;
        public boolean viewer = false;
        public boolean alive = true;
        public Player(int id, String name) { this.id = id; this.name = name; }
    }

    public static final class Snake {
        public final int playerId;
        public final Deque<Pos> body = new ArrayDeque<>();
        public boolean zombie = false;
        public Dir dir;
        public Dir pendingDir;

        public Snake(int playerId, Dir dir) {
            this.playerId = playerId;
            this.dir = dir;
            this.pendingDir = dir;
        }
    }
}
