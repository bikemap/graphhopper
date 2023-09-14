package net.bikemap.reader.dem.tiles;

import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.STRtree;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public class TileIndex {
    /**
     * Initialize the JTS GeometryFactory.
     */
    private static final GeometryFactory geometryFactory = new GeometryFactory();

    /**
     * Keeps an index of the tiles' bounds using an RTree.
     */
    private final STRtree tileIndex;

    /**
     * HashMap of the tiles available.
     */
    private final Map<Integer, Tile> tileMap;

    public TileIndex(List<Tile> tiles) {
        this.tileMap = tiles.stream().collect(Collectors.toMap(Tile::getId, Function.identity()));
        this.tileIndex = buildRTreeIndex(tiles);
    }

    public Optional<Tile> findIntersectingTile(double lat, double lon) {
        Coordinate coordinate = new Coordinate(lon, lat);
        Envelope envelope = new Envelope(coordinate);
        Point point = geometryFactory.createPoint(coordinate);

        //noinspection rawtypes
        List tilesId = tileIndex.query(envelope);

        for (Object id : tilesId) {
            Tile tile = tileMap.get((Integer) id);

            if (tile.getGeometry().intersects(point)) {
                return Optional.of(tile);
            }
        }

        return Optional.empty();
    }

    /**
     * Constructs an RTree index given a set of tiles.
     */
    private STRtree buildRTreeIndex(List<Tile> tiles) {
        STRtree rtree = new STRtree();

        for (Tile tile : tiles) {
            MultiPolygon multiPolygon = tile.getGeometry();

            for (int i = 0; i < multiPolygon.getNumGeometries(); i++) {
                Envelope envelope = multiPolygon.getGeometryN(i).getEnvelopeInternal();
                rtree.insert(envelope, tile.getId());
            }
        }

        return rtree;
    }
}
