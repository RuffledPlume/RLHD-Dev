package rs117.hd.overlays.inspectors;

import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import com.google.gson.Gson;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import rs117.hd.DeveloperPlugin;
import rs117.hd.HdPlugin;
import rs117.hd.model.ModelExporter;
import rs117.hd.model.ModelImporter;
import rs117.hd.overlays.InspectorPanel;
import rs117.hd.scene.GamevalManager;
import rs117.hd.scene.ModelOverrideManager;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.GsonUtils;
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
	private ClientThread clientThread;

	@Inject
	private HdPlugin plugin;

	@Inject
	private ModelOverrideManager modelOverrideManager;

	@Inject
	private ModelExporter modelExporter;

	@Inject
	private ModelImporter modelImporter;

	@Inject
	private InspectorHistory history;

	private Runnable refreshInspector = () -> {};

	public void setRefreshCallback(@Nullable Runnable callback) {
		refreshInspector = callback == null ? () -> {} : callback;
	}

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
		int type = ModelHash.getUuidType(uuid);
		int id = ModelHash.getUuidId(uuid);
		WorldPoint world = worldLocation(object);
		Model model = renderable == null ? null : Util.getModel(renderable);
		String name = object instanceof Player ? ((Player) object).getName() : "Unknown";
		if (type == ModelHash.TYPE_OBJECT) name = gamevals.getObjectName(id);
		else if (type == ModelHash.TYPE_NPC) name = gamevals.getNpcName(id);
		else if (type == ModelHash.TYPE_GRAPHICS_OBJECT || type == ModelHash.TYPE_PROJECTILE) name = gamevals.getSpotanimName(id);

		out.setTitle("Model: " + name);
		var identity = out.section("Identity");
		identity.line("GameVal ID", id + " (" + ModelHash.getTypeName(type) + ")");
		identity.line("World Position", world == null ? "unknown" : world);
		if (renderable != null)
			identity.line("Geometry", model == null ? "unknown" : model.getFaceCount() + " faces");

		int[] position = world == null ? null : new int[] { world.getX(), world.getY(), world.getPlane() };
		ModelOverride definition = modelOverrideManager.getDefinition(uuid, position);
		if (!matchesDefinition(definition, type, id, position))
			definition = findDefinition(type, id, position);
		final ModelOverride selectedDefinition = definition;
		String overrideTitle = selectedDefinition == null
			? "Model Override"
			: "Model Override: " + descriptionOr(selectedDefinition.description, "UNKNOWN");
		var parentSection = out.section(overrideTitle, definition);
		if (selectedDefinition != null) {
			InspectorOutput.Section customSection = addCustomModelSection(parentSection, selectedDefinition);
			customSection.actionWithOptions("Export", List.of(
				new InspectorOutput.ActionOption("Setup import", "Set up this model override to import the exported model", true),
				new InspectorOutput.ActionOption("Make unique", "Make a shared override unique before setting up import", false)
			), "Exporting selected model…", "Export operation finished.", (options, onCompleted) ->
				modelExporter.exportModel(object, renderable, filename -> {
					if (options[0] || options[1]) {
						ModelOverride currentDefinition = currentDefinition(uuid, type, id, position);
						setupOverrideForImport(uuid, currentDefinition, filename, options[1]);
						save();
						refreshInspector.run();
					}
				}, onCompleted));
			customSection.uiAction("Import…", true,
					parent -> browseForModel(parent, uuid, type, id, position));
			boolean canBake = selectedDefinition.model != null && !selectedDefinition.model.isBlank();
			customSection.actionWithBusy("Bake", canBake, "Baking model data…", "Bake operation finished.",
				() -> bakeModelReference(uuid, type, id, position));

			Set<Integer> objectIds = selectedDefinition.objectIds == null
				? Set.of() : new HashSet<>(selectedDefinition.objectIds);
			List<Integer> sortedObjectIds = new ArrayList<>(objectIds);
			sortedObjectIds.sort(Integer::compareTo);
			var objectIdSection = parentSection.child("Object IDs", null, sortedObjectIds.size() > 3);
			for (int objectId : sortedObjectIds) {
				String objectName = gamevals.getObjectName(objectId);
				objectIdSection.lineAction("", objectName == null ? objectId : objectName + " (" + objectId + ")",
					"X", () -> removeObjectId(selectedDefinition, objectId));
			}
			objectIdSection.objectIdSearch(gamevals.getObjects(), objectIds, objectId -> addObjectId(selectedDefinition, objectId));
			for (Field field : OverrideFields.fields(selectedDefinition))
				if (field.getName().equals("areas")) parentSection.field(field);
			for (Field field : OverrideFields.changedFields(selectedDefinition))
				if (!isCustomModelField(field))
					parentSection.field(field);
			parentSection.action("Make unique", isSharedDefinition(selectedDefinition), () -> makeUnique(uuid, selectedDefinition));
			parentSection.action("Add color", () -> addColorOverride(selectedDefinition));
			addColorOverrideSections(parentSection, selectedDefinition, modelColors(model));
		} else {
			parentSection.line("Status", "No model override defined");
			parentSection.action("New Override", () -> addOverride(uuid));
			List<ModelOverride> definitions = new ArrayList<>();
			for (ModelOverride candidate : modelOverrideManager.getModelOverrideDefinitions())
				if (candidate != null)
					definitions.add(candidate);
			parentSection.modelOverrideSearch(definitions, existing -> addExistingOverride(existing, uuid));
		}
	}

	@Override
	public void apply() {
		ModelOverride[] definitions = modelOverrideManager.getModelOverrideDefinitions().toArray(ModelOverride[]::new);
		definitions = modelImporter.prepareDefinitionsForLoad(definitions);
		modelOverrideManager.loadModelOverrides(definitions);
		refreshInspector.run();
	}

	@Override
	public void save() {
		try {
			if (!MODEL_OVERRIDES_PATH.isFileSystemResource())
				throw new IOException("Model overrides are loaded from a classpath resource; set rlhd.model-overrides-path to a writable file to save.");

			ModelOverride[] definitions = modelOverrideManager.getModelOverrideDefinitions().toArray(ModelOverride[]::new);
			GsonUtils.saveJson(MODEL_OVERRIDES_PATH, gsonForSaving(), definitions, ModelOverride.class, true);
		} catch (IOException | RuntimeException ex) {
			log.error("Failed to save model overrides", ex);
		}
	}

	private Gson gsonForSaving() {
		JsonSerializer<Material.Definition> definitionAdapter = (material, type, context) ->
			new JsonPrimitive(material.name);
		JsonSerializer<Material.Reference> referenceAdapter = (material, type, context) ->
			new JsonPrimitive(material.name);
		return plugin.getGson().newBuilder()
			.registerTypeAdapter(Material.Definition.class, definitionAdapter)
			.registerTypeAdapter(Material.Reference.class, referenceAdapter)
			.create();
	}

	private static boolean isSharedDefinition(ModelOverride definition) {
		int ids = 0;
		if (definition.npcIds != null) ids += definition.npcIds.size();
		if (definition.objectIds != null) ids += definition.objectIds.size();
		if (definition.projectileIds != null) ids += definition.projectileIds.size();
		if (definition.graphicsObjectIds != null) ids += definition.graphicsObjectIds.size();
		return ids > 1;
	}

	private static boolean isCustomModelField(Field field) {
		String name = field.getName();
		return name.equals("model") || name.equals("uvs") || name.equals("materials") || name.equals("faces");
	}

	private InspectorOutput.Section addCustomModelSection(InspectorOutput.Section parent,
		@Nullable ModelOverride definition) {
		InspectorOutput.Section custom = parent.child("Custom", definition, false).allowAddingFields(false);
		if (definition == null)
			return custom;

		Field modelField = null;
		Field materialsField = null;
		for (Field field : OverrideFields.fields(definition)) {
			switch (field.getName()) {
				case "model":
					modelField = field;
					break;
				case "materials":
					materialsField = field;
					break;
			}
		}

		ModelOverride[] materials = materialsField == null
			? null : (ModelOverride[]) OverrideFields.get(materialsField, definition);
		if (modelField != null)
			custom.field("Model file", modelField);
		if (definition.uvs != null) {
			int uvPairCount = definition.uvs.length / 2;
			String count = uvPairCount + " UV pairs";
			if (definition.uvs.length % 6 == 0)
				count += " (" + definition.uvs.length / 6 + " faces)";
			custom.line("UV count", count);
		}
		if (materials != null) {
			for (int i = 0; i < materials.length; i++) {
				ModelOverride group = materials[i];
				if (group == null) {
					custom.line("Material group " + (i + 1), "Invalid group");
					continue;
				}
				InspectorOutput.Section groupSection = custom.child("Material group " + (i + 1), group, true)
					.allowAddingFields(false);
				for (Field field : OverrideFields.fields(group)) {
					if (field.getName().equals("material"))
						groupSection.field(field, refreshInspector);
					else if (field.getName().equals("faces"))
						groupSection.line("Faces", OverrideFields.format(field, group));
				}
			}
		}
		return custom;
	}

	private ModelOverride findDefinition(int type, int id, int[] worldPos) {
		var definitions = modelOverrideManager.getModelOverrideDefinitions();
		for (ModelOverride definition : definitions) {
			if (matchesDefinition(definition, type, id, worldPos))
				return definition;
		}
		return null;
	}

	private ModelOverride currentDefinition(int uuid, int type, int id, int[] position) {
		ModelOverride definition = findDefinition(type, id, position);
		return definition == null ? modelOverrideManager.getDefinition(uuid, position) : definition;
	}

	private void browseForModel(Component parent, int uuid, int type, int id, int[] position) {
		DeveloperPlugin.MODEL_EXPORTS.mkdirs();
		Path root = DeveloperPlugin.MODEL_EXPORTS.toPath().toAbsolutePath().normalize();
		JFileChooser chooser = new JFileChooser(root.toFile());
		chooser.setDialogTitle("Import model export");
		chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
		FileNameExtensionFilter modelFilter = new FileNameExtensionFilter("Model exports (*.glb)", "glb");
		chooser.addChoosableFileFilter(modelFilter);
		chooser.setFileFilter(modelFilter);
		if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION)
			return;

		Path selected = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
		if (!selected.startsWith(root)) {
			javax.swing.JOptionPane.showMessageDialog(parent,
				"Choose a model inside the model-exports folder.", "Invalid model path", javax.swing.JOptionPane.WARNING_MESSAGE);
			return;
		}
		String relativePath = root.relativize(selected).toString().replace(File.separatorChar, '/');
		Runnable importModel = () -> {
			setupOverrideForImport(uuid, currentDefinition(uuid, type, id, position), relativePath, false);
			save();
			refreshInspector.run();
		};
		if (parent instanceof InspectorPanel)
			((InspectorPanel) parent).runWithBusyOverlay("Importing model…", "Import setup finished.", importModel);
		else
			clientThread.invoke(importModel);
	}

	private void bakeModelReference(int uuid, int type, int id, int[] position) {
		ModelOverride definition = currentDefinition(uuid, type, id, position);
		if (definition == null || definition.model == null || definition.model.isBlank())
			return;
		save();
		String modelPath = definition.model;
		modelImporter.bakeModel(modelPath, () -> {
			ModelOverride bakedDefinition = currentDefinition(uuid, type, id, position);
			if (bakedDefinition != null)
				bakedDefinition.model = null;
			refreshInspector.run();
		});
	}


	private boolean matchesDefinition(ModelOverride definition, int type, int id, int[] worldPos) {
		if (definition == null || !containsId(definition, type, id))
			return false;
		if (definition.seasonalTheme != null && definition.seasonalTheme != plugin.configSeasonalTheme)
			return false;

		boolean hasAreas = definition.areas != null && definition.areas.length > 0;
		boolean hasHideInAreas = definition.hideInAreas != null && definition.hideInAreas.length > 0;
		if (!hasAreas && !hasHideInAreas)
			return true;
		return worldPos != null && (containsPoint(definition.areas, worldPos)
			|| containsPoint(definition.hideInAreas, worldPos));
	}

	private static boolean containsPoint(AABB[] areas, int[] worldPos) {
		if (areas == null)
			return false;
		for (var area : areas)
			if (area != null && area.contains(worldPos))
				return true;
		return false;
	}

	private static boolean containsId(ModelOverride definition, int type, int id) {
		switch (type) {
			case ModelHash.TYPE_NPC:
				return definition.npcIds != null && definition.npcIds.contains(id);
			case ModelHash.TYPE_OBJECT:
				return definition.objectIds != null && definition.objectIds.contains(id);
			case ModelHash.TYPE_PROJECTILE:
				return definition.projectileIds != null && definition.projectileIds.contains(id);
			case ModelHash.TYPE_GRAPHICS_OBJECT:
				return definition.graphicsObjectIds != null && definition.graphicsObjectIds.contains(id);
			default:
				return false;
		}
	}

	private void addDefinition(ModelOverride definition) {
		var definitions = modelOverrideManager.getModelOverrideDefinitions();
		if (!definitions.contains(definition))
			definitions.add(definition);
	}

	private void removeDefinition(ModelOverride definition) {
		var definitions = modelOverrideManager.getModelOverrideDefinitions();
		definitions.remove(definition);
	}

	private void addOverride(int uuid) {
		ModelOverride definition = new ModelOverride();
		definition.description = "UNKNOWN";
		setOnlyId(definition, uuid);
		history.execute(
			() -> removeDefinition(definition),
			() -> addDefinition(definition));
	}

	private void addObjectId(ModelOverride definition, int objectId) {
		Set<Integer> after = definition.objectIds == null ? new HashSet<>() : new HashSet<>(definition.objectIds);
		if (after.add(objectId)) setObjectIds(definition, after);
	}

	private void removeObjectId(ModelOverride definition, int objectId) {
		Set<Integer> after = definition.objectIds == null ? new HashSet<>() : new HashSet<>(definition.objectIds);
		if (after.remove(objectId)) setObjectIds(definition, after);
	}

	private void setObjectIds(ModelOverride definition, Set<Integer> objectIds) {
		Set<Integer> before = definition.objectIds == null ? Set.of() : new HashSet<>(definition.objectIds);
		Set<Integer> after = new HashSet<>(objectIds);
		if (before.equals(after)) return;

		history.execute(
			() -> definition.objectIds = new HashSet<>(before),
			() -> definition.objectIds = new HashSet<>(after));
	}

	private void addExistingOverride(ModelOverride source, int uuid) {
		int type = ModelHash.getUuidType(uuid);
		int id = ModelHash.getUuidId(uuid);
		if (idsFor(source, type).contains(id))
			return;
		history.execute(() -> removeId(source, uuid), () -> addId(source, uuid));
	}

	private void addColorOverrideSections(InspectorOutput.Section parentSection, ModelOverride owner, int[] modelColors) {
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
					section.field(field, modelColors);
					break;
				}
			}
			for (Field field : OverrideFields.changedFields(colorOverride))
				if (!field.equals(colorsField)) section.field(field);
			section.action("Remove", () -> removeColorOverride(owner, colorOverride));
		}
	}

	private static int[] modelColors(Model model) {
		if (model == null || model.getFaceColors1() == null)
			return new int[0];
		return Arrays.stream(model.getFaceColors1()).filter(color -> color >= 0).distinct().sorted().toArray();
	}

	private void addColorOverride(ModelOverride owner) {
		ModelOverride colorOverride = new ModelOverride();
		colorOverride.description = "Color Override " + (owner.colorOverrides == null ? 1 : owner.colorOverrides.length + 1);
		colorOverride.updateColors(null);
		ModelOverride[] before = copy(owner.colorOverrides);
		ModelOverride[] after = before == null ? new ModelOverride[1] : Arrays.copyOf(before, before.length + 1);
		after[after.length - 1] = colorOverride;
		history.execute(() -> owner.replaceColorOverrides(copy(before)), () -> owner.replaceColorOverrides(copy(after)));
	}

	private void setupOverrideForImport(int uuid, @Nullable ModelOverride source, String filename, boolean makeUnique) {
		if (source == null) {
			ModelOverride definition = new ModelOverride();
			definition.description = "UNKNOWN";
			definition.model = filename;
			setOnlyId(definition, uuid);
			history.execute(() -> removeDefinition(definition), () -> addDefinition(definition));
			return;
		}

		ModelOverride definition = makeUnique && isSharedDefinition(source) ? makeUnique(uuid, source) : source;
		if (Objects.equals(definition.model, filename))
			return;

		String previousModel = definition.model;
		history.execute(
			() -> definition.model = previousModel,
			() -> definition.model = filename
		);
	}

	private ModelOverride makeUnique(int uuid, ModelOverride parent) {
		ModelOverride unique = parent.copy(true);
		unique.description = descriptionOr(parent.description, "Model Override") + " (unique)";
		setOnlyId(unique, uuid);
		history.execute(
			() -> {
				addId(parent, uuid);
				removeDefinition(unique);
			},
			() -> {
				removeId(parent, uuid);
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

	private static Set<Integer> idsFor(ModelOverride definition, int type) {
		switch (type) {
			case ModelHash.TYPE_NPC:
				return definition.npcIds == null ? Set.of() : definition.npcIds;
			case ModelHash.TYPE_OBJECT:
				return definition.objectIds == null ? Set.of() : definition.objectIds;
			case ModelHash.TYPE_PROJECTILE:
				return definition.projectileIds == null ? Set.of() : definition.projectileIds;
			case ModelHash.TYPE_GRAPHICS_OBJECT:
				return definition.graphicsObjectIds == null ? Set.of() : definition.graphicsObjectIds;
			default:
				return Set.of();
		}
	}

	private static void setIds(ModelOverride definition, int type, Set<Integer> ids) {
		switch (type) {
			case ModelHash.TYPE_NPC:
				definition.npcIds = ids;
				break;
			case ModelHash.TYPE_OBJECT:
				definition.objectIds = ids;
				break;
			case ModelHash.TYPE_PROJECTILE:
				definition.projectileIds = ids;
				break;
			case ModelHash.TYPE_GRAPHICS_OBJECT:
				definition.graphicsObjectIds = ids;
				break;
		}
	}

	private static void addId(ModelOverride definition, int uuid) {
		int type = ModelHash.getUuidType(uuid);
		Set<Integer> ids = new HashSet<>(idsFor(definition, type));
		ids.add(ModelHash.getUuidId(uuid));
		setIds(definition, type, ids);
	}

	private static void removeId(ModelOverride definition, int uuid) {
		int type = ModelHash.getUuidType(uuid);
		Set<Integer> ids = new HashSet<>(idsFor(definition, type));
		ids.remove(ModelHash.getUuidId(uuid));
		setIds(definition, type, ids);
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
