package rs117.hd.overlays.inspectors;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import rs117.hd.model.ModelExporter;
import rs117.hd.scene.GamevalManager;
import rs117.hd.scene.ModelOverrideManager;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.Util;

public final class ModelInspector implements Inspector {
	@Inject
	private Client client;

	@Inject
	private ModelOverrideManager modelOverrideManager;

	@Inject
	private ModelExporter modelExporter;

	@Inject
	private InspectorHistory history;

	@Override
	public boolean isApplicable(Object selected) {
		return selected instanceof Renderable || selected instanceof TileObject || selected instanceof Node;
	}

	@Override
	public void draw(Object object, InspectorOutput out, GamevalManager.Handle gamevals) {
		final Renderable renderable = Util.getRenderable(object);
		long hash = object instanceof TileObject ? ((TileObject) object).getHash()
			: object instanceof Node ? ((Node) object).getHash() : -1;
		int uuid = ModelHash.generateUuid(client, hash, renderable);
		int type = ModelHash.getUuidType(uuid), id = ModelHash.getUuidId(uuid);
		WorldPoint world = worldLocation(object);
		String name = object instanceof Player ? ((Player) object).getName() : "Unknown";
		if (type == ModelHash.TYPE_OBJECT) name = gamevals.getObjectName(id);
		else if (type == ModelHash.TYPE_NPC) name = gamevals.getNpcName(id);
		else if (type == ModelHash.TYPE_GRAPHICS_OBJECT || type == ModelHash.TYPE_PROJECTILE) name = gamevals.getSpotanimName(id);

		out.setTitle("Model: " + name);
		var identity = out.section("Identity");
		identity.action("Export", () -> modelExporter.exportModel(object, renderable));
		identity.line("GameVal ID", id + " (" + ModelHash.getTypeName(type) + ")");
		identity.line("World Position", world == null ? "unknown" : world);
		if (renderable != null) {
			Model model = Util.getModel(renderable);
			identity.line("Geometry", model == null ? "unknown" : model.getFaceCount() + " faces");
		}

		int[] position = world == null ? null : new int[] { world.getX(), world.getY(), world.getPlane() };
		ModelOverride baseOverride = modelOverrideManager.getOverride(uuid);
		ModelOverride override = modelOverrideManager.getOverride(uuid, position);
		boolean editable = override != ModelOverride.NONE;
		var parentSection = out.section("Model Override", editable ? override : null);
		if (editable) {
			Set<Integer> objectIds = baseOverride.objectIds == null ? Set.of() : new HashSet<>(baseOverride.objectIds);
			List<Integer> sortedObjectIds = new ArrayList<>(objectIds);
			sortedObjectIds.sort(Integer::compareTo);
			var objectIdSection = parentSection.child("Object IDs", null, sortedObjectIds.size() > 3);
			for (int objectId : sortedObjectIds) {
				String objectName = gamevals.getObjectName(objectId);
				objectIdSection.lineAction("", objectName == null ? objectId : objectName + " (" + objectId + ")",
					"X", () -> removeObjectId(baseOverride, objectId));
			}
			objectIdSection.objectIdSearch(gamevals.getObjects(), objectIds, objectId -> addObjectId(baseOverride, objectId));
			for (Field field : OverrideFields.fields(override))
				if (field.getName().equals("areas")) parentSection.field(field);
			for (Field field : OverrideFields.changedFields(override)) parentSection.field(field);
			parentSection.action("Make unique", modelOverrideManager.isSharedOverride(uuid),
				() -> makeUnique(uuid, baseOverride));
			parentSection.action("Add color", () -> addColorOverride(override));
			addColorOverrideSections(parentSection, override);
		} else {
			parentSection.line("Status", "No model override defined");
			if (baseOverride == ModelOverride.NONE)
				parentSection.action("Add override", () -> addOverride(uuid));
		}
	}

	private void addOverride(int uuid) {
		ModelOverride override = new ModelOverride();
		override.description = "UNKNOWN";
		setOnlyId(override, uuid);
		history.execute(
			() -> {
				modelOverrideManager.replaceOverride(uuid, ModelOverride.NONE);
				modelOverrideManager.removeDefinition(override);
			},
			() -> {
				modelOverrideManager.replaceOverride(uuid, override);
				modelOverrideManager.addDefinition(override);
			});
	}

	private void addObjectId(ModelOverride override, int objectId) {
		Set<Integer> after = override.objectIds == null ? new HashSet<>() : new HashSet<>(override.objectIds);
		if (after.add(objectId)) setObjectIds(override, after);
	}

	private void removeObjectId(ModelOverride override, int objectId) {
		Set<Integer> after = override.objectIds == null ? new HashSet<>() : new HashSet<>(override.objectIds);
		if (after.remove(objectId)) setObjectIds(override, after);
	}

	private void setObjectIds(ModelOverride override, Set<Integer> objectIds) {
		Set<Integer> before = override.objectIds == null ? Set.of() : new HashSet<>(override.objectIds);
		Set<Integer> after = new HashSet<>(objectIds);
		if (before.equals(after)) return;

		Set<Integer> affectedIds = new HashSet<>(before);
		affectedIds.addAll(after);
		var beforeMappings = modelOverrideManager.getObjectOverrideMappings(affectedIds);
		var afterMappings = new HashMap<>(beforeMappings);
		for (int id : before) {
			if (!after.contains(id) && beforeMappings.get(id) == override)
				afterMappings.put(id, null);
		}
		for (int id : after) afterMappings.put(id, override);

		history.execute(
			() -> {
				override.objectIds = new HashSet<>(before);
				modelOverrideManager.setObjectOverrideMappings(beforeMappings);
			},
			() -> {
				override.objectIds = new HashSet<>(after);
				modelOverrideManager.setObjectOverrideMappings(afterMappings);
			});
	}

	private void addColorOverrideSections(InspectorOutput.Section parentSection, ModelOverride owner) {
		if (owner.colorOverrides == null) return;
		for (int i = 0; i < owner.colorOverrides.length; i++) {
			ModelOverride colorOverride = owner.colorOverrides[i];
			if (colorOverride == null) continue;
			String fallback = "Color Override " + (i + 1);
			String name = descriptionOr(colorOverride.description, fallback);
			var section = parentSection.child("Color Override: " + name, colorOverride, true);
			Field colorsField = null;
			for (Field field : OverrideFields.fields(colorOverride)) {
				if (field.getName().equals("colors")) {
					colorsField = field;
					section.field(field);
					break;
				}
			}
			for (Field field : OverrideFields.changedFields(colorOverride))
				if (!field.equals(colorsField)) section.field(field);
			section.action("Remove", () -> removeColorOverride(owner, colorOverride));
		}
	}

	private void addColorOverride(ModelOverride owner) {
		ModelOverride colorOverride = new ModelOverride();
		colorOverride.description = "Color Override " + (owner.colorOverrides == null ? 1 : owner.colorOverrides.length + 1);
		colorOverride.updateColors(null);
		ModelOverride[] before = copy(owner.colorOverrides);
		ModelOverride[] after = before == null ? new ModelOverride[1] : java.util.Arrays.copyOf(before, before.length + 1);
		after[after.length - 1] = colorOverride;
		history.execute(() -> owner.replaceColorOverrides(copy(before)), () -> owner.replaceColorOverrides(copy(after)));
	}

	private ModelOverride makeUnique(int uuid, ModelOverride parent) {
		ModelOverride unique = parent.copy();
		if (parent.colorOverrides != null) unique.colorOverrides = parent.colorOverrides.clone();
		if (parent.areaOverrides != null) unique.areaOverrides = new LinkedHashMap<>();
		if (parent.materialOverrides != null) unique.materialOverrides = new HashMap<>(parent.materialOverrides);
		setOnlyId(unique, uuid);
		history.execute(
			() -> {
				modelOverrideManager.replaceOverride(uuid, parent);
				modelOverrideManager.removeDefinition(unique);
			},
			() -> {
				modelOverrideManager.replaceOverride(uuid, unique);
				modelOverrideManager.addDefinition(unique);
			});
		return unique;
	}

	private void removeColorOverride(ModelOverride owner, ModelOverride target) {
		if (owner.colorOverrides == null) return;
		List<ModelOverride> remaining = new ArrayList<>();
		for (ModelOverride override : owner.colorOverrides)
			if (override != target) remaining.add(override);
		if (remaining.size() == owner.colorOverrides.length) return;
		ModelOverride[] before = copy(owner.colorOverrides);
		ModelOverride[] after = remaining.isEmpty() ? null : remaining.toArray(ModelOverride[]::new);
		history.execute(() -> owner.replaceColorOverrides(copy(before)), () -> owner.replaceColorOverrides(copy(after)));
	}

	private static void setOnlyId(ModelOverride override, int uuid) {
		override.npcIds = Set.of();
		override.objectIds = Set.of();
		override.projectileIds = Set.of();
		override.graphicsObjectIds = Set.of();
		int id = ModelHash.getUuidId(uuid);
		switch (ModelHash.getUuidType(uuid)) {
			case ModelHash.TYPE_NPC: override.npcIds = Set.of(id); break;
			case ModelHash.TYPE_OBJECT: override.objectIds = Set.of(id); break;
			case ModelHash.TYPE_PROJECTILE: override.projectileIds = Set.of(id); break;
			case ModelHash.TYPE_GRAPHICS_OBJECT: override.graphicsObjectIds = Set.of(id); break;
		}
	}

	private static ModelOverride[] copy(ModelOverride[] overrides) {
		return overrides == null ? null : overrides.clone();
	}

	private static String descriptionOr(String description, String fallback) {
		return description == null || description.isBlank() || description.equals("UNKNOWN") ? fallback : description;
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
}
