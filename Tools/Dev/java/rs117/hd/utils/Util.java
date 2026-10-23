package rs117.hd.utils;

import net.runelite.api.*;

import static rs117.hd.utils.MathUtils.abs;
import static rs117.hd.utils.MathUtils.max;
import static rs117.hd.utils.MathUtils.min;

public final class Util {
	private Util() {}

	public static Renderable getRenderable(Object object) {
		if (object instanceof Renderable) return (Renderable) object;
		if (object instanceof GameObject) return ((GameObject) object).getRenderable();
		if (object instanceof WallObject) return ((WallObject) object).getRenderable1();
		if (object instanceof DecorativeObject) return ((DecorativeObject) object).getRenderable();
		if (object instanceof GroundObject) return ((GroundObject) object).getRenderable();
		return null;
	}

	public static Model getModel(Renderable renderable) {
		if (renderable instanceof Model) return (Model) renderable;
		return renderable == null ? null : renderable.getModel();
	}

	public static float rayIntersectAABB(
		float originX, float originY, float originZ,
		float directionX, float directionY, float directionZ,
		float minX, float minY, float minZ,
		float maxX, float maxY, float maxZ
	) {
		// The ray is normalized, so near is the distance to the first forward hit.
		float near = 0, far = Float.POSITIVE_INFINITY;
		float first, second;
		if (abs(directionX) < 1e-6f) {
			if (originX < minX || originX > maxX) return -1;
		} else {
			first = (minX - originX) / directionX;
			second = (maxX - originX) / directionX;
			near = max(near, min(first, second)); far = min(far, max(first, second));
		}
		if (abs(directionY) < 1e-6f) {
			if (originY < minY || originY > maxY) return -1;
		} else {
			first = (minY - originY) / directionY;
			second = (maxY - originY) / directionY;
			near = max(near, min(first, second)); far = min(far, max(first, second));
		}
		if (abs(directionZ) < 1e-6f) {
			if (originZ < minZ || originZ > maxZ) return -1;
		} else {
			first = (minZ - originZ) / directionZ;
			second = (maxZ - originZ) / directionZ;
			near = max(near, min(first, second)); far = min(far, max(first, second));
		}
		if (near > far) return -1;
		return far < 0 ? -1 : near;
	}
}
