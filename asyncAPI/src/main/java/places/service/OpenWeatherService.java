package places.service;

import com.fasterxml.jackson.databind.JsonNode;
import places.http.AsyncHttpJsonClient;
import places.model.WeatherInfo;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class OpenWeatherService {

    private final AsyncHttpJsonClient client;
    private final String apiKey;

    public OpenWeatherService(AsyncHttpJsonClient client, String apiKey) {
        this.client = Objects.requireNonNull(client);
        this.apiKey = Objects.requireNonNull(apiKey);
    }

    public static OpenWeatherService createDefaultFromEnv() {
        String key = System.getenv("OPENWEATHER_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("Не задан OPENWEATHER_API_KEY.");
        }
        return new OpenWeatherService(AsyncHttpJsonClient.createDefault(), key.trim());
    }

    public CompletableFuture<WeatherInfo> getWeather(double lat, double lon) {
        String url = "https://api.openweathermap.org/data/2.5/weather"
                + "?lat=" + lat
                + "&lon=" + lon
                + "&appid=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
                + "&units=metric"
                + "&lang=ru";

        return client.getJson(URI.create(url)).thenApply(this::parseWeather);
    }

    private WeatherInfo parseWeather(JsonNode root) {
        String desc = root.path("weather").isArray() && root.path("weather").size() > 0
                ? root.path("weather").get(0).path("description").asText("нет описания")
                : "нет описания";

        double temp = root.path("main").path("temp").asDouble(Double.NaN);
        int humidity = root.path("main").path("humidity").asInt(-1);
        double wind = root.path("wind").path("speed").asDouble(Double.NaN);

        if (Double.isNaN(temp)) temp = 0.0;
        if (humidity < 0) humidity = 0;
        if (Double.isNaN(wind)) wind = 0.0;

        return new WeatherInfo(desc, temp, humidity, wind);
    }
}
