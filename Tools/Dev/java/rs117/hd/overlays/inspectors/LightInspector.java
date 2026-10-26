package rs117.hd.overlays.inspectors;

import javax.inject.Inject;
import rs117.hd.scene.GamevalManager;
import rs117.hd.scene.lights.Light;
import rs117.hd.scene.lights.LightDefinition;

public final class LightInspector implements Inspector {
	@Override
	public boolean isApplicable(Object selected) {
		return selected instanceof Light;
	}

	@Override
	public void draw(Object selected, InspectorOutput out, GamevalManager.Handle gamevals) {
		LightDefinition definition = ((Light) selected).def;
		String description = definition.description;
		out.setTitle("Light: " + (description == null || description.isBlank() ? "Unknown" : description));
		var section = out.section("Light Definition", definition);
		for (var field : OverrideFields.changedFields(definition))
			section.field(field);
	}
}
