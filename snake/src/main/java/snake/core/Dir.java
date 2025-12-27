package snake.core;

public enum Dir {
    UP(0, -1), DOWN(0, 1), LEFT(-1, 0), RIGHT(1, 0);

    public final int dx, dy;
    Dir(int dx, int dy) { this.dx = dx; this.dy = dy; }

    public boolean opposite(Dir o) { return dx + o.dx == 0 && dy + o.dy == 0; }
}
