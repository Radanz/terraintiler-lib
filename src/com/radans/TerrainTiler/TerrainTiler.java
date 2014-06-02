 /*
 * 
 */
package com.radans.TerrainTiler;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AppState;
import com.jme3.app.state.AppStateManager;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.asset.ModelKey;
import com.jme3.asset.plugins.ClasspathLocator;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.asset.plugins.ZipLocator;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.font.plugins.BitmapFontLoader;
import com.jme3.material.Material;
import com.jme3.material.plugins.J3MLoader;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.scene.Node;
import com.jme3.shader.plugins.GLSLLoader;
import com.jme3.terrain.ProgressMonitor;
import com.jme3.terrain.Terrain;
import com.jme3.terrain.geomipmap.MultiTerrainLodControl;
import com.jme3.terrain.geomipmap.NeighbourFinder;
import com.jme3.terrain.geomipmap.TerrainQuad;
import com.jme3.terrain.geomipmap.lodcalc.DistanceLodCalculator;
import com.jme3.texture.plugins.AWTLoader;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * TerrainTiler class for managing very large terrains over an array of tiles
 * each being a TerrainQuad.
 *
 * @author Radan Vowles
 *
 */
public class TerrainTiler extends Node implements Terrain, NeighbourFinder {
    private static float version = 2013.1118f;
    private static int maxTiles = 4096;
    protected static final Logger tLog = Logger.getLogger(TerrainTiler.class.getCanonicalName());
    private final DesktopAssetManager dAssetManager;
    private volatile MultiTerrainLodControl terrainMLOD;
    private volatile boolean terrainLocked;         // disable editing if true
    private volatile boolean useJars;               // true if tiles packed into jars
    private volatile TerrainState terrainState;     // appState for updates
    private ConcurrentHashMap<Integer, TerrainQuad> tileSet;  // Array of tiles
    private ConcurrentHashMap<Integer, Integer> tileFlag;  // Array of tiles
    private volatile terrainThread tThread;         // Thread for tile updates
    private volatile boolean threadState;           // State of thread (running or not)
    private volatile boolean newTile;               // flag to alert to tile changes
    private volatile Camera camera;                 // Referance to camera to track movement for updates
    private volatile int numTilesX;                 // number of tiles in the X direction
    private volatile int numTilesZ;                 // number of tiles in the Z direction
    private volatile int tileSize;                  // size of each tile - power of 2
    private volatile int tileScale;                 // scale of each tile for world view
    private volatile int tileWSize;                 // world size (scaled) of each tile
    private volatile String tileLocator;            // Locator for asset loader to find tiles
    private volatile String terrainMapFile;         // location and name of map file
    private volatile SimpleApplication app;         // app calling hooks
    private volatile int gridSize;                  // number of tiles viewable (3, 5, 7, 9);
    private volatile int gridCenterX;               // center of viewable grid
    private volatile int gridCenterZ;               // center of viewable grid
    private Set<TerrainTilerAction> actionHooks = new HashSet<>();
    public boolean valid = false;                   // true if terrain initialized, false if somethings wrong;
    public int mapVersion = 0;

    /**
     * Constructor for new TerrainTiler object using a 3x3 tile grid as default
     * Uses the new <tiledTerrain>.map file system for details on the map tile data.
     *
     * @param cam - Camera to track movement for tile load/unload.
     * @param tLocator - Full pathname for the <tiledTerrain>.map file to load.
     *      This file containes all the details on the map tiles, size etc
     *      Should be absolute path and filename for loading.
     *      line 1: tiledTerrain : <version>          // eg "tiledTerrain : 19
     *      line 2: useJars   : <true|false>          // are tiles packed into jars
     *      line 3: numTilesX : <16...4096>           // number tiles in X direction
     *      line 4: numTilesZ : <16...4096>           // number tiles in Z direction
     *      line 5: tileSize  : <256...2048>          // base size of each tile
     *      line 6: tileScale : <scale>               // scale factors for each tile
     *      line 7: tileType  : <image|terrain|node>  // tile file type
     * @param caller - SimpleApplication calling us to attach appState to
     */
    public TerrainTiler(Camera cam, String tLocator, SimpleApplication caller) {
        this.setName("TiledTerrainNode");
        this.camera = cam;
        this.app = caller;
        this.terrainLocked = true;      // by default lock out editing
        this.gridSize = 3;              // default to 3x3 grid
        this.terrainMapFile = tLocator;
        this.valid = false;
        
        dAssetManager = new DesktopAssetManager();
        dAssetManager.registerLocator("/", ClasspathLocator.class);
        dAssetManager.registerLoader(J3MLoader.class, "j3md");
        dAssetManager.registerLoader(GLSLLoader.class, "vert");
        dAssetManager.registerLoader(GLSLLoader.class, "frag");
        dAssetManager.registerLoader(GLSLLoader.class, "glsl");
        dAssetManager.registerLoader(GLSLLoader.class, "glsllib");
        dAssetManager.registerLoader(BinaryImporter.class, "j3o");
        dAssetManager.registerLoader(BitmapFontLoader.class, "fnt");
        dAssetManager.registerLoader(AWTLoader.class, "png");
        
        // todo
        /** load in <tiledTerrain>.map file and parse parameters....*/
        File file = new File(tLocator);
        if (file.exists()) {
            try {
                tileLocator = file.getParentFile().getCanonicalPath();
                FileReader fRead = new FileReader(file);
                BufferedReader bRead = new BufferedReader(fRead);
                String rLine = bRead.readLine();
                if (rLine.startsWith("tiledTerrain")) {
                    mapVersion = Integer.valueOf(rLine.substring(15));
                    rLine = bRead.readLine();
                    useJars = Boolean.valueOf(rLine.substring(12));
                    rLine = bRead.readLine();
                    numTilesX = Integer.valueOf(rLine.substring(12));
                    rLine = bRead.readLine();
                    numTilesZ = Integer.valueOf(rLine.substring(12));
                    rLine = bRead.readLine();
                    tileSize = Integer.valueOf(rLine.substring(12));
                    rLine = bRead.readLine();
                    tileScale = Integer.valueOf(rLine.substring(12));
                    rLine = bRead.readLine();
                    if (rLine.substring(12).startsWith("terrain")) {
                        this.valid = true;
                        if (numTilesX < 16 | numTilesX > 4096 | numTilesZ < 16 | numTilesZ > 4096) {
                            this.valid = false;
                        }
                        if (tileSize != 256 & tileSize != 512 & tileSize != 1024 & tileSize != 2048) {
                            this.valid = false;
                        }
                        if (tileScale == 0) {
                            this.valid = false;
                        }
                    }
                    bRead.close();
                    fRead.close();
                }
            } catch (IOException ex) {
                Logger.getLogger(TerrainTiler.class.getName()).log(Level.SEVERE, "Error loading tiledTerrain.map {0}", ex);
            }
        }
        if (!this.valid) {
            Logger.getLogger(TerrainTiler.class.getName()).log(Level.SEVERE, "Cannot initialize tiledTerrain - tiledTerrain.map file does not exist at given location.");
            return;
        }
        // we have a valid mapfile - initialise the tiledterrain
        if (!this.useJars) {
            dAssetManager.registerLocator(tileLocator, FileLocator.class);
        } else {
            for (int z = 0; z < (numTilesZ / 64) + 1; z++) {
                for (int x = 0; x < (numTilesX / 64 + 1); x++) {
                    String jarFile = String.format("DIR-%02d%02d.jar", x, z);
                    tLog.log(Level.FINE, "Registering jar file: {0}{1}", new Object[]{tileLocator, jarFile});
                    dAssetManager.registerLocator(tileLocator + jarFile, ZipLocator.class);
                }
            }
        }
        // Check tile size by loading the origin one.
        TerrainQuad tq = LoadTile(0, 0);
        if (tq.getName().startsWith("OOB")) {
            tLog.log(Level.SEVERE, "TerrainTiler: Origin Tile Not Found! Aborting...");
            this.valid = false;
            return;
        }
        if (this.tileSize != (tq.getTerrainSize() - 1)) {
            tLog.log(Level.SEVERE, "TerrainTiler: Origin Tile size does not match mapfile! Aborting...");
            this.valid = false;
            return;
        }
        this.tileWSize = tileSize * tileScale;
        int pSize = tileSize / 4;

        // setup LOD system
        terrainMLOD = new MultiTerrainLodControl(camera);
        terrainMLOD.setLodCalculator(new DistanceLodCalculator(pSize + 1, 2.0f));
        this.addControl(terrainMLOD);

        // initialize our appState and attach it
        terrainState = new TerrainState();
        terrainState.initialize(app.getStateManager(), app);
        app.getStateManager().attach(terrainState);
    }
    
    /**
     * Constructor for new TerrainTiler object using a 3x3 tile grid as default
     * manual way with no map file - restricted as follows:
     *
     * @param cam - Camera to track movement for tile load/unload.
     * @param nTiles - Max number of tiles in the X & Z axis ( X==Z )
     * @param uJars - Boolean true if tiles are packed into jars
     * @param tScale - scale to apply to each tile to world size
     * @param tLocator - Tile Locator - root directory for tile subfolders. -
     * tiles loaded from <tLocator>/DIR-xxzz/TILE-xxzz.j3o - if useJars is true
     * then load tiles from <tLocator>/DIR-xxzz.jar
     * @param caller - SimpleApplication calling us to attach appState to
     */
    public TerrainTiler(Camera cam, int nTiles, boolean uJars, int tScale, String tLocator, SimpleApplication caller) {
        // Fill the local variables 
        this.setName("TerrainTilerNode");
        this.camera = cam;
        this.numTilesX = nTiles;
        this.numTilesZ = nTiles;
        this.useJars = uJars;
        this.tileScale = tScale;
        this.tileLocator = tLocator;
        this.valid = false;
        this.app = caller;
        this.gridSize = 3;              // default to 3x3 grid
        this.terrainLocked = useJars;
        this.tileSize = 128;

        dAssetManager = new DesktopAssetManager();
        dAssetManager.registerLocator("/", ClasspathLocator.class);
        dAssetManager.registerLoader(J3MLoader.class, "j3md");
        dAssetManager.registerLoader(GLSLLoader.class, "vert");
        dAssetManager.registerLoader(GLSLLoader.class, "frag");
        dAssetManager.registerLoader(GLSLLoader.class, "glsl");
        dAssetManager.registerLoader(GLSLLoader.class, "glsllib");
        dAssetManager.registerLoader(BinaryImporter.class, "j3o");
        dAssetManager.registerLoader(BitmapFontLoader.class, "fnt");
        dAssetManager.registerLoader(AWTLoader.class, "png");

        if (!this.useJars) {
            dAssetManager.registerLocator(tileLocator, FileLocator.class);
        } else {
            for (int z = 0; z < (numTilesZ / 64) + 1; z++) {
                for (int x = 0; x < (numTilesX / 64 + 1); x++) {
                    String jarFile = String.format("DIR-%02d%02d.jar", x, z);
                    tLog.log(Level.FINE, "Registering jar file: {0}{1}", new Object[]{tileLocator, jarFile});
                    dAssetManager.registerLocator(tileLocator + jarFile, ZipLocator.class);
                }
            }
        }

        // Check tile size by loading the origin one.
        TerrainQuad tq = LoadTile(0, 0);
        if (tq.getName().startsWith("OOB")) {
            tLog.log(Level.SEVERE, "TerrainTiler: Origin Tile Not Found! Aborting...");
            return;
        }
        this.tileSize = tq.getTerrainSize() - 1;
        this.tileWSize = tileSize * tileScale;
        int pSize = tileSize / 4;

        // setup LOD system
        terrainMLOD = new MultiTerrainLodControl(camera);
        terrainMLOD.setLodCalculator(new DistanceLodCalculator(pSize + 1, 2.0f));
        this.addControl(terrainMLOD);

        // initialize our appState and attach it
        terrainState = new TerrainState();
        terrainState.initialize(app.getStateManager(), app);
        app.getStateManager().attach(terrainState);
        this.valid = true;
    }

    /**
     * Loads a tile from file and returns the TerrainQuad for it.
     *
     * @param tileX - Tile X location in grid
     * @param tileZ - Tile Z location in grid
     * @return TerrainQuad of loaded tile or null if OOB or no file found
     */
    private synchronized TerrainQuad LoadTile(int tileX, int tileZ) {
        try {
            String tileName = String.format("TILE-%02d%02d.j3o", (tileX % 64), (tileZ % 64));
            String dirName = String.format("DIR-%02d%02d/", (tileX / 64), (tileZ / 64));
            tLog.log(Level.FINE, "Loading Tile: {0}{1}", new Object[]{dirName, tileName});

            ModelKey mk = new ModelKey(dirName + tileName);
            TerrainQuad tq = (TerrainQuad) dAssetManager.loadModel(mk);
            
            tq.setLocalScale(tileScale, 1f, tileScale);
            float ts = tileSize * tileScale;
            float to = ts / 2;
            tq.setLocalTranslation(new Vector3f(tileX * ts + to, 0f, tileZ * ts + to));

            // this is needed as tiles come with separate materials for each
            // patch so this makes the tile have one for all patches.
            tq.setMaterial(tq.getMaterial());

            // Debug LOD with wireframe: (if I dont do above only one patch will be wireframe!!)
            //tq.getMaterial().getAdditionalRenderState().setWireframe(true);

            tLog.log(Level.FINE, "Tile Loaded");
            dAssetManager.deleteFromCache(mk);
            return tq;
        } catch (Exception ex) {
            tLog.log(Level.WARNING, "Error loading tile!{0}", ex.getMessage());
            float[] hMap = new float[(tileSize + 1) * (tileSize + 1)];
            int pSize = tileSize / 4;
            if (tileSize <= 64) {
                pSize = tileSize;
            } else if (tileSize == 128) {
                pSize = 64;
            }
            String tileName = String.format("OOB%02d%02d%02d%02d",
                    (tileX / 64), (tileX % 64), (tileZ / 64), (tileZ % 64));
            TerrainQuad oobQuad = new TerrainQuad(tileName, pSize + 1, tileSize + 1, hMap);
            oobQuad.setLocalScale(tileScale);
            float ts = tileSize * tileScale;
            float to = ts / 2;
            oobQuad.setLocalTranslation(new Vector3f(tileX * ts + to, 0f, tileZ * ts + to));
            oobQuad.setNeighbourFinder(this);
            Material mat = new Material(dAssetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            mat.setColor("Color", ColorRGBA.Green);
            mat.getAdditionalRenderState().setWireframe(true);
            oobQuad.setMaterial(mat);
            return oobQuad;
        }
    }
    
    /**
     * return the library version
     */
    public double getVersion() {
        return version;
    }

    /**
     * Set the camera we will track for loading tiles
     *
     * @param cam - Camera to track
     */
    public void setCamera(Camera cam) {
        this.camera = cam;
    }

    /**
     * Gets the currently set root directory for finding the Tiles
     *
     * @return String containing the tile root directory
     */
    public String getTileLocator() {
        return this.tileLocator;
    }

    /**
     * Sets the root directory in which to find the Tiles. Do not use while
     * setEnabled(true)!
     *
     * @param directory - String with the root directory
     */
    public void setTileLocator(String directory) {
        dAssetManager.unregisterLocator(this.tileLocator, FileLocator.class);
        this.tileLocator = directory;
        dAssetManager.registerLocator(this.tileLocator, FileLocator.class);
    }

    /**
     * Sets the size of the viewable grid Must be 3 or 5 or 7 or 9!
     *
     * @param size - int of size
     */
    public void setGridSize(int size) {
        if (size == 3 | size == 5 | size == 7 | size == 9) {
            this.gridSize = size;
        }
    }

    /**
     * Get the current size of the viewable grid
     *
     * @return int of gridSize
     */
    public int getGridSize() {
        return gridSize;
    }

    /**
     * Set the max number of terrain tiles to use in the X direction <16...4096>
     * Do not set to less than current location or player will be plunged 
     * into the void!
     * 
     * @param number
     */
    public void setNumTilesX(int number) {
        if (number > 4 & (number & (number - 1)) == 0) {
            this.numTilesX = number;
        }
    }

    /**
     * Get the current max number of tiles in the X direction
     *
     * @return int number of tiles
     */
    public int getNumTilesX() {
        return numTilesX;
    }

    /**
     * Set the max number of terrain tiles to use in the Z direction <16...4096>
     * Do not set to less than current location or player will be plunged 
     * into the void!
     * 
     * @param number
     */
    public void setNumTilesZ(int number) {
        if (number > 4 & (number & (number - 1)) == 0) {
            this.numTilesZ = number;
        }
    }

    /**
     * Get the current max number of tiles in the Z direction
     *
     * @return int number of tiles
     */
    public int getNumTilesZ() {
        return numTilesZ;
    }

    /**
     * Get the current tile size (unscaled)
     *
     * @return - int of size
     */
    public int getTileSize() {
        return tileSize;
    }

    /**
     * Sets the scale to apply to each tile Do not use while setEnabled(true)!
     *
     * @param scale - int of scale factor
     */
    public void setTileScale(int scale) {
        if (scale > 0) {
            this.tileScale = scale;
            this.tileWSize = this.tileSize * this.tileScale;
        }
    }

    /**
     * Get the current tile scaling factor
     *
     * @return - int of scale
     */
    public int getTileScale() {
        return tileScale;
    }

    /**
     * Enable the terrainState and run the tiler thread
     *
     * @param state - true to enable and start, false to disable and stop
     */
    public void setEnabled(boolean state) {
        terrainState.setEnabled(state);
    }
    
    /**
     * Get the terrainState isEnabled state
     * 
     * @return true|false
     */
    public boolean getEnabled() {
        return terrainState.isEnabled();
    }

    /**
     * Adds a TerrainTilerAction Handler for parent program to handle events
     * when new tiles and loaded or old tiles unloaded from the scene.
     *
     * @param handler TerrainTilerAction to add to action handlers
     */
    public void addActionHandler(TerrainTilerAction handler) {
        actionHooks.add(handler);
    }

    /**
     * Removes a TerrainTilerAction handler from the list
     *
     * @param handler TerrainTilerAction to remove
     */
    public void delActionHandler(TerrainTilerAction handler) {
        actionHooks.remove(handler);
    }

    /**
     * Get the interpolated height at the specified point
     *
     * @param xz = Vector2f of World Coordinate
     * @return float of the height or NaN if tile not loaded
     */
    @Override
    public float getHeight(Vector2f xz) {
        // work out which tile it is
        int tx = (int) xz.x / tileWSize;
        int tz = (int) xz.y / tileWSize;
        int tk = tx + (tz * maxTiles);
        if (tileSet.containsKey(tk)) {
            return tileSet.get(tk).getHeight(xz);
        } else {
            return Float.NaN;
        }
    }

    /**
     * Return the normal for the specified point
     *
     * @param xz = Vector2f of World Coordinate
     * @return Vector3f or the normal or .ZERO if tile not loaded
     */
    @Override
    public Vector3f getNormal(Vector2f xz) {
        // work out which tile it is
        int tx = (int) xz.x / tileWSize;
        int tz = (int) xz.y / tileWSize;
        int tk = tx + (tz * maxTiles);
        if (tileSet.containsKey(tk)) {
            return tileSet.get(tk).getNormal(xz);
        } else {
            return Vector3f.ZERO;
        }
    }

    /**
     * Get the unscaled HeightMap height at the specified point snapped to
     * nearest grid point
     *
     * @param xz = Vector2f of World Coordinate
     * @return float of the height or NaN if tile not loaded
     */
    @Override
    public float getHeightmapHeight(Vector2f xz) {
        // work out which tile it is
        int tx = (int) xz.x / tileWSize;
        int tz = (int) xz.y / tileWSize;
        int tk = tx + (tz * maxTiles);
        if (tileSet.containsKey(tk)) {
            return tileSet.get(tk).getHeightmapHeight(xz);
        } else {
            return Float.NaN;
        }
    }

    /**
     * Set the terrain Height at the specified World coordinate
     *
     * @param xzCoordinate - Vector2f World Coordinate x,z
     * @param height - float of new height
     */
    @Override
    public void setHeight(Vector2f xzCoordinate, float height) {
        if (!terrainLocked) {
            int tx = (int) xzCoordinate.x / tileWSize;
            int tz = (int) xzCoordinate.y / tileWSize;
            int tk = tx + (tz * maxTiles);
            if (tileSet.containsKey(tk)) {
                tileSet.get(tk).setHeight(xzCoordinate, height);
            }
        }
    }

    /**
     * Set the height of many points
     *
     * @param xz - List<Vector2f> of World Coordinates x,z
     * @param height - List<Float> matching list of height values
     */
    @Override
    public void setHeight(List<Vector2f> xz, List<Float> height) {
        if (!terrainLocked) {
            Iterator it = tileFlag.keySet().iterator();
            while (it.hasNext()) {
                int ik = (Integer) it.next();
                if (tileFlag.get(ik) == 3) {
                    tileSet.get(ik).setHeight(xz, height);
                }
            }
        }
    }

    /**
     * Adjust the height of a point up or down bu the amount specified
     *
     * @param xzCoordinate - Vector2f of the World Coordinate x,z
     * @param delta - float to adjust the point by +/-
     */
    @Override
    public void adjustHeight(Vector2f xzCoordinate, float delta) {
        if (!terrainLocked) {
            int tx = (int) xzCoordinate.x / tileWSize;
            int tz = (int) xzCoordinate.y / tileWSize;
            int tk = tx + (tz * maxTiles);
            if (tileSet.containsKey(tk)) {
                tileSet.get(tk).adjustHeight(xzCoordinate, delta);
            }
        }
    }

    /**
     * Adjust the height of many points
     *
     * @param xz - List<Vector2f> of World Coordinates x,z
     * @param height - List<Float> matching list of height adjustment values
     */
    @Override
    public void adjustHeight(List<Vector2f> xz, List<Float> height) {
        if (!terrainLocked) {
            Iterator it = tileFlag.keySet().iterator();
            while (it.hasNext()) {
                int ik = (Integer) it.next();
                if (tileFlag.get(ik) == 3) {
                    tileSet.get(ik).adjustHeight(xz, height);
                }
            }
        }
    }

    /**
     * Gets the heightmap of the terrain tile at the center of the current grid
     *
     * @return float array of the heightmap
     */
    @Override
    public float[] getHeightMap() {
        int tk = gridCenterX + (gridCenterZ * maxTiles);
        if (tileSet.containsKey(tk)) {
            return tileSet.get(tk).getHeightMap();
        } else {
            return null;
        }
    }

    /**
     * Get the height map from the tile at the location in world coordinates.
     *
     * @param worldLocation in world coordinates
     * @return float array of heightmap for tile at location
     */
    public float[] getHeightMap(Vector3f worldLocation) {
        // work out which tile it is
        int tx = (int) worldLocation.x / tileWSize;
        int tz = (int) worldLocation.z / tileWSize;
        int tk = tx + (tz * maxTiles);
        if (tileSet.containsKey(tk)) {
            return tileSet.get(tk).getHeightMap();
        } else {
            return null;
        }
    }

    /**
     * Get the maximum LOD of the center terrain tile
     *
     * @return int of the current setting.
     */
    @Override
    public int getMaxLod() {
        int tk = gridCenterX + (gridCenterZ * maxTiles);
        if (tileSet.containsKey(tk)) {
            return tileSet.get(tk).getMaxLod();
        } else {
            return 1;
        }
    }

    /**
     * Set locking of terrain changes
     *
     * @param locked - true to lock changes, false to allow. Note that if loading
     * tiles from jars then terrain will always be locked
     */
    @Override
    public void setLocked(boolean locked) {
        if (!useJars) {
            terrainLocked = locked;
        }
    }
    
    /**
     * Check if terrain is locked from changes
     * @return boolean
     */
    public boolean isLocked() {
        return terrainLocked;
    }

    /**
     * Generate entropies
     *
     * @param monitor ProgressMonitor
     */
    @Override
    public void generateEntropy(ProgressMonitor monitor) {
        if (!terrainLocked) {
            Iterator it = tileFlag.keySet().iterator();
            while (it.hasNext()) {
                int ik = (Integer) it.next();
                if (tileFlag.get(ik) == 3) {
                    tileSet.get(ik).generateEntropy(monitor);
                }
            }
        }
    }

    /**
     * Get the material that the current center tile uses
     *
     * @return Material of centered tile or null if no tile loaded
     */
    @Override
    public Material getMaterial() {
        int tk = gridCenterX + (gridCenterZ * maxTiles);
        if (tileSet.containsKey(tk)) {
            return tileSet.get(tk).getMaterial();
        } else {
            return null;
        }
    }

    /**
     * Get the material of the terrain at the specified World location
     *
     * @param worldLocation - Vector3f of the World Coordinate x,y,z
     * @return Material of tile at that location or null if no tile loaded
     * there.
     */
    @Override
    public Material getMaterial(Vector3f worldLocation) {
        int tx = (int) worldLocation.x / tileWSize;
        int tz = (int) worldLocation.y / tileWSize;
        int tk = tx + (tz * maxTiles);
        if (tileSet.containsKey(tk)) {
            return tileSet.get(tk).getMaterial();
        } else {
            return null;
        }
    }

    /**
     * Sets the material for the TerrainQuad tile at the given world location
     * provided that tile has been loaded.
     *
     * @param worldLocation of tile to set
     * @param mat material to place on tile
     */
    public void setMaterial(Vector3f worldLocation, Material mat) {
        int tx = (int) worldLocation.x / tileWSize;
        int tz = (int) worldLocation.y / tileWSize;
        int tk = tx + (tz * maxTiles);
        if (tileSet.containsKey(tk)) {
            tileSet.get(tk).setMaterial(mat);
        }
    }

    /**
     * Get the terrain size - number of vertices along the side of one tile
     *
     * @return int of terrain size
     */
    @Override
    public int getTerrainSize() {
        return tileSize;
    }

    /**
     *
     * @return 1
     */
    @Override
    public int getNumMajorSubdivisions() {
        return 1;
    }

    /*
     * For NeigborFinder LOD routines
     * gets the quad to centers right (+x)
     * 
     * @param center TerrainQuad
     * @return TerrainQuad to the right
     */
    @Override
    public TerrainQuad getRightQuad(TerrainQuad center) {
        int tx = (int) center.getLocalTranslation().x / tileWSize;
        int tz = (int) center.getLocalTranslation().z / tileWSize;
        if (tx < numTilesX - 1) {
            tx++;
            int tk = tx + (tz * maxTiles);
            if (tileSet.containsKey(tk)) {
                return tileSet.get(tk);
            }
        }
        return null;
    }

    /**
     * For NeigborFinder LOD routines gets the quad to centers left (-x)
     *
     * @param center TerrainQuad
     * @return TerrainQuad to the left
     */
    @Override
    public TerrainQuad getLeftQuad(TerrainQuad center) {
        int tx = (int) center.getLocalTranslation().x / tileWSize;
        int tz = (int) center.getLocalTranslation().z / tileWSize;
        if (tx > 0) {
            tx--;
            int tk = tx + (tz * maxTiles);
            if (tileSet.containsKey(tk)) {
                return tileSet.get(tk);
            }
        }
        return null;
    }

    /**
     * For NeigborFinder LOD routines gets the quad to centers down (+z)
     *
     * @param center TerrainQuad
     * @return TerrainQuad down
     */
    @Override
    public TerrainQuad getDownQuad(TerrainQuad center) {
        int tx = (int) center.getLocalTranslation().x / tileWSize;
        int tz = (int) center.getLocalTranslation().z / tileWSize;
        if (tz < numTilesZ - 1) {
            tz++;
            int tk = tx + (tz * maxTiles);
            if (tileSet.containsKey(tk)) {
                return tileSet.get(tk);
            }
        }
        return null;
    }

    /**
     * For NeigborFinder LOD routines gets the quad to centers top (-z)
     *
     * @param center TerrainQuad
     * @return TerrainQuad top
     */
    @Override
    public TerrainQuad getTopQuad(TerrainQuad center) {
        int tx = (int) center.getLocalTranslation().x / tileWSize;
        int tz = (int) center.getLocalTranslation().z / tileWSize;
        if (tz > 0) {
            tz--;
            int tk = tx + (tz * maxTiles);
            if (tileSet.containsKey(tk)) {
                return tileSet.get(tk);
            }
        }
        return null;
    }

    private class terrainThread extends Thread {

        private boolean firstRun;
        /*
         * terrainThread constructor
         */

        terrainThread() {
            this.setName("Terrain Tiler Thread");
            gridCenterX = (int) camera.getLocation().x / tileWSize;
            gridCenterZ = (int) camera.getLocation().z / tileWSize;
            tileSet = new ConcurrentHashMap<>(gridSize * gridSize);
            tileFlag = new ConcurrentHashMap<>(gridSize * gridSize);
            firstRun = true;
        }

        @Override
        public void run() {
            threadState = true;
            tLog.log(Level.FINE, "Terrain Thread Started\n");
            while (threadState) {
                int cx = (int) camera.getLocation().x / tileWSize;
                int cz = (int) camera.getLocation().z / tileWSize;
                long time = System.nanoTime();
                if (firstRun | cx != gridCenterX | cz != gridCenterZ) {            // check if camera changed cells
                    gridCenterX = cx;
                    gridCenterZ = cz;
                    // iterrate tileSet to see if any tiles are now outside range
                    Iterator it = tileFlag.keySet().iterator();
                    while (it.hasNext()) {
                        int key = (Integer) it.next();
                        int kx = key % maxTiles;
                        int kz = key / maxTiles;
                        if (kx < (gridCenterX - (gridSize / 2)) | kx > (gridCenterX + (gridSize / 2))) {
                            tileFlag.replace(key, 4);
                            newTile = true;
                        }
                        if (kz < (gridCenterZ - (gridSize / 2)) | kz > (gridCenterZ + (gridSize / 2))) {
                            tileFlag.replace(key, 4);
                            newTile = true;
                        }
                    }
                    // Scan grid area for missing tiles
                    for (int z = 0; z < gridSize; z++) {
                        for (int x = 0; x < gridSize; x++) {
                            int tx = x - (gridSize / 2) + gridCenterX;  // get tile absolute position
                            int tz = z - (gridSize / 2) + gridCenterZ;
                            if (tx >= 0 & tx < numTilesX & tz >= 0 & tz < numTilesZ) {    // OOB check
                                int tk = tx + (tz * maxTiles);
                                if (!tileSet.containsKey(tk)) {         // no tile loaded so...
                                    tileSet.put(tk, LoadTile(tx, tz));  // Load it
                                    tileFlag.put(tk, 2);                // Flag for attaching
                                    newTile = true;
                                }
                            }
                        }
                    }
                    firstRun = false;
                }/*
                time = System.nanoTime() - time;
                time /= 1000000;
                if (time > 90) {
                    time = 90;
                }
                try {
                    sleep(100 - time);
                } catch (InterruptedException ex) {
                    tLog.log(Level.SEVERE, null, ex);
                    threadState = false;
                }*/
            }
            // thread has been stopped so flag all tiles for removal
            Iterator it = tileFlag.keySet().iterator();
            while (it.hasNext()) {
                int tk = (Integer) it.next();
                tileFlag.replace(tk, 4);
            }
        }
    }

    private class TerrainState implements AppState {

        private boolean isInit = false;
        private boolean isEnable = false;

        @Override
        public void initialize(AppStateManager stateManager, Application app) {
            // initialize terrainThread
            if (!isInit) {
                threadState = false;
                tThread = new terrainThread();
                isEnable = false;
                isInit = true;
            }
        }

        @Override
        public boolean isInitialized() {
            return isInit;
        }

        @Override
        public void setEnabled(boolean state) {
            if (!isEnable & state) {
                tThread.start();
                isEnable = true;
            } else if (isEnable & !state) {
                threadState = false;
                // detach and remove all tiles
                Iterator it = tileSet.keySet().iterator();
                while (it.hasNext()) {
                    int key = (Integer) it.next();
                    terrainMLOD.removeTerrain(tileSet.get(key));
                    tileSet.get(key).setNeighbourFinder(null);
                    TerrainTiler.this.detachChild(tileSet.get(key));
                    tileSet.get(key).detachAllChildren();
                    tileSet.remove(key);
                    tileFlag.remove(key);
                }
                tileSet.clear();
                tileFlag.clear();
                while (tThread.isAlive()){
                    // wait for thread to stop.
                }
                TerrainTiler.this.detachAllChildren();
                isEnable = false;
            }
        }

        @Override
        public boolean isEnabled() {
            return isEnable;
        }

        @Override
        public void stateAttached(AppStateManager stateManager) {
            // do stuff when attached
        }

        @Override
        public void stateDetached(AppStateManager stateManager) {
            // do stuff when detached
        }

        @Override
        public void update(float tpf) {
            if (isEnable & newTile) {
                // iterrate list and check if needs attaching or removing.
                Iterator it = tileFlag.keySet().iterator();
                boolean once = true;    // only one remove/attach per update.
                while (it.hasNext() & once) {
                    int key = (Integer) it.next();
                    if (tileFlag.get(key) == 2) {
                        // flagged to attach
                        TerrainTiler.this.attachChild(tileSet.get(key));
                        tileSet.get(key).setNeighbourFinder(TerrainTiler.this);
                        terrainMLOD.addTerrain(tileSet.get(key));
                        // run the actionHooks.tileAttached
                        for (TerrainTilerAction hooks : actionHooks) {
                            hooks.tileAttached(tileSet.get(key).getLocalTranslation(), tileSet.get(key));
                        }
                        tileFlag.replace(key, 3);
                        terrainMLOD.forceUpdate();
                        once = false;
                    } else if (tileFlag.get(key) == 4) {
                        // flagged for removal
                        terrainMLOD.removeTerrain(tileSet.get(key));
                        tileSet.get(key).setNeighbourFinder(null);
                        TerrainTiler.this.detachChild(tileSet.get(key));
                        // run the actionHooks.tileDetached
                        for (TerrainTilerAction hooks : actionHooks) {
                            hooks.tileDetached(tileSet.get(key).getLocalTranslation(), tileSet.get(key));
                        }
                        tileFlag.replace(key, 5); // Flag for deletion
                        once = false;
                    } else if (tileFlag.get(key) == 5) {
                        // flagged for deletion
                        tileSet.remove(key);
                        tileFlag.remove(key);
                        once = false;
                    }
                }
                if (!once) {    // new tile attached/removed so reset neighbor caches
                    it = tileFlag.keySet().iterator();
                    while (it.hasNext()) {
                        int key = (Integer) it.next();
                        if (tileFlag.get(key) == 3) {
                            tileSet.get(key).resetCachedNeighbours();
                        }
                    }
                } else {
                    // nothing changed so no new tiles to load
                    newTile = false;
                }
            }
        }

        @Override
        public void render(RenderManager rm) {
            
        }

        @Override
        public void postRender() {
        }

        @Override
        public void cleanup() {
            setEnabled(false);
            isInit = false;
        }
    }
}
