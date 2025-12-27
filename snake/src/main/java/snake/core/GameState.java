package snake.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

public final class GameState {
    public final int w, h;
    public final Deque<Pos> snake = new ArrayDeque<>();
    public final Set<Pos> food = new HashSet<>();

    public Dir dir = Dir.RIGHT;
    public Dir pendingDir = dir;

    public boolean alive = true;
    public int score = 0;
    public long stateOrder = 0;

    public GameState(int w, int h) { this.w = w; this.h = h; }

    public Pos wrap(Pos p) {
        int x = p.x() % w; if (x < 0) x += w;
        int y = p.y() % h; if (y < 0) y += h;
        return new Pos(x, y);
    }

    public Pos step(Pos from, Dir d) { return wrap(new Pos(from.x() + d.dx, from.y() + d.dy)); }
}
