package rs117.hd.overlays.inspectors;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.TreeSet;
import javax.inject.Inject;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import rs117.hd.HdPlugin;
import rs117.hd.data.ObjectType;
import rs117.hd.scene.GamevalManager;
import rs117.hd.scene.MaterialManager;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.TileOverrideManager;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.tile_overrides.TileOverride;
import rs117.hd.utils.GsonUtils;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.ResourcePath.path;
import static rs117.hd.scene.tile_overrides.TileOverride.OVERLAY_FLAG;
import static rs117.hd.utils.HDUtils.HIDDEN_HSL;

@Slf4j
public final class TileInspector implements Inspector {

	private static final ResourcePath TILE_OVERRIDES_PATH = Props
			.getFile("rlhd.tile-overrides-path", () -> path(TileOverrideManager.class, "tile_overrides.json"));

	@Inject
	private HdPlugin plugin;

	@Inject
	private TileOverrideManager tileOverrideManager;

	@Inject
	private MaterialManager materialManager;

	@Inject
	private ProceduralGenerator proceduralGenerator;

	@Inject
	private Client client;

	@Override
	public boolean isApplicable(Object selected) {
		return selected instanceof Tile;
	}

	@Override
	public void draw(Object selected, InspectorOutput out, GamevalManager.Handle gamevals) {
		Tile tile = (Tile) selected;
		out.setTitle("Tile: " + tile.getWorldLocation());

		var sceneContext = plugin.getSceneContext();
		var identity = out.section("Tile");
		identity.line("World Position", tile.getWorldLocation());
		identity.line("Local Position", tile.getLocalLocation());
		identity.line("Scene Position", tile.getSceneLocation());
		identity.line("Plane", tile.getPlane());
		identity.line("Render Level", tile.getRenderLevel());
		identity.line("Bridge", tile.getBridge() == null ? "No" : "Yes");

		if (sceneContext == null) {
			identity.line("Scene data", "Unavailable while the scene is not loaded");
			return;
		}

		int sceneX = tile.getSceneLocation().getX();
		int sceneY = tile.getSceneLocation().getY();
		int renderLevel = tile.getRenderLevel();
		int[] worldPosition = sceneContext.sceneToWorld(sceneX, sceneY, renderLevel);
		addSceneDetails(identity, sceneContext, sceneX, sceneY, renderLevel, worldPosition);

		addSurfaceDetails(out.section("Surface"), tile, sceneContext, worldPosition, sceneX, sceneY, renderLevel);
		addObjectDetails(out.section("Objects"), tile, gamevals, sceneContext);

		TileOverride runtimeOverride = tileOverrideManager.getOverride(sceneContext, tile);
		TileOverride definition = tileOverrideManager.getTileOverrideDefinition(runtimeOverride);
		var overrideSection = out.section(
			definition == null ? "Tile Override" : "Tile Override: " + definition,
			definition
		);
		if (definition == null) {
			overrideSection.line("Status", "No matching tile override");
			return;
		}
		for (var field : OverrideFields.changedFields(definition))
			overrideSection.field(field);
	}

	private static void addSceneDetails(InspectorOutput.Section section, SceneContext context,
		int sceneX, int sceneY, int plane, int[] worldPosition) {
		section.line("Zone", (sceneX + context.sceneOffset) / 8 + ", " + (sceneY + context.sceneOffset) / 8 + ", " + plane);
		section.line("Region", HDUtils.worldToRegionID(worldPosition) + " (" + (worldPosition[0] >> 6)
			+ ", " + (worldPosition[1] >> 6) + ")");
		section.line("Area", context.currentArea == null ? "None" : context.currentArea.name);
		for (var environment : context.environments) {
			if (environment.area != null && environment.area.containsPoint(worldPosition)) {
				section.line("Environment", environment.key + " (" + environment.area.name + ")");
				break;
			}
		}
	}

	private void addSurfaceDetails(InspectorOutput.Section section, Tile tile, SceneContext context,
		int[] worldPosition, int sceneX, int sceneY, int plane) {
		SceneTilePaint paint = tile.getSceneTilePaint();
		SceneTileModel model = tile.getSceneTileModel();
		if (paint == null && model == null) {
			section.line("Geometry", "No TilePaint or TileModel");
			return;
		}

		int x = sceneX + context.sceneOffset;
		int y = sceneY + context.sceneOffset;
		int overlayId = context.scene.getOverlayIds()[plane][x][y];
		int underlayId = context.scene.getUnderlayIds()[plane][x][y];
		ResolvedOverride overlay = resolveOverride(tile, worldPosition, OVERLAY_FLAG | overlayId);
		ResolvedOverride underlay = resolveOverride(tile, worldPosition, underlayId);
		section.line("Overlay", overlayId + " → " + overlay.path);
		section.line("Overlay Ground Material", materialName(overlay.override.groundMaterial.getRandomMaterial(worldPosition))
			+ " (" + overlay.override.groundMaterial + ")");
		section.line("Underlay", underlayId + " → " + underlay.path);
		section.line("Underlay Ground Material", materialName(underlay.override.groundMaterial.getRandomMaterial(worldPosition))
			+ " (" + underlay.override.groundMaterial + ")");

		section.line("Geometry", paint == null ? "TileModel" : "TilePaint");
		if (paint != null) {
			int textureId = paint.getTexture();
			Material material = materialManager.fromVanillaTexture(textureId);
			section.line("Texture", textureId);
			section.line("Material", materialName(material));
			section.line("HSL", hsl(tile));
			section.line("Water Type", proceduralGenerator.seasonalWaterType(
				tileOverrideManager.getOverride(context, tile, worldPosition,
					OVERLAY_FLAG | overlayId, underlayId), textureId));
		} else {
			section.line("Face Count", model.getFaceX().length);
			section.line("HSL", hsl(tile));
			int[] textureIds = model.getTriangleTextureId();
			if (textureIds != null) {
				Set<String> materials = new TreeSet<>();
				for (int textureId : textureIds)
					materials.add(materialName(materialManager.fromVanillaTexture(textureId)) + " (" + textureId + ")");
				section.line("Materials", materials.isEmpty() ? "None" : String.join(", ", materials));
			}
		}
	}

	private ResolvedOverride resolveOverride(Tile tile, int[] worldPosition, int id) {
		var variables = SceneContext.TILE_OVERRIDE_VARIABLES.get();
		variables.setTile(tile);
		try {
			TileOverride override = tileOverrideManager.getOverrideBeforeReplacements(worldPosition, id);
			StringBuilder path = new StringBuilder(override.toString());
			for (int i = 0; i < 32; i++) {
				TileOverride replacement = override.resolveNextReplacement(variables);
				if (replacement == override)
					break;
				path.append(" → ").append(replacement);
				override = replacement;
			}
			return new ResolvedOverride(override, path.toString());
		} finally {
			variables.setTile(null);
		}
	}

	private static final class ResolvedOverride {
		private final TileOverride override;
		private final String path;

		private ResolvedOverride(TileOverride override, String path) {
			this.override = override;
			this.path = path;
		}
	}

	private static String hsl(Tile tile) {
		int[] hsl = new int[3];
		int raw = HDUtils.getSouthWesternMostTileColor(hsl, tile);
		return raw == HIDDEN_HSL ? "Hidden" : raw + " " + Arrays.toString(hsl);
	}

	private static String materialName(Material material) {
		return material == null ? "None" : material.name;
	}

	private void addObjectDetails(InspectorOutput.Section section, Tile tile,
		GamevalManager.Handle gamevals, SceneContext context) {
		boolean hasObjects = false;
		GameObject[] gameObjects = tile.getGameObjects();
		if (gameObjects != null) {
			int count = 0;
			for (GameObject object : gameObjects) {
				if (object != null) {
					addTileObject(section, "Game Object " + count++, object, object.getRenderable(), gamevals,
						object.getConfig(), "object orientation " + object.getModelOrientation());
					hasObjects = true;
				}
			}
		}

		WallObject wall = tile.getWallObject();
		if (wall != null) {
			hasObjects = true;
			addTileObject(section, "Wall Object 1", wall, wall.getRenderable1(), gamevals, wall.getConfig(),
				"wall orientation " + wall.getOrientationA());
			if (wall.getRenderable2() != null)
				addTileObject(section, "Wall Object 2", wall, wall.getRenderable2(), gamevals, wall.getConfig(),
					"wall orientation " + wall.getOrientationB());
			section.line("Wall Type", ObjectType.fromConfig(wall.getConfig()));
		}

		DecorativeObject decorative = tile.getDecorativeObject();
		if (decorative != null) {
			hasObjects = true;
			if (decorative.getRenderable() != null)
				addTileObject(section, "Decorative Object 1", decorative, decorative.getRenderable(), gamevals, decorative.getConfig(),
					"offset " + decorative.getXOffset() + ", " + decorative.getYOffset());
			if (decorative.getRenderable2() != null)
				addTileObject(section, "Decorative Object 2", decorative, decorative.getRenderable2(), gamevals, decorative.getConfig(),
					"offset " + decorative.getXOffset2() + ", " + decorative.getYOffset2());
			section.line("Decorative Type", ObjectType.fromConfig(decorative.getConfig()));
		}

		GroundObject ground = tile.getGroundObject();
		if (ground != null) {
			hasObjects = true;
			addTileObject(section, "Ground Object", ground, ground.getRenderable(), gamevals, ground.getConfig(), "");
			section.line("Ground Type", ObjectType.fromConfig(ground.getConfig()));
		}

		ItemLayer itemLayer = tile.getItemLayer();
		if (itemLayer != null) {
			hasObjects = true;
			section.line("Item Layer", "Present");
		}

		WorldView worldView = client.getWorldView(context.scene.getWorldViewId());
		if (worldView != null && addActorsAndEffects(section, tile, worldView, gamevals) > 0)
			hasObjects = true;

		if (!hasObjects)
			section.line("Objects", "None");
	}

	private void addTileObject(InspectorOutput.Section section, String label, TileObject object,
		Renderable renderable, GamevalManager.Handle gamevals, int config, String extra) {
		int id = object.getId();
		ObjectComposition definition = client.getObjectDefinition(id);
		if (definition != null && definition.getImpostorIds() != null) {
			ObjectComposition impostor = definition.getImpostor();
			if (impostor != null)
				definition = impostor;
		}
		String name = gamevals.getObjectName(id);
		String idText = (name == null ? "Object" : name) + " (" + id + ")";
		if (definition != null && definition.getId() != id)
			idText += " → " + definition.getName() + " (" + definition.getId() + ")";

		String modelInfo = modelInfo(renderable);
		section.line(label, idText + (extra.isEmpty() ? "" : " | " + extra)
			+ " | " + ObjectType.fromConfig(config)
			+ " | preorientation " + HDUtils.getModelPreOrientation(config)
			+ " | " + modelInfo);
	}

	private String modelInfo(Renderable renderable) {
		if (renderable == null)
			return "no renderable";
		Model model = renderable instanceof Model ? (Model) renderable : renderable.getModel();
		if (model == null)
			return "no model";
		String kind = renderable instanceof Model || renderable instanceof DynamicObject
			&& ((DynamicObject) renderable).getModelZbuf() != null ? "static" : "dynamic";

		Set<Integer> faceColors = new TreeSet<>();
		int[] rawColors = model.getFaceColors1();
		byte[] transparencies = model.getFaceTransparencies();
		if (rawColors != null) {
			for (int face = 0; face < rawColors.length; face++) {
				int alpha = transparencies == null ? 0xFF : 0xFF - (transparencies[face] & 0xFF);
				faceColors.add(alpha << 16 | rawColors[face]);
			}
		}

		Set<String> textures = new TreeSet<>();
		short[] textureIds = model.getFaceTextures();
		if (textureIds != null) {
			for (short textureId : textureIds) {
				if (textureId < 0)
					continue;
				Material material = materialManager.fromVanillaTexture(textureId);
				textures.add(materialName(material) + " (" + textureId + ")");
			}
		}

		String details = kind + ", " + model.getFaceCount() + " faces, " + faceColors.size() + " face colors";
		if (!textures.isEmpty())
			details += ", textures: " + String.join(", ", textures);
		return details;
	}

	private int addActorsAndEffects(InspectorOutput.Section section, Tile tile,
		WorldView worldView, GamevalManager.Handle gamevals) {
		int sceneX = tile.getSceneLocation().getX();
		int sceneY = tile.getSceneLocation().getY();
		int count = 0;
		Set<Renderable> shown = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Player player : worldView.players()) {
			LocalPoint location = player.getLocalLocation();
			if (location != null && location.getSceneX() == sceneX && location.getSceneY() == sceneY) {
				section.line("Player", player.getName() + " | orientation " + player.getOrientation()
					+ " | animation " + player.getAnimation() + " | " + modelInfo(player));
				count++;
				count += addActorSpotanims(section, shown, player, gamevals);
			}
		}
		for (NPC npc : worldView.npcs()) {
			LocalPoint location = npc.getLocalLocation();
			if (location == null)
				continue;
			int size = Math.max(0, npc.getComposition().getSize() / 2);
			if (location.getSceneX() - size <= sceneX && sceneX <= location.getSceneX() + size
				&& location.getSceneY() - size <= sceneY && sceneY <= location.getSceneY() + size) {
				String name = gamevals.getNpcName(npc.getId());
				section.line("NPC", (name == null ? npc.getName() : name + " (" + npc.getId() + ")")
					+ " | orientation " + npc.getOrientation() + " | animation " + npc.getAnimation()
					+ " | " + modelInfo(npc));
				count++;
				count += addActorSpotanims(section, shown, npc, gamevals);
			}
		}

		GameObject[] gameObjects = tile.getGameObjects();
		if (gameObjects != null) {
			for (GameObject object : gameObjects) {
				if (object == null || object.getRenderable() == null || !shown.add(object.getRenderable()))
					continue;
				Renderable renderable = object.getRenderable();
				if (renderable instanceof ActorSpotAnim) {
					ActorSpotAnim spotAnim = (ActorSpotAnim) renderable;
					String name = gamevals.getSpotanimName(spotAnim.getId());
					section.line("Actor Spot Animation", (name == null ? "Spot animation" : name) + " ("
						+ spotAnim.getId() + ") | frame " + spotAnim.getFrame() + " | " + modelInfo(spotAnim));
					count++;
				} else if (renderable instanceof GraphicsObject) {
					GraphicsObject graphics = (GraphicsObject) renderable;
					String name = gamevals.getSpotanimName(graphics.getId());
					section.line("Graphics Object", (name == null ? "Graphics object" : name) + " ("
						+ graphics.getId() + ") | animation frame " + graphics.getAnimationFrame() + " | " + modelInfo(graphics));
					count++;
				}
			}
		}
		for (GraphicsObject graphics : worldView.getGraphicsObjects()) {
			LocalPoint location = graphics.getLocation();
			if (location == null || location.getSceneX() != sceneX || location.getSceneY() != sceneY
				|| !shown.add(graphics))
				continue;
			String name = gamevals.getSpotanimName(graphics.getId());
			Animation animation = graphics.getAnimation();
			section.line("Graphics Object", (name == null ? "Graphics object" : name) + " ("
				+ graphics.getId() + ") | animation " + (animation == null ? -1 : animation.getId())
				+ " | frame " + graphics.getAnimationFrame() + " | cycle "
				+ (client.getGameCycle() - graphics.getStartCycle()) + " | " + modelInfo(graphics));
			count++;
		}
		return count;
	}

	private int addActorSpotanims(InspectorOutput.Section section, Set<Renderable> shown,
		Actor actor, GamevalManager.Handle gamevals) {
		int count = 0;
		for (ActorSpotAnim spotanim : actor.getSpotAnims()) {
			if (!shown.add(spotanim))
				continue;
			String name = gamevals.getSpotanimName(spotanim.getId());
			section.line("Actor Spot Animation", (name == null ? "Spot animation" : name) + " ("
				+ spotanim.getId() + ") | frame " + spotanim.getFrame() + " | cycle "
				+ (client.getGameCycle() - spotanim.getStartCycle()) + " | " + modelInfo(spotanim));
			count++;
		}
		return count;
	}

	@Override
	public void apply() {
		TileOverride[] definitions = tileOverrideManager.getTileOverrideDefinitions().toArray(TileOverride[]::new);
		tileOverrideManager.loadTileOverrides(definitions);
	}

	@Override
	public void save() {
		try {
			if (!TILE_OVERRIDES_PATH.isFileSystemResource())
				throw new IOException("Tile overrides are loaded from a classpath resource; set rlhd.tile-overrides-path to a writable file to save.");

			TileOverride[] definitions = tileOverrideManager.getTileOverrideDefinitions().toArray(TileOverride[]::new);
			GsonUtils.saveJson(TILE_OVERRIDES_PATH, plugin.getGson(), definitions, TileOverride.class, true);
		} catch (IOException | RuntimeException ex) {
			log.error("Failed to save tile overrides", ex);
		}
	}
}
