package snake.core.multi;

import snake.core.Dir;
import snake.core.Pos;

import java.util.*;

public final class MultiEngine {
    public final int w, h;
    public final int foodStatic;
    public final Random rnd = new Random();

    public final MultiState st;

    private final Map<Integer, Dir> steerBuf = new HashMap<>();

    public MultiEngine(int w, int h, int foodStatic) {
        this.w = w; this.h = h; this.foodStatic = foodStatic;
        this.st = new MultiState(w, h);
    }

    public void upsertPlayer(int id, String name) {
        st.players.putIfAbsent(id, new MultiState.Player(id, name));
    }

    public void setViewer(int id, boolean viewer) {
        MultiState.Player p = st.players.get(id);
        if (p != null) p.viewer = viewer;
    }

    public void applySteer(int playerId, Dir d) {
        MultiState.Snake s = st.snakes.get(playerId);
        if (s == null) return;
        if (st.players.get(playerId) != null && st.players.get(playerId).viewer) return;
        if (!d.opposite(s.dir)) steerBuf.put(playerId, d);
    }

    public boolean placeNewSnakeForPlayer(int playerId) {
        final int tries = w * h;
        for (int t = 0; t < tries; t++) {
            int cx = rnd.nextInt(w);
            int cy = rnd.nextInt(h);

            if (!squareEmptyNoFood(cx, cy, 5)) continue;

            Pos head = new Pos(cx, cy);

            List<Dir> dirs = new ArrayList<>(List.of(Dir.UP, Dir.DOWN, Dir.LEFT, Dir.RIGHT));
            Collections.shuffle(dirs, rnd);

            for (Dir tailDir : dirs) {
                Pos tail = st.wrap(new Pos(head.x() - tailDir.dx, head.y() - tailDir.dy));
                if (occupied(tail) || st.food.contains(tail) || st.food.contains(head)) continue;

                MultiState.Snake s = new MultiState.Snake(playerId, tailDir);
                s.dir = opposite(tailDir);
                s.pendingDir = s.dir;
                s.body.addFirst(head);
                s.body.addLast(tail);
                st.snakes.put(playerId, s);
                return true;
            }
        }
        return false;
    }

    private Dir opposite(Dir d) {
        return switch (d) {
            case UP -> Dir.DOWN;
            case DOWN -> Dir.UP;
            case LEFT -> Dir.RIGHT;
            case RIGHT -> Dir.LEFT;
        };
    }

    private boolean squareEmptyNoFood(int cx, int cy, int size) {
        int r = size / 2;
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                Pos p = st.wrap(new Pos(cx + dx, cy + dy));
                if (occupied(p)) return false;
                if (st.food.contains(p)) return false;
            }
        }
        return true;
    }

    private boolean occupied(Pos p) {
        for (MultiState.Snake s : st.snakes.values()) {
            if (s.body.contains(p)) return true;
        }
        return false;
    }

    private int aliveSnakeCount() {
        int c = 0;
        for (var e : st.snakes.entrySet()) {
            int pid = e.getKey();
            MultiState.Player p = st.players.get(pid);
            if (p != null && !p.viewer) c++;
        }
        return c;
    }

    private int desiredFoodCount() {
        int need = foodStatic + aliveSnakeCount();
        int used = 0;
        for (MultiState.Snake s : st.snakes.values()) used += s.body.size();
        int free = w * h - used - st.food.size();
        int maxCan = st.food.size() + Math.max(0, free);
        return Math.min(need, maxCan);
    }

    private void ensureFood() {
        int target = desiredFoodCount();
        int attempts = w * h * 3;
        while (st.food.size() < target && attempts-- > 0) {
            Pos p = new Pos(rnd.nextInt(w), rnd.nextInt(h));
            if (st.food.contains(p)) continue;
            if (occupied(p)) continue;
            st.food.add(p);
        }
    }

    public void tick() {
        for (var e : steerBuf.entrySet()) {
            MultiState.Snake s = st.snakes.get(e.getKey());
            if (s != null) {
                Dir d = e.getValue();
                if (!d.opposite(s.dir)) s.pendingDir = d;
            }
        }
        steerBuf.clear();

        Map<Integer, Pos> nextHead = new HashMap<>();
        for (var e : st.snakes.entrySet()) {
            int pid = e.getKey();
            MultiState.Player p = st.players.get(pid);
            if (p == null || p.viewer) continue;
            MultiState.Snake s = e.getValue();
            s.dir = s.pendingDir;
            Pos head = s.body.peekFirst();
            if (head == null) continue;
            nextHead.put(pid, st.step(head, s.dir));
        }

        Set<Pos> eatenFoodCells = new HashSet<>();
        Set<Integer> ate = new HashSet<>();
        for (var e : nextHead.entrySet()) {
            int pid = e.getKey();
            Pos nh = e.getValue();
            if (st.food.contains(nh)) {
                ate.add(pid);
                eatenFoodCells.add(nh);
            }
        }
        st.food.removeAll(eatenFoodCells);

        for (var e : nextHead.entrySet()) {
            int pid = e.getKey();
            MultiState.Snake s = st.snakes.get(pid);
            if (s == null) continue;
            Pos nh = e.getValue();
            s.body.addFirst(nh);
            if (!ate.contains(pid)) {
                s.body.removeLast();
            } else {
                MultiState.Player p = st.players.get(pid);
                if (p != null) p.score += 1;
            }
        }

        record Occ(int pid, boolean head) {}
        Map<Pos, List<Occ>> occ = new HashMap<>();
        for (var e : st.snakes.entrySet()) {
            int pid = e.getKey();
            MultiState.Player p = st.players.get(pid);
            if (p == null || p.viewer) continue;
            boolean first = true;
            for (Pos cell : e.getValue().body) {
                occ.computeIfAbsent(cell, k -> new ArrayList<>()).add(new Occ(pid, first));
                first = false;
            }
        }

        Set<Integer> dead = new HashSet<>();
        for (var e : nextHead.entrySet()) {
            int pid = e.getKey();
            Pos headCell = e.getValue();
            List<Occ> list = occ.getOrDefault(headCell, List.of());
            if (list.size() <= 1) continue;

            boolean otherPresent = list.stream().anyMatch(o -> o.pid() != pid || !o.head());
            if (otherPresent) dead.add(pid);
        }

        for (int pid : dead) {
            Pos headCell = nextHead.get(pid);
            if (headCell == null) continue;
            List<Occ> list = occ.getOrDefault(headCell, List.of());
            for (Occ o : list) {
                if (o.pid() == pid) continue;
                MultiState.Player victim = st.players.get(o.pid());
                if (victim != null && !victim.viewer) victim.score += 1;
                break;
            }
        }

        for (int pid : dead) {
            MultiState.Snake s = st.snakes.remove(pid);
            if (s != null) {
                for (Pos cell : s.body) {
                    if (rnd.nextBoolean()) st.food.add(cell);
                }
            }
            MultiState.Player p = st.players.get(pid);
            if (p != null) p.viewer = true;
        }

        st.stateOrder++;
        ensureFood();
    }
}
