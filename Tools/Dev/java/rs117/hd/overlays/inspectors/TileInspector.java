package rs117.hd.overlays.inspectors;

import javax.inject.Inject;
import net.runelite.api.GameObject;
import net.runelite.api.Tile;
import rs117.hd.HdPlugin;
import rs117.hd.scene.GamevalManager;
import rs117.hd.scene.TileOverrideManager;
import rs117.hd.scene.tile_overrides.TileOverride;

public final class TileInspector implements Inspector {
	@Inject
	private HdPlugin plugin;

	@Inject
	private TileOverrideManager tileOverrideManager;

	@Override
	public boolean isApplicable(Object selected) {
		return selected instanceof Tile;
	}

	@Override
	public void draw(Object selected, InspectorOutput out, GamevalManager.Handle gamevals) {
		Tile tile = (Tile) selected;
		out.setTitle("Tile: " + tile.getWorldLocation());

		var sceneContext = plugin.getSceneContext();
		var override = sceneContext == null ? null : tileOverrideManager.getOverride(sceneContext, tile);
		boolean editable = sceneContext != null && override != TileOverride.NONE;
		var section = out.section(editable ? "Tile Override: " + override : "Tile", editable ? override : null);
		section.line("World Position", tile.getWorldLocation());
		section.line("Local Position", tile.getLocalLocation());
		section.line("Scene Position", tile.getSceneLocation());
		section.line("Plane", tile.getPlane());
		section.line("Render Level", tile.getRenderLevel());
		section.line("Bridge", tile.getBridge() == null ? "None" : "Present");

		GameObject[] objects = tile.getGameObjects();
		int count = 0;
		if (objects != null) {
			for (GameObject object : objects) {
				if (object != null)
					section.line("Game Object " + count++, "ID " + object.getId() + " | hash " + object.getHash());
			}
		}
		if (tile.getWallObject() != null)
			section.line("Wall Object", "ID " + tile.getWallObject().getId());
		if (tile.getDecorativeObject() != null)
			section.line("Decorative Object", "ID " + tile.getDecorativeObject().getId());
		if (tile.getGroundObject() != null)
			section.line("Ground Object", "ID " + tile.getGroundObject().getId());
		if (tile.getItemLayer() != null)
			section.line("Item Layer", "Present");
		if (count == 0 && tile.getWallObject() == null && tile.getDecorativeObject() == null
			&& tile.getGroundObject() == null && tile.getItemLayer() == null)
			section.line("Objects", "None");

		if (sceneContext == null) {
			section.line("Override", "Unavailable while the scene is not loaded");
			return;
		}
		if (!editable) {
			section.line("Override", "No matching tile override");
			return;
		}
		for (var field : OverrideFields.changedFields(override))
			section.field(field);
	}
}
