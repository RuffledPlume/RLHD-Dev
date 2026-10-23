package rs117.hd.overlays.inspectors;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import rs117.hd.scene.GamevalManager;
import rs117.hd.scene.ModelOverrideManager;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.Util;

public final class ModelInspector implements Inspector {

	@Inject
	private Client client;

	@Inject
	private ModelOverrideManager modelOverrideManager;

	@Override
	public boolean isApplicable(Object selected) {
		return selected instanceof Renderable || selected instanceof TileObject || selected instanceof Node;
	}

	@Override
	public void draw(Object object, StringBuilder out, GamevalManager.Handle gamevals) {
		Renderable renderable = Util.getRenderable(object);
		long hash = object instanceof TileObject ? ((TileObject) object).getHash()
			: object instanceof Node ? ((Node) object).getHash() : -1;
		int uuid = ModelHash.generateUuid(client, hash, renderable);
		int type = ModelHash.getUuidType(uuid), id = ModelHash.getUuidId(uuid);
		WorldPoint world = worldLocation(object);
		String name = object instanceof Player ? ((Player) object).getName() : "Unknown";
		if (type == ModelHash.TYPE_OBJECT) name = gamevals.getObjectName(id);
		else if (type == ModelHash.TYPE_NPC) name = gamevals.getNpcName(id);
		else if (type == ModelHash.TYPE_GRAPHICS_OBJECT || type == ModelHash.TYPE_PROJECTILE) name = gamevals.getSpotanimName(id);
		int[] position = world == null ? new int[] {0, 0, 0} : new int[] {world.getX(), world.getY(), world.getPlane()};
		ModelOverride override = modelOverrideManager.getOverride(uuid, position);
		InspectorOutput.line(out, name, "Model");
		InspectorOutput.section(out, "Identity");
		InspectorOutput.line(out, "Model Name", name);
		InspectorOutput.line(out, "GameVal ID", id + " (" + ModelHash.getTypeName(type) + ")");
		InspectorOutput.line(out, "World Position", world == null ? "unknown" : world);
		InspectorOutput.section(out, "Model Override");
		InspectorOutput.line(out, "ModelOverride", override.description);
		InspectorOutput.line(out, "Base Material", override.baseMaterial);
		InspectorOutput.line(out, "Texture Material", override.textureMaterial);
		InspectorOutput.line(out, "Color Overrides", colorOverrides(override.colorOverrides));
		InspectorOutput.line(out, "Materials", materials(override));
		if (renderable != null) {
			Model model = Util.getModel(renderable);
			InspectorOutput.line(out, "Geometry", model == null ? "unknown" : model.getFaceCount() + " faces");
		}
	}

	private WorldPoint worldLocation(Object object) {
		if (object instanceof Actor) return ((Actor) object).getWorldLocation();
		if (object instanceof TileObject) return ((TileObject) object).getWorldLocation();
		if (object instanceof GraphicsObject) return WorldPoint.fromLocal(client, ((GraphicsObject) object).getLocation());
		if (object instanceof Projectile) {
			Projectile p = (Projectile) object;
			return WorldPoint.fromLocal(client, (int) p.getX(), (int) p.getY(), client.getPlane());
		}
		return null;
	}

	private static String materials(ModelOverride override) {
		List<String> values = new ArrayList<>();
		if (override.baseMaterial != null && override.baseMaterial != Material.NONE) values.add("base=" + override.baseMaterial.name);
		if (override.textureMaterial != null && override.textureMaterial != Material.NONE) values.add("texture=" + override.textureMaterial.name);
		if (override.materialOverrides != null)
			for (var entry : override.materialOverrides.entrySet()) values.add(entry.getKey().name + " -> " + entry.getValue().baseMaterial.name);
		return values.isEmpty() ? "none" : String.join(", ", values);
	}

	private static String colorOverrides(ModelOverride[] overrides) {
		if (overrides == null || overrides.length == 0) return "none";
		List<String> values = new ArrayList<>();
		for (ModelOverride color : overrides)
			values.add(color.description + " [material=" + color.baseMaterial + ", modifiesColor=" + color.modifiesColor + "]");
		return String.join("; ", values);
	}
}
