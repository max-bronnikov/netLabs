package snake.net;

import java.util.concurrent.atomic.AtomicLong;

public final class MsgSeq {
    private final AtomicLong seq = new AtomicLong(1);
    public long next() { return seq.getAndIncrement(); }
}
