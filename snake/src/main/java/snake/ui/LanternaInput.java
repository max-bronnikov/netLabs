package snake.ui;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import snake.core.Dir;

public final class LanternaInput {
    public enum Action { NONE, QUIT, RESTART, PAUSE_TOGGLE }

    public record Input(Action action, Dir dir) {}

    public Input map(KeyStroke k) {
        if (k == null) return new Input(Action.NONE, null);

        if (k.getKeyType() == KeyType.EOF || k.getKeyType() == KeyType.Escape)
            return new Input(Action.QUIT, null);

        if (k.getKeyType() == KeyType.Character) {
            char c = k.getCharacter();
            if (c == 'q' || c == 'Q') return new Input(Action.QUIT, null);
            if (c == 'r' || c == 'R') return new Input(Action.RESTART, null);
            if (c == 'p' || c == 'P' || c == ' ') return new Input(Action.PAUSE_TOGGLE, null);
        }

        Dir d = switch (k.getKeyType()) {
            case ArrowUp -> Dir.UP;
            case ArrowDown -> Dir.DOWN;
            case ArrowLeft -> Dir.LEFT;
            case ArrowRight -> Dir.RIGHT;
            default -> null;
        };
        return new Input(Action.NONE, d);
    }
}
