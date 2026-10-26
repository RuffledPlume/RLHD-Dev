package rs117.hd.overlays.inspectors;

import java.awt.Color;
import java.awt.Component;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.lang.reflect.Field;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import rs117.hd.scene.ModelOverrideManager;
import rs117.hd.scene.model_overrides.ModelOverride;

/** Builds the Swing editors the inspector uses to change override fields in place. */
public final class InspectorEditors {
	private static final Color INVALID = new Color(120, 40, 40);

	private InspectorEditors() {}

	/** Renders a reflected field by its name, used by both the suggestions list and the rows. */
	public static ListCellRenderer<Object> fieldRenderer() {
		return new DefaultListCellRenderer() {
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index,
				boolean selected, boolean hasFocus) {
				Component component = super.getListCellRendererComponent(list, value, index, selected, hasFocus);
				if (component instanceof JLabel && value instanceof Field)
					((JLabel) component).setText(((Field) value).getName());
				return component;
			}
		};
	}

	/** An editor bound to the field of the given override, or a read-only label for unsupported types. */
	public static JComponent create(Field field, Object target, InspectorHistory history, ModelOverrideManager modelOverrideManager) {
		Class<?> type = field.getType();
		if (type == boolean.class || type == Boolean.class)
			return checkBox(field, target, history);
		if (type.isEnum())
			return comboBox(field, target, type.getEnumConstants(), history);
		Object[] options = OverrideFields.options(type);
		if (options != null)
			return comboBox(field, target, options, history);
		if (OverrideFields.isEditable(field)) {
			Runnable afterSet = null;
			if (field.getName().equals("areas") && target instanceof ModelOverride) {
				var owners = modelOverrideManager.getAreaOverrideOwners((ModelOverride) target);
				afterSet = () -> modelOverrideManager.updateAreaOverrideMappings(owners, (ModelOverride) target);
			}
			return textField(field, target, history, afterSet);
		}
		return readOnly(OverrideFields.format(field, target));
	}

	public static JLabel readOnly(String value) {
		JLabel label = new JLabel(value);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return label;
	}

	private static JComponent checkBox(Field field, Object target, InspectorHistory history) {
		JCheckBox checkBox = new JCheckBox();
		checkBox.setSelected(Boolean.TRUE.equals(OverrideFields.get(field, target)));
		checkBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		checkBox.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		checkBox.addActionListener(e -> {
			history.setField(field, target, checkBox.isSelected(), false);
		});
		return checkBox;
	}

	private static JComponent comboBox(Field field, Object target, Object[] options, InspectorHistory history) {
		JComboBox<Object> comboBox = new JComboBox<>(options);
		comboBox.setSelectedItem(OverrideFields.get(field, target));
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
			history.setField(field, target, comboBox.getSelectedItem(), false);
		});
		return comboBox;
	}

	private static JComponent textField(Field field, Object target, InspectorHistory history, Runnable afterSet) {
		JTextField textField = new JTextField(OverrideFields.format(field, target));
		Color background = ColorScheme.DARK_GRAY_COLOR;
		textField.setBackground(background);
		textField.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		textField.setCaretColor(ColorScheme.LIGHT_GRAY_COLOR);
		textField.getDocument().addDocumentListener(new DocumentListener() {
			private void commit() {
				boolean valid = OverrideFields.parseAndSet(field, target, textField.getText(), history, true, afterSet);
				textField.setBackground(valid ? background : INVALID);
			}

			@Override public void insertUpdate(DocumentEvent e) { commit(); }
			@Override public void removeUpdate(DocumentEvent e) { commit(); }
			@Override public void changedUpdate(DocumentEvent e) { commit(); }
		});
		textField.addFocusListener(new FocusAdapter() {
			@Override public void focusLost(FocusEvent e) { history.endCoalescing(); }
		});
		return textField;
	}
}
