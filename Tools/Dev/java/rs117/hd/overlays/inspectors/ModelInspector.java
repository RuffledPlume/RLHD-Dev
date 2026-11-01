package rs117.hd.overlays.inspectors;

import java.awt.Component;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
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
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import rs117.hd.DeveloperPlugin;
import rs117.hd.HdPlugin;
import rs117.hd.model.ModelExporter;
import rs117.hd.model.ModelImporter;
import rs117.hd.overlays.InspectorPanel;
import rs117.hd.overlays.InspectorSelectionOverlay;
import rs117.hd.scene.GamevalManager;
import rs117.hd.scene.ModelOverrideManager;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.GsonUtils;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;
import rs117.hd.utils.Util;

import static rs117.hd.utils.ResourcePath.path;
import static net.runelite.api.Perspective.modelToCanvas;

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

	@Inject
	private InspectorSelectionOverlay selectionOverlay;

	private Runnable refreshInspector = () -> {};
	private volatile Integer hoveredPreviewColor;
	private volatile boolean importVertexColors;

	public void setRefreshCallback(@Nullable Runnable callback) {
		refreshInspector = callback == null ? () -> {} : callback;
	}

	@Override
	public boolean isApplicable(Object selected) {
		return selected instanceof Renderable || selected instanceof TileObject || selected instanceof Node;
	}

	@Override
	public void draw(Object object, InspectorOutput out, GamevalManager.Handle gamevals) {
		clearColorPreview();
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
			InspectorOutput.Section customSection = parentSection.child("Custom", selectedDefinition, false)
				.allowAddingFields(false);
			addCustomModelFields(customSection, selectedDefinition);
			InspectorOutput.Section importSection = customSection.child("", selectedDefinition, false, false)
				.allowAddingFields(false);
			importSection.toggle("Import Vertex Colours", importVertexColors, selected -> importVertexColors = selected);
			importSection.uiAction("Import…", true,
				parent -> browseForModel(parent, uuid, type, id, position, importVertexColors));

			InspectorOutput.Section exportSection = customSection.child("", selectedDefinition, false, false)
				.allowAddingFields(false);
			exportSection.actionWithOptions("Export", List.of(
				new InspectorOutput.ActionOption("Setup import", "Set up this model override to import the exported model", true),
				new InspectorOutput.ActionOption("Make unique", "Make a shared override unique before setting up import", false)
			), "Exporting selected model…", "Export operation finished.", (options, onCompleted) -> {
				boolean importColors = importVertexColors;
				modelExporter.exportModel(object, renderable, filename -> {
					if (options[0] || options[1]) {
						ModelOverride currentDefinition = currentDefinition(uuid, type, id, position);
						setupOverrideForImport(uuid, currentDefinition, filename, options[1], importColors);
						save();
						refreshInspector.run();
					}
				}, onCompleted);
			});

			InspectorOutput.Section bakeSection = customSection.child("", selectedDefinition, false, false)
				.allowAddingFields(false);
			boolean canBake = selectedDefinition.model != null && !selectedDefinition.model.isBlank();
			bakeSection.actionWithBusy("Bake", canBake, "Baking model data…", "Bake operation finished.",
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
			addColorOverrideSections(parentSection, selectedDefinition, modelColors(model), object, renderable, model);
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
		return name.equals("model") || name.equals("vertexColors") ||
			name.equals("uvs") || name.equals("materials") || name.equals("faces");
	}

	private void addCustomModelFields(InspectorOutput.Section custom, ModelOverride definition) {
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
		if (definition.vertexColors != null)
			custom.line("Vertex colors", definition.vertexColors.length + " corner colors");
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

	private void browseForModel(Component parent, int uuid, int type, int id, int[] position,
		boolean importVertexColors) {
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
			setupOverrideForImport(uuid, currentDefinition(uuid, type, id, position), relativePath, false,
				importVertexColors);
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

	private void addColorOverrideSections(InspectorOutput.Section parentSection, ModelOverride owner, int[] modelColors,
		Object object, Renderable renderable, Model model) {
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
					section.field(field, new InspectorEditors.ColorEditorData(modelColors,
						color -> previewColorFaces(object, renderable, model, color)));
					break;
				}
			}
			for (Field field : OverrideFields.changedFields(colorOverride))
				if (!field.equals(colorsField)) section.field(field);
			section.action("Remove", () -> removeColorOverride(owner, colorOverride));
		}
	}

	private void previewColorFaces(Object object, Renderable renderable, Model model, Integer color) {
		if (color == null) {
			clearColorPreview();
			return;
		}
		if (Objects.equals(hoveredPreviewColor, color))
			return;
		hoveredPreviewColor = color;
		clientThread.invoke(() -> selectionOverlay.setPreviewShapes(findFacesMatchingColor(object, renderable, model, color)));
	}

	private void clearColorPreview() {
		hoveredPreviewColor = null;
		clientThread.invoke(selectionOverlay::clearPreviewShapes);
	}

	private List<Shape> findFacesMatchingColor(Object object, Renderable renderable, Model model, int color) {
		if (model == null || renderable == null)
			return List.of();
		WorldView worldView = client.getTopLevelWorldView();
		int centerX, centerY, centerZ, orientation = 0;
		if (object instanceof Actor) {
			Actor actor = (Actor) object;
			LocalPoint location = actor.getLocalLocation();
			worldView = actor.getWorldView();
			if (location == null || worldView == null)
				return List.of();
			centerX = location.getX();
			centerY = location.getY();
			centerZ = worldView.getTileHeight(centerX, centerY, worldView.getPlane()) + actor.getAnimationHeightOffset();
			orientation = actor.getOrientation();
		} else if (object instanceof TileObject) {
			TileObject tileObject = (TileObject) object;
			worldView = tileObject.getWorldView();
			centerX = tileObject.getX();
			centerY = tileObject.getY();
			centerZ = tileObject.getZ();
			if (tileObject instanceof ItemLayer)
				centerZ -= ((ItemLayer) tileObject).getHeight();
			if (tileObject instanceof GameObject) {
				GameObject gameObject = (GameObject) tileObject;
				orientation = gameObject.getModelOrientation() + HDUtils.getModelPreOrientation(gameObject.getConfig());
			} else if (tileObject instanceof WallObject) {
				WallObject wall = (WallObject) tileObject;
				orientation = HDUtils.convertWallObjectOrientation(renderable == wall.getRenderable1()
					? wall.getOrientationA() : wall.getOrientationB());
			} else if (tileObject instanceof DecorativeObject) {
				DecorativeObject decoration = (DecorativeObject) tileObject;
				boolean first = renderable == decoration.getRenderable();
				centerX += first ? decoration.getXOffset() : decoration.getXOffset2();
				centerY += first ? decoration.getYOffset() : decoration.getYOffset2();
				orientation = HDUtils.getModelPreOrientation(decoration.getConfig());
			} else if (tileObject instanceof GroundObject) {
				orientation = HDUtils.getModelPreOrientation(((GroundObject) tileObject).getConfig());
			}
		} else if (object instanceof GraphicsObject) {
			GraphicsObject graphicsObject = (GraphicsObject) object;
			LocalPoint location = graphicsObject.getLocation();
			if (location == null)
				return List.of();
			centerX = location.getX();
			centerY = location.getY();
			centerZ = graphicsObject.getZ();
		} else if (object instanceof Projectile) {
			Projectile projectile = (Projectile) object;
			centerX = (int) projectile.getX();
			centerY = (int) projectile.getY();
			centerZ = (int) projectile.getZ();
			orientation = projectile.getOrientation();
		} else {
			return List.of();
		}

		if (worldView == null)
			return List.of();
		orientation &= 2047;
		int vertexCount = model.getVerticesCount();
		float[] verticesX = model.getVerticesX();
		float[] verticesY = model.getVerticesY();
		float[] verticesZ = model.getVerticesZ();
		int[] canvasX = new int[vertexCount];
		int[] canvasY = new int[vertexCount];
		modelToCanvas(client, worldView, vertexCount, centerX, centerY, centerZ, orientation,
			verticesX, verticesZ, verticesY, canvasX, canvasY);
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
		for (int i = 0; i < vertexCount; i++) {
			if (canvasX[i] == Integer.MIN_VALUE || canvasY[i] == Integer.MIN_VALUE)
				continue;
			minX = Math.min(minX, canvasX[i]);
			minY = Math.min(minY, canvasY[i]);
			maxX = Math.max(maxX, canvasX[i]);
			maxY = Math.max(maxY, canvasY[i]);
		}

		int[] faceColors = model.getFaceColors1();
		int[] faceColors3 = model.getFaceColors3();
		int[] indices1 = model.getFaceIndices1();
		int[] indices2 = model.getFaceIndices2();
		int[] indices3 = model.getFaceIndices3();
		if (faceColors == null || faceColors3 == null || indices1 == null || indices2 == null || indices3 == null)
			return List.of();

		List<Shape> shapes = new ArrayList<>();
		for (int face = 0; face < model.getFaceCount(); face++) {
			if (faceColors[face] != color || faceColors3[face] == -2)
				continue;
			int a = indices1[face], b = indices2[face], c = indices3[face];
			if (a < 0 || b < 0 || c < 0 || a >= vertexCount || b >= vertexCount || c >= vertexCount ||
				canvasX[a] == Integer.MIN_VALUE || canvasX[b] == Integer.MIN_VALUE || canvasX[c] == Integer.MIN_VALUE ||
				canvasY[a] == Integer.MIN_VALUE || canvasY[b] == Integer.MIN_VALUE || canvasY[c] == Integer.MIN_VALUE)
				continue;
			Polygon triangle = new Polygon(
				new int[] { canvasX[a], canvasX[b], canvasX[c] },
				new int[] { canvasY[a], canvasY[b], canvasY[c] }, 3);
			if (!triangle.getBounds().isEmpty())
				shapes.add(triangle);
		}

		Shape referenceHull = object instanceof Actor ? ((Actor) object).getConvexHull()
			: object instanceof TileObject ? ((TileObject) object).getClickbox() : null;
		if (referenceHull != null && minX != Integer.MAX_VALUE && !shapes.isEmpty()) {
			Rectangle projectedBounds = new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
			Rectangle hullBounds = referenceHull.getBounds();
			int offsetX = (int) Math.round(hullBounds.getCenterX() - projectedBounds.getCenterX());
			int offsetY = (int) Math.round(hullBounds.getCenterY() - projectedBounds.getCenterY());
			if (offsetX != 0 || offsetY != 0) {
				AffineTransform translation = AffineTransform.getTranslateInstance(offsetX, offsetY);
				shapes.replaceAll(shape -> translation.createTransformedShape(shape));
			}
		}
		return shapes;
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

	private void setupOverrideForImport(int uuid, @Nullable ModelOverride source, String filename, boolean makeUnique,
		boolean importVertexColors) {
		if (source == null) {
			ModelOverride definition = new ModelOverride();
			definition.description = "UNKNOWN";
			definition.model = filename;
			definition.vertexColors = importVertexColors ? modelImporter.loadVertexColors(filename) : null;
			setOnlyId(definition, uuid);
			history.execute(() -> removeDefinition(definition), () -> addDefinition(definition));
			return;
		}

		ModelOverride definition = makeUnique && isSharedDefinition(source) ? makeUnique(uuid, source) : source;
		if (!Objects.equals(definition.model, filename))
			history.setField(field(definition, "model"), definition, filename, false);
		int[] vertexColors = importVertexColors ? modelImporter.loadVertexColors(filename) : null;
		history.setField(field(definition, "vertexColors"), definition, vertexColors, false);
	}

	private static Field field(ModelOverride definition, String name) {
		for (Field field : OverrideFields.fields(definition))
			if (field.getName().equals(name))
				return field;
		throw new IllegalStateException("Missing model override field: " + name);
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
