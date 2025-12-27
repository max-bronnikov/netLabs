package snake.ui;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import me.ippolitov.fit.snakes.SnakesProto;
import snake.core.Pos;
import snake.net.StateCodec;

import java.util.List;
import java.util.function.Supplier;

public final class GameView extends AbstractComponent<GameView> {
    private static final TextColor BG = new TextColor.RGB(12, 12, 12);
    private static final TextColor BORDER = new TextColor.RGB(110, 110, 110);

    private static final TextColor MY_HEAD = new TextColor.RGB(50, 220, 90);
    private static final TextColor MY_BODY = new TextColor.RGB(25, 140, 60);

    private static final TextColor ENEMY_HEAD = new TextColor.RGB(60, 160, 240);
    private static final TextColor ENEMY_BODY = new TextColor.RGB(30, 90, 170);

    private static final TextColor ZOMBIE = new TextColor.RGB(130, 130, 130);

    private static final TextColor FOOD = new TextColor.RGB(230, 70, 70);

    private final Supplier<StateCodec.RenderState> state;
    private final Supplier<Integer> myId;

    public GameView(Supplier<StateCodec.RenderState> state, Supplier<Integer> myId) {
        this.state = state;
        this.myId = myId;
    }

    @Override protected ComponentRenderer<GameView> createDefaultRenderer() {
        return new ComponentRenderer<>() {
            @Override public TerminalSize getPreferredSize(GameView c) {
                StateCodec.RenderState s = state.get();
                if (s == null) return new TerminalSize(10, 10);
                return new TerminalSize(fieldW(s), fieldH(s));
            }

            @Override public void drawComponent(TextGUIGraphics g, GameView c) {
                StateCodec.RenderState s = state.get();
                g.setBackgroundColor(BG);
                g.setForegroundColor(BG);
                g.fill(' ');

                if (s == null) return;

                int fw = fieldW(s), fh = fieldH(s);
                int aw = g.getSize().getColumns(), ah = g.getSize().getRows();
                int ox = Math.max(0, (aw - fw) / 2);
                int oy = Math.max(0, (ah - fh) / 2);

                g.setForegroundColor(BORDER);
                g.setBackgroundColor(BG);
                box(g, ox, oy, fw, fh);

                for (Pos p : s.food) cell(g, ox, oy, p.x(), p.y(), FOOD);

                int me = myId.get() == null ? 0 : myId.get();
                for (var e : s.snakes.entrySet()) {
                    int pid = e.getKey();
                    StateCodec.RenderState.SnakeCells sc = e.getValue();
                    List<Pos> cells = sc.cells();
                    boolean isMe = (pid == me);

                    boolean zombie = (sc.snakeState() == SnakesProto.GameState.Snake.SnakeState.ZOMBIE);
                    TextColor head = zombie ? ZOMBIE : (isMe ? MY_HEAD : ENEMY_HEAD);
                    TextColor body = zombie ? ZOMBIE : (isMe ? MY_BODY : ENEMY_BODY);

                    boolean first = true;
                    for (Pos p : cells) {
                        cell(g, ox, oy, p.x(), p.y(), first ? head : body);
                        first = false;
                    }
                }
            }
        };
    }

    private static int fieldW(StateCodec.RenderState s) { return s.w * 2 + 2; }
    private static int fieldH(StateCodec.RenderState s) { return s.h + 2; }

    private static void box(TextGUIGraphics g, int x, int y, int w, int h) {
        int x2 = x + w - 1, y2 = y + h - 1;
        g.drawLine(x, y, x2, y, '─');
        g.drawLine(x, y2, x2, y2, '─');
        g.drawLine(x, y, x, y2, '│');
        g.drawLine(x2, y, x2, y2, '│');
        g.setCharacter(x, y, '┌');
        g.setCharacter(x2, y, '┐');
        g.setCharacter(x, y2, '└');
        g.setCharacter(x2, y2, '┘');
    }

    private static void cell(TextGUIGraphics g, int ox, int oy, int x, int y, TextColor color) {
        int sx = ox + 1 + x * 2;
        int sy = oy + 1 + y;
        if (sx < 0 || sy < 0 || sx + 1 >= g.getSize().getColumns() || sy >= g.getSize().getRows()) return;
        g.setBackgroundColor(color);
        g.setForegroundColor(color);
        g.putString(sx, sy, "  ");
    }
}
