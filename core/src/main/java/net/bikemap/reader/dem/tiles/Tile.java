package net.bikemap.reader.dem.tiles;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Tile {
    private Integer id;
    private String source;
    private String path;
    private String hash;
    private MultiPolygon geometry;
    private Integer width;
    private Integer height;
    private Polygon bounds;
    private Double resolution;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }

    public Polygon getBounds() {
        return bounds;
    }

    public void setBounds(Polygon bounds) {
        this.bounds = bounds;
    }

    public MultiPolygon getGeometry() {
        return geometry;
    }

    public void setGeometry(MultiPolygon geometry) {
        this.geometry = geometry;
    }

    public Double getResolution() {
        return resolution;
    }

    public void setResolution(Double resolution) {
        this.resolution = resolution;
    }

    public Integer getWidth() {
        return width;
    }

    public void setWidth(Integer width) {
        this.width = width;
    }

    public Integer getHeight() {
        return height;
    }

    public void setHeight(Integer height) {
        this.height = height;
    }
}

