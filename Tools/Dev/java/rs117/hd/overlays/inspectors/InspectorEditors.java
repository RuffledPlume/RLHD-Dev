package rs117.hd.overlays.inspectors;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.ColorScheme;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.MaterialManager;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.areas.Area;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.ColorUtils;

/** Builds the Swing editors the inspector uses to change override fields in place. */
public final class InspectorEditors {
	private static final Color INVALID = new Color(120, 40, 40);

	private InspectorEditors() {}

	public static void registerCustomEditors() {
		OverrideFields.registerCustomEditor(ModelOverride.class, "areas", InspectorEditors::areaDropdown);
		OverrideFields.registerCustomEditor(ModelOverride.class, "colors", InspectorEditors::colorExpressionEditor);
		OverrideFields.registerCustomEditor(ModelOverride.class, "material", InspectorEditors::materialDropdown);
	}

	/** An editor bound to the field of the given override, or a read-only label for unsupported types. */
	public static JComponent create(Field field, Object target, InspectorHistory history, ClientThread clientThread,
		Object initialValue, String initialText, Object editorData) {
		OverrideFields.CustomEditor customEditor = OverrideFields.customEditor(field);
		if (customEditor != null)
			return customEditor.create(field, target, history, clientThread, initialValue, initialText, editorData);

		Class<?> type = field.getType();
		if (type == boolean.class || type == Boolean.class)
			return checkBox(field, target, history, clientThread, initialValue);
		if (type.isEnum())
			return comboBox(field, target, type.getEnumConstants(), history, clientThread, initialValue);
		Object[] options = OverrideFields.options(type);
		if (options != null)
			return comboBox(field, target, options, history, clientThread, initialValue);
		if (OverrideFields.isEditable(field))
			return textField(field, target, history, clientThread, initialText);
		return readOnly(initialText);
	}

	private static JComponent areaDropdown(Field field, Object target, InspectorHistory history,
		ClientThread clientThread, Object initialValue, String initialText, Object editorData) {
		AABB[] current = initialValue instanceof AABB[] ? (AABB[]) initialValue : new AABB[0];
		List<AreaChoice> choices = new ArrayList<>();
		AreaChoice noRestriction = new AreaChoice("No area restriction", new AABB[0]);
		choices.add(noRestriction);
		for (Area area : AreaManager.AREAS) {
			if (area == null || area == Area.NONE || area.aabbs == null || area.aabbs.length == 0)
				continue;
			choices.add(new AreaChoice(area.name, area.aabbs));
		}
		choices.subList(1, choices.size()).sort(Comparator.comparing(choice -> choice.name, String.CASE_INSENSITIVE_ORDER));

		AreaChoice selected = null;
		for (AreaChoice choice : choices) {
			if (Arrays.equals(current, choice.areas)) {
				selected = choice;
				break;
			}
		}
		if (selected == null) {
			selected = new AreaChoice("Custom: " + initialText, current);
			choices.add(selected);
		}

		SearchBox<AreaChoice> search = new SearchBox<>(choices, choice -> choice.name, choice -> choice.name,
			"Search areas…", "Search named areas or remove the area restriction", 0, choice -> {
				AABB[] areas = choice.areas.clone();
				clientThread.invoke(() -> history.setField(field, target, areas, false));
			});
		search.setSelectionDisplay(selected.name);
		return search;
	}

	private static JComponent materialDropdown(Field field, Object target, InspectorHistory history,
		ClientThread clientThread, Object initialValue, String initialText, Object editorData) {
		List<Material> materials = new ArrayList<>(MaterialManager.MATERIAL_MAP.values());
		if (initialValue instanceof Material && !materials.contains(initialValue))
			materials.add((Material) initialValue);
		materials.sort(Comparator.comparing(InspectorEditors::materialName, String.CASE_INSENSITIVE_ORDER));

		Runnable refresh = editorData instanceof Runnable ? (Runnable) editorData : () -> {};
		SearchBox<Material> search = new SearchBox<>(materials, InspectorEditors::materialName,
			InspectorEditors::materialName, "Search materials…", "Search for a material", 0, material ->
			clientThread.invoke(() -> {
				history.setField(field, target, material, false);
				SwingUtilities.invokeLater(refresh);
			}));
		search.setSelectionDisplay(initialValue instanceof Material
			? materialName((Material) initialValue) : initialText);
		return search;
	}

	private static String materialName(Material material) {
		return material.name == null ? "Unnamed material" : material.name;
	}

	private static JComponent colorExpressionEditor(Field field, Object target, InspectorHistory history,
		ClientThread clientThread, Object initialValue, String initialText, Object editorData) {
		JTextField expression = (JTextField) textField(field, target, history, clientThread, initialText);
		int[] modelColors = editorData instanceof int[] ? (int[]) editorData : new int[0];
		ColorChoice[] choices = Arrays.stream(modelColors).distinct().sorted()
			.mapToObj(ColorChoice::new).toArray(ColorChoice[]::new);
		JComboBox<ColorChoice> colors = new JComboBox<>(choices);
		colors.setMaximumRowCount(12);
		colors.setRenderer((list, value, index, isSelected, cellHasFocus) -> {
			JPanel row = new JPanel(new BorderLayout(6, 0));
			row.setOpaque(true);
			row.setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());

			JPanel swatch = new JPanel();
			swatch.setOpaque(true);
			swatch.setBackground(value.swatch);
			swatch.setPreferredSize(new java.awt.Dimension(16, 16));
			swatch.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
			row.add(swatch, BorderLayout.WEST);

			JLabel label = new JLabel(value.label);
			label.setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
			row.add(label, BorderLayout.CENTER);
			return row;
		});
		colors.setToolTipText("Choose a color from the selected model");
		colors.setEnabled(choices.length > 0);

		JButton add = new JButton("+");
		add.setToolTipText("Add the selected model color to this expression");
		add.setMargin(new java.awt.Insets(1, 6, 1, 6));
		add.setEnabled(choices.length > 0);
		add.addActionListener(e -> {
			ColorChoice choice = (ColorChoice) colors.getSelectedItem();
			if (choice == null)
				return;

			JsonArray expressionArray = new JsonArray();
			String current = expression.getText().trim();
			if (!current.isEmpty()) {
				try {
					JsonElement parsed = new JsonParser().parse(current);
					if (parsed.isJsonArray()) {
						for (JsonElement value : parsed.getAsJsonArray())
							expressionArray.add(value.deepCopy());
					} else {
						expressionArray.add(parsed);
					}
				} catch (RuntimeException ex) {
					expressionArray.add(new JsonPrimitive(current));
				}
			}

			JsonPrimitive color = new JsonPrimitive(choice.color);
			if (!expressionArray.contains(color))
				expressionArray.add(color);
			expression.setText(expressionArray.toString());
		});

		JPanel editor = new JPanel(new BorderLayout(4, 0));
		editor.setOpaque(false);
		editor.add(expression, BorderLayout.CENTER);
		JPanel controls = new JPanel(new BorderLayout(3, 0));
		controls.setOpaque(false);
		controls.add(colors, BorderLayout.CENTER);
		controls.add(add, BorderLayout.EAST);
		editor.add(controls, BorderLayout.EAST);
		return editor;
	}

	private static final class ColorChoice {
		private final int color;
		private final String label;
		private final Color swatch;

		private ColorChoice(int color) {
			this.color = color;
			int[] hsl = rs117.hd.utils.ColorUtils.unpackRawHsl(color);
			label = String.format("%d [H:%d S:%d L:%d]", color, hsl[0], hsl[1], hsl[2]);
			swatch = new Color(ColorUtils.packSrgb(ColorUtils.packedHslToSrgb(color)));
		}
	}

	private static final class AreaChoice {
		private final String name;
		private final AABB[] areas;

		private AreaChoice(String name, AABB[] areas) {
			this.name = name;
			this.areas = areas;
		}
	}

	public static JLabel readOnly(String value) {
		JLabel label = new JLabel(value);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return label;
	}

	private static JComponent checkBox(Field field, Object target, InspectorHistory history, ClientThread clientThread,
		Object initialValue) {
		JCheckBox checkBox = new JCheckBox();
		checkBox.setSelected(Boolean.TRUE.equals(initialValue));
		checkBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		checkBox.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		checkBox.addActionListener(e -> {
			boolean selected = checkBox.isSelected();
			clientThread.invoke(() -> history.setField(field, target, selected, false));
		});
		return checkBox;
	}

	private static JComponent comboBox(Field field, Object target, Object[] options, InspectorHistory history,
		ClientThread clientThread, Object initialValue) {
		JComboBox<Object> comboBox = new JComboBox<>(options);
		comboBox.setSelectedItem(initialValue);
		comboBox.setBackground(ColorScheme.DARK_GRAY_COLOR);
		comboBox.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		comboBox.setRenderer(new DefaultListCellRenderer() {
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index,
				boolean selected, boolean hasFocus) {
				Component component = super.getListCellRendererComponent(list, value, index, selected, hasFocus);
				if (component instanceof JLabel)
					((JLabel) component).setText(OverrideFields.nameOf(value));
				return component;
			}
		});
		comboBox.addActionListener(e -> {
			Object selected = comboBox.getSelectedItem();
			clientThread.invoke(() -> history.setField(field, target, selected, false));
		});
		return comboBox;
	}

	private static JComponent textField(Field field, Object target, InspectorHistory history,
		ClientThread clientThread, String initialText) {
		JTextField textField = new JTextField(initialText);
		Color background = ColorScheme.DARK_GRAY_COLOR;
		textField.setBackground(background);
		textField.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		textField.setCaretColor(ColorScheme.LIGHT_GRAY_COLOR);
		textField.getDocument().addDocumentListener(new DocumentListener() {
			private void commit() {
				String text = textField.getText();
				clientThread.invoke(() -> {
					boolean valid = OverrideFields.parseAndSet(field, target, text, history, true);
					SwingUtilities.invokeLater(() -> textField.setBackground(valid ? background : INVALID));
				});
			}

			@Override public void insertUpdate(DocumentEvent e) { commit(); }
			@Override public void removeUpdate(DocumentEvent e) { commit(); }
			@Override public void changedUpdate(DocumentEvent e) { commit(); }
		});
		textField.addFocusListener(new FocusAdapter() {
			@Override public void focusLost(FocusEvent e) { clientThread.invoke(history::endCoalescing); }
		});
		return textField;
	}
}
