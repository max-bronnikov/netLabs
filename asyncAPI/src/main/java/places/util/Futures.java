package places.util;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class Futures {
    private Futures() {}

    public static <T> CompletableFuture<List<T>> sequence(List<CompletableFuture<T>> futures) {
        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        return all.thenApply(v -> futures.stream().map(CompletableFuture::join).toList());
    }
}
