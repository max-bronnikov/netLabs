package places.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class AsyncHttpJsonClient {

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String userAgent;

    public AsyncHttpJsonClient(HttpClient httpClient, ObjectMapper mapper, String userAgent) {
        this.httpClient = Objects.requireNonNull(httpClient);
        this.mapper = Objects.requireNonNull(mapper);
        this.userAgent = Objects.requireNonNull(userAgent);
    }

    public static AsyncHttpJsonClient createDefault() {
        String ua = "student project"; // вики требует ua
        return new AsyncHttpJsonClient(HttpClient.newHttpClient(), new ObjectMapper(), ua);
    }

    public CompletableFuture<JsonNode> getJson(URI uri) {
        HttpRequest req = HttpRequest.newBuilder(uri)
                .GET()
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.7")
                .build();

        return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    int code = resp.statusCode();
                    if (code < 200 || code >= 300) {
                        throw new RuntimeException("HTTP " + code + " for " + uri + ": " + safeSnippet(resp.body()));
                    }
                    return resp.body();
                })
                .thenApply(body -> {
                    try {
                        return mapper.readTree(body);
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to parse JSON from " + uri + ": " + e.getMessage(), e);
                    }
                });
    }

    private static String safeSnippet(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
