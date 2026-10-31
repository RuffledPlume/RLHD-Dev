package rs117.hd.overlays.inspectors;

import java.awt.Component;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import com.google.gson.JsonElement;
import javax.annotation.Nullable;
import rs117.hd.scene.model_overrides.ModelOverride;

/**
 * The structured result of an inspection. Inspectors append sections of rows, which the panel turns
 * into labels or, for rows bound to an override field, into editors.
 */
public final class InspectorOutput {
	public static final class Row {
		public final String key;
		public final String value;
		@Nullable
		public final Field field;
		public final Object fieldValue;
		public final Object editorData;
		@Nullable
		public final String actionLabel;
		@Nullable
		public final Runnable action;

		private Row(String key, String value, @Nullable Field field, Object fieldValue, Object editorData,
			@Nullable String actionLabel, @Nullable Runnable action) {
			this.key = key;
			this.value = value;
			this.field = field;
			this.fieldValue = fieldValue;
			this.editorData = editorData;
			this.actionLabel = actionLabel;
			this.action = action;
		}
	}

	public static final class ObjectIdSearch {
		public final Map<String, Integer> objectNames;
		public final Set<Integer> currentIds;
		public final IntConsumer onPick;

		private ObjectIdSearch(Map<String, Integer> objectNames, Set<Integer> currentIds, IntConsumer onPick) {
			this.objectNames = objectNames;
			this.currentIds = currentIds;
			this.onPick = onPick;
		}
	}

	public static final class Section {
		public final String title;
		public final List<Row> rows = new ArrayList<>();
		public final List<Section> children = new ArrayList<>();
		public final List<Action> actions = new ArrayList<>();
		@Nullable
		public ObjectIdSearch objectIdSearch;
		@Nullable
		public ModelOverrideSearch modelOverrideSearch;
		/** The override instance whose fields this section edits, or null for read-only sections. */
		@Nullable
		public final Object target;
		public final boolean collapsible;
		public final boolean initiallyCollapsed;
		public boolean allowAddingFields = true;
		private final Map<Field, Object> fieldValues = new HashMap<>();
		private final Map<Field, String> fieldTexts = new HashMap<>();

		private Section(String title, @Nullable Object target, boolean collapsible, boolean initiallyCollapsed) {
			this.title = title;
			this.target = target;
			this.collapsible = collapsible;
			this.initiallyCollapsed = initiallyCollapsed;
			if (target != null) {
				for (Field field : OverrideFields.fields(target)) {
					fieldValues.put(field, snapshot(OverrideFields.get(field, target)));
					fieldTexts.put(field, OverrideFields.format(field, target));
				}
			}
		}

		public Section line(String key, Object value) {
			rows.add(new Row(key, String.valueOf(value), null, null, null, null, null));
			return this;
		}

		public Section lineAction(String key, Object value, String actionLabel, Runnable action) {
			rows.add(new Row(key, String.valueOf(value), null, null, null, actionLabel, action));
			return this;
		}

		public Section field(Field field) {
			return field(field.getName(), field, null);
		}

		public Section field(String key, Field field) {
			return field(key, field, null);
		}

		public Section field(Field field, Object editorData) {
			return field(field.getName(), field, editorData);
		}

		private Section field(String key, Field field, Object editorData) {
			rows.add(new Row(key, fieldTexts.get(field), field, fieldValues.get(field), editorData, null, null));
			return this;
		}

		public Object fieldValue(Field field) {
			return fieldValues.get(field);
		}

		public String fieldText(Field field) {
			return fieldTexts.get(field);
		}

		public Section objectIdSearch(Map<String, Integer> objectNames, Set<Integer> currentIds, IntConsumer onPick) {
			objectIdSearch = new ObjectIdSearch(objectNames, currentIds, onPick);
			return this;
		}

		public Section modelOverrideSearch(List<ModelOverride> definitions, Consumer<ModelOverride> onPick) {
			modelOverrideSearch = new ModelOverrideSearch(definitions, onPick);
			return this;
		}

		public Section child(String title, @Nullable Object target, boolean initiallyCollapsed) {
			Section child = new Section(title, target, true, initiallyCollapsed);
			children.add(child);
			return child;
		}

		public Section allowAddingFields(boolean allow) {
			allowAddingFields = allow;
			return this;
		}

		public Section action(String label, Runnable callback) {
			return action(label, true, callback);
		}

		public Section action(String label, boolean enabled, Runnable callback) {
			actions.add(new Action(label, enabled, callback));
			return this;
		}

		public Section actionWithBusy(String label, boolean enabled, String busyMessage,
			String completionMessage, Runnable callback) {
			actions.add(new Action(label, enabled, callback, busyMessage, completionMessage));
			return this;
		}

		public Section actionWithOptions(String label, List<ActionOption> options, String busyMessage,
			String completionMessage, BiConsumer<boolean[], Runnable> callback) {
			actions.add(new Action(label, true, options, busyMessage, completionMessage, callback));
			return this;
		}

		public Section uiAction(String label, boolean enabled, Consumer<Component> callback) {
			actions.add(new Action(label, enabled, callback));
			return this;
		}
	}

	public static final class Action {
		public final String label;
		public final boolean enabled;
		@Nullable
		public final Runnable callback;
		public final List<ActionOption> options;
		@Nullable
		public final BiConsumer<boolean[], Runnable> optionsCallback;
		@Nullable
		public final String busyMessage;
		@Nullable
		public final String completionMessage;
		@Nullable
		public final Consumer<Component> uiCallback;

		private Action(String label, boolean enabled, Runnable callback) {
			this(label, enabled, callback, null, null);
		}

		private Action(String label, boolean enabled, Runnable callback, @Nullable String busyMessage,
			@Nullable String completionMessage) {
			this.label = label;
			this.enabled = enabled;
			this.callback = callback;
			this.options = List.of();
			this.optionsCallback = null;
			this.busyMessage = busyMessage;
			this.completionMessage = completionMessage;
			this.uiCallback = null;
		}

		private Action(String label, boolean enabled, List<ActionOption> options, String busyMessage,
			String completionMessage, BiConsumer<boolean[], Runnable> callback) {
			this.label = label;
			this.enabled = enabled;
			this.callback = null;
			this.options = List.copyOf(options);
			this.optionsCallback = callback;
			this.busyMessage = busyMessage;
			this.completionMessage = completionMessage;
			this.uiCallback = null;
		}

		private Action(String label, boolean enabled, Consumer<Component> callback) {
			this.label = label;
			this.enabled = enabled;
			this.callback = null;
			this.options = List.of();
			this.optionsCallback = null;
			this.busyMessage = null;
			this.completionMessage = null;
			this.uiCallback = callback;
		}
	}

	public static final class ActionOption {
		public final String label;
		public final String toolTip;
		public final boolean initiallySelected;

		public ActionOption(String label, String toolTip, boolean initiallySelected) {
			this.label = label;
			this.toolTip = toolTip;
			this.initiallySelected = initiallySelected;
		}
	}

	public static final class ModelOverrideSearch {
		public final List<ModelOverride> definitions;
		public final Consumer<ModelOverride> onPick;

		private ModelOverrideSearch(List<ModelOverride> definitions, Consumer<ModelOverride> onPick) {
			this.definitions = definitions;
			this.onPick = onPick;
		}
	}

	private final List<Section> sections = new ArrayList<>();
	private String title = "";

	public List<Section> getSections() {
		return sections;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public boolean isEmpty() {
		return sections.isEmpty();
	}

	public Section section(String title) {
		return section(title, null);
	}

	/** Starts a section whose rows may be edited in place on the given override. */
	public Section section(String title, @Nullable Object target) {
		Section section = new Section(title, target, false, false);
		sections.add(section);
		return section;
	}

	private static Object snapshot(Object value) {
		if (value instanceof JsonElement)
			return ((JsonElement) value).deepCopy();
		if (value != null && value.getClass().isArray()) {
			int length = Array.getLength(value);
			Object copy = Array.newInstance(value.getClass().getComponentType(), length);
			System.arraycopy(value, 0, copy, 0, length);
			return copy;
		}
		return value;
	}
}
