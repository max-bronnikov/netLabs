package places.service;

import com.fasterxml.jackson.databind.JsonNode;
import places.http.AsyncHttpJsonClient;

import java.net.URI;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;

public final class WikiExtractService {

    private final AsyncHttpJsonClient client;

    public WikiExtractService(AsyncHttpJsonClient client) {
        this.client = client;
    }

    public static WikiExtractService createDefault() {
        return new WikiExtractService(AsyncHttpJsonClient.createDefault());
    }

    public CompletableFuture<String> getIntroExtract(long pageId) {
        String url = "https://ru.wikipedia.org/w/api.php"
                + "?action=query"
                + "&prop=extracts"
                + "&exintro=1"
                + "&explaintext=1"
                + "&pageids=" + pageId
                + "&format=json";

        return client.getJson(URI.create(url)).thenApply(this::parseExtract);
    }

    private String parseExtract(JsonNode root) {
        JsonNode pages = root.path("query").path("pages");
        if (!pages.isObject()) return "";

        Iterator<String> fields = pages.fieldNames();
        if (!fields.hasNext()) return "";
        String firstKey = fields.next();

        JsonNode page = pages.path(firstKey);
        return page.path("extract").asText("");
    }
}
