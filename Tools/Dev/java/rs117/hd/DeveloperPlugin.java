package rs117.hd;

import com.google.inject.Injector;
import com.google.inject.Provides;
import java.awt.event.KeyEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.*;
import net.runelite.api.Client;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import rs117.hd.overlays.InspectorPanel;
import rs117.hd.overlays.InspectorSelectionOverlay;
import rs117.hd.overlays.ProfilerOverlay;
import rs117.hd.overlays.ProfilerUI;
import rs117.hd.scene.GamevalManager;
import rs117.hd.scene.ModelOverrideManager;
import rs117.hd.utils.DrawCallbacksRedirector;
import rs117.hd.utils.DeveloperTools;
import rs117.hd.utils.SceneGeometry;

@Slf4j
@Singleton
@PluginDescriptor(
	name = "117 HD Developer",
	description = "development and profiling tools for 117 HD",
	tags = {"117", "hd", "developer", "development", "profiler"}
)
@PluginDependency(HdPlugin.class)
public class DeveloperPlugin extends Plugin implements KeyListener {

	@Inject
	private ClientThread clientThread;

	@Inject
	private KeyManager keyManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private Client client;

	@Inject
	private ProfilerOverlay profilerOverlay;

	@Inject
	private HdPlugin plugin;

	@Inject
	private ProfilerUI profilerUI;

	@Inject
	private GamevalManager gamevalManager;

	@Inject
	private ModelOverrideManager modelOverrideManager;

	@Inject
	private InspectorSelectionOverlay inspectorSelectionOverlay;

	@Inject
	private Injector injector;

	@Inject
	private DeveloperConfig config;

	@Inject
	private DeveloperTools developerTools;

	@Inject
	private InspectorPanel inspectorPanel;

	@Inject
	private SceneGeometry sceneGeometry;

	private boolean frameTimingsOverlayEnabled;
	private DrawCallbacksRedirector drawCallbacksRedirector;

	@Provides
	DeveloperConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(DeveloperConfig.class);
	}

	@Override
	protected void startUp() {
		clientThread.invoke(() -> {
			installDrawCallbacksRedirector();
			inspectorSelectionOverlay.startUp();
			keyManager.registerKeyListener(this);
			developerTools.setDeveloperPluginActive(true);

			processConfigChanges();
			profilerUI.load();
			frameTimingsOverlayEnabled = profilerUI.loadOverlayEnabled();

			profilerOverlay.setActive(frameTimingsOverlayEnabled);
			if (frameTimingsOverlayEnabled)
				profilerUI.applyGraphOverlayState();

			inspectorPanel.addInspectorPanel();
		});
	}

	@Override
	protected void shutDown() {
		clientThread.invoke(() -> {
			removeDrawCallbacksRedirector();
			inspectorSelectionOverlay.shutDown();
			keyManager.unregisterKeyListener(this);
			developerTools.setDeveloperPluginActive(false);
			profilerOverlay.setActive(false);
			profilerUI.setGraphOverlayActive(false);

			inspectorPanel.removeInspectorPanel();
		});
	}

	private void installDrawCallbacksRedirector() {
		DrawCallbacks previous = client.getDrawCallbacks();
		if (previous == null) return;
		drawCallbacksRedirector = new DrawCallbacksRedirector(previous, sceneGeometry);
		client.setDrawCallbacks(drawCallbacksRedirector);
	}

	private void removeDrawCallbacksRedirector() {
		DrawCallbacksRedirector redirector = drawCallbacksRedirector;
		if (redirector == null) return;
		if (client.getDrawCallbacks() == redirector)
			client.setDrawCallbacks(redirector.getPrevious());
		drawCallbacksRedirector = null;
		sceneGeometry.clearRoofRemovalSettings();
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event) {
		InspectorPanel panel = inspectorPanel;
		if (panel == null || !panel.isSelectionMode()) return;
		event.consume();
		panel.selectAtCursor();
		SwingUtilities.invokeLater(() -> panel.setSelectionMode(false));
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event) {
		if (event.getGroup().equals(DeveloperConfig.CONFIG_GROUP))
			processConfigChanges();
	}

	private void processConfigChanges() {
		DeveloperTools.KEY_TOGGLE_TILE_INFO = config.toggleTileInfo();
		DeveloperTools.KEY_TOGGLE_FRAME_TIMINGS = config.toggleFrameTimings();
		DeveloperTools.KEY_RECORD_TIMINGS_SNAPSHOT = config.recordTimingsSnapshot();
		DeveloperTools.KEY_TOGGLE_SHADOW_MAP_OVERLAY = config.toggleShadowMapOverlay();
		DeveloperTools.KEY_TOGGLE_LIGHT_GIZMO_OVERLAY = config.toggleLightGizmoOverlay();
		DeveloperTools.KEY_TOGGLE_TILED_LIGHTING_OVERLAY = config.toggleTiledLightingOverlay();
		DeveloperTools.KEY_TOGGLE_FREEZE_FRAME = config.toggleFreezeFrame();
		DeveloperTools.KEY_TOGGLE_ORTHOGRAPHIC = config.toggleOrthographic();
		DeveloperTools.KEY_TOGGLE_HIDE_UI = config.toggleHideUi();
		DeveloperTools.KEY_RELOAD_SCENE = config.reloadScene();

		if(plugin.configSceneShaderDebugMode != config.sceneShaderDebugMode()) {
			plugin.configSceneShaderDebugMode = config.sceneShaderDebugMode();
			plugin.recompilePrograms();
		}
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted commandExecuted) {
		if (!commandExecuted.getCommand().equalsIgnoreCase("117hd"))
			return;

		String[] args = commandExecuted.getArguments();
		if (args.length < 1)
			return;

		String action = args[0].toLowerCase();
		switch (action) {
			case "timers":
			case "timings":
				profilerOverlay.setActive(frameTimingsOverlayEnabled = !frameTimingsOverlayEnabled);
				profilerUI.saveOverlayEnabled(frameTimingsOverlayEnabled);
				if (!frameTimingsOverlayEnabled)
					profilerUI.setGraphOverlayActive(false);
				else
					profilerUI.applyGraphOverlayState();
				break;
			case "graph":
				profilerUI.toggleGraph();
				if (profilerUI.isGraphEnabled()) {
					profilerOverlay.setActive(frameTimingsOverlayEnabled = true);
				}
				break;
		}
	}

	@Override
	public void keyPressed(KeyEvent e) {
		if (DeveloperTools.KEY_TOGGLE_FRAME_TIMINGS.matches(e)) {
			profilerOverlay.setActive(frameTimingsOverlayEnabled = !frameTimingsOverlayEnabled);
			profilerUI.saveOverlayEnabled(frameTimingsOverlayEnabled);
			if (!frameTimingsOverlayEnabled)
				profilerUI.setGraphOverlayActive(false);
			else
				profilerUI.applyGraphOverlayState();
		} else {
			return;
		}
		e.consume();
	}

	@Override
	public void keyReleased(KeyEvent e) {}

	@Override
	public void keyTyped(KeyEvent e) {}
}
