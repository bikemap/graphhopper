package net.bikemap.reader.dem.tiles;

import java.util.List;

public class TileResponse {
    private List<Tile> tiles;

    public TileResponse() {}

    public List<Tile> getTiles() {
        return tiles;
    }

    public void setTiles(List<Tile> tiles) {
        this.tiles = tiles;
    }
}
