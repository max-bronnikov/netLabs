package places.model;

public record WeatherInfo(
        String description,
        double temperatureC,
        int humidityPct,
        double windSpeed
) {}
