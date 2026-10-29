package rs117.hd.overlays.inspectors;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import rs117.hd.HdPlugin;
import rs117.hd.model.ModelExporter;
import rs117.hd.scene.GamevalManager;
import rs117.hd.scene.ModelOverrideManager;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;
import rs117.hd.utils.Util;

import static rs117.hd.utils.ResourcePath.path;

@Slf4j
public final class ModelInspector implements Inspector {

	private static final ResourcePath MODEL_OVERRIDES_PATH = Props
			.getFile("rlhd.model-overrides-path", () -> path(ModelOverrideManager.class, "model_overrides.json"));

	@Inject
	private Client client;

	@Inject
	private HdPlugin plugin;

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
			parentSection.action("Make unique", isSharedOverride(uuid),
				() -> makeUnique(uuid, baseOverride));
			parentSection.action("Add color", () -> addColorOverride(override));
			addColorOverrideSections(parentSection, override);
		} else {
			parentSection.line("Status", "No model override defined");
			if (baseOverride == ModelOverride.NONE)
				parentSection.action("Add override", () -> addOverride(uuid));
		}
	}

	@Override
	public void save() {
		try {
			if (!MODEL_OVERRIDES_PATH.isFileSystemResource())
				throw new IOException("Model overrides are loaded from a classpath resource; set rlhd.model-overrides-path to a writable file to save.");

			List<ModelOverride> sourceDefinitions;
			var definitions = modelOverrideManager.getModelOverrideDefinitions();
			synchronized (definitions) {
				sourceDefinitions = new ArrayList<>(definitions);
			}
			String json = plugin.getGson().toJson(getSaveDefinitions(sourceDefinitions));
			MODEL_OVERRIDES_PATH.mkdirs();
			try (var writer = MODEL_OVERRIDES_PATH.toWriter()) {
				writer.write(json);
			} catch (IOException | RuntimeException ex) {
				throw ex;
			}
		} catch (IOException | RuntimeException ex) {
			log.error("Failed to save model overrides", ex);
		}
	}

	private boolean isSharedOverride(int uuid) {
		int baseUuid = ModelHash.getUuidWithoutSubType(uuid);
		var overrides = modelOverrideManager.getModelOverrides();
		ModelOverride override = overrides.get(baseUuid);
		if (override == null || override == ModelOverride.NONE || override.isDummy || override.isGenerated)
			return false;

		int matches = 0;
		for (var entry : overrides) {
			if (entry.getValue() == override && ++matches > 1)
				return true;
		}
		return false;
	}

	private void replaceOverride(int uuid, ModelOverride override) {
		modelOverrideManager.getModelOverrides().put(ModelHash.getUuidWithoutSubType(uuid), override);
	}

	private Map<Integer, ModelOverride> getObjectOverrideMappings(Set<Integer> objectIds) {
		Map<Integer, ModelOverride> mappings = new HashMap<>();
		var overrides = modelOverrideManager.getModelOverrides();
		for (int id : objectIds) {
			int uuid = ModelHash.packUuid(ModelHash.TYPE_OBJECT, id);
			mappings.put(id, overrides.get(uuid));
		}
		return mappings;
	}

	private void setObjectOverrideMappings(Map<Integer, ModelOverride> mappings) {
		var overrides = modelOverrideManager.getModelOverrides();
		for (var entry : mappings.entrySet()) {
			int uuid = ModelHash.packUuid(ModelHash.TYPE_OBJECT, entry.getKey());
			if (entry.getValue() == null)
				overrides.remove(uuid);
			else
				overrides.put(uuid, entry.getValue());
		}
	}

	public List<ModelOverride> getAreaOverrideOwners(ModelOverride areaOverride) {
		List<ModelOverride> owners = new ArrayList<>();
		var seen = Collections.newSetFromMap(new IdentityHashMap<ModelOverride, Boolean>());
		for (var entry : modelOverrideManager.getModelOverrides()) {
			ModelOverride root = entry.getValue();
			if (containsAreaOverride(root, areaOverride) && seen.add(root))
				owners.add(root);
		}
		return owners;
	}

	public void updateAreaOverrideMappings(List<ModelOverride> owners, ModelOverride areaOverride) {
		var areas = areaOverride.areas;
		int added = areas == null ? 0 : areas.length;
		for (ModelOverride root : owners) {
			int kept = 0;
			if (root.areaOverrides != null)
				for (var override : root.areaOverrides)
					if (override != areaOverride)
						kept++;

			if (kept + added == 0) {
				root.areaOverrides = null;
				root.areaOverrideBounds = null;
				continue;
			}

			var overrides = new ModelOverride[kept + added];
			var bounds = new AABB[kept + added];
			int index = 0;
			if (root.areaOverrides != null) {
				for (int i = 0; i < root.areaOverrides.length; i++) {
					if (root.areaOverrides[i] == areaOverride) continue;
					overrides[index] = root.areaOverrides[i];
					bounds[index++] = root.areaOverrideBounds[i];
				}
			}
			if (areas != null) {
				for (var area : areas) {
					overrides[index] = areaOverride;
					bounds[index++] = area;
				}
			}
			root.areaOverrides = overrides;
			root.areaOverrideBounds = bounds;
		}
	}

	private static boolean containsAreaOverride(ModelOverride root, ModelOverride areaOverride) {
		if (root.areaOverrides == null)
			return false;
		for (var override : root.areaOverrides)
			if (override == areaOverride)
				return true;
		return false;
	}

	private static boolean hasAreaOverride(ModelOverride root, AABB area, ModelOverride override) {
		if (root.areaOverrides == null || root.areaOverrideBounds == null)
			return false;
		for (int i = 0; i < root.areaOverrides.length; i++)
			if (root.areaOverrideBounds[i] == area && root.areaOverrides[i] == override)
				return true;
		return false;
	}

	private void addDefinition(ModelOverride override) {
		var definitions = modelOverrideManager.getModelOverrideDefinitions();
		synchronized (definitions) {
			if (!definitions.contains(override))
				definitions.add(override);
		}
	}

	private void removeDefinition(ModelOverride override) {
		var definitions = modelOverrideManager.getModelOverrideDefinitions();
		synchronized (definitions) {
			definitions.remove(override);
		}
	}

	private ModelOverride[] getSaveDefinitions(List<ModelOverride> sourceDefinitions) {
		List<ModelOverride> definitions = new ArrayList<>();
		var overrides = modelOverrideManager.getModelOverrides();
		for (ModelOverride definition : sourceDefinitions) {
			if (definition.areas == null || definition.areas.length == 0) {
				definitions.add(definition);
				continue;
			}

			for (var area : definition.areas) {
				Set<Integer> npcIds = new HashSet<>();
				Set<Integer> objectIds = new HashSet<>();
				Set<Integer> projectileIds = new HashSet<>();
				Set<Integer> graphicsObjectIds = new HashSet<>();
				for (var entry : overrides) {
					var root = entry.getValue();
					if (!hasAreaOverride(root, area, definition))
						continue;

					int id = ModelHash.getUuidId(entry.getKey());
					switch (ModelHash.getUuidType(entry.getKey())) {
						case ModelHash.TYPE_NPC:
							npcIds.add(id);
							break;
						case ModelHash.TYPE_OBJECT:
							objectIds.add(id);
							break;
						case ModelHash.TYPE_PROJECTILE:
							projectileIds.add(id);
							break;
						case ModelHash.TYPE_GRAPHICS_OBJECT:
							graphicsObjectIds.add(id);
							break;
					}
				}

				if (npcIds.isEmpty() && objectIds.isEmpty() && projectileIds.isEmpty() && graphicsObjectIds.isEmpty())
					continue;

				ModelOverride savedDefinition = definition.copy();
				savedDefinition.areas = new AABB[]{area};
				savedDefinition.npcIds = npcIds;
				savedDefinition.objectIds = objectIds;
				savedDefinition.projectileIds = projectileIds;
				savedDefinition.graphicsObjectIds = graphicsObjectIds;
				definitions.add(savedDefinition);
			}
		}
		return definitions.toArray(ModelOverride[]::new);
	}

	private void addOverride(int uuid) {
		ModelOverride override = new ModelOverride();
		override.description = "UNKNOWN";
		setOnlyId(override, uuid);
		history.execute(
			() -> {
				replaceOverride(uuid, ModelOverride.NONE);
				removeDefinition(override);
			},
			() -> {
				replaceOverride(uuid, override);
				addDefinition(override);
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
		var beforeMappings = getObjectOverrideMappings(affectedIds);
		var afterMappings = new HashMap<>(beforeMappings);
		for (int id : before) {
			if (!after.contains(id) && beforeMappings.get(id) == override)
				afterMappings.put(id, null);
		}
		for (int id : after) afterMappings.put(id, override);

		history.execute(
			() -> {
				override.objectIds = new HashSet<>(before);
				setObjectOverrideMappings(beforeMappings);
			},
			() -> {
				override.objectIds = new HashSet<>(after);
				setObjectOverrideMappings(afterMappings);
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
		if (parent.areaOverrides != null) {
			unique.areaOverrides = parent.areaOverrides.clone();
			unique.areaOverrideBounds = parent.areaOverrideBounds == null ? null : parent.areaOverrideBounds.clone();
		}
		if (parent.materialOverrides != null) unique.materialOverrides = new HashMap<>(parent.materialOverrides);
		setOnlyId(unique, uuid);
		history.execute(
			() -> {
				replaceOverride(uuid, parent);
				removeDefinition(unique);
			},
			() -> {
				replaceOverride(uuid, unique);
				addDefinition(unique);
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
