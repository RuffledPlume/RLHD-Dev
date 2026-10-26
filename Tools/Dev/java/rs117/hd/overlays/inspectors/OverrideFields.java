package rs117.hd.overlays.inspectors;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.scene.GroundMaterialManager;
import rs117.hd.scene.MaterialManager;
import rs117.hd.scene.WaterTypeManager;
import rs117.hd.scene.ground_materials.GroundMaterial;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.water_types.WaterType;

/**
 * Reflection helpers used by the inspectors to list, format and edit the fields of an override,
 * comparing them against a freshly constructed instance of the same type to find the ones that
 * differ from their default value.
 */
public final class OverrideFields {
	private static final Map<Class<?>, Object> DEFAULTS = new HashMap<>();

	private OverrideFields() {}

	/** All fields of the override which can be listed in the inspector, sorted by name. */
	public static List<Field> fields(Object override) {
		List<Field> fields = new ArrayList<>();
		if (override == null) return fields;
		for (Class<?> type = override.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
			for (Field field : type.getDeclaredFields()) {
				int mod = field.getModifiers();
				if (Modifier.isStatic(mod) || Modifier.isTransient(mod) || field.isSynthetic()) continue;
				field.setAccessible(true);
				fields.add(field);
			}
		}
		fields.sort(Comparator.comparing(Field::getName));
		return fields;
	}

	/** The fields whose value differs from the value held by a default instance of the same type. */
	public static List<Field> changedFields(Object override) {
		List<Field> changed = new ArrayList<>();
		Object defaults = defaults(override);
		for (Field field : fields(override)) {
			if (isNestedOverrideContainer(field)) continue;
			if (!isDefault(field, override, defaults)) changed.add(field);
		}
		return changed;
	}

	private static boolean isNestedOverrideContainer(Field field) {
		String name = field.getName();
		return name.equals("areas") || name.equals("colorOverrides") || name.equals("areaOverrides") || name.equals("materialOverrides")
			|| name.equals("npcIds") || name.equals("objectIds") || name.equals("projectileIds")
			|| name.equals("graphicsObjectIds");
	}

	public static boolean isDefault(Field field, Object override, Object defaults) {
		if (defaults == null) return false;
		Object value = get(field, override);
		Object defaultValue = get(field, defaults);
		return isEmpty(value) && isEmpty(defaultValue) || Objects.deepEquals(value, defaultValue);
	}

	private static boolean isEmpty(Object value) {
		if (value == null) return true;
		if (value.getClass().isArray()) return Array.getLength(value) == 0;
		if (value instanceof Collection) return ((Collection<?>) value).isEmpty();
		if (value instanceof Map) return ((Map<?, ?>) value).isEmpty();
		return false;
	}

	/** A cached instance holding the default value of every field of the given override's type. */
	public static Object defaults(Object override) {
		if (override == null) return null;
		return DEFAULTS.computeIfAbsent(override.getClass(), type -> {
			try {
				var constructor = type.getDeclaredConstructor();
				constructor.setAccessible(true);
				return constructor.newInstance();
			} catch (ReflectiveOperationException e) {
				return null;
			}
		});
	}

	public static Object get(Field field, Object override) {
		try {
			return field.get(override);
		} catch (IllegalAccessException e) {
			return null;
		}
	}

	public static String format(Field field, Object override) {
		if (field.getName().equals("areas") && field.getType() == AABB[].class && get(field, override) == null)
			return "[]";
		if (JsonElement.class.isAssignableFrom(field.getType())) {
			Object value = get(field, override);
			if (value == null || ((JsonElement) value).isJsonNull()) return "";
			JsonElement element = (JsonElement) value;
			return element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
				? element.getAsString() : element.toString();
		}
		return format(get(field, override));
	}

	public static String format(Object value) {
		if (value == null) return "none";
		if (value instanceof AABB[]) {
			StringBuilder result = new StringBuilder("[");
			for (AABB area : (AABB[]) value) {
				if (result.length() > 1) result.append(", ");
				result.append("[").append(area.minX).append(", ").append(area.minY);
				if (area.hasZ()) result.append(", ").append(area.minZ);
				if (area.isVolume()) {
					result.append(", ").append(area.maxX).append(", ").append(area.maxY);
					if (area.hasZ()) result.append(", ").append(area.maxZ);
				}
				result.append("]");
			}
			return result.append("]").toString();
		}
		if (value.getClass().isArray()) {
			int length = Array.getLength(value);
			if (length == 0) return "none";
			List<String> values = new ArrayList<>(length);
			for (int i = 0; i < length; i++) values.add(format(Array.get(value, i)));
			return String.join(", ", values);
		}
		if (value instanceof Collection) {
			var collection = (Collection<?>) value;
			if (collection.isEmpty()) return "none";
			List<String> values = new ArrayList<>(collection.size());
			for (Object element : collection) values.add(format(element));
			return String.join(", ", values);
		}
		if (value instanceof Map) {
			var map = (Map<?, ?>) value;
			if (map.isEmpty()) return "none";
			List<String> values = new ArrayList<>(map.size());
			for (var entry : map.entrySet()) values.add(format(entry.getKey()) + " -> " + format(entry.getValue()));
			return String.join(", ", values);
		}
		return String.valueOf(value);
	}

	/** Whether the inspector knows how to present an editor for the field's type. */
	public static boolean isEditable(Field field) {
		Class<?> type = field.getType();
		return type == boolean.class || type == Boolean.class
			|| type == int.class || type == Integer.class
			|| type == long.class || type == Long.class
			|| type == short.class || type == Short.class
			|| type == byte.class || type == Byte.class
			|| type == float.class || type == Float.class
			|| type == double.class || type == Double.class
			|| type == String.class
			|| field.getName().equals("areas") && type == AABB[].class
			|| field.getName().equals("colors") && JsonElement.class.isAssignableFrom(type)
			|| type.isEnum()
			|| options(type) != null;
	}

	/** The selectable values for types backed by a registry, or null when the type has none. */
	public static Object[] options(Class<?> type) {
		if (type == Material.class) {
			var materials = MaterialManager.MATERIAL_MAP.values().toArray();
			Arrays.sort(materials, Comparator.comparing(OverrideFields::nameOf));
			return materials;
		}
		if (type == GroundMaterial.class) return GroundMaterialManager.GROUND_MATERIALS.clone();
		if (type == WaterType.class) return WaterTypeManager.WATER_TYPES.clone();
		return null;
	}

	public static String nameOf(Object value) {
		if (value == null) return "none";
		if (value instanceof Material) return ((Material) value).name;
		if (value instanceof GroundMaterial) return ((GroundMaterial) value).name;
		if (value instanceof WaterType) return ((WaterType) value).name;
		return String.valueOf(value);
	}

	public static String label(Field field) {
		String name = field.getName().replace('_', ' ').replaceAll("([a-z0-9])([A-Z])", "$1 $2");
		return Character.toUpperCase(name.charAt(0)) + name.substring(1);
	}

	/** Parses the text into the field's type and assigns it, returning false when the text is invalid. */
	public static boolean parseAndSet(Field field, Object override, String text) {
		return parseAndSet(field, override, text, null, false);
	}

	public static boolean parseAndSet(Field field, Object override, String text,
		InspectorHistory history, boolean coalesce) {
		return parseAndSet(field, override, text, history, coalesce, null);
	}

	public static boolean parseAndSet(Field field, Object override, String text,
		InspectorHistory history, boolean coalesce, Runnable afterSet) {
		Class<?> type = field.getType();
		try {
			Object value;
			if (type == String.class) {
				value = text;
			} else if (type == boolean.class || type == Boolean.class) {
				value = Boolean.parseBoolean(text);
			} else if (type == int.class || type == Integer.class) {
				value = Integer.parseInt(text.trim());
			} else if (type == long.class || type == Long.class) {
				value = Long.parseLong(text.trim());
			} else if (type == short.class || type == Short.class) {
				value = Short.parseShort(text.trim());
			} else if (type == byte.class || type == Byte.class) {
				value = Byte.parseByte(text.trim());
			} else if (type == float.class || type == Float.class) {
				value = Float.parseFloat(text.trim());
			} else if (type == double.class || type == Double.class) {
				value = Double.parseDouble(text.trim());
			} else if (field.getName().equals("areas") && type == AABB[].class) {
				value = parseAreas(text);
			} else if (field.getName().equals("colors") && JsonElement.class.isAssignableFrom(type)) {
				String input = text.trim();
				if (input.isEmpty()) value = null;
				else {
					try {
						value = new JsonParser().parse(input);
					} catch (RuntimeException e) {
						if (input.startsWith("[") || input.startsWith("{") || input.startsWith("\"")) return false;
						value = new JsonPrimitive(input);
					}
				}
			} else {
				return false;
			}
			if (history == null) {
				boolean success = set(field, override, value);
				if (success && afterSet != null) afterSet.run();
				return success;
			}
			return history.setField(field, override, value, coalesce, afterSet);
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static AABB[] parseAreas(String text) {
		JsonElement parsed = new JsonParser().parse(text.trim());
		if (!parsed.isJsonArray()) throw new IllegalArgumentException("Areas must be a JSON array");
		JsonArray areas = parsed.getAsJsonArray();
		AABB[] result = new AABB[areas.size()];
		for (int i = 0; i < areas.size(); i++) {
			JsonElement areaElement = areas.get(i);
			if (!areaElement.isJsonArray()) throw new IllegalArgumentException("Each area must be a coordinate array");
			JsonArray coordinates = areaElement.getAsJsonArray();
			if (coordinates.size() < 2 || coordinates.size() > 6)
				throw new IllegalArgumentException("Areas require 2 to 6 coordinates");
			int[] values = new int[coordinates.size()];
			for (int j = 0; j < coordinates.size(); j++) {
				JsonElement coordinate = coordinates.get(j);
				if (!coordinate.isJsonPrimitive() || !coordinate.getAsJsonPrimitive().isNumber())
					throw new IllegalArgumentException("Area coordinates must be integers");
				values[j] = Integer.parseInt(coordinate.getAsString());
			}
			switch (values.length) {
				case 2: result[i] = new AABB(values[0], values[1]); break;
				case 3: result[i] = new AABB(values[0], values[1], values[2]); break;
				case 4: result[i] = new AABB(values[0], values[1], values[2], values[3]); break;
				case 5: result[i] = new AABB(values[0], values[1], values[2], values[3], values[4]); break;
				case 6: result[i] = new AABB(values[0], values[1], values[2], values[3], values[4], values[5]); break;
				default: throw new IllegalArgumentException("Invalid area coordinates");
			}
		}
		return result;
	}

	public static boolean set(Field field, Object override, Object value) {
		try {
			if (field.getName().equals("colors") && override instanceof ModelOverride) {
				((ModelOverride) override).updateColors((JsonElement) value);
				return true;
			}
			field.set(override, value);
			return true;
		} catch (IllegalAccessException | RuntimeException e) {
			return false;
		}
	}
}
