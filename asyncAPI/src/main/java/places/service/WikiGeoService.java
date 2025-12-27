package places.service;

import com.fasterxml.jackson.databind.JsonNode;
import places.http.AsyncHttpJsonClient;
import places.model.NearbyPlace;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class WikiGeoService {

    private final AsyncHttpJsonClient client;

    public WikiGeoService(AsyncHttpJsonClient client) {
        this.client = client;
    }

    public static WikiGeoService createDefault() {
        return new WikiGeoService(AsyncHttpJsonClient.createDefault());
    }

    public CompletableFuture<List<NearbyPlace>> findNearbyPlaces(double lat, double lon, int radiusMeters, int limit) {
        String coord = lat + "|" + lon;
        String url = "https://ru.wikipedia.org/w/api.php"
                + "?action=query"
                + "&list=geosearch"
                + "&gscoord=" + URLEncoder.encode(coord, StandardCharsets.UTF_8)
                + "&gsradius=" + radiusMeters
                + "&gslimit=" + limit
                + "&format=json";

        return client.getJson(URI.create(url)).thenApply(this::parseNearby);
    }

    private List<NearbyPlace> parseNearby(JsonNode root) {
        List<NearbyPlace> out = new ArrayList<>();
        JsonNode arr = root.path("query").path("geosearch");
        if (!arr.isArray()) return out;

        for (JsonNode n : arr) {
            long pageId = n.path("pageid").asLong(-1);
            String title = n.path("title").asText("");
            int dist = n.path("dist").asInt(-1);
            if (pageId <= 0 || title.isBlank() || dist < 0) continue;
            out.add(new NearbyPlace(pageId, title, dist));
        }
        return out;
    }
}
