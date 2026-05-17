package com.im.api.content;

import java.util.Objects;

/**
 * 位置消息内容。
 * 对标 OpenIM LocationElem。
 */
public class LocationContent implements IMessageContent {

    private String description;
    private double longitude;
    private double latitude;

    public LocationContent() {}

    public LocationContent(String description, double longitude, double latitude) {
        this.description = description;
        this.longitude = longitude;
        this.latitude = latitude;
    }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }

    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }

    @Override
    public ContentType getContentType() { return ContentType.LOCATION; }

    @Override
    public void validate() {
        if (longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("longitude must be in [-180, 180], got: " + longitude);
        }
        if (latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("latitude must be in [-90, 90], got: " + latitude);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LocationContent that)) return false;
        return Double.compare(longitude, that.longitude) == 0
                && Double.compare(latitude, that.latitude) == 0
                && Objects.equals(description, that.description);
    }

    @Override
    public int hashCode() { return Objects.hash(description, longitude, latitude); }

    @Override
    public String toString() {
        return "LocationContent{desc='" + description + "', lng=" + longitude + ", lat=" + latitude + "}";
    }
}
