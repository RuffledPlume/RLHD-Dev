package rs117.hd.overlays.inspectors;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.DefaultListCellRenderer;
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

/** A searchable dropdown for selecting an item from a supplied list. */
public final class SearchBox<T> extends JPanel {
	private static final int MAX_VISIBLE_ROWS = 10;

	private final List<T> available;
	private final Function<T, String> displayText;
	private final Function<T, String> searchText;
	private final Consumer<T> onPick;
	private final int maxSuggestions;
	private final String placeholder;
	private final JTextField search = new JTextField();
	private final DefaultListModel<T> matches = new DefaultListModel<>();
	private final JList<T> suggestions = new JList<>(matches);
	private final JPopupMenu popup = new JPopupMenu();
	private boolean placeholderShown = true;
	private boolean keepSelection;
	private boolean suppressDocumentRefresh;

	public SearchBox(
		List<T> available,
		Function<T, String> displayText,
		Function<T, String> searchText,
		String placeholder,
		String toolTip,
		int maxSuggestions,
		Consumer<T> onPick
	) {
		super(new BorderLayout());
		this.available = new ArrayList<>(available);
		this.displayText = displayText;
		this.searchText = searchText;
		this.placeholder = placeholder;
		this.maxSuggestions = maxSuggestions;
		this.onPick = onPick;
		setBackground(ColorScheme.DARKER_GRAY_COLOR);

		search.setText(placeholder);
		search.setToolTipText(toolTip);
		search.setBackground(ColorScheme.DARK_GRAY_COLOR);
		search.setForeground(Color.GRAY);
		search.setCaretColor(ColorScheme.LIGHT_GRAY_COLOR);
		add(search, BorderLayout.CENTER);
		search.addFocusListener(new FocusAdapter() {
			@Override
			public void focusGained(FocusEvent e) {
				if (placeholderShown) {
					placeholderShown = false;
					search.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
					search.setText("");
				} else {
					search.selectAll();
					refresh();
				}
			}

			@Override
			public void focusLost(FocusEvent e) {
				popup.setVisible(false);
				if (search.getText().isEmpty())
					resetPlaceholder();
			}
		});

		suggestions.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		suggestions.setBackground(ColorScheme.DARK_GRAY_COLOR);
		suggestions.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		DefaultListCellRenderer renderer = new DefaultListCellRenderer();
		suggestions.setCellRenderer((list, value, index, selected, hasFocus) -> {
			Component component = renderer.getListCellRendererComponent(list, value, index, selected, hasFocus);
			if (component instanceof JLabel)
				((JLabel) component).setText(displayText.apply(value));
			return component;
		});

		JScrollPane scroll = new JScrollPane(suggestions);
		scroll.setBorder(null);
		popup.setFocusable(false);
		popup.add(scroll);

		search.getDocument().addDocumentListener(new DocumentListener() {
			@Override public void insertUpdate(DocumentEvent e) { if (!suppressDocumentRefresh) refresh(); }
			@Override public void removeUpdate(DocumentEvent e) { if (!suppressDocumentRefresh) refresh(); }
			@Override public void changedUpdate(DocumentEvent e) { if (!suppressDocumentRefresh) refresh(); }
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
				if (e.getClickCount() >= 1)
					pickSelected();
			}
		});
	}

	public void openSearch() {
		placeholderShown = false;
		search.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		search.setText("");
		SwingUtilities.invokeLater(() -> {
			if (!isShowing())
				return;
			search.requestFocusInWindow();
			refresh();
		});
	}

	/** Keeps the picked value visible instead of restoring the placeholder after selection. */
	public void setSelectionDisplay(String value) {
		keepSelection = true;
		placeholderShown = false;
		search.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		setSearchText(value);
	}

	private void refresh() {
		if (!search.hasFocus() || placeholderShown) {
			popup.setVisible(false);
			return;
		}

		String query = search.getText().trim().toLowerCase(Locale.ROOT);
		matches.clear();
		for (T item : available) {
			if (!searchText.apply(item).toLowerCase(Locale.ROOT).contains(query))
				continue;
			matches.addElement(item);
			if (maxSuggestions > 0 && matches.size() >= maxSuggestions)
				break;
		}
		if (matches.isEmpty()) {
			popup.setVisible(false);
			return;
		}

		suggestions.setSelectedIndex(0);
		suggestions.setVisibleRowCount(Math.min(MAX_VISIBLE_ROWS, matches.size()));
		Dimension viewport = suggestions.getPreferredScrollableViewportSize();
		SwingUtilities.invokeLater(() -> {
			if (!isShowing() || !search.hasFocus())
				return;
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
		T selected = suggestions.getSelectedValue();
		if (selected == null)
			return;
		popup.setVisible(false);
		if (keepSelection)
			setSelectionDisplay(displayText.apply(selected));
		else
			resetPlaceholder();
		onPick.accept(selected);
	}

	private void resetPlaceholder() {
		placeholderShown = true;
		search.setForeground(Color.GRAY);
		setSearchText(placeholder);
	}

	private void setSearchText(String text) {
		suppressDocumentRefresh = true;
		search.setText(text);
		suppressDocumentRefresh = false;
	}
}
