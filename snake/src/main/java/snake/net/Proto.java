package snake.net;

import me.ippolitov.fit.snakes.SnakesProto;

public final class Proto {
    private Proto() {}

    public static byte[] toBytes(SnakesProto.GameMessage msg) {
        return msg.toByteArray();
    }

    public static SnakesProto.GameMessage parse(byte[] data, int len) throws Exception {
        return SnakesProto.GameMessage.parseFrom(java.util.Arrays.copyOf(data, len));
    }
}
