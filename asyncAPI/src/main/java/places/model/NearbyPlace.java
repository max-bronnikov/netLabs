package places.model;

public record NearbyPlace(
        long pageId,
        String title,
        int distanceMeters
) {}
