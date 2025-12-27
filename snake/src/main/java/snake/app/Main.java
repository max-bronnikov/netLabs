package snake.app;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.SimpleTheme;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.googlecode.lanterna.gui2.dialogs.TextInputDialog;
import com.googlecode.lanterna.gui2.table.Table;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import snake.core.Dir;
import snake.net.*;
import snake.ui.GameView;
import snake.ui.LanternaInput;

import java.io.IOException;
import java.util.List;

public final class Main {

    public static void main(String[] args) throws Exception {
        Screen screen = new TerminalScreen(new DefaultTerminalFactory().createTerminal());
        screen.startScreen();
        screen.setCursorPosition(null);

        UdpSockets socks = new UdpSockets();
        DiscoveryRegistry reg = new DiscoveryRegistry();
        GameNode node = new GameNode(socks, reg);

        try {
            MultiWindowTextGUI gui = new MultiWindowTextGUI(
                    screen, new DefaultWindowManager(), new EmptySpace()
            );
            gui.setTheme(new SimpleTheme(
                    TextColor.ANSI.WHITE,
                    TextColor.ANSI.BLACK
            ));

            BasicWindow lobby = new BasicWindow("Lobby");
            lobby.setHints(List.of(Window.Hint.FULL_SCREEN));

            Panel root = new Panel(new BorderLayout());

            Label top = new Label("N: new game   J: join selected   V: join as viewer   R: refresh list   Q: quit");
            root.addComponent(top.withBorder(Borders.singleLine()), BorderLayout.Location.TOP);

            Table<String> table = new Table<>("Game", "Master", "Players", "Config", "Join?");
            table.setSelectAction(() -> {});
            root.addComponent(table.withBorder(Borders.singleLine("Games")), BorderLayout.Location.CENTER);

            Label status = new Label("");
            root.addComponent(status.withBorder(Borders.singleLine("Status")), BorderLayout.Location.BOTTOM);

            lobby.setComponent(root);
            gui.addWindow(lobby);

            long lastRefresh = 0;

            while (true) {
                for (KeyStroke k; (k = screen.pollInput()) != null; ) {
                    if (k.getCharacter() != null) {
                        char c = k.getCharacter();
                        if (c == 'q' || c == 'Q') return;

                        if (c == 'r' || c == 'R') {
                            refreshTable(table, node.registry().snapshot());
                            lastRefresh = System.currentTimeMillis();
                        }

                        if (c == 'n' || c == 'N') {
                            hostDialog(gui, node);
                            refreshTable(table, node.registry().snapshot());
                        }

                        if (c == 'j' || c == 'J') {
                            joinSelected(gui, node, table, false);
                        }

                        if (c == 'v' || c == 'V') {
                            joinSelected(gui, node, table, true);
                        }
                    }
                }

                long now = System.currentTimeMillis();
                if (now - lastRefresh > 1000) {
                    refreshTable(table, node.registry().snapshot());
                    lastRefresh = now;
                }

                status.setText(node.view.lastError != null ? ("Error: " + node.view.lastError) : "");

                if (node.view.inGame) {
                    runGameScreen(gui, screen, node);
                    node.view.lastError = null;
                    refreshTable(table, node.registry().snapshot());
                }

                gui.updateScreen();
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
            }
        } finally {
            try { node.shutdown(); } catch (Exception ignored) {}
            try { socks.close(); } catch (Exception ignored) {}
            screen.stopScreen();
        }
    }

    private static void refreshTable(Table<String> table, List<DiscoveryRegistry.GameInfo> games) throws Exception {
        table.getTableModel().clear();
        for (DiscoveryRegistry.GameInfo g : games) {
            String players = String.valueOf(g.players.getPlayersCount());
            String cfg = String.format("%dx%d food=%d tick=%dms",
                    g.config.getWidth(), g.config.getHeight(), g.config.getFoodStatic(), g.config.getStateDelayMs());
            table.getTableModel().addRow(
                    g.gameName,
                    g.masterAddr.getAddress().getHostAddress() + ":" + g.masterAddr.getPort(),
                    players,
                    cfg,
                    g.canJoin ? "yes" : "no"
            );
        }
    }

    private static void hostDialog(WindowBasedTextGUI gui, GameNode node) {
        String gameName = TextInputDialog.showDialog(gui, "New Game", "Game name:", "РУБИЛОВО");
        if (gameName == null || gameName.isBlank()) return;

        String myName = TextInputDialog.showDialog(gui, "New Game", "Your player name:", "player");
        if (myName == null || myName.isBlank()) return;
        String wS = TextInputDialog.showDialog(gui, "New Game", "Width (10..100):", "20");
        String hS = TextInputDialog.showDialog(gui, "New Game", "Height (10..100):", "20");
        String foodS = TextInputDialog.showDialog(gui, "New Game", "Food static (0..100):", "1");
        String tickS = TextInputDialog.showDialog(gui, "New Game", "State delay ms (100..3000):", "150");

        try {
            int w = clamp(Integer.parseInt(wS), 10, 100);
            int h = clamp(Integer.parseInt(hS), 10, 100);
            int food = clamp(Integer.parseInt(foodS), 0, 100);
            int tick = clamp(Integer.parseInt(tickS), 100, 3000);

            node.hostNewGame(gameName, w, h, food, tick, myName);
        } catch (Exception e) {
            MessageDialog.showMessageDialog(gui, "Error", "Invalid parameters: " + e.getMessage(), MessageDialogButton.OK);
        }
    }

    private static void joinSelected(WindowBasedTextGUI gui, GameNode node, Table<String> table, boolean viewer) {
        int idx = table.getSelectedRow();
        if (idx < 0) return;

        List<DiscoveryRegistry.GameInfo> snap = node.registry().snapshot();
        if (idx >= snap.size()) return;

        DiscoveryRegistry.GameInfo g = snap.get(idx);
        if (!g.canJoin && !viewer) {
            MessageDialog.showMessageDialog(gui, "Join", "This game reports can_join=false", MessageDialogButton.OK);
            return;
        }

        String myName = TextInputDialog.showDialog(gui, "Join Game", "Your player name:", "player");
        if (myName == null || myName.isBlank()) return;

        node.joinGame(g, myName, viewer);
    }

    private static void runGameScreen(MultiWindowTextGUI gui, Screen screen, GameNode node) throws Exception {
        BasicWindow w = new BasicWindow("Game: " + node.view.gameName);
        w.setHints(List.of(Window.Hint.FULL_SCREEN));

        Panel root = new Panel(new BorderLayout());

        Label help = new Label("L: leave to lobby   Q/Esc: quit");
        Label status = new Label("");

        Panel top = new Panel(new LinearLayout(Direction.VERTICAL));
        top.addComponent(help);
        top.addComponent(status);

        GameView view = new GameView(() -> node.view.render, () -> node.view.myId);

        TextBox playersBox = new TextBox(new TerminalSize(35, 20), TextBox.Style.MULTI_LINE);
        playersBox.setReadOnly(true);

        Panel right = new Panel(new LinearLayout(Direction.VERTICAL));
        right.addComponent(new Label("Players"));
        right.addComponent(new Separator(Direction.HORIZONTAL));
        right.addComponent(playersBox);
        right.withBorder(Borders.singleLine());

        root.addComponent(top.withBorder(Borders.singleLine()), BorderLayout.Location.TOP);
        root.addComponent(view.withBorder(Borders.singleLine("Field")), BorderLayout.Location.CENTER);
        root.addComponent(right, BorderLayout.Location.RIGHT);

        w.setComponent(root);
        gui.addWindow(w);

        LanternaInput input = new LanternaInput();

        while (node.view.inGame) {
            for (KeyStroke k; (k = screen.pollInput()) != null; ) {
                if (k.getKeyType() == com.googlecode.lanterna.input.KeyType.Escape) {
                    node.leaveToViewer();
                    break;
                }
                if (k.getCharacter() != null) {
                    char c = k.getCharacter();
                    if (c == 'q' || c == 'Q') {
                        node.leaveToViewer();
                        break;
                    }
                    if (c == 'l' || c == 'L') {
                        node.leaveToViewer();
                        break;
                    }
                }

                var in = input.map(k);
                Dir d = in.dir();
                if (d != null) node.sendSteer(d);
            }

            var r = node.view.render;
            String role = String.valueOf(node.view.myRole);
            status.setText("MyId=" + node.view.myId + " Role=" + role +
                    (r == null ? "" : (" StateOrder=" + r.stateOrder)));

            if (r != null) {
                StringBuilder sb = new StringBuilder();
                r.players.entrySet().stream()
                        .sorted(java.util.Map.Entry.comparingByKey())
                        .forEach(e -> {
                            sb.append(e.getKey())
                                    .append("  ")
                                    .append(e.getValue().name())
                                    .append("  score=")
                                    .append(e.getValue().score())
                                    .append("  role=")
                                    .append(e.getValue().role())
                                    .append("\n");
                        });
                playersBox.setText(sb.toString());
            }

            view.invalidate();
            try {
                gui.updateScreen();
            } catch (IOException ignored) {}

            try { Thread.sleep(10); } catch (InterruptedException ignored) {}
        }

        gui.removeWindow(w);
    }

    private static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
