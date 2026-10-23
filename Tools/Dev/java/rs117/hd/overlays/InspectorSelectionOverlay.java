package rs117.hd.overlays;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.Point2D;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.model.Jarvis;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;

import static net.runelite.api.Perspective.*;

/** Cursor picking and model outline used by the DeveloperPlugin Inspector. */
@Singleton
public final class InspectorSelectionOverlay extends Overlay {
	private static final Color OUTLINE_COLOR = new Color(0xFFFF00FF, true);
	private static final Color TILE_FILL = new Color(255, 190, 50, 48);
	private static final Color TILE_OUTLINE = new Color(255, 190, 50, 230);
	private static final int OUTLINE_WIDTH = 4;
	private static final int OUTLINE_FEATHER = 4;

	private final Client client;
	private final EventBus eventBus;
	private final ClientThread clientThread;
	private final OverlayManager overlayManager;
	private final ModelOutlineRenderer modelOutlineRenderer;
	private volatile boolean selectionMode;
	private volatile boolean active;
	private volatile Object hoveredObject;
	private volatile Object selectedObject;
	private volatile Tile selectedTile;
	private volatile boolean modelPickingEnabled = true;
	private int ticks;

	@Inject
	public InspectorSelectionOverlay(Client client, EventBus eventBus, ClientThread clientThread, OverlayManager overlayManager,
		ModelOutlineRenderer modelOutlineRenderer) {
		this.client = client;
		this.eventBus = eventBus;
		this.clientThread = clientThread;
		this.overlayManager = overlayManager;
		this.modelOutlineRenderer = modelOutlineRenderer;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPriority(OverlayPriority.HIGH);
	}

	public void startUp() { eventBus.register(this); }

	public void shutDown() {
		selectionMode = false;
		hoveredObject = null;
		selectedObject = null;
		selectedTile = null;
		setActive(false);
		eventBus.unregister(this);
	}

	public void setSelectionMode(boolean enabled) {
		selectionMode = enabled;
		if (enabled) {
			selectedObject = null;
			selectedTile = null;
			hoveredObject = null;
		}
		setActive(enabled || selectedObject != null);
	}

	public void updateHoverNow() { if (selectionMode) updateHover(); }
	public void setModelPickingEnabled(boolean enabled) {
		modelPickingEnabled = enabled;
		if (!enabled) hoveredObject = null;
	}
	public Object getHoveredObject() { return hoveredObject; }
	public Object selectHovered() {
		selectedObject = hoveredObject;
		selectionMode = false;
		setActive(selectedObject != null);
		return selectedObject;
	}
	public void setSelectedObject(Object object) {
		selectedObject = object;
		selectionMode = false;
		setActive(object != null);
	}
	public void setSelectedTile(Tile tile) {
		selectedObject = null;
		selectedTile = tile;
		selectionMode = false;
		setActive(tile != null);
	}

	private void setActive(boolean enabled) {
		if (active == enabled) return;
		active = enabled;
		clientThread.invoke(() -> {
			if (enabled) overlayManager.add(this); else overlayManager.remove(this);
		});
	}

	@Subscribe
	public void onClientTick(ClientTick tick) {
		if (selectionMode && ticks++ % 5 == 0) updateHover();
	}

	private void updateHover() {
		Point mouse = client.getMouseCanvasPosition();
		if (mouse == null || mouse.getX() < 0 || mouse.getY() < 0) {
			hoveredObject = null;
			return;
		}
		Point2D cursor = new Point2D.Float(mouse.getX(), mouse.getY());
		if (!modelPickingEnabled) { hoveredObject = null; return; }
		Scene scene = client.getTopLevelWorldView().getScene();
		if (scene == null) { hoveredObject = null; return; }
		HoverCandidate best = new HoverCandidate(client.getCameraX(), client.getCameraY(), client.getCameraZ());
		Tile[][][] tiles = scene.getTiles();
		for (int plane = tiles.length - 1; plane >= 0; plane--)
			for (Tile[] column : tiles[plane])
				for (Tile tile : column)
					if (tile != null) processTile(cursor, tile, best);
		for (NPC npc : client.getTopLevelWorldView().npcs()) considerIfHovered(cursor, npc, best);
		for (Player player : client.getPlayers()) considerIfHovered(cursor, player, best);
		for (GraphicsObject object : client.getGraphicsObjects()) considerIfHovered(cursor, object, best);
		for (Projectile projectile : client.getProjectiles()) considerIfHovered(cursor, projectile, best);
		hoveredObject = best.object;
	}

	private void processTile(Point2D cursor, Tile tile, HoverCandidate best) {
		if (tile.getBridge() != null) processTile(cursor, tile.getBridge(), best);
		GameObject[] gameObjects = tile.getGameObjects();
		if (gameObjects != null) for (GameObject object : gameObjects) if (object != null) considerIfHovered(cursor, object, best);
		if (tile.getWallObject() != null) considerIfHovered(cursor, tile.getWallObject(), best);
		if (tile.getDecorativeObject() != null) considerIfHovered(cursor, tile.getDecorativeObject(), best);
		if (tile.getGroundObject() != null) considerIfHovered(cursor, tile.getGroundObject(), best);
	}

	private void considerIfHovered(Point2D cursor, Object object, HoverCandidate best) {
		Shape shape = null;
		if (object instanceof Actor) shape = ((Actor) object).getConvexHull();
		else if (object instanceof TileObject) shape = ((TileObject) object).getClickbox();
		else if (object instanceof GraphicsObject) shape = convexHull((GraphicsObject) object);
		else if (object instanceof Projectile) shape = convexHull((Projectile) object);
		if (shape != null && shape.contains(cursor)) {
			long distance = distanceSquared(object, best.cameraX, best.cameraY, best.cameraZ);
			if (distance < best.distance) {
				best.object = object;
				best.distance = distance;
			}
		}
	}

	private static final class HoverCandidate {
		final int cameraX, cameraY, cameraZ;
		Object object;
		long distance = Long.MAX_VALUE;

		HoverCandidate(int cameraX, int cameraY, int cameraZ) {
			this.cameraX = cameraX;
			this.cameraY = cameraY;
			this.cameraZ = cameraZ;
		}
	}

	private Shape convexHull(GraphicsObject object) {
		Model model = object.getModel();
		if (model == null || model.getVerticesCount() < 3) return null;
		int n = model.getVerticesCount();
		int[] x = new int[n], y = new int[n];
		modelToCanvas(client, n, object.getLocation().getX(), object.getLocation().getY(), object.getZ(), 0,
			model.getVerticesX(), model.getVerticesZ(), model.getVerticesY(), x, y);
		return Jarvis.convexHull(x, y);
	}

	private Shape convexHull(Projectile object) {
		Model model = object.getModel();
		if (model == null || model.getVerticesCount() < 3) return null;
		int n = model.getVerticesCount();
		int[] x = new int[n], y = new int[n];
		modelToCanvas(client, n, (int) object.getX(), (int) object.getY(), (int) object.getZ(), 0,
			model.getVerticesX(), model.getVerticesZ(), model.getVerticesY(), x, y);
		return Jarvis.convexHull(x, y);
	}

	private long distanceSquared(Object object, int cameraX, int cameraY, int cameraZ) {
		int x = cameraX, y = cameraY, z = cameraZ;
		if (object instanceof Actor) {
			LocalPoint location = ((Actor) object).getLocalLocation();
			if (location == null) return Long.MAX_VALUE;
			x = location.getX(); y = location.getY();
			z = getTileHeight(client, location, client.getPlane());
		} else if (object instanceof TileObject) {
			TileObject tileObject = (TileObject) object;
			x = tileObject.getX(); y = tileObject.getY(); z = tileObject.getZ();
		} else if (object instanceof GraphicsObject) {
			GraphicsObject graphicsObject = (GraphicsObject) object;
			x = graphicsObject.getLocation().getX(); y = graphicsObject.getLocation().getY(); z = graphicsObject.getZ();
		} else if (object instanceof Projectile) {
			Projectile projectile = (Projectile) object;
			x = (int) projectile.getX(); y = (int) projectile.getY(); z = (int) projectile.getZ();
		}
		long dx = x - cameraX, dy = y - cameraY, dz = z - cameraZ;
		return dx * dx + dy * dy + dz * dz;
	}

	@Override
	public Dimension render(Graphics2D graphics) {
		Object object = selectionMode ? hoveredObject : selectedObject;
		if (object instanceof Actor) modelOutlineRenderer.drawOutline((Actor) object, OUTLINE_WIDTH, OUTLINE_COLOR, OUTLINE_FEATHER);
		else if (object instanceof TileObject) modelOutlineRenderer.drawOutline((TileObject) object, OUTLINE_WIDTH, OUTLINE_COLOR, OUTLINE_FEATHER);
		else if (object instanceof GraphicsObject) modelOutlineRenderer.drawOutline((GraphicsObject) object, OUTLINE_WIDTH, OUTLINE_COLOR, OUTLINE_FEATHER);
		else if (object instanceof Projectile) {
			Shape shape = convexHull((Projectile) object);
			if (shape != null) { graphics.setColor(OUTLINE_COLOR); graphics.setStroke(new BasicStroke(OUTLINE_WIDTH)); graphics.draw(shape); }
		} else if (selectionMode) {
			Tile tile = client.getSelectedSceneTile();
			drawTileHighlight(graphics, tile);
		} else if (selectedTile != null) {
			drawTileHighlight(graphics, selectedTile);
		}
		return null;
	}

	private void drawTileHighlight(Graphics2D graphics, Tile tile) {
		if (tile == null) return;
		Shape shape = getCanvasTilePoly(client, tile.getLocalLocation());
		if (shape == null) return;
		graphics.setColor(TILE_FILL);
		graphics.fill(shape);
		graphics.setColor(TILE_OUTLINE);
		graphics.setStroke(new BasicStroke(2));
		graphics.draw(shape);
	}
}
