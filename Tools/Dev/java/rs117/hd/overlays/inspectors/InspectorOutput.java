package rs117.hd.overlays.inspectors;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;
import javax.annotation.Nullable;

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
		@Nullable
		public final String actionLabel;
		@Nullable
		public final Runnable action;

		private Row(String key, String value, @Nullable Field field, @Nullable String actionLabel, @Nullable Runnable action) {
			this.key = key;
			this.value = value;
			this.field = field;
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
		/** The override instance whose fields this section edits, or null for read-only sections. */
		@Nullable
		public final Object target;
		public final boolean collapsible;
		public final boolean initiallyCollapsed;

		private Section(String title, @Nullable Object target, boolean collapsible, boolean initiallyCollapsed) {
			this.title = title;
			this.target = target;
			this.collapsible = collapsible;
			this.initiallyCollapsed = initiallyCollapsed;
		}

		public Section line(String key, Object value) {
			rows.add(new Row(key, String.valueOf(value), null, null, null));
			return this;
		}

		public Section lineAction(String key, Object value, String actionLabel, Runnable action) {
			rows.add(new Row(key, String.valueOf(value), null, actionLabel, action));
			return this;
		}

		public Section field(Field field) {
			rows.add(new Row(field.getName(), OverrideFields.format(field, target), field, null, null));
			return this;
		}

		public Section objectIdSearch(Map<String, Integer> objectNames, Set<Integer> currentIds, IntConsumer onPick) {
			objectIdSearch = new ObjectIdSearch(objectNames, currentIds, onPick);
			return this;
		}

		public Section child(String title, @Nullable Object target, boolean initiallyCollapsed) {
			Section child = new Section(title, target, true, initiallyCollapsed);
			children.add(child);
			return child;
		}

		public Section action(String label, Runnable callback) {
			return action(label, true, callback);
		}

		public Section action(String label, boolean enabled, Runnable callback) {
			actions.add(new Action(label, enabled, callback));
			return this;
		}
	}

	public static final class Action {
		public final String label;
		public final boolean enabled;
		public final Runnable callback;

		private Action(String label, boolean enabled, Runnable callback) {
			this.label = label;
			this.enabled = enabled;
			this.callback = callback;
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
}
