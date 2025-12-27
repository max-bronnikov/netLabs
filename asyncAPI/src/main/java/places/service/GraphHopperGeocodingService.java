package places.service;

import com.fasterxml.jackson.databind.JsonNode;
import places.http.AsyncHttpJsonClient;
import places.model.LocationOption;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class GraphHopperGeocodingService {

    private final AsyncHttpJsonClient client;
    private final String apiKey;

    public GraphHopperGeocodingService(AsyncHttpJsonClient client, String apiKey) {
        this.client = Objects.requireNonNull(client);
        this.apiKey = Objects.requireNonNull(apiKey);
    }

    public static GraphHopperGeocodingService createDefaultFromEnv() {
        String key = System.getenv("GRAPHOPPER_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("Не задан GRAPHOPPER_API_KEY");
        }
        return new GraphHopperGeocodingService(AsyncHttpJsonClient.createDefault(), key.trim());
    }

    public CompletableFuture<List<LocationOption>> search(String query, int limit) {
        String q = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://graphhopper.com/api/1/geocode"
                + "?q=" + q
                + "&limit=" + limit
                + "&key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);

        return client.getJson(URI.create(url)).thenApply(this::parseLocations);
    }

    private List<LocationOption> parseLocations(JsonNode root) {
        List<LocationOption> out = new ArrayList<>();
        JsonNode hits = root.path("hits");
        if (!hits.isArray()) return out;

        for (JsonNode h : hits) {
            String name = h.path("name").asText("");
            String country = h.path("country").asText("");
            String city = h.path("city").asText("");
            String state = h.path("state").asText("");

            double lat = h.path("point").path("lat").asDouble(Double.NaN);
            double lng = h.path("point").path("lng").asDouble(Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lng)) continue;

            String display = buildDisplayName(name, city, state, country, lat, lng);
            out.add(new LocationOption(display, lat, lng));
        }
        return out;
    }

    private static String buildDisplayName(String name, String city, String state, String country, double lat, double lon) {
        List<String> parts = new ArrayList<>();
        if (!name.isBlank()) parts.add(name);
        if (!city.isBlank() && !city.equalsIgnoreCase(name)) parts.add(city);
        if (!state.isBlank()) parts.add(state);
        if (!country.isBlank()) parts.add(country);
        String base = String.join(", ", parts);
        return base + String.format(" [%.5f, %.5f]", lat, lon);
    }
}
