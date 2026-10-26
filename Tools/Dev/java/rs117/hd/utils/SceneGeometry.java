package rs117.hd.utils;

import lombok.RequiredArgsConstructor;
import net.runelite.api.*;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.model.Jarvis;
import rs117.hd.scene.GamevalManager;
import rs117.hd.scene.lights.Light;
import rs117.hd.utils.Util.Ray;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.awt.geom.Point2D;
import java.util.*;
import java.util.List;

import static net.runelite.api.Perspective.*;
import static rs117.hd.utils.HDUtils.HIDDEN_HSL;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.Util.rayTriangle;

@Singleton
public final class SceneGeometry {

    @Inject
    private Client client;

    private final Map<Scene, StaticSceneCache> staticSceneCaches = new IdentityHashMap<>();
    private final Map<Scene, RoofVisibility> roofVisibilityByScene = new IdentityHashMap<>();

    public void setRoofRemovalSettings(Scene scene, int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds) {
        if (scene == null) return;
        roofVisibilityByScene.keySet().removeIf(cachedScene ->
                cachedScene != scene && cachedScene.getWorldViewId() == scene.getWorldViewId());
        roofVisibilityByScene.put(scene,
                new RoofVisibility(minLevel, level, maxLevel, hideRoofIds == null ? Set.of() : hideRoofIds));
    }

    public void clearRoofRemovalSettings() {
        roofVisibilityByScene.clear();
    }

    public void removeRoofRemovalSettings(Scene scene) {
        if (scene != null) roofVisibilityByScene.remove(scene);
    }

    public boolean getHovering(WorldView rootWorldView, Point mouse, Point2D cursor, List<Hit> hits) {
        int originalSize = hits.size();
        Ray mainSceneRay = createMouseRay(mouse);
        if (mainSceneRay == null || rootWorldView == null) {
            sortHits(hits);
            return false;
        }

        List<WorldView> worldViews = getWorldViews(rootWorldView);
        pruneStaticSceneCaches(worldViews);
        for (WorldView worldView : worldViews) {
            Scene scene = worldView.getScene();
            if (scene == null) continue;
            Ray ray = transformRayToWorldView(mainSceneRay, worldView);
            getObjectAtCursor(worldView, scene, cursor, ray, hits);
            getTileAtCursor(worldView, scene, cursor, ray, hits);
        }
        sortHits(hits);
        return hits.size() > originalSize;
    }

    public boolean getObjectAtCursor(Scene scene, Point mouse, Point2D cursor, List<Hit> hits) {
        Ray ray = createMouseRay(mouse);
        if (ray == null) {
            sortHits(hits);
            return false;
        }
        WorldView worldView = client.getWorldView(scene.getWorldViewId());
        boolean hit = worldView != null && getObjectAtCursor(worldView, scene, cursor,
                transformRayToWorldView(ray, worldView), hits);
        sortHits(hits);
        return hit;
    }

    private boolean getObjectAtCursor(WorldView worldView, Scene scene, Point2D cursor, Ray ray, List<Hit> hits) {
        Tile[][][] tiles = scene.getExtendedTiles();
        List<Candidate> staticCandidates = getStaticCandidates(worldView, scene, tiles);
        List<Candidate> objectHits = new ArrayList<>();
        for (Candidate candidate : staticCandidates) {
            int sceneOffset = worldView.isTopLevel() ? HDUtils.EXTENDED_SCENE_OFFSET : 0;
            int sceneX = candidate.sceneX - sceneOffset;
            int sceneY = candidate.sceneY - sceneOffset;
            if (isTileVisibleByRoofRemoval(scene, candidate.scenePlane, sceneX, sceneY)) {
                addIntersection(candidate.object, candidate.bounds, ray, worldView,
                        candidate.scenePlane, candidate.sceneX, candidate.sceneY, objectHits);
            }
        }
        for (NPC npc : worldView.npcs()) addDynamicCandidate(npc, worldView, ray, objectHits);
        for (Player player : worldView.players()) addDynamicCandidate(player, worldView, ray, objectHits);
        for (GraphicsObject object : worldView.getGraphicsObjects()) addDynamicCandidate(object, worldView, ray, objectHits);

        if(worldView == client.getTopLevelWorldView()) {
            for (Projectile projectile : client.getProjectiles())
                addDynamicCandidate(projectile, worldView, ray, objectHits);
        }

        int originalSize = hits.size();
        for (Candidate candidate : objectHits) {
            Shape hull = convexHull(candidate.object, candidate.worldView);
            if (hull != null && hull.contains(cursor)) hits.add(new Hit(candidate));
        }
        return hits.size() > originalSize;
    }

    public boolean getTileAtCursor(Scene scene, Point mouse, Point2D cursor, List<Hit> hits) {
        Ray ray = createMouseRay(mouse);
        if (ray == null) {
            sortHits(hits);
            return false;
        }
        WorldView worldView = client.getWorldView(scene.getWorldViewId());
        boolean hit = worldView != null && getTileAtCursor(worldView, scene, cursor,
                transformRayToWorldView(ray, worldView), hits);
        sortHits(hits);
        return hit;
    }

    private boolean getTileAtCursor(WorldView worldView, Scene scene, Point2D cursor, Ray ray, List<Hit> hits) {
        if (abs(ray.dz) < 1e-6f) return false;

        int originalSize = hits.size();
        Tile[][][] tiles = scene.getExtendedTiles();
        int sceneOffset = worldView.isTopLevel() ? HDUtils.EXTENDED_SCENE_OFFSET : 0;
        int tileSize = LOCAL_TILE_SIZE;
        for (int plane = tiles.length - 1; plane >= 0; plane--) {
            for (int bridgePass = 1; bridgePass >= 0; bridgePass--) {
                for (int x = 0; x < tiles[plane].length; x++) {
                    for (int y = 0; y < tiles[plane][x].length; y++) {
                        Tile sceneTile = tiles[plane][x][y];
                        if (sceneTile == null) continue;
                        Tile tile = bridgePass == 1 ? sceneTile.getBridge() : sceneTile;
                        if (tile == null || !isVisible(tile) ||
                                !isTileVisibleByRoofRemoval(scene, plane, x - sceneOffset, y - sceneOffset)) continue;

                        int tx = (x - sceneOffset) * tileSize;
                        int ty = (y - sceneOffset) * tileSize;
                        float h00 = worldView.getTileHeight(tx, ty, plane);
                        float h10 = worldView.getTileHeight(tx + tileSize, ty, plane);
                        float h11 = worldView.getTileHeight(tx + tileSize, ty + tileSize, plane);
                        float h01 = worldView.getTileHeight(tx, ty + tileSize, plane);

                        float t = rayTriangle(ray, tx, ty, h00, tx + tileSize, ty, h10, tx + tileSize, ty + tileSize, h11, Float.POSITIVE_INFINITY);
                        if (t < 0) t = rayTriangle(ray, tx, ty, h00, tx + tileSize, ty + tileSize, h11, tx, ty + tileSize, h01, Float.POSITIVE_INFINITY);
                        if (t >= 0) hits.add(new Hit(new Candidate(tile, null, t, worldView, plane, x, y)));
                    }
                }
            }
        }
        return hits.size() > originalSize;
    }

    private void sortHits(List<Hit> hits) {
        hits.sort((first, second) -> Float.compare(first.candidate.distance, second.candidate.distance));
    }

    public static final class Hit {
        private final Candidate candidate;
        private final String displayName;

        private Hit(Candidate candidate) {
            this(candidate, candidate.name);
        }

        private Hit(Candidate candidate, String displayName) {
            this.candidate = candidate;
            this.displayName = displayName;
        }

        public static Hit forLight(Light light) {
            String description = light.def.description;
            String name = description == null || description.isBlank() ? "Light" : "Light: " + description;
            return new Hit(new Candidate(light, null, 0, null, 0, 0, 0), name);
        }

        public Object getObject() { return candidate.object; }

        public String getDisplayName(GamevalManager.Handle gamevals) {
            if (candidate.object instanceof Light) return displayName;
            if (gamevals == null) return toString();

            String name = null;
            if (candidate.object instanceof TileObject) name = gamevals.getObjectName(candidate.id);
            else if (candidate.object instanceof NPC) name = gamevals.getNpcName(candidate.id);
            else if (candidate.object instanceof GraphicsObject || candidate.object instanceof Projectile)
                name = gamevals.getSpotanimName(candidate.id);
            else if (candidate.object instanceof Player) name = candidate.name;

            return name == null ? candidate.name : name;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private boolean isVisible(Tile tile) {
        SceneTilePaint paint = tile.getSceneTilePaint();
        return (paint != null && paint.getNeColor() != HIDDEN_HSL) || tile.getSceneTileModel() != null;
    }

    private boolean isTileVisibleByRoofRemoval(Scene scene, int plane, int x, int y) {
        RoofVisibility visibility = roofVisibilityByScene.get(scene);
        if (visibility == null || plane < 0) return true;
        if (plane < visibility.minLevel || plane > visibility.maxLevel) return false;
        if (visibility.hideRoofIds.isEmpty() || plane <= visibility.level) return true;

        int sceneOffset = scene.getWorldViewId() == WorldView.TOPLEVEL ? HDUtils.EXTENDED_SCENE_OFFSET : 0;
        int extendedX = x + sceneOffset;
        int extendedY = y + sceneOffset;
        byte[][][] tileSettings = scene.getExtendedTileSettings();
        int mapLevel = plane;
        if (!isInBounds(tileSettings, 1, extendedX, extendedY)) return true;
        if ((tileSettings[1][extendedX][extendedY] & Constants.TILE_FLAG_BRIDGE) != 0) mapLevel++;
        if (!isInBounds(tileSettings, mapLevel, extendedX, extendedY)) return true;

        boolean visibleBelow = mapLevel <= 3 &&
                (tileSettings[mapLevel][extendedX][extendedY] & Constants.TILE_FLAG_VIS_BELOW) != 0;
        if (visibleBelow || mapLevel == 0) return true;

        int roofLevel = mapLevel - 1;
        int[][][] roofs = scene.getRoofs();
        if (roofs == null || roofLevel < 0 || roofLevel >= roofs.length || roofs[roofLevel] == null ||
                extendedX < 0 || extendedY < 0 || extendedX >= roofs[roofLevel].length ||
                roofs[roofLevel][extendedX] == null || extendedY >= roofs[roofLevel][extendedX].length) return true;
        int roofId = roofs[roofLevel][extendedX][extendedY];
        return roofId <= 0 || !visibility.hideRoofIds.contains(roofId);
    }

    private static boolean isInBounds(byte[][][] values, int plane, int x, int y) {
        return values != null && plane >= 0 && plane < values.length && values[plane] != null &&
                x >= 0 && x < values[plane].length && values[plane][x] != null &&
                y >= 0 && y < values[plane][x].length;
    }

    public Shape getConvexHull(Projectile projectile) {
        Model model = projectile.getModel();
        if (model == null || model.getVerticesCount() < 3) return null;
        WorldView worldView = client.getTopLevelWorldView();
        int n = model.getVerticesCount();
        int[] x = new int[n], y = new int[n];
        modelToCanvas(client, worldView, n,
                (int) projectile.getX(), (int) projectile.getY(), (int) projectile.getZ(), 0,
                model.getVerticesX(), model.getVerticesZ(), model.getVerticesY(), x, y);
        return Jarvis.convexHull(x, y);
    }

    public void reset() {
        staticSceneCaches.clear();
    }

    private List<Candidate> getStaticCandidates(WorldView worldView, Scene scene, Tile[][][] tiles) {
        StaticSceneCache cache = staticSceneCaches.get(scene);
        if (cache != null && cache.tiles == tiles) return cache.candidates;

        List<Candidate> candidates = new ArrayList<>();
        Set<TileObject> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int plane = 0; plane < tiles.length; plane++)
            for (int x = 0; x < tiles[plane].length; x++)
                for (int y = 0; y < tiles[plane][x].length; y++) {
                    Tile tile = tiles[plane][x][y];
                    if (tile == null) continue;
                    addStaticTile(tile, candidates, seen, worldView, plane, x, y);
                    if (tile.getBridge() != null)
                        addStaticTile(tile.getBridge(), candidates, seen, worldView, plane, x, y);
                }
        cache = new StaticSceneCache(tiles, candidates);
        staticSceneCaches.put(scene, cache);
        return candidates;
    }

    private void pruneStaticSceneCaches(List<WorldView> worldViews) {
        Set<Scene> activeScenes = Collections.newSetFromMap(new IdentityHashMap<>());
        for (WorldView worldView : worldViews) {
            Scene scene = worldView.getScene();
            if (scene != null) activeScenes.add(scene);
        }
        staticSceneCaches.keySet().removeIf(scene -> !activeScenes.contains(scene));
    }

    private List<WorldView> getWorldViews(WorldView root) {
        List<WorldView> worldViews = new ArrayList<>();
        Set<WorldView> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        collectWorldViews(root, seen, worldViews);
        return worldViews;
    }

    private void collectWorldViews(WorldView worldView, Set<WorldView> seen, List<WorldView> result) {
        if (worldView == null || !seen.add(worldView)) return;
        result.add(worldView);
        for (WorldView subWorldView : worldView.worldViews())
            collectWorldViews(subWorldView, seen, result);
    }

    private void addStaticTile(Tile tile, List<Candidate> candidates, Set<TileObject> seen,
            WorldView worldView, int plane, int x, int y) {
        GameObject[] gameObjects = tile.getGameObjects();
        if (gameObjects != null)
            for (GameObject object : gameObjects)
                if (object != null) addStaticCandidate(object, candidates, seen, worldView, plane, x, y);
        if (tile.getWallObject() != null)
            addStaticCandidate(tile.getWallObject(), candidates, seen, worldView, plane, x, y);
        if (tile.getDecorativeObject() != null)
            addStaticCandidate(tile.getDecorativeObject(), candidates, seen, worldView, plane, x, y);
        if (tile.getGroundObject() != null)
            addStaticCandidate(tile.getGroundObject(), candidates, seen, worldView, plane, x, y);
    }

    private void addStaticCandidate(TileObject object, List<Candidate> candidates, Set<TileObject> seen,
            WorldView worldView, int plane, int x, int y) {
        if (!seen.add(object)) return;
        Model model = Util.getModel(Util.getRenderable(object));
        Bounds bounds = modelBounds(model, object.getX(), object.getY(), object.getZ());
        if (bounds == null) bounds = new Bounds(object.getX() - 128, object.getY() - 128, object.getZ() - 256,
                object.getX() + 128, object.getY() + 128, object.getZ() + 128);
        candidates.add(new Candidate(object, bounds, worldView, plane, x, y));
    }

    private void addDynamicCandidate(Object object, WorldView worldView, Ray ray, List<Candidate> candidates) {
        Model model;
        int x, y, z;
        if (object instanceof Actor) {
            Actor actor = (Actor) object;
            LocalPoint location = actor.getLocalLocation();
            if (location == null || actor.getModel() == null) return;
            model = actor.getModel();
            x = location.getX();
            y = location.getY();
            z = getTileHeight(client, location, actor.getWorldView().getPlane());
        } else if (object instanceof GraphicsObject) {
            GraphicsObject graphics = (GraphicsObject) object;
            model = graphics.getModel();
            if (model == null) return;
            x = graphics.getLocation().getX();
            y = graphics.getLocation().getY();
            z = graphics.getZ();
        } else if (object instanceof Projectile) {
            Projectile projectile = (Projectile) object;
            model = projectile.getModel();
            if (model == null) return;
            x = (int) projectile.getX();
            y = (int) projectile.getY();
            z = (int) projectile.getZ();
        } else return;
        Bounds bounds = modelBounds(model, x, y, z);
        if (bounds != null) addIntersection(object, bounds, ray, worldView, -1, -1, -1, candidates);
    }

    private void addIntersection(Object object, Bounds bounds, Ray ray, WorldView worldView,
            int plane, int sceneX, int sceneY, List<Candidate> candidates) {
        float distance = Util.rayIntersectAABB(ray, bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
        if (distance >= 0) candidates.add(new Candidate(object, bounds, distance, worldView, plane, sceneX, sceneY));
    }

    private Bounds modelBounds(Model model, float x, float y, float z) {
        if (model == null || model.getVerticesCount() == 0) return null;
        float[] verticesX = model.getVerticesX();
        float[] verticesY = model.getVerticesY();
        float[] verticesZ = model.getVerticesZ();
        float radiusSquared = 0;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < model.getVerticesCount(); i++) {
            radiusSquared = max(radiusSquared, verticesX[i] * verticesX[i] + verticesZ[i] * verticesZ[i]);
            minZ = min(minZ, z + verticesY[i]);
            maxZ = max(maxZ, z + verticesY[i]);
        }
        int radius = ceil(sqrt(radiusSquared)) + 1;
        return new Bounds(x - radius, y - radius, minZ - 1, x + radius, y + radius, maxZ + 1);
    }

    private Ray createMouseRay(Point mouse) {
        float screenX = (mouse.getX() - client.getViewportXOffset() - client.getViewportWidth() / 2f) / client.getScale();
        float screenY = (mouse.getY() - client.getViewportYOffset() - client.getViewportHeight() / 2f) / client.getScale();
        float pitch = client.getCameraPitch() * JAU_FINE_TO_RAD;
        float yaw = client.getCameraYaw() * JAU_FINE_TO_RAD;
        float sinPitch = sin(pitch), cosPitch = cos(pitch);
        float sinYaw = sin(yaw), cosYaw = cos(yaw);
        // Invert RuneLite's camera-space projection to obtain the cursor's world ray.
        float cameraY = cosPitch - screenY * sinPitch;
        float cameraZ = sinPitch + screenY * cosPitch;
        float dirX = screenX * cosYaw - cameraY * sinYaw;
        float dirY = screenX * sinYaw + cameraY * cosYaw;
        float length = sqrt(dirX * dirX + dirY * dirY + cameraZ * cameraZ);
        if (length == 0) return null;
        return new Ray(client.getCameraX(), client.getCameraY(), client.getCameraZ(),
                dirX / length, dirY / length, cameraZ / length);
    }

    private Ray transformRayToWorldView(Ray mainSceneRay, WorldView worldView) {
        if (worldView.isTopLevel()) return mainSceneRay;
        Projection projection = worldView.getMainWorldProjection();
        if (!(projection instanceof FloatProjection)) return mainSceneRay;

        float[] inverseProjection = Mat4.inverse(((FloatProjection) projection).getProjection());
        // Projection coordinates are (x, height, y), while scene geometry uses (x, y, height).
        float[] origin = {mainSceneRay.x, mainSceneRay.z, mainSceneRay.y, 1};
        float[] direction = {mainSceneRay.dx, mainSceneRay.dz, mainSceneRay.dy, 0};
        Mat4.mulVec(origin, inverseProjection, origin);
        Mat4.mulVec(direction, inverseProjection, direction);
        if (origin[3] == 0) return mainSceneRay;
        float inverseW = 1 / origin[3];
        return new Ray(origin[0] * inverseW, origin[2] * inverseW, origin[1] * inverseW,
                direction[0], direction[2], direction[1]);
    }

    private Shape convexHull(Object object, WorldView worldView) {
        if (object instanceof Actor) return ((Actor) object).getConvexHull();
        if (object instanceof TileObject) return ((TileObject) object).getClickbox();
        if (object instanceof GraphicsObject) return convexHull((GraphicsObject) object, worldView);
        if (object instanceof Projectile) return getConvexHull((Projectile) object);
        return null;
    }

    private Shape convexHull(GraphicsObject object, WorldView worldView) {
        Model model = object.getModel();
        if (model == null || model.getVerticesCount() < 3) return null;
        int n = model.getVerticesCount();
        int[] x = new int[n], y = new int[n];
        modelToCanvas(client, worldView, n,
                object.getLocation().getX(), object.getLocation().getY(), object.getZ(), 0,
                model.getVerticesX(), model.getVerticesZ(), model.getVerticesY(), x, y);
        return Jarvis.convexHull(x, y);
    }

    private static final class StaticSceneCache {
        final Tile[][][] tiles;
        final List<Candidate> candidates;

        StaticSceneCache(Tile[][][] tiles, List<Candidate> candidates) {
            this.tiles = tiles;
            this.candidates = candidates;
        }
    }

    private static final class RoofVisibility {
        final int minLevel, level, maxLevel;
        final Set<Integer> hideRoofIds;

        RoofVisibility(int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds) {
            this.minLevel = minLevel;
            this.level = level;
            this.maxLevel = maxLevel;
            this.hideRoofIds = hideRoofIds;
        }
    }

    private static final class Candidate {
        final Object object;
        final Bounds bounds;
        final float distance;
        final WorldView worldView;
        final int scenePlane, sceneX, sceneY;
        final int id;
        final String name;

        Candidate(Object object, Bounds bounds, WorldView worldView, int scenePlane, int sceneX, int sceneY) {
            this(object, bounds, -1, worldView, scenePlane, sceneX, sceneY);
        }

        Candidate(Object object, Bounds bounds, float distance, WorldView worldView,
                int scenePlane, int sceneX, int sceneY) {
            this.object = object;
            this.bounds = bounds;
            this.distance = distance;
            this.worldView = worldView;
            this.scenePlane = scenePlane;
            this.sceneX = sceneX;
            this.sceneY = sceneY;
            this.id = getId(object);
            this.name = getName(object, id);
        }

        private static int getId(Object object) {
            if (object instanceof TileObject) return ((TileObject) object).getId();
            if (object instanceof NPC) return ((NPC) object).getId();
            if (object instanceof GraphicsObject) return ((GraphicsObject) object).getId();
            if (object instanceof Projectile) return ((Projectile) object).getId();
            return -1;
        }

        private static String getName(Object object, int id) {
            if (object instanceof Tile) {
                Tile tile = (Tile) object;
                return "Tile (plane " + tile.getPlane() + ") " + tile.getWorldLocation();
            }
            if (object instanceof TileObject) return object.getClass().getSimpleName() + " " + id;
            if (object instanceof NPC) return "NPC " + ((NPC) object).getName();
            if (object instanceof Player) return "Player " + ((Player) object).getName();
            if (object instanceof GraphicsObject) return "GraphicsObject " + id;
            if (object instanceof Projectile) return "Projectile " + id;
            return object.getClass().getSimpleName();
        }
    }

    @RequiredArgsConstructor
    private static final class Bounds {
        final float minX, minY, minZ, maxX, maxY, maxZ;
    }
}
