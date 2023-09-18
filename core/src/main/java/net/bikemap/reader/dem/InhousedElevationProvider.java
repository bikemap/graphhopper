package net.bikemap.reader.dem;

import com.graphhopper.coll.GHIntObjectHashMap;
import com.graphhopper.reader.dem.ElevationProvider;
import com.graphhopper.storage.DAType;
import com.graphhopper.storage.DataAccess;
import com.graphhopper.storage.Directory;
import com.graphhopper.storage.GHDirectory;
import net.bikemap.reader.dem.tiles.Tile;
import net.bikemap.reader.dem.tiles.TileClient;
import net.bikemap.reader.dem.tiles.TileIndex;
import org.locationtech.jts.geom.Envelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * An ElevationProvider based on the Elevation DB tiles.
 * @author Marco Rodriguez
 */
public class InhousedElevationProvider implements ElevationProvider {
    /**
     * Adds a tiny margin to the DEMs to allow points outside of the bounds but close to the edge
     * to be accessed, if there's no more precise data. The name is a bit confusing, but it was kept
     * to comply with the Graphhopper's HeightTile constructor.
     */
    private static final double PRECISION = 1e-6;

    /**
     * Defines that the .gh tiles should always be loaded on the disk.
     */
    private static final DAType STORAGE = DAType.MMAP;

    /**
     * Initialize the logger class.
     */
    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * Caches the PrecisionHeightTile tile access object.
     */
    private final GHIntObjectHashMap<PrecisionHeightTile> tileCache = new GHIntObjectHashMap<>();

    /**
     * Defines whether a linear interpolation should be performed when retrieving the elevation
     * of a point.
     */
    private final boolean interpolate;

    /**
     * Keeps an spatial index of all the tiles available.
     */
    private final TileIndex tileIndex;

    /**
     * Graphhopper .gh file cache directory.
     */
    private final Directory cacheDir;

    /**
     * Defines the path where all the tiles present in the index are stored.
     */
    private final File datasetDir;

    public InhousedElevationProvider(
            String baseUrl, String datasetPath, String cachePath, boolean interpolate
    ) {
        this.interpolate = interpolate;

        // Initialize the Graphhopper cache directory for .gh files.
        File cacheDir = new File(cachePath);
        assertCacheDirectoryIsValid(cacheDir);
        this.cacheDir = new GHDirectory(cacheDir.getAbsolutePath(), STORAGE);

        // Initialize the dataset directory containing all the tiles from the Elevation DB.
        this.datasetDir = new File(datasetPath);
        if (!this.datasetDir.exists() || !this.datasetDir.isDirectory()) {
            throw new IllegalArgumentException("Dataset directory does not exist");
        }

        // Fetch the prioritized tiles & build the spatial index.
        try {
            TileClient tileClient = new TileClient(baseUrl);
            List<Tile> tiles = tileClient.fetchTileIndex();
            assertDatasetDirectoryIsComplete(datasetDir, tiles);

            this.tileIndex = new TileIndex(tiles);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("invalid base url");
        } catch (IOException e) {
            throw new RuntimeException("could not connect to " + baseUrl + ": " + e);
        }

        logger.info(this.getClass().getName() + "to:" + cacheDir + ", as: " + STORAGE +
                " using interpolate: " + interpolate);
    }

    public static void main(String[] args) {
        String baseUrl = "http://localhost:8001";
        String cachePath = "/tmp/inhoused";

        String resourcesPath = "core/src/main/resources/net/bikemap/reader/dem/dataset";
        String datasetPath = new File(resourcesPath).getAbsolutePath();

        InhousedElevationProvider provider = new InhousedElevationProvider(
                baseUrl, datasetPath, cachePath, false
        );

        provider.compareResults(47.04638444368381,8.308393666039633,439.0);
        provider.compareResults(46.94362266151541,8.00715057516004,724.7);
        provider.compareResults(46.49848271203861,7.989910985840626,2993.7);
    }

    private void compareResults(double lat, double lon, double expected) {
        double actual = getEle(lat, lon);
        System.out.printf("%f,%f -> Actual: %f - Expected: %f\n", lat, lon, actual, expected);
    }

    @Override
    public boolean canInterpolate() {
        return interpolate;
    }

    @Override
    public double getEle(double lat, double lon) {
        // Find the tile from the index that intersects the given point.
        Optional<Tile> foundTile = tileIndex.findIntersectingTile(lat, lon);
        if (!foundTile.isPresent()) {
            logger.error("no matching tile was found for point: " + lat + "," + lon);
            return 0;
        }

        // Load cached tile.
        Tile tile = foundTile.get();
        PrecisionHeightTile heightTile = tileCache.get(tile.getId());

        // If it wasn't loaded previously, read it and store it in the cache.
        if (heightTile == null) {
            try {
                heightTile = loadHeightTile(tile);
            } catch (Exception ex) {
                logger.error("could not load height tile: " + tile.getPath(), " - " + ex.getMessage());
                return 0;
            }

            tileCache.put(tile.getId(), heightTile);
        }

        if (heightTile.isSeaLevel()) {
            return 0;
        }

        return heightTile.getHeight(lat, lon);
    }

    @Override
    public void release() {
        tileCache.clear();

        if (cacheDir == null) {
            return;
        }

        // Release directory.
        if (STORAGE == DAType.MMAP) {
            cacheDir.clear();
        }

        cacheDir.close();
    }


    /**
     * Asserts that all the files listed in the tiles response are available in the dataset
     * directory.
     */
    private static void assertDatasetDirectoryIsComplete(File datasetDir, List<Tile> tiles) {
        for (Tile tile : tiles) {
            File tiffFile = new File(datasetDir, tile.getPath());
            if (!tiffFile.exists()) {
                throw new RuntimeException("could not find " + tile.getPath() + " in the dataset directory");
            }
        }
    }

    /**
     * Asserts that the cache directory exists (or that it can be created).
     */
    private static void assertCacheDirectoryIsValid(File cacheDir) {
        if (cacheDir.exists() && !cacheDir.isDirectory()) {
            throw new IllegalArgumentException("Cache path has to be a directory");
        }
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new RuntimeException("Could not create cache directory");
        }
    }

    /**
     * Loads a PrecisionHeightTile access object from the Tile metadata. If the .gh file doesn't
     * exist, it's created from the tiff file.
     * @param tile Tile metadata.
     * @return A PrecisionHeightTile ready to use.
     */
    private PrecisionHeightTile loadHeightTile(Tile tile) {
        int width = tile.getWidth();
        int height = tile.getHeight();

        Envelope bounds = tile.getBounds().getEnvelopeInternal();
        double minLat = bounds.getMinY();
        double minLon = bounds.getMinX();
        double horizontalDegree = bounds.getMaxX() - bounds.getMinX();
        double verticalDegree = bounds.getMaxY() - bounds.getMinY();

        PrecisionHeightTile heightTile = new PrecisionHeightTile(
                minLat, minLon, width, height, PRECISION, horizontalDegree, verticalDegree
        );
        heightTile.setInterpolate(interpolate);

        // Create .gh file, which is used by Graphhopper to store elevation rasters with 16-bit
        // integer precision (short).
        String heightsPath = tile.getPath().toLowerCase() + ".gh";
        File parentDir = new File(this.cacheDir.getLocation(), heightsPath).getParentFile();
        if (!parentDir.exists() && !parentDir.mkdirs()) {
            throw new RuntimeException("could not create parent directory for the cache file: " + parentDir.getAbsolutePath());
        }

        DataAccess heights = this.cacheDir.create(heightsPath);
        heightTile.setHeights(heights);

        try {
            if (heights.loadExisting()) {
                return heightTile;
            }
        } catch (Exception ex) {
            logger.warn("cannot load dem" + heightsPath + ", error:" + ex.getMessage());
        }

        // Allocate the space for a 16-bit precision raster.
        heights.create(2L * width * height); // short == 2 bytes

        // Check if the tiff file exists.
        File tiffFile = new File(datasetDir, tile.getPath());
        if (!tiffFile.exists() || !tiffFile.isFile()) {
            throw new RuntimeException("tiff file not found: " + tiffFile.getAbsolutePath());
        }

        // Read raster file and fill the .gh file with its content.
        Raster raster = readFile(tiffFile);
        fillDataAccessWithElevationData(raster, heights); // width

        return heightTile;
    }

    /**
     * Reads a .tiff file from the disk and returns a raster instance.
     * @param tiffFile Non-compressed GeoTIFF file.
     * @return A java.awt.image.Raster instance.
     */
    private static Raster readFile(File tiffFile) {
        // Load the raster using the ImageIO package, as the TIFFImageDecoder class from
        // org.apache.xmlgraphics used in other elevation providers doesn't support float32 tiffs.
        try (ImageInputStream input = ImageIO.createImageInputStream(tiffFile)) {
            ImageReader reader = ImageIO.getImageReaders(input).next();
            reader.setInput(input);

            BufferedImage image = reader.read(0);
            return image.getData();
        } catch (NoSuchElementException ex) {
            throw new RuntimeException(
                    "Could not find a suitable image reader for: " + tiffFile.getName(), ex
            );
        } catch (IOException ex) {
            throw new RuntimeException("Could not open DEM: " + tiffFile.getName(), ex);
        }
    }

    /**
     * Copies the data from the raster into the DataAccess object (.gh file) using 16-bit integers.
     * @param raster Source raster.
     * @param heights Destination data access object (.gh file)
     */
    private static void fillDataAccessWithElevationData(Raster raster, DataAccess heights) {
        int x = 0;
        int y = 0;

        try {
            for (y = 0; y < raster.getHeight(); y++) {
                for (x = 0; x < raster.getWidth(); x++) {
                    short val = (short) raster.getPixel(x, y, (int[]) null)[0];
                    if (val < -1000 || val > 12000) {
                        val = Short.MIN_VALUE;
                    }

                    heights.setShort(2 * ((long) y * raster.getWidth() + x), val);
                }
            }
            heights.flush();
        } catch (Exception ex) {
            throw new RuntimeException("Problem at x:" + x + ", y:" + y, ex);
        }
    }
}
