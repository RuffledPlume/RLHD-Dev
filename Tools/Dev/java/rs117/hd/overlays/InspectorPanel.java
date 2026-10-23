package rs117.hd.overlays;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import rs117.hd.DeveloperConfig;
import rs117.hd.HdPlugin;
import rs117.hd.scene.GamevalManager;
import rs117.hd.scene.ModelOverrideManager;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.lights.Light;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.ModelHash;

public final class InspectorPanel extends PluginPanel {
	private static final int DEFAULT_WIDTH = 620, MIN_WIDTH = 420, MAX_WIDTH = 1100, RESIZE_HANDLE_WIDTH = 8;
	private final ConfigManager configManager;
	private final ModelOverrideManager modelOverrideManager;
	private final GamevalManager gamevalManager;
	private final HdPlugin plugin;
	private final Client client;
	private final InspectorSelectionOverlay selectionOverlay;
	private final JButton selectionButton = new JButton("Enable selection mode");
	private final JComboBox<String> contextMode = new JComboBox<>(new String[] {"Auto", "Model", "Light"});
	private final JLabel status = new JLabel("Choose a context, then select something in the scene.");
	private final JTextArea details = new JTextArea();
	private volatile String selectedContext = "Auto";
	private volatile boolean selectionMode;
	private int panelWidth, dragStartX, dragStartWidth;

	public InspectorPanel(ConfigManager configManager, ModelOverrideManager modelOverrideManager,
		GamevalManager gamevalManager, HdPlugin plugin, Client client, InspectorSelectionOverlay selectionOverlay) {
		super(false);
		this.configManager = configManager;
		this.modelOverrideManager = modelOverrideManager;
		this.gamevalManager = gamevalManager;
		this.plugin = plugin;
		this.client = client;
		this.selectionOverlay = selectionOverlay;
		panelWidth = loadWidth();
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel resizeHandle = new JPanel();
		resizeHandle.setBackground(ColorScheme.BORDER_COLOR);
		resizeHandle.setCursor(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR));
		resizeHandle.setToolTipText("Drag to resize the inspector");
		resizeHandle.setPreferredSize(new Dimension(RESIZE_HANDLE_WIDTH, 1));
		installResizeHandler(resizeHandle);
		add(resizeHandle, BorderLayout.WEST);

		JPanel content = new JPanel(new BorderLayout(0, 8));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		JPanel header = new JPanel(new BorderLayout(0, 6));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JLabel title = new JLabel("117 HD Inspector");
		title.setForeground(Color.WHITE);
		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		JPanel controls = new JPanel(new BorderLayout(0, 6));
		controls.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JPanel actions = new JPanel(new BorderLayout(6, 0));
		actions.setBackground(ColorScheme.DARK_GRAY_COLOR);
		actions.add(contextMode, BorderLayout.CENTER);
		actions.add(selectionButton, BorderLayout.EAST);
		controls.add(status, BorderLayout.NORTH);
		controls.add(actions, BorderLayout.SOUTH);
		header.add(title, BorderLayout.NORTH);
		header.add(controls, BorderLayout.CENTER);
		content.add(header, BorderLayout.NORTH);

		details.setEditable(false);
		details.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
		details.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		details.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		details.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		JScrollPane scroll = new JScrollPane(details);
		scroll.setBorder(BorderFactory.createLineBorder(ColorScheme.BORDER_COLOR));
		content.add(scroll, BorderLayout.CENTER);
		add(content, BorderLayout.CENTER);

		selectionButton.addActionListener(e -> setSelectionMode(!selectionMode));
		contextMode.addActionListener(e -> {
			selectedContext = (String) contextMode.getSelectedItem();
			selectionOverlay.setModelPickingEnabled(!"Light".equals(selectedContext));
		});
	}

	public boolean isSelectionMode() { return selectionMode; }
	public void setSelectionMode(boolean enabled) {
		selectionMode = enabled;
		selectionOverlay.setSelectionMode(enabled);
		selectionButton.setText(enabled ? "Cancel selection" : "Enable selection mode");
		status.setText(enabled ? "Hover to preview; click to inspect." : "Choose a context, then select something in the scene.");
	}

	public void selectAtCursor() {
		selectionOverlay.updateHoverNow();
		Object object = selectionOverlay.getHoveredObject();
		selectionOverlay.selectHovered();
		Tile tile = client.getSelectedSceneTile();
		boolean lightMode = "Light".equals(selectedContext);
		boolean modelMode = "Model".equals(selectedContext);
		if (!lightMode && object != null) {
			showModel(object);
			return;
		}
		if (!modelMode && tile != null) {
			SceneContext scene = plugin.getSceneContext();
			Light light = scene == null ? null : findLight(scene, tile.getWorldLocation());
			if (light != null) {
				selectionOverlay.setSelectedTile(tile);
				showLight(light);
				return;
			}
		}
		show("No model or RLHD light was found under the cursor.");
	}

	private void showModel(Object object) {
		Renderable renderable = resolveRenderable(object);
		long hash = object instanceof TileObject ? ((TileObject) object).getHash()
			: object instanceof Node ? ((Node) object).getHash() : -1;
		int uuid = ModelHash.generateUuid(client, hash, renderable);
		int type = ModelHash.getUuidType(uuid), id = ModelHash.getUuidId(uuid);
		WorldPoint world = worldLocation(object);
		String name = object instanceof Player ? ((Player) object).getName() : "Unknown";
		try (GamevalManager.Handle gamevals = gamevalManager.obtainHandle()) {
			if (type == ModelHash.TYPE_OBJECT) name = gamevals.getObjectName(id);
			else if (type == ModelHash.TYPE_NPC) name = gamevals.getNpcName(id);
			else if (type == ModelHash.TYPE_GRAPHICS_OBJECT || type == ModelHash.TYPE_PROJECTILE) name = gamevals.getSpotanimName(id);
		}
		int[] position = world == null ? new int[] {0, 0, 0} : new int[] {world.getX(), world.getY(), world.getPlane()};
		ModelOverride override = modelOverrideManager.getOverride(uuid, position);
		StringBuilder out = new StringBuilder();
		line(out, "Context", "Model");
		line(out, "Model Name", name);
		line(out, "GameVal ID", id + " (" + ModelHash.getTypeName(type) + ")");
		line(out, "World Position", world == null ? "unknown" : world);
		line(out, "ModelOverride", override.description);
		line(out, "Base Material", override.baseMaterial);
		line(out, "Texture Material", override.textureMaterial);
		line(out, "Color Overrides", colorOverrides(override.colorOverrides));
		line(out, "Materials", materials(override));
		if (renderable != null) {
			Model model = renderable instanceof Model ? (Model) renderable : renderable.getModel();
			line(out, "Faces", model == null ? "unknown" : model.getFaceCount());
		}
		show(out.toString());
	}

	private Renderable resolveRenderable(Object object) {
		if (object instanceof Renderable) return (Renderable) object;
		if (object instanceof GameObject) return ((GameObject) object).getRenderable();
		if (object instanceof WallObject) return ((WallObject) object).getRenderable1();
		if (object instanceof DecorativeObject) return ((DecorativeObject) object).getRenderable();
		if (object instanceof GroundObject) return ((GroundObject) object).getRenderable();
		return null;
	}

	private WorldPoint worldLocation(Object object) {
		if (object instanceof Actor) return ((Actor) object).getWorldLocation();
		if (object instanceof TileObject) return ((TileObject) object).getWorldLocation();
		if (object instanceof GraphicsObject) return WorldPoint.fromLocal(client, ((GraphicsObject) object).getLocation());
		if (object instanceof Projectile) {
			Projectile p = (Projectile) object;
			return WorldPoint.fromLocal(client, (int) p.getX(), (int) p.getY(), client.getPlane());
		}
		return null;
	}

	private Light findLight(SceneContext scene, WorldPoint location) {
		if (location == null) return null;
		Light closest = null;
		int distance = Integer.MAX_VALUE;
		for (Light light : scene.lights) {
			int d = Math.abs(light.worldPos[0] - location.getX()) + Math.abs(light.worldPos[2] - location.getY());
			if (light.worldPos[0] != 0 && d < distance) { closest = light; distance = d; }
		}
		return distance <= 1 ? closest : null;
	}

	private String materials(ModelOverride override) {
		List<String> values = new ArrayList<>();
		if (override.baseMaterial != null && override.baseMaterial != Material.NONE) values.add("base=" + override.baseMaterial.name);
		if (override.textureMaterial != null && override.textureMaterial != Material.NONE) values.add("texture=" + override.textureMaterial.name);
		if (override.materialOverrides != null)
			for (var entry : override.materialOverrides.entrySet())
				values.add(entry.getKey().name + " -> " + entry.getValue().baseMaterial.name);
		return values.isEmpty() ? "none" : String.join(", ", values);
	}

	private String colorOverrides(ModelOverride[] overrides) {
		if (overrides == null || overrides.length == 0) return "none";
		List<String> values = new ArrayList<>();
		for (ModelOverride color : overrides)
			values.add(color.description + " [material=" + color.baseMaterial + ", modifiesColor=" + color.modifiesColor + "]");
		return String.join("; ", values);
	}

	private void showLight(Light light) {
		StringBuilder out = new StringBuilder();
		line(out, "Context", "RLHD Light");
		line(out, "Description", light.def.description);
		line(out, "Source GameVal IDs", "objects=" + light.def.objectIds + ", npcs=" + light.def.npcIds
			+ ", projectiles=" + light.def.projectileIds + ", graphics=" + light.def.graphicsObjectIds);
		line(out, "World Position", java.util.Arrays.toString(light.worldPos) + " (plane " + light.plane + ")");
		line(out, "Type", light.def.type);
		line(out, "Radius", light.radius);
		line(out, "Strength", light.strength);
		line(out, "Color", java.util.Arrays.toString(light.color));
		line(out, "Offset", java.util.Arrays.toString(light.offset));
		line(out, "Origin", java.util.Arrays.toString(light.origin));
		show(out.toString());
	}

	private static void line(StringBuilder out, String key, Object value) { out.append(key).append(": ").append(value).append('\n'); }
	private void show(String text) { SwingUtilities.invokeLater(() -> { details.setText(text); details.setCaretPosition(0); status.setText(text.startsWith("No ") ? text : "Selection captured."); }); }
	@Override public Dimension getPreferredSize() { return new Dimension(panelWidth, super.getPreferredSize().height); }
	@Override public Dimension getMinimumSize() { return new Dimension(MIN_WIDTH, super.getMinimumSize().height); }

	private void installResizeHandler(JPanel handle) {
		MouseAdapter resize = new MouseAdapter() {
			@Override public void mousePressed(MouseEvent e) { dragStartX = e.getXOnScreen(); dragStartWidth = panelWidth; }
			@Override public void mouseDragged(MouseEvent e) { setPanelWidth(dragStartWidth + dragStartX - e.getXOnScreen(), false); }
			@Override public void mouseReleased(MouseEvent e) { setPanelWidth(panelWidth, true); }
		};
		handle.addMouseListener(resize); handle.addMouseMotionListener(resize);
	}
	private void setPanelWidth(int width, boolean persist) {
		panelWidth = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width));
		if (persist) configManager.setConfiguration(DeveloperConfig.CONFIG_GROUP, DeveloperConfig.KEY_INSPECTOR_PANEL_WIDTH, String.valueOf(panelWidth));
		revalidate(); if (getParent() != null) getParent().revalidate();
		var window = SwingUtilities.getWindowAncestor(this); if (window != null) window.validate();
	}
	private int loadWidth() {
		String configured = configManager.getConfiguration(DeveloperConfig.CONFIG_GROUP, DeveloperConfig.KEY_INSPECTOR_PANEL_WIDTH);
		if (configured == null) return DEFAULT_WIDTH;
		try { return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, Integer.parseInt(configured))); }
		catch (NumberFormatException ignored) { return DEFAULT_WIDTH; }
	}
}
