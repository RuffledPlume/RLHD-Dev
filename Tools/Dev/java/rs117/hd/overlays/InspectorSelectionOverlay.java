package rs117.hd.overlays;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;

import lombok.Getter;
import net.runelite.api.*;
import net.runelite.api.events.ClientTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import rs117.hd.utils.DrawCallbacksRedirector;
import rs117.hd.utils.SceneGeometry;
import rs117.hd.utils.SceneGeometry.Hit;

import static net.runelite.api.Perspective.getCanvasTileAreaPoly;


/** Cursor picking and model outline used by the DeveloperPlugin Inspector. */
@Singleton
public final class InspectorSelectionOverlay extends Overlay {
	private static final Color OUTLINE_COLOR = new Color(0xFFFF00FF, true);
	private static final Color TILE_FILL = new Color(255, 190, 50, 48);
	private static final Color TILE_OUTLINE = new Color(255, 190, 50, 230);
	private static final int OUTLINE_WIDTH = 4;
	private static final int OUTLINE_FEATHER = 4;

	@Inject
	private Client client;

	@Inject
	private EventBus eventBus;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ModelOutlineRenderer modelOutlineRenderer;

	@Inject
	private DrawCallbacksRedirector drawCallbacksRedirector;

	@Inject
	private SceneGeometry sceneGeometry;

	private volatile boolean selectionMode;
	private volatile boolean active;
	private volatile boolean modelSelectionEnabled = true;
	private volatile boolean tileSelectionEnabled = true;
	@Getter
    private volatile Object hoveredObject;
	@Getter
    private volatile Tile hoveredTile;
	private volatile Object selectedObject;
	private volatile Tile selectedTile;
	private volatile List<Hit> hoverCandidates = Collections.emptyList();
	private volatile int hoveredCandidateIndex;

	@Inject
	public InspectorSelectionOverlay() {
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPriority(OverlayPriority.HIGH);
	}

	public void startUp() {
		drawCallbacksRedirector.install();
		eventBus.register(this);
	}

	public void shutDown() {
		selectionMode = false;
		hoveredObject = null;
		hoveredTile = null;
		hoverCandidates = Collections.emptyList();
		selectedObject = null;
		selectedTile = null;
		sceneGeometry.reset();
		setActive(false);
		drawCallbacksRedirector.uninstall();
		eventBus.unregister(this);
	}

	public void setSelectionMode(boolean enabled) {
		selectionMode = enabled;
		if (enabled) {
			selectedObject = null;
			selectedTile = null;
			hoveredObject = null;
			hoveredTile = null;
			hoverCandidates = Collections.emptyList();
			hoveredCandidateIndex = 0;
		}
		setActive(enabled || selectedObject != null || selectedTile != null);
	}

	public void clearSelection() {
		selectionMode = false;
		selectedObject = null;
		selectedTile = null;
		hoveredObject = null;
		hoveredTile = null;
		hoverCandidates = Collections.emptyList();
		hoveredCandidateIndex = 0;
		setActive(false);
	}

	public void setSelectionModes(boolean modelEnabled, boolean tileEnabled) {
		modelSelectionEnabled = modelEnabled;
		tileSelectionEnabled = tileEnabled;
		if (!isSelectionAllowed(selectedObject)) selectedObject = null;
		if (!isSelectionAllowed(selectedTile)) selectedTile = null;
		if (selectionMode) setActive(true);
		else setActive(selectedObject != null || selectedTile != null);
	}

	public void updateHoverNow() {
		if (selectionMode) updateHover();
	}

	public Object selectHovered() {
		Object selected = getHoveredCandidateObject();
		selectedObject = selected instanceof Tile ? null : selected;
		selectedTile = selected instanceof Tile ? (Tile) selected : null;
		selectionMode = false;
		setActive(selected != null);
		return selected;
	}

	public List<Hit> getHoverCandidates() { return hoverCandidates; }
	public int getHoveredCandidateIndex() { return hoveredCandidateIndex; }
	public void setHoveredCandidateIndex(int index) {
		hoveredCandidateIndex = hoverCandidates.isEmpty() ? 0 : Math.max(0, Math.min(index, hoverCandidates.size() - 1));
		Object candidate = getHoveredCandidateObject();
		hoveredObject = candidate instanceof Tile ? null : candidate;
		hoveredTile = candidate instanceof Tile ? (Tile) candidate : null;
	}

	public void selectCandidate(int index) {
		setHoveredCandidateIndex(index);
		Object candidate = getHoveredCandidateObject();
		selectedObject = candidate instanceof Tile ? null : candidate;
		selectedTile = candidate instanceof Tile ? (Tile) candidate : null;
		setActive(candidate != null);
	}

	private Object getHoveredCandidateObject() {
		List<Hit> candidates = hoverCandidates;
		return candidates.isEmpty() ? null : candidates.get(Math.min(hoveredCandidateIndex, candidates.size() - 1)).getObject();
	}

	public void setSelectedObject(Object object) {
		selectedObject = object;
		selectedTile = null;
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
		if (selectionMode) updateHover();
	}

	private void updateHover() {
		if (!modelSelectionEnabled && !tileSelectionEnabled) {
			setHoverCandidates(Collections.emptyList());
			return;
		}
		Point mouse = client.getMouseCanvasPosition();
		if (mouse == null || mouse.getX() < 0 || mouse.getY() < 0) {
			setHoverCandidates(Collections.emptyList());
			return;
		}
		Point2D cursor = new Point2D.Float(mouse.getX(), mouse.getY());
		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null || worldView.getScene() == null) {
			setHoverCandidates(Collections.emptyList());
			return;
		}
		List<Hit> candidates = new ArrayList<>();
		sceneGeometry.getHovering(worldView, mouse, cursor, candidates);
		candidates.removeIf(hit -> !isSelectionAllowed(hit.getObject()));
		setHoverCandidates(Collections.unmodifiableList(candidates));
	}

	private boolean isSelectionAllowed(Object object) {
		if (object == null) return true;
		return object instanceof Tile ? tileSelectionEnabled : modelSelectionEnabled;
	}

	private void setHoverCandidates(List<Hit> candidates) {
		hoverCandidates = candidates;
		if (candidates.isEmpty()) hoveredCandidateIndex = 0;
		else hoveredCandidateIndex = Math.min(hoveredCandidateIndex, candidates.size() - 1);
		Object candidate = getHoveredCandidateObject();
		hoveredObject = candidate instanceof Tile ? null : candidate;
		hoveredTile = candidate instanceof Tile ? (Tile) candidate : null;
	}

	@Override
	public Dimension render(Graphics2D graphics) {
		Object object = selectionMode ? hoveredObject : selectedObject;
		if (object instanceof Actor) modelOutlineRenderer.drawOutline((Actor) object, OUTLINE_WIDTH, OUTLINE_COLOR, OUTLINE_FEATHER);
		else if (object instanceof TileObject) modelOutlineRenderer.drawOutline((TileObject) object, OUTLINE_WIDTH, OUTLINE_COLOR, OUTLINE_FEATHER);
		else if (object instanceof GraphicsObject) modelOutlineRenderer.drawOutline((GraphicsObject) object, OUTLINE_WIDTH, OUTLINE_COLOR, OUTLINE_FEATHER);
		else if (object instanceof Projectile) {
			Shape shape = sceneGeometry.getConvexHull((Projectile) object);
			if (shape != null) { graphics.setColor(OUTLINE_COLOR); graphics.setStroke(new BasicStroke(OUTLINE_WIDTH)); graphics.draw(shape); }
		} else if (selectionMode) {
			drawTileHighlight(graphics, hoveredTile);
		} else if (selectedTile != null) {
			drawTileHighlight(graphics, selectedTile);
		}
		return null;
	}

	private void drawTileHighlight(Graphics2D graphics, Tile tile) {
		if (tile == null) return;
		final var point = tile.getLocalLocation();
		if (point == null) return;
		final var wv = client.getWorldView(point.getWorldView());
		if(wv == null)
			return;
		final Shape shape = getCanvasTileAreaPoly(client, point, 1, 1, tile.getPlane(), 0);
		if (shape == null)
			return;
		graphics.setColor(TILE_FILL);
		graphics.fill(shape);
		graphics.setColor(TILE_OUTLINE);
		graphics.setStroke(new BasicStroke(2));
		graphics.draw(shape);
	}
}
