package rs117.hd.overlays.inspectors;

import com.google.gson.JsonElement;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;
import rs117.hd.DeveloperConfig;

/** Bounded undo and redo history for in-memory inspector edits. */
@Singleton
public final class InspectorHistory {
	private static final int MAX_HISTORY = 1000;

	private final DeveloperConfig config;
	private final Deque<Change> undo = new ArrayDeque<>();
	private final Deque<Change> redo = new ArrayDeque<>();
	private Runnable changeListener = () -> {};
	private FieldChange coalescingChange;
	private int limit;
	private boolean restoring;

	@Inject
	public InspectorHistory(DeveloperConfig config) {
		this.config = config;
		updateLimit();
	}

	public void setChangeListener(Runnable listener) {
		changeListener = listener == null ? () -> {} : listener;
		notifyChanged();
	}

	public boolean canUndo() {
		updateLimit();
		return !undo.isEmpty();
	}

	public boolean canRedo() {
		updateLimit();
		return !redo.isEmpty();
	}

	public boolean undo() {
		updateLimit();
		if (undo.isEmpty()) return false;
		Change change = undo.removeLast();
		endCoalescing();
		restoring = true;
		try {
			change.undo();
		} finally {
			restoring = false;
		}
		redo.addLast(change);
		trimHistory();
		notifyChanged();
		return true;
	}

	public boolean redo() {
		updateLimit();
		if (redo.isEmpty()) return false;
		Change change = redo.removeLast();
		endCoalescing();
		restoring = true;
		try {
			change.redo();
		} finally {
			restoring = false;
		}
		undo.addLast(change);
		trimHistory();
		notifyChanged();
		return true;
	}

	/** Assigns a field and records the prior value, optionally merging consecutive text edits. */
	public boolean setField(Field field, Object target, Object value, boolean coalesce) {
		return setField(field, target, value, coalesce, null);
	}

	/** Assigns a field and invokes an optional callback after setting it and when restoring history. */
	public boolean setField(Field field, Object target, Object value, boolean coalesce, Runnable afterSet) {
		if (!coalesce) endCoalescing();
		Object before = snapshot(OverrideFields.get(field, target));
		Object after = snapshot(value);
		if (Objects.deepEquals(before, after)) return true;
		if (!OverrideFields.set(field, target, after)) return false;
		if (afterSet != null) afterSet.run();
		if (!restoring) record(new FieldChange(field, target, before, after, afterSet), coalesce);
		return true;
	}

	/** Applies an action and records the callbacks needed to reverse and replay it. */
	public void execute(Runnable undoAction, Runnable redoAction) {
		endCoalescing();
		redoAction.run();
		if (!restoring) record(new ActionChange(undoAction, redoAction), false);
	}

	/** Ends a text-edit group, for example when its editor loses focus. */
	public void endCoalescing() {
		coalescingChange = null;
	}

	private void record(Change change, boolean coalesce) {
		updateLimit();
		if (limit == 0) {
			undo.clear();
			redo.clear();
			notifyChanged();
			return;
		}

		if (coalesce && change instanceof FieldChange && !undo.isEmpty()) {
			Change previous = undo.peekLast();
			if (previous == coalescingChange && previous instanceof FieldChange
				&& ((FieldChange) previous).sameField((FieldChange) change)) {
				FieldChange fieldChange = (FieldChange) previous;
				fieldChange.after = ((FieldChange) change).after;
				if (Objects.deepEquals(fieldChange.before, fieldChange.after)) {
					undo.removeLast();
					endCoalescing();
				}
				redo.clear();
				notifyChanged();
				return;
			}
		}

		undo.addLast(change);
		coalescingChange = coalesce && change instanceof FieldChange ? (FieldChange) change : null;
		redo.clear();
		trimHistory();
		notifyChanged();
	}

	private void updateLimit() {
		int newLimit = Math.max(0, Math.min(MAX_HISTORY, config.inspectorUndoHistory()));
		if (newLimit == limit) return;
		limit = newLimit;
		endCoalescing();
		if (limit == 0) {
			undo.clear();
			redo.clear();
		} else {
			trimHistory();
		}
		notifyChanged();
	}

	private void trimHistory() {
		while (undo.size() + redo.size() > limit) {
			if (!undo.isEmpty()) undo.removeFirst();
			else redo.removeLast();
		}
	}

	private void notifyChanged() {
		changeListener.run();
	}

	private static Object snapshot(Object value) {
		return value instanceof JsonElement ? ((JsonElement) value).deepCopy() : value;
	}

	private interface Change {
		void undo();
		void redo();
	}

	private static final class FieldChange implements Change {
		private final Field field;
		private final Object target;
		private final Object before;
		private final Runnable afterSet;
		private Object after;

		private FieldChange(Field field, Object target, Object before, Object after, Runnable afterSet) {
			this.field = field;
			this.target = target;
			this.before = before;
			this.after = after;
			this.afterSet = afterSet;
		}

		private boolean sameField(FieldChange other) {
			return target == other.target && field.equals(other.field);
		}

		@Override public void undo() { OverrideFields.set(field, target, before); if (afterSet != null) afterSet.run(); }
		@Override public void redo() { OverrideFields.set(field, target, after); if (afterSet != null) afterSet.run(); }
	}

	private static final class ActionChange implements Change {
		private final Runnable undoAction;
		private final Runnable redoAction;

		private ActionChange(Runnable undoAction, Runnable redoAction) {
			this.undoAction = undoAction;
			this.redoAction = redoAction;
		}

		@Override public void undo() { undoAction.run(); }
		@Override public void redo() { redoAction.run(); }
	}
}
