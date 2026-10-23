package rs117.hd.overlays.inspectors;

import rs117.hd.scene.GamevalManager;

/** Renders details for selections understood by one inspector. */
public interface Inspector {
	boolean isApplicable(Object selected);

	void draw(Object selected, StringBuilder output, GamevalManager.Handle gamevals);
}
