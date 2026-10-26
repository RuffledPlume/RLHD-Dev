package rs117.hd;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("117hd-developer")
public interface DeveloperConfig extends Config {
	String CONFIG_GROUP = "117hd-developer";

	String KEY_INSPECTOR_UNDO_HISTORY = "inspectorUndoHistory";
	@Range(min = 0, max = 1000)
	@ConfigItem(
		keyName = KEY_INSPECTOR_UNDO_HISTORY,
		position = 11,
		name = "Inspector undo history",
		description = "Maximum number of inspector edits that can be undone or redone; 0 disables history."
	)
	default int inspectorUndoHistory() { return 20; }

	/*====== Inspector and profiler UI state ======*/

	String KEY_INSPECTOR_PANEL_WIDTH = "inspectorPanelWidth";
	@ConfigItem(keyName = KEY_INSPECTOR_PANEL_WIDTH, hidden = true, name = "", description = "")
	default int inspectorPanelWidth() { return 620; }

}
