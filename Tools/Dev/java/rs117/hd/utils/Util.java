package rs117.hd.utils;

import lombok.RequiredArgsConstructor;
import net.runelite.api.*;

import static rs117.hd.utils.MathUtils.abs;
import static rs117.hd.utils.MathUtils.max;
import static rs117.hd.utils.MathUtils.min;

public final class Util {

	private static final float DET_EPS  = 1e-12f; // only rejects degenerate/parallel cases
	private static final float BARY_EPS = 1e-4f;  // edge tolerance to avoid cracks between triangles

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

	public static float rayIntersectAABB(Ray r,
		float minX, float minY, float minZ,
		float maxX, float maxY, float maxZ
	) {
		// The ray is normalized, so near is the distance to the first forward hit.
		float near = 0, far = Float.POSITIVE_INFINITY;
		float first, second;
		if (abs(r.dx) < 1e-6f) {
			if (r.x < minX || r.x > maxX) return -1;
		} else {
			first = (minX - r.x) / r.dx;
			second = (maxX - r.x) / r.dx;
			near = max(near, min(first, second)); far = min(far, max(first, second));
		}
		if (abs(r.dy) < 1e-6f) {
			if (r.y < minY || r.y > maxY) return -1;
		} else {
			first = (minY - r.y) / r.dy;
			second = (maxY - r.y) / r.dy;
			near = max(near, min(first, second)); far = min(far, max(first, second));
		}
		if (abs(r.dz) < 1e-6f) {
			if (r.z < minZ || r.z > maxZ) return -1;
		} else {
			first = (minZ - r.z) / r.dz;
			second = (maxZ - r.z) / r.dz;
			near = max(near, min(first, second)); far = min(far, max(first, second));
		}
		if (near > far) return -1;
		return far < 0 ? -1 : near;
	}

	/**
	 * Returns the hit distance t in [0, tMax], or Float.POSITIVE_INFINITY on a miss.
	 * Pass the current closest hit as tMax so farther hits are rejected early.
	 */
	public static float rayTriangle(Ray r,
									 float ax, float ay, float az,
									 float bx, float by, float bz,
									 float cx, float cy, float cz,
									 float tMax) {

		float e1x = bx - ax, e1y = by - ay, e1z = bz - az;
		float e2x = cx - ax, e2y = cy - ay, e2z = cz - az;

		// p = d × e2
		float px = r.dy * e2z - r.dz * e2y;
		float py = r.dz * e2x - r.dx * e2z;
		float pz = r.dx * e2y - r.dy * e2x;

		float det = e1x * px + e1y * py + e1z * pz;

		// Fold the sign into the math so every test below works on |det|
		// and no division is needed until we have a confirmed hit.
		float sign = det < 0 ? -1f : 1f;
		float absDet = det * sign;
		if (absDet < DET_EPS) return Float.POSITIVE_INFINITY; // parallel or degenerate

		float tx = r.x - ax, ty = r.y - ay, tz = r.z - az;

		float u = (tx * px + ty * py + tz * pz) * sign;
		float lo = -BARY_EPS * absDet;
		float hi = (1f + BARY_EPS) * absDet;
		if (u < lo || u > hi) return Float.POSITIVE_INFINITY;

		// q = tvec × e1
		float qx = ty * e1z - tz * e1y;
		float qy = tz * e1x - tx * e1z;
		float qz = tx * e1y - ty * e1x;

		float v = (r.dx * qx + r.dy * qy + r.dz * qz) * sign;
		if (v < lo || u + v > hi) return Float.POSITIVE_INFINITY;

		float t = (e2x * qx + e2y * qy + e2z * qz) * sign;
		if (t < 0f || t > tMax * absDet) return Float.POSITIVE_INFINITY;

		return t / absDet; // the only division, and only on a hit
	}

	@RequiredArgsConstructor
	public static final class Ray {
		public final float x, y, z, dx, dy, dz;
	}
}
