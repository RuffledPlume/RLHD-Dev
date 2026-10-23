package rs117.hd.overlays.inspectors;


import net.runelite.api.GameObject;
import net.runelite.api.Tile;
import javax.inject.Inject;
import rs117.hd.scene.GamevalManager;

public final class TileInspector implements Inspector {
	@Inject
	public TileInspector() {}

	@Override
	public boolean isApplicable(Object selected) {
		return selected instanceof Tile;
	}

	@Override
	public void draw(Object selected, StringBuilder out, GamevalManager.Handle gamevals) {
		Tile tile = (Tile) selected;
		InspectorOutput.line(out, tile.getWorldLocation().toString(), "Tile");
		InspectorOutput.section(out, "Transform");
		InspectorOutput.line(out, "World Position", tile.getWorldLocation());
		InspectorOutput.line(out, "Local Position", tile.getLocalLocation());
		InspectorOutput.line(out, "Scene Position", tile.getSceneLocation());
		InspectorOutput.line(out, "Plane", tile.getPlane());
		InspectorOutput.section(out, "Tile");
		InspectorOutput.line(out, "Render Level", tile.getRenderLevel());
		InspectorOutput.line(out, "Bridge", tile.getBridge() == null ? "None" : "Present");
		InspectorOutput.section(out, "Components");
		GameObject[] objects = tile.getGameObjects();
		int count = 0;
		if (objects != null) for (GameObject object : objects) if (object != null) {
			InspectorOutput.line(out, "Game Object " + count++, "ID " + object.getId() + " | hash " + object.getHash());
		}
		if (tile.getWallObject() != null) InspectorOutput.line(out, "Wall Object", "ID " + tile.getWallObject().getId());
		if (tile.getDecorativeObject() != null) InspectorOutput.line(out, "Decorative Object", "ID " + tile.getDecorativeObject().getId());
		if (tile.getGroundObject() != null) InspectorOutput.line(out, "Ground Object", "ID " + tile.getGroundObject().getId());
		if (tile.getItemLayer() != null) InspectorOutput.line(out, "Item Layer", "Present");
		if (count == 0 && tile.getWallObject() == null && tile.getDecorativeObject() == null
			&& tile.getGroundObject() == null && tile.getItemLayer() == null) InspectorOutput.line(out, "Objects", "None");
	}
}
