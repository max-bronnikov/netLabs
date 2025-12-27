package snake.core;

import java.util.Random;

// осталось от одиночной версии
public final class GameEngine {
    public final GameConfig cfg;
    public final Random rnd = new Random();
    public GameState state;

    public GameEngine(GameConfig cfg) { this.cfg = cfg; reset(); }

    public void reset() {
        state = new GameState(cfg.w(), cfg.h());
        Pos head = new Pos(cfg.w() / 2, cfg.h() / 2);
        Pos tail = state.step(head, Dir.LEFT);
        state.snake.addFirst(head);
        state.snake.addLast(tail);
        ensureFood();
    }

    public void applySteer(Dir d) {
        if (!state.alive) return;
        if (!d.opposite(state.dir)) state.pendingDir = d; // ignore reverse
    }

    public void tick() {
        if (!state.alive) { state.stateOrder++; return; }

        state.dir = state.pendingDir;
        Pos next = state.step(state.snake.peekFirst(), state.dir);

        boolean ate = state.food.remove(next);
        state.snake.addFirst(next);
        if (!ate) state.snake.removeLast();
        else state.score++;

        int i = 0;
        for (Pos p : state.snake) {
            if (i++ > 0 && p.equals(next)) { state.alive = false; break; }
        }

        state.stateOrder++;
        if (ate || state.food.size() < desiredFoodCount()) ensureFood();
    }

    int desiredFoodCount() {
        int aliveSnakes = state.alive ? 1 : 0;
        int need = cfg.foodStatic() + aliveSnakes;
        int free = cfg.w() * cfg.h() - state.snake.size();
        return Math.min(need, Math.max(0, free));
    }

    void ensureFood() {
        int target = desiredFoodCount();
        while (state.food.size() < target) {
            Pos p = new Pos(rnd.nextInt(cfg.w()), rnd.nextInt(cfg.h()));
            if (state.food.contains(p) || state.snake.contains(p)) continue;
            state.food.add(p);
        }
    }
}
