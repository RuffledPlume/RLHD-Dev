package rs117.hd.utils;

import java.util.Set;

import lombok.Getter;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Texture;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.hooks.DrawCallbacks;

/** Forwards draw callbacks while keeping SceneGeometry's roof-removal state current. */
public final class DrawCallbacksRedirector implements DrawCallbacks {
	@Getter
    private final DrawCallbacks previous;
	private final SceneGeometry sceneGeometry;

	public DrawCallbacksRedirector(DrawCallbacks previous, SceneGeometry sceneGeometry) {
		this.previous = previous;
		this.sceneGeometry = sceneGeometry;
	}

    private void updateRoofRemovalSettings(Scene scene, int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds) {
		sceneGeometry.setRoofRemovalSettings(scene, minLevel, level, maxLevel, hideRoofIds);
	}

	@Override
	public void draw(Projection projection, Scene scene, Renderable renderable, int orientation, int x, int y, int z, long hash) {
		previous.draw(projection, scene, renderable, orientation, x, y, z, hash);
	}

	@Override
	public void drawScenePaint(Scene scene, SceneTilePaint paint, int plane, int tileX, int tileZ) {
		previous.drawScenePaint(scene, paint, plane, tileX, tileZ);
	}

	@Override
	public void drawSceneTileModel(Scene scene, SceneTileModel model, int tileX, int tileZ) {
		previous.drawSceneTileModel(scene, model, tileX, tileZ);
	}

	@Override
	public void draw(int overlayColor) {
		previous.draw(overlayColor);
	}

	@Override
	public void drawScene(double cameraX, double cameraY, double cameraZ, double cameraPitch, double cameraYaw, int plane) {
		previous.drawScene(cameraX, cameraY, cameraZ, cameraPitch, cameraYaw, plane);
	}

	@Override
	public void postDrawScene() {
		previous.postDrawScene();
	}

	@Override
	public void animate(Texture texture, int diff) {
		previous.animate(texture, diff);
	}

	@Override
	public void loadScene(Scene scene) {
		previous.loadScene(scene);
	}

	@Override
	public void swapScene(Scene scene) {
		previous.swapScene(scene);
	}

	@Override
	public boolean tileInFrustum(Scene scene, float pitchSin, float pitchCos, float yawSin, float yawCos,
		int cameraX, int cameraY, int cameraZ, int plane, int msx, int msy) {
		return previous.tileInFrustum(scene, pitchSin, pitchCos, yawSin, yawCos,
			cameraX, cameraY, cameraZ, plane, msx, msy);
	}

	@Override
	public boolean zoneInFrustum(int zoneX, int zoneZ, int maxY, int minY) {
		return previous.zoneInFrustum(zoneX, zoneZ, maxY, minY);
	}

	@Override
	public void loadScene(WorldView worldView, Scene scene) {
		previous.loadScene(worldView, scene);
	}

	@Override
	public void despawnWorldView(WorldView worldView) {
		sceneGeometry.removeRoofRemovalSettings(worldView.getScene());
		previous.despawnWorldView(worldView);
	}

	@Override
	public void preSceneDraw(Scene scene, Projection entityProjection,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw,
		int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds) {
		updateRoofRemovalSettings(scene, minLevel, level, maxLevel, hideRoofIds);
		previous.preSceneDraw(scene, entityProjection, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw,
			minLevel, level, maxLevel, hideRoofIds);
	}

	@Override
	@Deprecated
	public void preSceneDraw(Scene scene,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw,
		int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds) {
		updateRoofRemovalSettings(scene, minLevel, level, maxLevel, hideRoofIds);
		previous.preSceneDraw(scene, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw,
			minLevel, level, maxLevel, hideRoofIds);
	}

	@Override
	public void postSceneDraw(Scene scene) {
		previous.postSceneDraw(scene);
	}

	@Override
	public void drawPass(Projection entityProjection, Scene scene, int pass) {
		previous.drawPass(entityProjection, scene, pass);
	}

	@Override
	public void drawZoneOpaque(Projection entityProjection, Scene scene, int zx, int zz) {
		previous.drawZoneOpaque(entityProjection, scene, zx, zz);
	}

	@Override
	public void drawZoneAlpha(Projection entityProjection, Scene scene, int level, int zx, int zz) {
		previous.drawZoneAlpha(entityProjection, scene, level, zx, zz);
	}

	@Override
	public void drawDynamic(Projection worldProjection, Scene scene, TileObject tileObject, Renderable renderable,
		Model model, int orientation, int x, int y, int z) {
		previous.drawDynamic(worldProjection, scene, tileObject, renderable, model, orientation, x, y, z);
	}

	@Override
	public void drawDynamic(int renderThreadId, Projection worldProjection, Scene scene, TileObject tileObject,
		Renderable renderable, Model model, int orientation, int x, int y, int z) {
		previous.drawDynamic(renderThreadId, worldProjection, scene, tileObject, renderable, model, orientation, x, y, z);
	}

	@Override
	public void drawTemp(Projection worldProjection, Scene scene, GameObject gameObject, Model model,
		int orientation, int x, int y, int z) {
		previous.drawTemp(worldProjection, scene, gameObject, model, orientation, x, y, z);
	}

	@Override
	public void invalidateZone(Scene scene, int zx, int zz) {
		previous.invalidateZone(scene, zx, zz);
	}
}
