package rs117.hd.overlays;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.AbstractAction;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComponent;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import lombok.Getter;
import net.runelite.api.Tile;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;
import rs117.hd.DeveloperConfig;
import rs117.hd.HdPlugin;
import rs117.hd.overlays.inspectors.FieldSearchBox;
import rs117.hd.overlays.inspectors.Inspector;
import rs117.hd.overlays.inspectors.InspectorEditors;
import rs117.hd.overlays.inspectors.InspectorHistory;
import rs117.hd.overlays.inspectors.InspectorOutput;
import rs117.hd.overlays.inspectors.ModelInspector;
import rs117.hd.overlays.inspectors.ObjectIdSearchBox;
import rs117.hd.overlays.inspectors.OverrideFields;
import rs117.hd.overlays.inspectors.TileInspector;
import rs117.hd.scene.GamevalManager;
import rs117.hd.scene.LightManager;
import rs117.hd.scene.TileOverrideManager;
import rs117.hd.scene.lights.Light;
import rs117.hd.utils.SceneGeometry.Hit;

public final class InspectorPanel extends PluginPanel {
	private static final int DEFAULT_WIDTH = 620;
	private static final int MIN_WIDTH = 420;
	private static final int MAX_WIDTH = 1100;
	private static final int RESIZE_HANDLE_WIDTH = 8;
	private static final String DEFAULT_STATUS = "Select an object in the scene.";
	private static final String SELECTING_STATUS = "Hover and click to inspect.";
	private static final String SELECTION_READY_STATUS = "Selection ready.";
	private static final String EMPTY_SELECTION = "Nothing found under cursor.";
	private static final String UNSUPPORTED_SELECTION = "Unsupported selection.";

	@Inject
	private ConfigManager configManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ClientThread clientThread;

	@Inject
	private HdPlugin plugin;

	@Inject
	private GamevalManager gamevalManager;

	@Inject
	private InspectorHistory history;

	@Inject
	private InspectorSelectionOverlay selectionOverlay;

	@Inject
	private ModelInspector modelInspector;

	@Inject
	private TileInspector tileInspector;

	private final List<Inspector> inspectors = new ArrayList<>();
	private final JButton selectionButton = new JButton("Select");
	private final JCheckBox modelMode = new JCheckBox("Model", true);
	private final JCheckBox tileMode = new JCheckBox("Tile", true);
	private final JButton saveButton = new JButton("Save");
	private final JButton applyButton = new JButton("Apply");
	private final JButton undoButton = new JButton("Undo");
	private final JButton redoButton = new JButton("Redo");
	private final JComboBox<Hit> candidates = new JComboBox<>();
	private final JLabel status = new JLabel(DEFAULT_STATUS);
	private final JPanel details = new JPanel();
	private final Map<Object, Set<String>> addedFields = new IdentityHashMap<>();
	private final Map<Object, Boolean> collapsedSections = new IdentityHashMap<>();
	private final Timer candidateRefreshTimer = new Timer(100, e -> refreshCandidates());

	@Getter
    private volatile boolean selectionMode;
	private volatile boolean modelModeEnabled = true;
	private volatile boolean tileModeEnabled = true;
	private volatile GamevalManager.Handle gamevalHandle;

	private NavigationButton navigationButton;
	private boolean updatingCandidates;
	private Object selection;
	private int panelWidth, dragStartX, dragStartWidth;

	public InspectorPanel() {
		super(false);
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(createResizeHandle(), BorderLayout.WEST);
		add(createContent(), BorderLayout.CENTER);

		selectionButton.addActionListener(e -> setSelectionMode(!selectionMode));
		selectionButton.setToolTipText("Select a model or tile");
		modelMode.addActionListener(e -> updateSelectionModes());
		tileMode.addActionListener(e -> updateSelectionModes());
		saveButton.setEnabled(false);
		saveButton.setToolTipText("Save override definition edits to their JSON file");
		saveButton.addActionListener(e -> saveChanges());
		applyButton.setEnabled(false);
		applyButton.setToolTipText("Apply edits and reload the scene");
		applyButton.addActionListener(e -> applyChanges());
		undoButton.setToolTipText("Undo the last inspector edit (Ctrl+Z)");
		redoButton.setToolTipText("Redo the last undone inspector edit (Ctrl+Y)");
		undoButton.addActionListener(e -> undo());
		redoButton.addActionListener(e -> redo());
		installHistoryShortcuts();
		updateHistoryButtons();
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
			Hit selected = (Hit) candidates.getSelectedItem();
			if (selected != null)
				selectionOverlay.setHoveredCandidateIndex(index);
			if (!selectionMode) {
				if (selected != null) {
					selectionOverlay.selectCandidate(index);
					showSelection(selected.getObject());
				}
			}
		});
	}

	public void startup() {
		if (navigationButton != null) return;
		panelWidth = loadPanelWidth();
		inspectors.addAll(List.of(modelInspector, tileInspector));
		history.setChangeListener(this::updateHistoryButtons);
		selectionOverlay.setSelectionModes(modelModeEnabled, tileModeEnabled);
		navigationButton = NavigationButton.builder()
			.tooltip("117 HD Inspector")
			.icon(ImageUtil.loadImageResource(HdPlugin.class, "logo.png"))
			.priority(5)
			.panel(this)
			.build();
		clientToolbar.addNavigation(navigationButton);
	}

	public void shutdown() {
		onDeactivate();
		history.setChangeListener(null);
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

		details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
		details.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		details.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		wrapper.add(details, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(wrapper);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.setBorder(BorderFactory.createLineBorder(ColorScheme.BORDER_COLOR));
		content.add(scroll, BorderLayout.CENTER);
		return content;
	}

	private JPanel createHeader() {
		JPanel header = new JPanel(new BorderLayout(0, 6));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JLabel title = new JLabel("117 HD Inspector");
		title.setForeground(Color.WHITE);
		JPanel controls = new JPanel(new BorderLayout(6, 0));
		controls.setBackground(ColorScheme.DARK_GRAY_COLOR);
		controls.add(candidates, BorderLayout.CENTER);
		controls.add(selectionButton, BorderLayout.EAST);

		JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		actions.setBackground(ColorScheme.DARK_GRAY_COLOR);
		actions.add(undoButton);
		actions.add(redoButton);
		actions.add(saveButton);
		actions.add(applyButton);

		JPanel modes = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		modes.setOpaque(false);
		modes.add(modelMode);
		modes.add(tileMode);
		header.add(title, BorderLayout.NORTH);
		header.add(modes, BorderLayout.CENTER);
		header.add(controls, BorderLayout.SOUTH);
		header.add(actions, BorderLayout.EAST);
		return header;
	}

	private void installHistoryShortcuts() {
		getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("control Z"), "inspectorUndo");
		getActionMap().put("inspectorUndo", new AbstractAction() {
			@Override public void actionPerformed(ActionEvent e) { undo(); }
		});
		getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("control Y"), "inspectorRedo");
		getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("control shift Z"), "inspectorRedo");
		getActionMap().put("inspectorRedo", new AbstractAction() {
			@Override public void actionPerformed(ActionEvent e) { redo(); }
		});
	}

	private void undo() {
		if (history.undo()) render();
	}

	private void redo() {
		if (history.redo()) render();
	}

	private void updateHistoryButtons() {
		Runnable update = () -> {
			undoButton.setEnabled(history.canUndo());
			redoButton.setEnabled(history.canRedo());
		};
		if (SwingUtilities.isEventDispatchThread()) update.run();
		else SwingUtilities.invokeLater(update);
	}

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
		clientThread.invoke(() -> {
			if (enabled && !hasEnabledMode()) {
				status.setText("Enable a mode to inspect selections.");
				return;
			}
			selectionMode = enabled;
			selectionOverlay.setSelectionMode(enabled);
			if (enabled) {
				candidateRefreshTimer.start();
			} else {
				candidateRefreshTimer.stop();
				refreshCandidates();
			}
			selectionButton.setText(enabled ? "Cancel" : "Select");
			status.setText(enabled ? SELECTING_STATUS : selection == null ? DEFAULT_STATUS : SELECTION_READY_STATUS);
		});
	}

	public void selectAtCursor() {
		selectionOverlay.updateHoverNow();
		refreshCandidates();
		Hit selected = (Hit) candidates.getSelectedItem();
		if (selected == null) {
			showMessage(EMPTY_SELECTION);
			return;
		}
		Object object = selected.getObject();
		selectionOverlay.selectCandidate(candidates.getSelectedIndex());
		showSelection(object);
	}

	private void refreshCandidates() {
		List<Hit> next = new ArrayList<>(selectionOverlay.getHoverCandidates());
		int sceneCandidateCount = next.size();
		int preferredIndex = next.isEmpty() ? -1 :
                Math.min(selectionOverlay.getHoveredCandidateIndex(), sceneCandidateCount - 1);
		if (candidateListMatches(next)) {
			if (selectionMode && preferredIndex >= 0) {
				candidates.setSelectedIndex(preferredIndex);
				if (preferredIndex < sceneCandidateCount) selectionOverlay.setHoveredCandidateIndex(preferredIndex);
			}
			return;
		}
		updatingCandidates = true;
		try {
			candidates.removeAllItems();
			for (Hit candidate : next) candidates.addItem(candidate);
			if (preferredIndex >= 0) {
				candidates.setSelectedIndex(preferredIndex);
				if (preferredIndex < sceneCandidateCount) selectionOverlay.setHoveredCandidateIndex(preferredIndex);
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

	private void updateSelectionModes() {
		modelModeEnabled = modelMode.isSelected();
		tileModeEnabled = tileMode.isSelected();
		selectionOverlay.setSelectionModes(modelModeEnabled, tileModeEnabled);
		selectionButton.setEnabled(hasEnabledMode());
		if (selectionMode && !hasEnabledMode()) setSelectionMode(false);
		if (selection != null && !isSelectionEnabled(selection)) {
			selection = null;
			addedFields.clear();
			collapsedSections.clear();
			showMessage(hasEnabledMode() ? DEFAULT_STATUS : "Enable a mode to inspect selections.");
		} else if (selection != null) {
			render();
		}
		refreshCandidates();
	}

	private boolean hasEnabledMode() {
		return modelModeEnabled || tileModeEnabled;
	}

	private boolean isSelectionEnabled(Object selected) {
		if (selected instanceof Tile) return tileModeEnabled;
		return modelModeEnabled;
	}

	private void showSelection(Object selected) {
		if (selected != selection) {
			addedFields.clear();
			collapsedSections.clear();
		}
		selection = selected;
		SwingUtilities.invokeLater(() -> {
			applyButton.setEnabled(false);
			saveButton.setEnabled(false);
		});
		render();
	}

	private void applyChanges() {
		clientThread.invoke(() -> {
			if (plugin.renderer == null) {
				SwingUtilities.invokeLater(() -> status.setText("Renderer unavailable."));
				return;
			}
			plugin.renderer.reloadScene();
			SwingUtilities.invokeLater(() -> status.setText("Scene reload requested."));
		});
	}

	private void saveChanges() {
		for (Inspector inspector : inspectors) {
			if (inspector.isApplicable(selection))
				inspector.save();
		}
	}

	/** Re-runs the inspectors for the current selection and rebuilds the rows from their output. */
	private void render() {
		Object selected = selection;
		if (selected == null) {
			showMessage(DEFAULT_STATUS);
			return;
		}
		ensureGamevalHandle();
		InspectorOutput out = new InspectorOutput();
		for (Inspector inspector : inspectors) {
			if (inspector.isApplicable(selected))
				inspector.draw(selected, out, gamevalHandle);
		}
		if (out.isEmpty()) {
			showMessage(UNSUPPORTED_SELECTION);
			return;
		}
		SwingUtilities.invokeLater(() -> {
			details.removeAll();
			if (!out.getTitle().isEmpty()) details.add(createTitle(out.getTitle()));
			for (var section : out.getSections()) details.add(createSection(section));
			details.revalidate();
			details.repaint();
			applyButton.setEnabled(true);
			saveButton.setEnabled(true);
			status.setText(SELECTION_READY_STATUS);
		});
	}

	private JComponent createTitle(String text) {
		JLabel title = new JLabel(text);
		title.setForeground(Color.WHITE);
		title.setFont(title.getFont().deriveFont(Font.BOLD));
		title.setAlignmentX(LEFT_ALIGNMENT);
		title.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
		return title;
	}

	private JComponent createSection(InspectorOutput.Section section) {
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setAlignmentX(LEFT_ALIGNMENT);
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createEmptyBorder(0, 0, 10, 0),
			BorderFactory.createLineBorder(ColorScheme.BORDER_COLOR)
		));

		boolean expanded = !section.collapsible || !collapsedSections.getOrDefault(section.target, section.initiallyCollapsed);
		JPanel header = new JPanel(new BorderLayout(6, 0));
		header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		header.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 4));
		if (section.collapsible) {
			JButton toggle = new JButton((expanded ? "▾ " : "▸ ") + section.title);
			toggle.setHorizontalAlignment(JButton.LEFT);
			toggle.setFocusPainted(false);
			toggle.setBorderPainted(false);
			toggle.setContentAreaFilled(false);
			toggle.setForeground(Color.WHITE);
			toggle.addActionListener(e -> {
				collapsedSections.put(section.target, expanded);
				render();
			});
			header.add(toggle, BorderLayout.CENTER);
		} else {
			header.add(createSectionTitle(section.title), BorderLayout.CENTER);
		}

		if (!section.actions.isEmpty() || section.objectIdSearch != null) {
			JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
			actions.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			for (var action : section.actions) {
				JButton button = new JButton(action.label);
				button.setMargin(new Insets(2, 6, 2, 6));
				button.setEnabled(action.enabled);
				button.addActionListener(e -> clientThread.invoke(() -> {
                    action.callback.run();
                    SwingUtilities.invokeLater(this::render);
                }));
				actions.add(button);
			}
			if (section.objectIdSearch != null) actions.add(createObjectIdSearch(section.objectIdSearch));
			header.add(actions, BorderLayout.EAST);
		}
		panel.add(header);

		if (expanded) {
			JPanel body = new JPanel();
			body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
			body.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			body.setBorder(BorderFactory.createEmptyBorder(0, 8, 6, 8));
			Set<String> shown = new LinkedHashSet<>();
			for (var row : section.rows) {
				if (row.field != null) shown.add(row.field.getName());
				body.add(createRow(row, section.target));
			}
			if (section.target != null) {
				Set<String> added = addedFields.getOrDefault(section.target, Set.of());
				List<Field> remaining = new ArrayList<>();
				for (Field field : OverrideFields.fields(section.target)) {
					if (shown.contains(field.getName())) continue;
					if (added.contains(field.getName())) {
						body.add(createFieldRow(field, section.target));
						continue;
					}
					if (OverrideFields.isEditable(field)) remaining.add(field);
				}
				if (!remaining.isEmpty()) body.add(createAddField(remaining, section.target));
			}
			for (var child : section.children) body.add(createSection(child));
			panel.add(body);
		}
		return panel;
	}

	private JComponent createSectionTitle(String text) {
		JLabel title = new JLabel(text);
		title.setForeground(Color.WHITE);
		title.setFont(title.getFont().deriveFont(Font.BOLD));
		return title;
	}

	private JComponent createAddField(List<Field> remaining, Object target) {
		FieldSearchBox search = new FieldSearchBox(remaining, field -> {
			Set<String> added = addedFields.computeIfAbsent(target, ignored -> new LinkedHashSet<>());
			added.add(field.getName());
			render();
		});
		search.setAlignmentX(LEFT_ALIGNMENT);
		search.setMaximumSize(new Dimension(Integer.MAX_VALUE, search.getPreferredSize().height));
		return search;
	}

	private JComponent createRow(InspectorOutput.Row row, Object target) {
		JComponent editor = row.field != null
			? InspectorEditors.create(row.field, target, history, modelInspector)
			: InspectorEditors.readOnly(row.value);
		if (row.actionLabel == null) {
			return createRow(row.field == null ? row.key : OverrideFields.label(row.field), editor);
		}

		JPanel panel = new JPanel(new BorderLayout(6, 0));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setAlignmentX(LEFT_ALIGNMENT);
		panel.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
		panel.add(createKey(row.key), BorderLayout.WEST);
		panel.add(editor, BorderLayout.CENTER);
		JButton action = new JButton(row.actionLabel);
		action.setMargin(new Insets(2, 6, 2, 6));
		action.addActionListener(e -> {
			row.action.run();
			render();
		});
		JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		actions.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		actions.add(action);
		panel.add(actions, BorderLayout.EAST);
		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
		return panel;
	}

	private JComponent createObjectIdSearch(InspectorOutput.ObjectIdSearch search) {
		JPanel control = new JPanel(new BorderLayout());
		control.setOpaque(false);
		JButton add = new JButton("Add Object ID");
		add.setMargin(new Insets(2, 6, 2, 6));
		control.add(add, BorderLayout.CENTER);
		add.addActionListener(e -> {
			ObjectIdSearchBox box = new ObjectIdSearchBox(search.objectNames, search.currentIds, id -> {
				search.onPick.accept(id);
				render();
			});
			box.setPreferredSize(new Dimension(220, box.getPreferredSize().height));
			control.removeAll();
			control.add(box, BorderLayout.CENTER);
			control.revalidate();
			control.repaint();
			box.openSearch();
		});
		return control;
	}

	private JComponent createFieldRow(Field field, Object target) {
		return createRow(OverrideFields.label(field), InspectorEditors.create(field, target, history, modelInspector));
	}

	private JComponent createRow(String key, JComponent editor) {
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
		row.add(createKey(key), BorderLayout.WEST);
		row.add(editor, BorderLayout.CENTER);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	private JComponent createKey(String key) {
		JLabel label = new JLabel(key);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setPreferredSize(new Dimension(130, label.getPreferredSize().height));
		label.setToolTipText(key);
		return label;
	}

	private void ensureGamevalHandle() {
		if (gamevalHandle == null) gamevalHandle = gamevalManager.obtainHandle();
	}

	private void showMessage(String message) {
		SwingUtilities.invokeLater(() -> {
			details.removeAll();
			details.add(InspectorEditors.readOnly(message));
			details.revalidate();
			details.repaint();
			applyButton.setEnabled(false);
			saveButton.setEnabled(false);
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
