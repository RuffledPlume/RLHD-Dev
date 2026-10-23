package rs117.hd.overlays.inspectors;

/** Formatting helpers for the inspector's plain text output. */
public final class InspectorOutput {
	private InspectorOutput() {}

	public static void section(StringBuilder output, String title) {
		output.append("\n▾ ").append(title).append('\n');
	}

	public static void line(StringBuilder output, String key, Object value) {
		output.append("  ").append(key).append(": ").append(value).append('\n');
	}
}
