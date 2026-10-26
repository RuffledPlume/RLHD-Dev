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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
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

/** A searchable picker for adding GameVal object IDs to a model override. */
public final class ObjectIdSearchBox extends JPanel {
	private static final int MAX_VISIBLE_ROWS = 10;
	private static final int MAX_SUGGESTIONS = 100;
	private static final String PLACEHOLDER = "Add object ID…";

	private final List<Option> available = new ArrayList<>();
	private final Set<Integer> currentIds;
	private final IntConsumer onPick;
	private final JTextField search = new JTextField();
	private final DefaultListModel<Option> matches = new DefaultListModel<>();
	private final JList<Option> suggestions = new JList<>(matches);
	private final JPopupMenu popup = new JPopupMenu();
	private boolean placeholder = true;

	public ObjectIdSearchBox(Map<String, Integer> objectNames, Set<Integer> currentIds, IntConsumer onPick) {
		super(new BorderLayout());
		this.currentIds = currentIds;
		this.onPick = onPick;
		for (var entry : objectNames.entrySet())
			available.add(new Option(entry.getKey(), entry.getValue()));
		available.sort(Comparator.comparing((Option option) -> option.name, String.CASE_INSENSITIVE_ORDER)
			.thenComparingInt(option -> option.id));
		setBackground(ColorScheme.DARKER_GRAY_COLOR);

		search.setText(PLACEHOLDER);
		search.setToolTipText("Type an object name to add its GameVal ID");
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
		suggestions.setCellRenderer((list, value, index, selected, focus) -> {
			JLabel label = new JLabel(value.name + " (" + value.id + ")");
			label.setOpaque(true);
			label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
			label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
			return label;
		});

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

	public void openSearch() {
		placeholder = false;
		search.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		search.setText("");
		SwingUtilities.invokeLater(() -> {
			if (!isShowing()) return;
			search.requestFocusInWindow();
			refresh();
		});
	}

	private void refresh() {
		if (!search.hasFocus() || placeholder) {
			popup.setVisible(false);
			return;
		}
		String query = search.getText().trim().toLowerCase(Locale.ROOT);
		matches.clear();
		for (Option option : available) {
			String name = option.name.toLowerCase(Locale.ROOT);
			String id = Integer.toString(option.id);
			if (currentIds.contains(option.id) || !query.isEmpty() && !name.contains(query) && !id.contains(query)) continue;
			matches.addElement(option);
			if (matches.size() >= MAX_SUGGESTIONS) break;
		}
		if (matches.isEmpty()) {
			popup.setVisible(false);
			return;
		}
		suggestions.setSelectedIndex(0);
		suggestions.setVisibleRowCount(Math.min(MAX_VISIBLE_ROWS, matches.size()));
		Dimension viewport = suggestions.getPreferredScrollableViewportSize();
		SwingUtilities.invokeLater(() -> {
			if (!isShowing() || !search.hasFocus()) return;
			popup.setPopupSize(Math.max(getWidth(), 260), viewport.height + 4);
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
		Option option = suggestions.getSelectedValue();
		if (option == null) return;
		popup.setVisible(false);
		placeholder = true;
		search.setForeground(Color.GRAY);
		search.setText(PLACEHOLDER);
		onPick.accept(option.id);
	}

	private static final class Option {
		private final String name;
		private final int id;

		private Option(String name, int id) {
			this.name = name;
			this.id = id;
		}
	}
}
