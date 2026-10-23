package rs117.hd.overlays;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;
import rs117.hd.DeveloperConfig;
import rs117.hd.HdPlugin;
import rs117.hd.overlays.inspectors.Inspector;
import rs117.hd.overlays.inspectors.ModelInspector;
import rs117.hd.overlays.inspectors.TileInspector;
import rs117.hd.scene.GamevalManager;
import rs117.hd.utils.SceneGeometry.Hit;

public final class InspectorPanel extends PluginPanel {
	private static final int DEFAULT_WIDTH = 620;
	private static final int MIN_WIDTH = 420;
	private static final int MAX_WIDTH = 1100;
	private static final int RESIZE_HANDLE_WIDTH = 8;
	private static final String DEFAULT_STATUS = "Choose a context, then select something in the scene.";
	private static final String SELECTING_STATUS = "Hover to preview; click to inspect.";
	private static final String EMPTY_SELECTION = "No model, tile, or RLHD light was found under the cursor.";
	private static final String UNSUPPORTED_SELECTION = "No inspector supports this selection.";
	private final ConfigManager configManager;
	private final ClientToolbar clientToolbar;
	private final GamevalManager gamevalManager;
	private final InspectorSelectionOverlay selectionOverlay;
	private final List<Inspector> inspectors;
	private final JButton selectionButton = new JButton("Enable selection mode");
	private final JComboBox<Hit> candidates = new JComboBox<>();
	private final JLabel status = new JLabel(DEFAULT_STATUS);
	private final JTextArea details = new JTextArea();
	private final Timer candidateRefreshTimer = new Timer(100, e -> refreshCandidates());
	private volatile boolean selectionMode;
	private volatile GamevalManager.Handle gamevalHandle;
	private NavigationButton navigationButton;
	private boolean updatingCandidates;
	private int panelWidth, dragStartX, dragStartWidth;

	@Inject
	public InspectorPanel(ConfigManager configManager, ClientToolbar clientToolbar, GamevalManager gamevalManager,
		InspectorSelectionOverlay selectionOverlay,
		ModelInspector modelInspector, TileInspector tileInspector) {
		super(false);
		this.configManager = configManager;
		this.clientToolbar = clientToolbar;
		this.gamevalManager = gamevalManager;
		this.selectionOverlay = selectionOverlay;
		inspectors = List.of(modelInspector, tileInspector);
		panelWidth = loadPanelWidth();
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(createResizeHandle(), BorderLayout.WEST);
		add(createContent(), BorderLayout.CENTER);

		selectionButton.addActionListener(e -> setSelectionMode(!selectionMode));
		candidates.setToolTipText("Objects under the cursor, nearest first");
		candidates.setRenderer(new DefaultListCellRenderer() {
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index,
				boolean selected, boolean hasFocus) {
				Component component = super.getListCellRendererComponent(list, value, index, selected, hasFocus);
				if (component instanceof JLabel && value instanceof Hit) {
					((JLabel) component).setText(((Hit) value).getDisplayName(gamevalHandle));
				}
				return component;
			}
		});
		candidates.addActionListener(e -> {
			if (updatingCandidates || candidates.getSelectedIndex() < 0) return;
			int index = candidates.getSelectedIndex();
			selectionOverlay.setHoveredCandidateIndex(index);
			if (!selectionMode) {
				selectionOverlay.selectCandidate(index);
				Hit selected = (Hit) candidates.getSelectedItem();
				if (selected != null) showSelection(selected.getObject());
			}
		});
	}

	public void addInspectorPanel() {
		if (navigationButton != null) return;
		navigationButton = NavigationButton.builder()
			.tooltip("117 HD Inspector")
			.icon(ImageUtil.loadImageResource(HdPlugin.class, "logo.png"))
			.priority(5)
			.panel(this)
			.build();
		clientToolbar.addNavigation(navigationButton);
	}

	public void removeInspectorPanel() {
		onDeactivate();
		if (navigationButton != null) clientToolbar.removeNavigation(navigationButton);
		navigationButton = null;
	}

	private JPanel createResizeHandle() {
		JPanel handle = new JPanel();
		handle.setBackground(ColorScheme.BORDER_COLOR);
		handle.setCursor(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR));
		handle.setToolTipText("Drag to resize the inspector");
		handle.setPreferredSize(new Dimension(RESIZE_HANDLE_WIDTH, 1));
		installResizeHandler(handle);
		return handle;
	}

	private JPanel createContent() {
		JPanel content = new JPanel(new BorderLayout(0, 8));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		content.add(createHeader(), BorderLayout.NORTH);

		details.setEditable(false);
		details.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
		details.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		details.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		details.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		JScrollPane scroll = new JScrollPane(details);
		scroll.setBorder(BorderFactory.createLineBorder(ColorScheme.BORDER_COLOR));
		content.add(scroll, BorderLayout.CENTER);
		return content;
	}

	private JPanel createHeader() {
		JPanel header = new JPanel(new BorderLayout(0, 6));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JLabel title = new JLabel("117 HD Inspector");
		title.setForeground(Color.WHITE);
		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		JPanel controls = new JPanel(new BorderLayout(0, 6));
		controls.setBackground(ColorScheme.DARK_GRAY_COLOR);
		controls.add(status, BorderLayout.NORTH);
		JPanel actions = new JPanel(new BorderLayout(6, 0));
		actions.setBackground(ColorScheme.DARK_GRAY_COLOR);
		actions.add(candidates, BorderLayout.CENTER);
		actions.add(selectionButton, BorderLayout.EAST);
		controls.add(actions, BorderLayout.SOUTH);
		header.add(title, BorderLayout.NORTH);
		header.add(controls, BorderLayout.CENTER);
		return header;
	}

	public boolean isSelectionMode() { return selectionMode; }
	@Override
	public void onActivate() {
		ensureGamevalHandle();
	}

	@Override
	public void onDeactivate() {
		setSelectionMode(false);
		GamevalManager.Handle handle = gamevalHandle;
		gamevalHandle = null;
		if (handle != null) handle.close();
	}

	public void setSelectionMode(boolean enabled) {
		selectionMode = enabled;
		selectionOverlay.setSelectionMode(enabled);
		if (enabled) {
			candidateRefreshTimer.start();
		} else {
			candidateRefreshTimer.stop();
			refreshCandidates();
		}
		selectionButton.setText(enabled ? "Cancel selection" : "Enable selection mode");
		status.setText(enabled ? SELECTING_STATUS : DEFAULT_STATUS);
	}

	public void selectAtCursor() {
		selectionOverlay.updateHoverNow();
		refreshCandidates();
		Object selected = selectionOverlay.selectHovered();
		if (selected != null) showSelection(selected);
		else show(EMPTY_SELECTION, EMPTY_SELECTION);
	}

	private void refreshCandidates() {
		List<Hit> next = selectionOverlay.getHoverCandidates();
		if (candidateListMatches(next)) return;
		updatingCandidates = true;
		try {
			candidates.removeAllItems();
			for (Hit candidate : next) candidates.addItem(candidate);
			if (!next.isEmpty()) {
				int index = Math.min(selectionOverlay.getHoveredCandidateIndex(), next.size() - 1);
				candidates.setSelectedIndex(index);
				selectionOverlay.setHoveredCandidateIndex(index);
			}
		} finally {
			updatingCandidates = false;
		}
	}

	private boolean candidateListMatches(List<Hit> values) {
		if (candidates.getItemCount() != values.size()) return false;
		for (int i = 0; i < values.size(); i++) {
			if (candidates.getItemAt(i).getObject() != values.get(i).getObject()) return false;
		}
		return true;
	}

	private void showSelection(Object selected) {
		ensureGamevalHandle();
		StringBuilder out = new StringBuilder();
		for (Inspector inspector : inspectors) {
			if (inspector.isApplicable(selected)) inspector.draw(selected, out, gamevalHandle);
		}
		if (out.length() == 0) {
			show(UNSUPPORTED_SELECTION, UNSUPPORTED_SELECTION);
			return;
		}
		show(out.toString(), "Selection captured.");
	}

	private void ensureGamevalHandle() {
		if (gamevalHandle == null) gamevalHandle = gamevalManager.obtainHandle();
	}
	private void show(String text, String message) {
		SwingUtilities.invokeLater(() -> {
			details.setText(text);
			details.setCaretPosition(0);
			status.setText(message);
		});
	}
	@Override public Dimension getPreferredSize() { return new Dimension(panelWidth, super.getPreferredSize().height); }
	@Override public Dimension getMinimumSize() { return new Dimension(MIN_WIDTH, super.getMinimumSize().height); }

	private void installResizeHandler(JPanel handle) {
		MouseAdapter resize = new MouseAdapter() {
			@Override public void mousePressed(MouseEvent e) {
				dragStartX = e.getXOnScreen();
				dragStartWidth = panelWidth;
			}
			@Override public void mouseDragged(MouseEvent e) {
				setPanelWidth(dragStartWidth + dragStartX - e.getXOnScreen());
			}
			@Override public void mouseReleased(MouseEvent e) { savePanelWidth(); }
		};
		handle.addMouseListener(resize); handle.addMouseMotionListener(resize);
	}
	private void setPanelWidth(int width) {
		panelWidth = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width));
		revalidate();
		if (getParent() != null) getParent().revalidate();
		var window = SwingUtilities.getWindowAncestor(this);
		if (window != null) window.validate();
	}
	private void savePanelWidth() {
		configManager.setConfiguration(DeveloperConfig.CONFIG_GROUP,
			DeveloperConfig.KEY_INSPECTOR_PANEL_WIDTH, String.valueOf(panelWidth));
	}

	private int loadPanelWidth() {
		String configured = configManager.getConfiguration(DeveloperConfig.CONFIG_GROUP, DeveloperConfig.KEY_INSPECTOR_PANEL_WIDTH);
		if (configured == null) return DEFAULT_WIDTH;
		try { return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, Integer.parseInt(configured))); }
		catch (NumberFormatException ignored) { return DEFAULT_WIDTH; }
	}
}
