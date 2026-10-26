package rs117.hd.overlays.inspectors;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;

/** A search box which suggests the override fields that aren't shown yet, so they can be added. */
public final class FieldSearchBox extends JPanel {
	private static final int MAX_VISIBLE_ROWS = 10;
	private static final String PLACEHOLDER = "Add field…";

	private final List<Field> available;
	private final Consumer<Field> onPick;
	private final JTextField search = new JTextField();
	private final DefaultListModel<Field> matches = new DefaultListModel<>();
	private final JList<Field> suggestions = new JList<>(matches);
	private final JPopupMenu popup = new JPopupMenu();
	private boolean placeholder = true;

	public FieldSearchBox(List<Field> available, Consumer<Field> onPick) {
		super(new BorderLayout());
		this.available = available;
		this.onPick = onPick;
		setBackground(ColorScheme.DARKER_GRAY_COLOR);

		search.setText(PLACEHOLDER);
		search.setToolTipText("Type to find a field to add");
		search.setBackground(ColorScheme.DARK_GRAY_COLOR);
		search.setForeground(Color.GRAY);
		search.setCaretColor(ColorScheme.LIGHT_GRAY_COLOR);
		add(search, BorderLayout.CENTER);
		search.addFocusListener(new FocusAdapter() {
			@Override
			public void focusGained(FocusEvent e) {
				if (placeholder) {
					placeholder = false;
					search.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
					search.setText("");
				} else {
					refresh();
				}
			}

			@Override
			public void focusLost(FocusEvent e) {
				popup.setVisible(false);
				if (search.getText().isEmpty()) {
					placeholder = true;
					search.setForeground(Color.GRAY);
					search.setText(PLACEHOLDER);
				}
			}
		});

		suggestions.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		suggestions.setBackground(ColorScheme.DARK_GRAY_COLOR);
		suggestions.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		suggestions.setCellRenderer(InspectorEditors.fieldRenderer());

		JScrollPane scroll = new JScrollPane(suggestions);
		scroll.setBorder(null);
		popup.setFocusable(false);
		popup.add(scroll);

		search.getDocument().addDocumentListener(new DocumentListener() {
			@Override public void insertUpdate(DocumentEvent e) { refresh(); }
			@Override public void removeUpdate(DocumentEvent e) { refresh(); }
			@Override public void changedUpdate(DocumentEvent e) { refresh(); }
		});
		search.addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				switch (e.getKeyCode()) {
					case KeyEvent.VK_DOWN:
						move(1);
						e.consume();
						break;
					case KeyEvent.VK_UP:
						move(-1);
						e.consume();
						break;
					case KeyEvent.VK_ENTER:
						pickSelected();
						e.consume();
						break;
					case KeyEvent.VK_ESCAPE:
						popup.setVisible(false);
						break;
				}
			}
		});
		suggestions.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() >= 1) pickSelected();
			}
		});
	}

	private void refresh() {
		if (!search.hasFocus() || placeholder) {
			popup.setVisible(false);
			return;
		}
		String query = search.getText().trim().toLowerCase(Locale.ROOT);
		List<Field> filtered = new ArrayList<>();
		for (Field field : available) {
			if (query.isEmpty() || field.getName().toLowerCase(Locale.ROOT).contains(query)) filtered.add(field);
		}
		matches.clear();
		for (Field field : filtered) matches.addElement(field);
		if (filtered.isEmpty()) {
			popup.setVisible(false);
			return;
		}
		suggestions.setSelectedIndex(0);
		suggestions.setVisibleRowCount(Math.min(MAX_VISIBLE_ROWS, filtered.size()));
		Dimension viewport = suggestions.getPreferredScrollableViewportSize();
		SwingUtilities.invokeLater(() -> {
			if (!isShowing() || !search.hasFocus()) return;
			popup.setPopupSize(Math.max(getWidth(), 240), viewport.height + 4);
			popup.show(this, 0, getHeight());
			search.requestFocusInWindow();
		});
	}

	private void move(int delta) {
		if (matches.isEmpty()) {
			refresh();
			return;
		}
		int index = Math.max(0, Math.min(matches.size() - 1, suggestions.getSelectedIndex() + delta));
		suggestions.setSelectedIndex(index);
		suggestions.ensureIndexIsVisible(index);
	}

	private void pickSelected() {
		Field field = suggestions.getSelectedValue();
		if (field == null) return;
		popup.setVisible(false);
		placeholder = true;
		search.setForeground(Color.GRAY);
		search.setText(PLACEHOLDER);
		onPick.accept(field);
	}
}
