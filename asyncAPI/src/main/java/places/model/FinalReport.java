package places.model;

import java.util.List;
import java.util.Objects;

public final class FinalReport {

    private final LocationOption location;
    private final WeatherInfo weather;
    private final List<PlaceInfo> places;

    public FinalReport(LocationOption location, WeatherInfo weather, List<PlaceInfo> places) {
        this.location = Objects.requireNonNull(location);
        this.weather = Objects.requireNonNull(weather);
        this.places = List.copyOf(Objects.requireNonNull(places));
    }

    public LocationOption location() { return location; }
    public WeatherInfo weather() { return weather; }
    public List<PlaceInfo> places() { return places; }

    public String toPrettyString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Локация: ").append(location.displayName())
                .append(" (").append(location.lat()).append(", ").append(location.lon()).append(")\n\n");

        sb.append("Погода:\n");
        sb.append("  ").append(weather.description()).append("\n");
        sb.append("  Температура: ").append(weather.temperatureC()).append("°C\n");
        sb.append("  Влажность: ").append(weather.humidityPct()).append("%\n");
        sb.append("  Ветер: ").append(weather.windSpeed()).append(" м/с\n\n");

        sb.append("Интересные места поблизости (Wikipedia):\n");
        if (places.isEmpty()) {
            sb.append("  (ничего не найдено)\n");
        } else {
            for (int i = 0; i < places.size(); i++) {
                PlaceInfo p = places.get(i);
                sb.append(String.format("  %d) %s — %d м\n", i + 1, p.title(), p.distanceMeters()));
                String text = (p.introExtract() == null || p.introExtract().isBlank())
                        ? "(описание отсутствует)"
                        : p.introExtract().trim();
                sb.append("     ").append(text.replace("\n", "\n     ")).append("\n\n");
            }
        }
        return sb.toString();
    }
}
