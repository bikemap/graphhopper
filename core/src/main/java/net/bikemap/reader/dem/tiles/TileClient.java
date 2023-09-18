package net.bikemap.reader.dem.tiles;

import com.bedatadriven.jackson.datatype.jts.JtsModule;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;

import static java.lang.System.lineSeparator;
import static java.util.stream.Collectors.joining;

/**
 * HTTP Client for the Elevation API /v1/tiles endpoints.
 */
public class TileClient {
    private final ObjectMapper objectMapper;
    private final URI baseUrl;

    public TileClient(String baseUrl) throws URISyntaxException {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JtsModule());
        this.objectMapper = objectMapper;
        this.baseUrl = new URI(baseUrl);
    }

    /**
     * Fetches the tile index endpoint.
     * @return All the prioritized tiles available in the Elevation DB.
     */
    public List<Tile> fetchTileIndex() throws IOException {
        URL tileIndexUrl = baseUrl.resolve("/v1/tiles").toURL();
        return fetchTileIndex(tileIndexUrl, 0);
    }

    /**
     * Fetches the tile index endpoint.
     * @param tileIndexUrl Full URL of the GET /v1/tiles endpoint.
     * @param tries Count of the tries.
     * @return All the prioritized tiles available in the Elevation DB.
     */
    private List<Tile> fetchTileIndex(URL tileIndexUrl, int tries) throws IOException {
        try (InputStream is = tileIndexUrl.openStream()) {
            // Create a buffered input stream reader.
            BufferedReader in = new BufferedReader(new InputStreamReader(is));
            String responseBody = in.lines().collect(joining(lineSeparator()));
            TileResponse response = objectMapper.readValue(responseBody, TileResponse.class);

            return response.getTiles();
        } catch (IOException ex) {
            int MAX_RETRIES = 5;
            if (tries >= MAX_RETRIES - 1) {
                throw ex;
            }

            try { Thread.sleep(1500L * tries); }
            catch (InterruptedException ignored) {}

            return fetchTileIndex(tileIndexUrl, tries + 1);
        }
    }
}
