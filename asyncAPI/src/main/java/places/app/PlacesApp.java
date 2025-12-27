package places.app;

import places.model.*;
import places.service.*;
import places.util.Futures;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class PlacesApp {

    private final GraphHopperGeocodingService geocodingService;
    private final OpenWeatherService weatherService;
    private final WikiGeoService wikiGeoService;
    private final WikiExtractService wikiExtractService;

    public PlacesApp(
            GraphHopperGeocodingService geocodingService,
            OpenWeatherService weatherService,
            WikiGeoService wikiGeoService,
            WikiExtractService wikiExtractService
    ) {
        this.geocodingService = Objects.requireNonNull(geocodingService);
        this.weatherService = Objects.requireNonNull(weatherService);
        this.wikiGeoService = Objects.requireNonNull(wikiGeoService);
        this.wikiExtractService = Objects.requireNonNull(wikiExtractService);
    }

    public static PlacesApp createDefault() {
        var geocoding = GraphHopperGeocodingService.createDefaultFromEnv();
        var weather = OpenWeatherService.createDefaultFromEnv();
        var wikiGeo = WikiGeoService.createDefault();
        var wikiExtract = WikiExtractService.createDefault();
        return new PlacesApp(geocoding, weather, wikiGeo, wikiExtract);
    }

    public CompletableFuture<List<LocationOption>> searchLocations(String query) {
        return geocodingService.search(query, 8);
    }

    public CompletableFuture<FinalReport> buildReport(LocationOption selected) {
        CompletableFuture<WeatherInfo> weatherF =
                weatherService.getWeather(selected.lat(), selected.lon());

        CompletableFuture<List<PlaceInfo>> placesF =
                wikiGeoService.findNearbyPlaces(selected.lat(), selected.lon(), 10_000, 10)
                        .thenCompose(nearby -> {
                            List<CompletableFuture<PlaceInfo>> perPlace =
                                    nearby.stream()
                                            .map(p ->
                                                    wikiExtractService.getIntroExtract(p.pageId())
                                                            .exceptionally(ex -> "Описание недоступно: " + ex.getMessage())
                                                            .thenApply(extract -> new PlaceInfo(
                                                                    p.pageId(),
                                                                    p.title(),
                                                                    p.distanceMeters(),
                                                                    extract
                                                            ))
                                            )
                                            .toList();

                            return Futures.sequence(perPlace);
                        });

        return weatherF.thenCombine(placesF,
                (weather, places) -> new FinalReport(selected, weather, places)
        );
    }
}
