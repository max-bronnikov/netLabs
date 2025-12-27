package places.model;

public record PlaceInfo(
        long pageId,
        String title,
        int distanceMeters,
        String introExtract
) {}
