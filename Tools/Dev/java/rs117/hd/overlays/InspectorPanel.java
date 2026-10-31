package rs117.hd.overlays;

import com.google.gson.JsonElement;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComponent;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.KeyStroke;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
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
import rs117.hd.overlays.inspectors.Inspector;
import rs117.hd.overlays.inspectors.InspectorEditors;
import rs117.hd.overlays.inspectors.InspectorHistory;
import rs117.hd.overlays.inspectors.InspectorOutput;
import rs117.hd.overlays.inspectors.ModelInspector;
import rs117.hd.overlays.inspectors.SearchBox;
import rs117.hd.overlays.inspectors.OverrideFields;
import rs117.hd.overlays.inspectors.TileInspector;
import rs117.hd.scene.GamevalManager;
import rs117.hd.scene.LightManager;
import rs117.hd.scene.TileOverrideManager;
import rs117.hd.scene.lights.Light;
import rs117.hd.scene.model_overrides.ModelOverride;
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
	private final JPanel busyOverlay = new JPanel(new GridBagLayout());
	private final JLabel busyMessage = new JLabel();
	private final JProgressBar busyProgress = new JProgressBar();
	private final Map<Object, Set<String>> addedFields = new IdentityHashMap<>();
	private final Map<SectionCollapseKey, Boolean> collapsedSections = new HashMap<>();
	private final Timer candidateRefreshTimer = new Timer(100, e -> refreshCandidates());

	@Getter
    private volatile boolean selectionMode;
	private volatile boolean modelModeEnabled = true;
	private volatile boolean tileModeEnabled = true;
	private volatile GamevalManager.Handle gamevalHandle;

	private NavigationButton navigationButton;
	private boolean updatingCandidates;
	private volatile Object selection;
	private int panelWidth, dragStartX, dragStartWidth;

	public InspectorPanel() {
		super(false);
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(createResizeHandle(), BorderLayout.WEST);
		add(createContentLayer(), BorderLayout.CENTER);

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
		undoButton.setEnabled(false);
		redoButton.setEnabled(false);
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
		InspectorEditors.registerCustomEditors();
		inspectors.clear();
		inspectors.addAll(List.of(modelInspector, tileInspector));
		modelInspector.setRefreshCallback(this::render);
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
		modelInspector.setRefreshCallback(null);
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

		details.setLayout(new GridBagLayout());
		details.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		details.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		JPanel wrapper = new InspectorScrollPanel();
		wrapper.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		wrapper.add(details, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(wrapper);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.setBorder(BorderFactory.createLineBorder(ColorScheme.BORDER_COLOR));
		content.add(scroll, BorderLayout.CENTER);
		return content;
	}

	private JComponent createContentLayer() {
		JPanel content = createContent();
		busyOverlay.setOpaque(true);
		busyOverlay.setBackground(new Color(25, 25, 25, 220));
		busyOverlay.setVisible(false);

		JPanel card = new JPanel(new BorderLayout(0, 10));
		card.setBackground(ColorScheme.DARK_GRAY_COLOR);
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.BORDER_COLOR),
			BorderFactory.createEmptyBorder(14, 18, 14, 18)
		));
		busyMessage.setForeground(Color.WHITE);
		busyMessage.setHorizontalAlignment(JLabel.CENTER);
		busyProgress.setIndeterminate(true);
		busyProgress.setPreferredSize(new Dimension(220, 16));
		card.add(busyMessage, BorderLayout.NORTH);
		card.add(busyProgress, BorderLayout.CENTER);
		busyOverlay.add(card, new GridBagConstraints());

		JLayeredPane layers = new JLayeredPane() {
			@Override
			public void doLayout() {
				content.setBounds(0, 0, getWidth(), getHeight());
				busyOverlay.setBounds(0, 0, getWidth(), getHeight());
			}

			@Override
			public Dimension getPreferredSize() {
				return content.getPreferredSize();
			}

			@Override
			public Dimension getMinimumSize() {
				return content.getMinimumSize();
			}
		};
		layers.add(content, JLayeredPane.DEFAULT_LAYER);
		layers.add(busyOverlay, JLayeredPane.PALETTE_LAYER);
		return layers;
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
		clientThread.invoke(() -> {
			if (history.undo())
				SwingUtilities.invokeLater(this::render);
		});
	}

	private void redo() {
		clientThread.invoke(() -> {
			if (history.redo())
				SwingUtilities.invokeLater(this::render);
		});
	}

	private void updateHistoryButtons() {
		clientThread.invoke(() -> {
			boolean canUndo = history.canUndo();
			boolean canRedo = history.canRedo();
			SwingUtilities.invokeLater(() -> {
				undoButton.setEnabled(canUndo);
				redoButton.setEnabled(canRedo);
			});
		});
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

	public void clearSelection() {
		selectionMode = false;
		selectionOverlay.clearSelection();
		SwingUtilities.invokeLater(() -> {
			candidateRefreshTimer.stop();
			selection = null;
			addedFields.clear();
			collapsedSections.clear();
			updatingCandidates = true;
			try {
				candidates.removeAllItems();
			} finally {
				updatingCandidates = false;
			}
			selectionButton.setText("Select");
			status.setText(DEFAULT_STATUS);
			showMessage(DEFAULT_STATUS);
		});
	}

	public void selectAtCursor() {
		selectionOverlay.updateHoverNow();
		List<Hit> hovered = selectionOverlay.getHoverCandidates();
		if (hovered.isEmpty()) {
			showMessage(EMPTY_SELECTION);
			return;
		}
		int selectedIndex = Math.min(selectionOverlay.getHoveredCandidateIndex(), hovered.size() - 1);
		Hit selected = hovered.get(selectedIndex);
		Object object = selected.getObject();
		selectionOverlay.selectCandidate(selectedIndex);
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
		if (candidates.isPopupVisible())
			candidates.hidePopup();
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
		runWithBusyOverlay("Applying overrides and reloading the scene…", "Changes applied.", () -> {
			if (plugin.renderer == null)
				throw new IllegalStateException("Renderer unavailable.");
			boolean applied = false;
			for (Inspector inspector : inspectors) {
				if (inspector.isApplicable(selection)) {
					inspector.apply();
					applied = true;
				}
			}
			if (!applied)
				plugin.renderer.reloadScene();
		});
	}

	private void saveChanges() {
		runWithBusyOverlay("Saving inspector edits…", "Save operation finished.", () -> {
			for (Inspector inspector : inspectors) {
				if (inspector.isApplicable(selection))
					inspector.save();
			}
		});
	}

	public void runWithBusyOverlay(String message, String completionMessage, Runnable operation) {
		showBusyOverlay(message);
		clientThread.invoke(() -> {
			String result = completionMessage;
			try {
				operation.run();
			} catch (RuntimeException ex) {
				result = "Operation failed: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
			}
			String statusMessage = result;
			SwingUtilities.invokeLater(() -> finishBusyOverlay(statusMessage));
		});
	}

	private void showBusyOverlay(String message) {
		busyMessage.setText(message);
		busyOverlay.setVisible(true);
		busyOverlay.repaint();
		applyButton.setEnabled(false);
		saveButton.setEnabled(false);
	}

	private void finishBusyOverlay(String message) {
		busyOverlay.setVisible(false);
		status.setText(message);
		boolean hasSelection = selection != null;
		applyButton.setEnabled(hasSelection);
		saveButton.setEnabled(hasSelection);
	}

	/** Re-runs the inspectors for the current selection and rebuilds the rows from their output. */
	private void render() {
		final Object selected = selection;
		if (selected == null) {
			showMessage(DEFAULT_STATUS);
			return;
		}
		clientThread.invoke(() -> {
			if (selected != selection)
				return;
			ensureGamevalHandle();
			InspectorOutput out = new InspectorOutput();
			for (Inspector inspector : inspectors) {
				if (inspector.isApplicable(selected))
					inspector.draw(selected, out, gamevalHandle);
			}
			SwingUtilities.invokeLater(() -> {
				if (selected != selection)
					return;
				if (out.isEmpty()) {
					showMessage(UNSUPPORTED_SELECTION);
					return;
				}
				details.removeAll();
				int row = 0;
				if (!out.getTitle().isEmpty())
					addDetailRow(createTitle(out.getTitle()), row++);
				for (var section : out.getSections())
					addDetailRow(createSection(section), row++);
				details.revalidate();
				details.repaint();
				applyButton.setEnabled(true);
				saveButton.setEnabled(true);
				status.setText(SELECTION_READY_STATUS);
			});
		});
	}

	private void addDetailRow(JComponent component, int row) {
		GridBagConstraints constraints = new GridBagConstraints();
		constraints.gridx = 0;
		constraints.gridy = row;
		constraints.weightx = 1;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		constraints.anchor = GridBagConstraints.NORTHWEST;
		constraints.insets = new Insets(0, 0, 8, 0);
		details.add(component, constraints);
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
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createEmptyBorder(0, 0, 10, 0),
			BorderFactory.createLineBorder(ColorScheme.BORDER_COLOR)
		));

		SectionCollapseKey collapseKey = new SectionCollapseKey(section.target, section.title);
		boolean expanded = !section.collapsible || !collapsedSections.getOrDefault(collapseKey, section.initiallyCollapsed);
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
				collapsedSections.put(collapseKey, expanded);
				render();
			});
			header.add(toggle, BorderLayout.CENTER);
		} else {
			header.add(createSectionTitle(section.title), BorderLayout.CENTER);
		}

		if (!section.actions.isEmpty() || section.objectIdSearch != null || section.modelOverrideSearch != null) {
			JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
			actions.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			for (var action : section.actions) {
				List<JCheckBox> optionControls = new ArrayList<>();
				for (var option : action.options) {
					JCheckBox checkBox = new JCheckBox(option.label, option.initiallySelected);
					checkBox.setToolTipText(option.toolTip);
					checkBox.setOpaque(false);
					checkBox.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
					optionControls.add(checkBox);
					actions.add(checkBox);
				}
				JButton button = new JButton(action.label);
				button.setMargin(new Insets(2, 6, 2, 6));
				button.setEnabled(action.enabled);
				button.addActionListener(e -> {
					if (action.uiCallback != null) {
						action.uiCallback.accept(this);
						return;
					}
					boolean[] optionValues = new boolean[optionControls.size()];
					for (int i = 0; i < optionValues.length; i++)
						optionValues[i] = optionControls.get(i).isSelected();
					if (action.optionsCallback != null) {
						if (action.busyMessage != null)
							showBusyOverlay(action.busyMessage);
						Runnable onCompleted = () -> SwingUtilities.invokeLater(() -> {
							if (action.completionMessage != null)
								finishBusyOverlay(action.completionMessage);
							else
								render();
						});
						clientThread.invoke(() -> action.optionsCallback.accept(optionValues, onCompleted));
						return;
					}
					if (action.callback != null && action.busyMessage != null) {
						runWithBusyOverlay(action.busyMessage,
							action.completionMessage == null ? "Operation finished." : action.completionMessage,
							action.callback);
						return;
					}
					clientThread.invoke(() -> {
						if (action.callback != null) {
							action.callback.run();
						}
						SwingUtilities.invokeLater(this::render);
					});
				});
				actions.add(button);
			}
			if (section.objectIdSearch != null) actions.add(createObjectIdSearch(section.objectIdSearch));
			if (section.modelOverrideSearch != null) actions.add(createModelOverrideSearch(section.modelOverrideSearch));
			header.add(actions, BorderLayout.EAST);
		}
		panel.add(header, BorderLayout.NORTH);

		if (expanded) {
			JPanel body = new JPanel(new GridBagLayout());
			body.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			int contentInset = section.collapsible ? 8 : 0;
			body.setBorder(BorderFactory.createEmptyBorder(0, contentInset, 6, contentInset));
			Set<String> shown = new LinkedHashSet<>();
			int rowIndex = 0;
			for (var row : section.rows) {
				if (row.field != null) shown.add(row.field.getName());
				addBodyRow(body, createRow(row, section.target), rowIndex++);
			}
			if (section.target != null && section.allowAddingFields) {
				Set<String> added = addedFields.getOrDefault(section.target, Set.of());
				List<Field> remaining = new ArrayList<>();
				for (Field field : OverrideFields.fields(section.target)) {
					if (shown.contains(field.getName())) continue;
					if (added.contains(field.getName())) {
						addBodyRow(body, createFieldRow(field, section), rowIndex++);
						continue;
					}
					if (OverrideFields.isEditable(field)) remaining.add(field);
				}
				if (!remaining.isEmpty())
					addBodyRow(body, createAddField(remaining, section.target), rowIndex++);
			}
			for (var child : section.children)
				addBodyRow(body, createSection(child), rowIndex++);
			Dimension bodySize = body.getPreferredSize();
			body.setMaximumSize(new Dimension(Integer.MAX_VALUE, bodySize.height));
			panel.add(body, BorderLayout.CENTER);
		}

		Dimension panelSize = panel.getPreferredSize();
		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panelSize.height));
		return panel;
	}

	private static void addBodyRow(JPanel body, JComponent component, int row) {
		GridBagConstraints constraints = new GridBagConstraints();
		constraints.gridx = 0;
		constraints.gridy = row;
		constraints.weightx = 1;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		constraints.anchor = GridBagConstraints.NORTHWEST;
		constraints.insets = new Insets(0, 0, 2, 0);
		body.add(component, constraints);
	}

	private JComponent createSectionTitle(String text) {
		JLabel title = new JLabel(text);
		title.setForeground(Color.WHITE);
		title.setFont(title.getFont().deriveFont(Font.BOLD));
		return title;
	}

	private JComponent createAddField(List<Field> remaining, Object target) {
		SearchBox<Field> search = new SearchBox<>(remaining, Field::getName, Field::getName,
			"Add field…", "Type to find a field to add", 0, field -> {
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
			? InspectorEditors.create(row.field, target, history, clientThread, row.fieldValue, row.value, row.editorData)
			: InspectorEditors.readOnly(row.value);
		if (row.actionLabel == null) {
			if (row.field != null && OverrideFields.isEditable(row.field))
				return createFieldRow(OverrideFields.label(row.field), editor, row.field, target);
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
		action.addActionListener(e -> clientThread.invoke(() -> {
			row.action.run();
			SwingUtilities.invokeLater(this::render);
		}));
		JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		actions.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		actions.add(action);
		if (row.field != null && OverrideFields.isEditable(row.field))
			actions.add(createRevertButton(row.field, target));
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
			List<NamedId> options = new ArrayList<>();
			for (var entry : search.objectNames.entrySet())
				if (!search.currentIds.contains(entry.getValue()))
					options.add(new NamedId(entry.getKey(), entry.getValue()));
			options.sort(Comparator.comparing((NamedId option) -> option.name, String.CASE_INSENSITIVE_ORDER)
				.thenComparingInt(option -> option.id));
			SearchBox<NamedId> box = new SearchBox<>(options, NamedId::displayName, NamedId::searchText,
				"Add object ID…", "Type an object name or ID to add it", 100, option -> {
				clientThread.invoke(() -> {
					search.onPick.accept(option.id);
					SwingUtilities.invokeLater(this::render);
				});
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

	private JComponent createModelOverrideSearch(InspectorOutput.ModelOverrideSearch search) {
		JPanel control = new JPanel(new BorderLayout());
		control.setOpaque(false);
		JButton add = new JButton("Add Existing Override");
		add.setMargin(new Insets(2, 6, 2, 6));
		control.add(add, BorderLayout.CENTER);
		add.addActionListener(e -> {
			SearchBox<ModelOverride> box = new SearchBox<>(search.definitions,
				InspectorPanel::modelOverrideDescription, InspectorPanel::modelOverrideDescription,
				"Search override descriptions…", "Type to find an existing override", 0, definition ->
				clientThread.invoke(() -> {
					search.onPick.accept(definition);
					SwingUtilities.invokeLater(this::render);
				}));
			box.setPreferredSize(new Dimension(240, box.getPreferredSize().height));
			control.removeAll();
			control.add(box, BorderLayout.CENTER);
			control.revalidate();
			control.repaint();
			box.openSearch();
		});
		return control;
	}

	private static String modelOverrideDescription(ModelOverride definition) {
		String description = definition.description;
		return description == null || description.isBlank() ? "Unnamed model override" : description;
	}

	private static final class NamedId {
		private final String name;
		private final int id;

		private NamedId(String name, int id) {
			this.name = name;
			this.id = id;
		}

		private String displayName() {
			return name + " (" + id + ")";
		}

		private String searchText() {
			return name + " " + id;
		}
	}

	private static final class SectionCollapseKey {
		private final Object target;
		private final String title;

		private SectionCollapseKey(Object target, String title) {
			this.target = target;
			this.title = title;
		}

		@Override
		public boolean equals(Object other) {
			if (!(other instanceof SectionCollapseKey))
				return false;
			SectionCollapseKey key = (SectionCollapseKey) other;
			return target == key.target && title.equals(key.title);
		}

		@Override
		public int hashCode() {
			return System.identityHashCode(target) * 31 + title.hashCode();
		}
	}

	private static final class InspectorScrollPanel extends JPanel implements Scrollable {
		private InspectorScrollPanel() {
			super(new BorderLayout());
		}

		@Override
		public Dimension getPreferredScrollableViewportSize() {
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
			return orientation == javax.swing.SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width;
		}

		@Override
		public boolean getScrollableTracksViewportWidth() {
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight() {
			return false;
		}
	}

	private JComponent createFieldRow(Field field, InspectorOutput.Section section) {
		JComponent editor = InspectorEditors.create(field, section.target, history,
			clientThread, section.fieldValue(field), section.fieldText(field), null);
		return createFieldRow(OverrideFields.label(field), editor, field, section.target);
	}

	private JComponent createFieldRow(String key, JComponent editor, Field field, Object target) {
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
		row.add(createKey(key), BorderLayout.WEST);
		row.add(editor, BorderLayout.CENTER);
		JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		actions.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		actions.add(createRevertButton(field, target));
		row.add(actions, BorderLayout.EAST);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	private JButton createRevertButton(Field field, Object target) {
		JButton revert = new JButton("↩");
		revert.setToolTipText("Reset to default value");
		revert.setMargin(new Insets(0, 4, 0, 4));
		revert.setPreferredSize(new Dimension(22, 22));
		revert.setFont(revert.getFont().deriveFont(Font.BOLD, 14f));
		revert.setFocusable(false);
		revert.setBorderPainted(false);
		revert.setContentAreaFilled(false);
		revert.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		revert.addActionListener(e -> clientThread.invoke(() -> {
			Object defaults = OverrideFields.defaults(target);
			if (defaults != null) {
				Object defaultValue = OverrideFields.get(field, defaults);
				resetFieldToDefault(field, target, defaultValue);
			}
			SwingUtilities.invokeLater(this::render);
		}));
		return revert;
	}

	private void resetFieldToDefault(Field field, Object target, Object defaultValue) {
		if (target instanceof ModelOverride && field.getName().equals("model")) {
			ModelOverride definition = (ModelOverride) target;
			Object defaults = OverrideFields.defaults(definition);
			Map<Field, Object> previousValues = new IdentityHashMap<>();
			Map<Field, Object> defaultValues = new IdentityHashMap<>();
			Set<String> customModelFields = Set.of("model", "uvs", "materials", "faces", "material");
			for (Field candidate : OverrideFields.fields(definition)) {
				if (!customModelFields.contains(candidate.getName()))
					continue;
				previousValues.put(candidate, snapshotCustomModelField(OverrideFields.get(candidate, definition)));
				Object defaultFieldValue = candidate.equals(field)
					? defaultValue : OverrideFields.get(candidate, defaults);
				defaultValues.put(candidate, snapshotCustomModelField(defaultFieldValue));
			}
			if (customModelFieldsMatch(previousValues, defaultValues))
				return;

			history.execute(
				() -> applyCustomModelFields(definition, previousValues),
				() -> applyCustomModelFields(definition, defaultValues)
			);
			return;
		}
		history.setField(field, target, defaultValue, false);
	}

	private static boolean customModelFieldsMatch(Map<Field, Object> current, Map<Field, Object> defaults) {
		for (var entry : current.entrySet())
			if (!Objects.deepEquals(entry.getValue(), defaults.get(entry.getKey())))
				return false;
		return true;
	}

	private static void applyCustomModelFields(ModelOverride target, Map<Field, Object> values) {
		for (var entry : values.entrySet())
			OverrideFields.set(entry.getKey(), target, snapshotCustomModelField(entry.getValue()));
	}

	private static Object snapshotCustomModelField(Object value) {
		if (value instanceof float[])
			return ((float[]) value).clone();
		if (value instanceof ModelOverride[]) {
			ModelOverride[] overrides = (ModelOverride[]) value;
			ModelOverride[] copy = new ModelOverride[overrides.length];
			for (int i = 0; i < overrides.length; i++)
				copy[i] = overrides[i] == null ? null : overrides[i].copy(true);
			return copy;
		}
		if (value instanceof JsonElement)
			return ((JsonElement) value).deepCopy();
		return value;
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
