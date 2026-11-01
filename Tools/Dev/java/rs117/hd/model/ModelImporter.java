package rs117.hd.model;

import com.google.gson.Gson;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonArray;
import rs117.hd.scene.model_overrides.ModelOverride;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginChanged;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import org.lwjgl.assimp.AIColor4D;
import org.lwjgl.assimp.AIMaterial;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AIString;
import org.lwjgl.system.MemoryStack;
import rs117.hd.HdPlugin;
import rs117.hd.scene.MaterialManager;
import rs117.hd.scene.ModelOverrideManager;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.GsonUtils;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static org.lwjgl.assimp.Assimp.AI_MATKEY_NAME;
import static org.lwjgl.assimp.Assimp.AI_MAX_NUMBER_OF_COLOR_SETS;
import static org.lwjgl.assimp.Assimp.aiGetErrorString;
import static org.lwjgl.assimp.Assimp.aiGetMaterialString;
import static org.lwjgl.assimp.Assimp.aiImportFile;
import static org.lwjgl.assimp.Assimp.aiReleaseImport;
import static org.lwjgl.assimp.Assimp.aiReturn_SUCCESS;
import static rs117.hd.DeveloperPlugin.MODEL_EXPORTS;
import static rs117.hd.utils.ResourcePath.path;

/** Imports editable UV0 and materials, using immutable UV1 corner IDs for custom models. */
@Slf4j
public class ModelImporter {
	// Consume whole strings and comments so embedded property names cannot match.
	private static final Pattern JSON_TOKEN = Pattern.compile(
		"\"(?:\\\\.|[^\"\\\\])*\"|//[^\\r\\n]*|/\\*[\\s\\S]*?\\*/|[{}\\[\\],:]|true|false|null|-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");

	@Inject
	private HdPlugin plugin;

	@Inject
	private ModelOverrideManager overrides;

	@Inject
	private ClientThread clientThread;

	@Inject
	private EventBus eventBus;

	@Inject
	private ScheduledExecutorService executor;

	private ResourcePath source;
	private Gson originalGson, installedGson;
	private FileWatcher.UnregisterCallback modelExportsWatcher;
	private ScheduledFuture<?> debounce;
	private long reloadRevision;
	private boolean closed;

	public void startup() {
		source = Props.getFile("rlhd.model-overrides-path", () -> path(ModelOverrideManager.class, "model_overrides.json"));
		eventBus.register(this);
		installAdapter();
		try {
			modelExportsWatcher = FileWatcher.watchPath(
					MODEL_EXPORTS.mkdirs(),
					changed -> {
						synchronized (this) {
							if (closed)
								return;
							if (debounce != null)
								debounce.cancel(false);
							long revision = ++reloadRevision;
							debounce = executor.schedule(() -> clientThread.invoke(() -> {
								synchronized (this) {
									// Cancellation cannot remove a callback already queued on the client thread.
									if (!closed && revision == reloadRevision)
										reloadOverrides();
								}
							}), 100, TimeUnit.MILLISECONDS);
						}
					}
			);
		} catch (RuntimeException ex) {
			log.warn("Unable to watch model-exports", ex);
		}
	}

	public synchronized void shutdown() {
		closed = true;
		if (debounce != null)
			debounce.cancel(false);
		debounce = null;
		if (modelExportsWatcher != null)
			modelExportsWatcher.unregister();
		modelExportsWatcher = null;
		eventBus.unregister(this);
		if (installedGson != null && plugin.getGson() == installedGson)
			setGson(originalGson);
	}

	@Subscribe
	public void onPluginChanged(PluginChanged event) {
		if (event.getPlugin() == plugin && event.isLoaded())
			clientThread.invoke(() -> {
				if (!closed)
					installAdapter();
			});
	}

	private void installAdapter() {
		Gson gson = plugin.getGson();
		if (gson == null || gson == installedGson)
			return;
		originalGson = gson;
		installedGson = gson.newBuilder()
			.registerTypeAdapter(ModelOverride[].class, (JsonDeserializer<ModelOverride[]>) (json, type, context) -> {
				resolve(json);
				return gson.fromJson(json, ModelOverride[].class);
			}).create();
		setGson(installedGson);
		reloadOverrides();
	}

	private void setGson(Gson gson) {
		try {
			var field = HdPlugin.class.getDeclaredField("gson");
			field.setAccessible(true);
			field.set(plugin, gson);
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("Unable to install model import adapter", ex);
		}
	}

	private void reloadOverrides() {
		if (!plugin.isActive())
			return;

		ModelOverride[] definitions = overrides.getModelOverrideDefinitions().toArray(ModelOverride[]::new);
		if (definitions.length == 0 || originalGson == null) {
			overrides.reload();
		} else {
			overrides.loadModelOverrides(prepareDefinitionsForLoad(definitions), true);
		}
	}

	public ModelOverride[] prepareDefinitionsForLoad(ModelOverride[] definitions) {
		if (originalGson == null)
			return definitions;

		JsonElement json = originalGson.toJsonTree(definitions);
		resolve(json);
		GsonUtils.removeNulls(json);
		return originalGson.fromJson(json, ModelOverride[].class);
	}

	private int resolve(JsonElement json) {
		int count = 0;
		if (json == null || json.isJsonNull())
			return count;
		if (json.isJsonArray()) {
			for (var child : json.getAsJsonArray())
				count += resolve(child);
		} else if (json.isJsonObject()) {
			var object = json.getAsJsonObject();
			var custom = object.get("model");
			if (custom != null && custom.isJsonPrimitive() && custom.getAsJsonPrimitive().isString()) {
				var path = MODEL_EXPORTS.toPath().resolve(custom.getAsString()).normalize();
				var material = object.has("material") ? object.get("material") : object.get("baseMaterial");
				var imported = loadCustom(path, materialName(material), false);
				for (var entry : imported.getAsJsonObject("custom").entrySet())
					object.add(entry.getKey(), entry.getValue());
				object.remove("baseMaterial");
				object.add("material", imported.get("material"));
				log.info("Loaded custom model override '{}' from {}", object.get("description"), path);
				count++;
			}
			var uvs = object.get("uvs");
			if (uvs != null && uvs.isJsonPrimitive() && uvs.getAsJsonPrimitive().isString()) {
				var path = MODEL_EXPORTS.toPath().resolve(uvs.getAsString()).normalize();
				object.add("uvs", loadUvs(path));
				var description = object.get("description");
				log.info("Loaded UVs for model override '{}' from {}",
					description == null || description.isJsonNull() ? "UNKNOWN" : description.getAsString(), path);
				count++;
			}
			for (var entry : object.entrySet())
				if (!entry.getKey().equals("uvs"))
					count += resolve(entry.getValue());
		}
		return count;
	}

	public int[] loadVertexColors(String modelPath) {
		Path path = MODEL_EXPORTS.toPath().resolve(modelPath).normalize();
		try {
			int[] customColors = loadGlbColorAttribute(path);
			if (customColors != null)
				return customColors;
		} catch (IOException ex) {
			throw new IllegalArgumentException("Unable to read _COLOR from " + path, ex);
		}
		JsonArray colors = loadCustom(path, "NONE", true).getAsJsonObject("custom").getAsJsonArray("vertexColors");
		int[] values = new int[colors.size()];
		for (int i = 0; i < values.length; i++)
			values[i] = colors.get(i).getAsInt();
		return values;
	}

	@Nullable
	private static int[] loadGlbColorAttribute(Path path) throws IOException {
		byte[] data = Files.readAllBytes(path);
		if (data.length < 12 || data[0] != 'g' || data[1] != 'l' || data[2] != 'T' || data[3] != 'F')
			return null;

		ByteBuffer glb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		if (glb.getInt(4) != 2)
			return null;
		final int JSON_CHUNK = 0x4E4F534A;
		final int BIN_CHUNK = 0x004E4942;
		JsonObject root = null;
		ByteBuffer binary = null;
		for (int offset = 12; offset + 8 <= data.length;) {
			int length = glb.getInt(offset);
			int type = glb.getInt(offset + 4);
			offset += 8;
			if (length < 0 || offset + length > data.length)
				throw new IOException("Invalid GLB chunk length");
			if (type == JSON_CHUNK) {
				String json = new String(data, offset, length, StandardCharsets.UTF_8).trim();
				root = new JsonParser().parse(json).getAsJsonObject();
			} else if (type == BIN_CHUNK) {
				binary = ByteBuffer.wrap(data, offset, length).slice().order(ByteOrder.LITTLE_ENDIAN);
			}
			offset += length;
		}
		if (root == null || binary == null)
			return null;

		JsonArray meshes = root.getAsJsonArray("meshes");
		if (meshes == null)
			return null;
		int[] result = null;
		boolean foundCustomColors = false;
		for (var meshElement : meshes) {
			for (var primitiveElement : meshElement.getAsJsonObject().getAsJsonArray("primitives")) {
				var primitive = primitiveElement.getAsJsonObject();
				var attributes = primitive.getAsJsonObject("attributes");
				if (attributes == null || !attributes.has("_COLOR"))
					continue;
				if (!attributes.has("TEXCOORD_1"))
					throw new IOException("_COLOR primitive is missing TEXCOORD_1 corner IDs");

				float[][] vertexColors = readAccessor(root, binary, attributes.get("_COLOR").getAsInt());
				float[][] cornerIds = readAccessor(root, binary, attributes.get("TEXCOORD_1").getAsInt());
				if (cornerIds.length == 0 || cornerIds[0].length < 2 ||
					vertexColors.length != cornerIds.length || vertexColors[0].length < 3)
					throw new IOException("Invalid _COLOR or TEXCOORD_1 accessor");

				float countValue = Math.abs(cornerIds[0][1]);
				int cornerCount = Math.round(countValue);
				if (cornerCount <= 0 || countValue != cornerCount || cornerCount % 3 != 0)
					throw new IOException("Invalid original corner count in TEXCOORD_1");
				if (result == null) {
					result = new int[cornerCount];
					java.util.Arrays.fill(result, ModelOverride.NO_VERTEX_COLOR);
				} else if (result.length != cornerCount) {
					throw new IOException("Inconsistent original corner count across GLB primitives");
				}

				int[] indices;
				if (primitive.has("indices")) {
					float[][] indexValues = readAccessor(root, binary, primitive.get("indices").getAsInt());
					indices = new int[indexValues.length];
					for (int i = 0; i < indices.length; i++)
						indices[i] = Math.round(indexValues[i][0]);
				} else {
					indices = new int[vertexColors.length];
					for (int i = 0; i < indices.length; i++)
						indices[i] = i;
				}
				if (indices.length % 3 != 0)
					throw new IOException("_COLOR primitive does not contain triangles");

				for (int i = 0; i < indices.length; i++) {
					int vertex = indices[i];
					if (vertex < 0 || vertex >= vertexColors.length)
						throw new IOException("_COLOR primitive has an invalid vertex index");
					float cornerValue = cornerIds[vertex][0];
					int corner = Math.round(cornerValue);
					if (cornerValue != corner || corner < 0 || corner >= result.length)
						throw new IOException("Invalid corner ID in TEXCOORD_1");

					float[] color = vertexColors[vertex];
					float alpha = color.length > 3 ? color[3] : 1;
					float[] srgb = {
						clampColor(color[0]), clampColor(color[1]), clampColor(color[2])
					};
					int transparency = Math.round((1 - clampColor(alpha)) * 255);
					result[corner] = transparency << 24 | ColorUtils.srgbToPackedHsl(srgb);
				}
				foundCustomColors = true;
			}
		}
		return foundCustomColors ? result : null;
	}

	private static float[][] readAccessor(JsonObject root, ByteBuffer binary, int accessorIndex) throws IOException {
		JsonArray accessors = root.getAsJsonArray("accessors");
		JsonArray views = root.getAsJsonArray("bufferViews");
		if (accessors == null || views == null || accessorIndex < 0 || accessorIndex >= accessors.size())
			throw new IOException("Invalid GLB accessor index");
		var accessor = accessors.get(accessorIndex).getAsJsonObject();
		if (!accessor.has("bufferView"))
			throw new IOException("Sparse GLB accessors are not supported for _COLOR");
		int viewIndex = accessor.get("bufferView").getAsInt();
		if (viewIndex < 0 || viewIndex >= views.size())
			throw new IOException("Invalid GLB bufferView index");
		var view = views.get(viewIndex).getAsJsonObject();
		int componentType = accessor.get("componentType").getAsInt();
		int componentBytes = componentByteSize(componentType);
		int components = accessorComponentCount(accessor.get("type").getAsString());
		int elementBytes = componentBytes * components;
		int stride = view.has("byteStride") ? view.get("byteStride").getAsInt() : elementBytes;
		int start = (view.has("byteOffset") ? view.get("byteOffset").getAsInt() : 0) +
			(accessor.has("byteOffset") ? accessor.get("byteOffset").getAsInt() : 0);
		int count = accessor.get("count").getAsInt();
		if (stride < elementBytes || start < 0 || count < 0 ||
			(long) start + (long) Math.max(0, count - 1) * stride + elementBytes > binary.limit())
			throw new IOException("GLB accessor exceeds its binary buffer");

		float[][] values = new float[count][components];
		boolean normalized = accessor.has("normalized") && accessor.get("normalized").getAsBoolean();
		for (int item = 0; item < count; item++) {
			for (int component = 0; component < components; component++) {
				int offset = start + item * stride + component * componentBytes;
				values[item][component] = readComponent(binary, offset, componentType, normalized);
			}
		}
		return values;
	}

	private static int componentByteSize(int type) throws IOException {
		switch (type) {
			case 5120:
			case 5121: return 1;
			case 5122:
			case 5123: return 2;
			case 5125:
			case 5126: return 4;
			default: throw new IOException("Unsupported GLB accessor component type " + type);
		}
	}

	private static int accessorComponentCount(String type) throws IOException {
		switch (type) {
			case "SCALAR": return 1;
			case "VEC2": return 2;
			case "VEC3": return 3;
			case "VEC4": return 4;
			default: throw new IOException("Unsupported GLB accessor type " + type);
		}
	}

	private static float readComponent(ByteBuffer data, int offset, int type, boolean normalized) throws IOException {
		switch (type) {
			case 5120: {
				byte value = data.get(offset);
				return normalized ? Math.max(-1, value / 127f) : value;
			}
			case 5121: {
				int value = data.get(offset) & 0xFF;
				return normalized ? value / 255f : value;
			}
			case 5122: {
				short value = data.getShort(offset);
				return normalized ? Math.max(-1, value / 32767f) : value;
			}
			case 5123: {
				int value = data.getShort(offset) & 0xFFFF;
				return normalized ? value / 65535f : value;
			}
			case 5125: {
				long value = data.getInt(offset) & 0xFFFFFFFFL;
				return normalized ? (float) (value / 4294967295.0) : value;
			}
			case 5126: return data.getFloat(offset);
			default: throw new IOException("Unsupported GLB accessor component type " + type);
		}
	}

	private static float clampColor(float value) {
		return Math.max(0, Math.min(1, value));
	}

	private static String materialName(@Nullable JsonElement material) {
		if (material == null || material.isJsonNull())
			return "NONE";
		if (material.isJsonPrimitive() && material.getAsJsonPrimitive().isString())
			return material.getAsString();
		if (material.isJsonObject()) {
			var name = material.getAsJsonObject().get("name");
			if (name != null && name.isJsonPrimitive() && name.getAsJsonPrimitive().isString())
				return name.getAsString();
		}
		throw new IllegalArgumentException("Expected a material name or object with a string name, got: " + material);
	}

	static JsonObject loadCustom(Path path, String baseMaterial, boolean importVertexColors) {
		var scene = aiImportFile(path.toString(), 0);
		if (scene == null)
			throw new IllegalArgumentException("Unable to import " + path + ": " + aiGetErrorString());
		try (var stack = MemoryStack.stackPush()) {
			int cornerCount = 0;
			for (int m = 0; m < scene.mNumMeshes(); m++) {
				var mesh = AIMesh.create(scene.mMeshes().get(m));
				if (mesh.mNumFaces() == 0)
					continue;
				var ids = mesh.mTextureCoords(1);
				if (ids == null || mesh.mNumUVComponents(1) < 2)
					throw new IllegalArgumentException("Missing original corner IDs in UV1; re-export the model: " + path);
				float count = ids.get(mesh.mFaces().get(0).mIndices().get(0)).y();
				// UV1 retains the original size, including omitted hidden faces. IDs must be exact floats.
				if (count <= 0 || count > (1 << 24) || count != (int) count || (int) count % 3 != 0 ||
					cornerCount != 0 && count != cornerCount)
					throw new IllegalArgumentException("Invalid original corner count in " + path);
				cornerCount = (int) count;
			}
			if (cornerCount == 0)
				throw new IllegalArgumentException("No triangles in " + path);
			int faceCount = cornerCount / 3;
			float[] coordinates = new float[Math.multiplyExact(faceCount, 6)];
			int[] vertexColors = importVertexColors ? new int[cornerCount] : null;
			if (vertexColors != null)
				java.util.Arrays.fill(vertexColors, ModelOverride.NO_VERTEX_COLOR);
			boolean[] seen = new boolean[faceCount * 3];
			String[] faceMaterials = new String[faceCount];
			var counts = new LinkedHashMap<String, Integer>();
			var name = AIString.calloc(stack);
			for (int m = 0; m < scene.mNumMeshes(); m++) {
				var mesh = AIMesh.create(scene.mMeshes().get(m));
				var uvs = mesh.mTextureCoords(0);
				var ids = mesh.mTextureCoords(1);
				AIColor4D.Buffer colors = importVertexColors ? findVertexColorChannel(mesh) : null;
				if (uvs == null || ids == null || mesh.mNumUVComponents(0) < 2 || mesh.mNumUVComponents(1) < 2)
					throw new IllegalArgumentException("Missing UV0 or original corner IDs in UV1; re-export the model: " + path);
				if (importVertexColors && colors == null)
					throw new IllegalArgumentException("Missing vertex colors in mesh " + m + " of " + path);
				if (aiGetMaterialString(AIMaterial.create(scene.mMaterials().get(mesh.mMaterialIndex())), AI_MATKEY_NAME, 0, 0, name) != aiReturn_SUCCESS)
					throw new IllegalArgumentException("Missing material name in " + path);
				String material = name.dataString();
				// Blender appends numeric suffixes when a material name already exists.
				if (!MaterialManager.MATERIAL_MAP.containsKey(material))
					material = material.replaceFirst("\\.[0-9]+$", "");
				if (!MaterialManager.MATERIAL_MAP.containsKey(material))
					throw new IllegalArgumentException("Unknown material '" + name.dataString() + "' in " + path);
				float[] scale = (float[]) ModelExporter.materialProperty(MaterialManager.MATERIAL_MAP.get(material), "textureScale");
				for (int f = 0; f < mesh.mNumFaces(); f++) {
					var face = mesh.mFaces().get(f);
					if (face.mNumIndices() != 3)
						throw new IllegalArgumentException("Keep the original triangles when editing " + path);
					int originalFace = -1;
					for (int c = 0; c < 3; c++) {
						int vertex = face.mIndices().get(c);
						var id = ids.get(vertex);
						int corner = (int) id.x();
						if (id.x() != corner || corner < 0 || corner >= seen.length || id.y() != seen.length || seen[corner])
							throw new IllegalArgumentException("Missing, modified or duplicate original corner IDs in " + path);
						if (originalFace != -1 && originalFace != corner / 3)
							throw new IllegalArgumentException("Triangle topology changed in " + path);
						originalFace = corner / 3;
						seen[corner] = true;
						var uv = uvs.get(vertex);
						if (!Float.isFinite(uv.x()) || !Float.isFinite(uv.y()))
							throw new IllegalArgumentException("Non-finite UV in " + path);
						// Undo the image-orientation flip and material scale applied by ModelExporter.
						coordinates[corner * 2] = (.5f - uv.x()) * scale[0] + .5f;
						coordinates[corner * 2 + 1] = (.5f - uv.y()) * scale[1] + .5f;
						if (vertexColors != null) {
							var color = colors.get(vertex);
							float red = color.r();
							float green = color.g();
							float blue = color.b();
							float alpha = color.a();
							if (!Float.isFinite(red) || !Float.isFinite(green) || !Float.isFinite(blue) || !Float.isFinite(alpha))
								throw new IllegalArgumentException("Non-finite vertex color in " + path);
							float[] srgb = {
								Math.max(0, Math.min(1, red)),
								Math.max(0, Math.min(1, green)),
								Math.max(0, Math.min(1, blue))
							};
							int transparency = Math.round((1 - Math.max(0, Math.min(1, alpha))) * 255);
							vertexColors[corner] = transparency << 24 | ColorUtils.srgbToPackedHsl(srgb);
						}
					}
					faceMaterials[originalFace] = material;
					counts.merge(material, 1, Integer::sum);
				}
			}
			int largest = counts.getOrDefault(baseMaterial, 0);
			for (var entry : counts.entrySet()) {
				if (entry.getValue() > largest) {
					baseMaterial = entry.getKey();
					largest = entry.getValue();
				}
			}
			var custom = new JsonObject();
			var uvs = new JsonArray();
			for (float coordinate : coordinates)
				uvs.add(coordinate);
			custom.add("uvs", uvs);
			var materials = new JsonArray();
			for (String material : counts.keySet()) {
				if (material.equals(baseMaterial))
					continue;
				var faces = new JsonArray();
				for (int first = 0; first < faceCount; first++) {
					if (!material.equals(faceMaterials[first]))
						continue;
					int last = first;
					while (last + 1 < faceCount && material.equals(faceMaterials[last + 1]))
						last++;
					String range = first + "-" + last;
					int separateLength = -2;
					for (int f = first; f <= last; f++)
						separateLength += Integer.toString(f).length() + 2;
					if (range.length() + 2 < separateLength) {
						faces.add(range);
					} else {
						for (int f = first; f <= last; f++)
							faces.add(f);
					}
					first = last;
				}
				var override = new JsonObject();
				override.addProperty("material", material);
				override.add("faces", faces);
				materials.add(override);
			}
			if (materials.size() > 0)
				custom.add("materials", materials);
			if (vertexColors != null) {
				var colors = new JsonArray();
				for (int color : vertexColors)
					colors.add(color);
				custom.add("vertexColors", colors);
			}
			var result = new JsonObject();
			result.addProperty("material", baseMaterial);
			result.add("custom", custom);
			return result;
		} finally {
			aiReleaseImport(scene);
		}
	}

	@Nullable
	private static AIColor4D.Buffer findVertexColorChannel(AIMesh mesh) {
		AIColor4D.Buffer firstChannel = null;
		for (int channel = 0; channel < AI_MAX_NUMBER_OF_COLOR_SETS; channel++) {
			AIColor4D.Buffer colors = mesh.mColors(channel);
			if (colors == null)
				continue;
			if (firstChannel == null)
				firstChannel = colors;
			for (int i = 0; i < mesh.mNumVertices(); i++) {
				AIColor4D color = colors.get(i);
				if (color.r() < .9999f || color.g() < .9999f || color.b() < .9999f)
					return colors;
			}
		}
		return firstChannel;
	}

	static JsonArray loadUvs(Path path) {
		if (!Files.isRegularFile(path))
			throw new IllegalArgumentException("UV model does not exist: " + path);
		// Do not triangulate, optimize, or join vertices: face order is the override's contract.
		var scene = aiImportFile(path.toString(), 0);
		if (scene == null)
			throw new IllegalArgumentException("Unable to import " + path + ": " + aiGetErrorString());
		try {
			var result = new JsonArray();
			for (int m = 0; m < scene.mNumMeshes(); m++) {
				var mesh = AIMesh.create(scene.mMeshes().get(m));
				var uvs = mesh.mTextureCoords(0);
				if (uvs == null || mesh.mNumUVComponents(0) < 2)
					throw new IllegalArgumentException("Missing 2D UV channel in mesh " + m + " of " + path);
				var faces = mesh.mFaces();
				for (int f = 0; f < mesh.mNumFaces(); f++) {
					var face = faces.get(f);
					if (face.mNumIndices() != 3)
						throw new IllegalArgumentException("UV model must contain only triangles: " + path);
					for (int c = 0; c < 3; c++) {
						var uv = uvs.get(face.mIndices().get(c));
						if (!Float.isFinite(uv.x()) || !Float.isFinite(uv.y()))
							throw new IllegalArgumentException("Non-finite UV in " + path);
						result.add(uv.x());
						result.add(uv.y());
					}
				}
			}
			if (result.size() == 0)
				throw new IllegalArgumentException("No UV triangles in " + path);
			return result;
		} finally {
			aiReleaseImport(scene);
		}
	}

	public void bakeModel(String modelPath, Runnable onBaked) {
		bake(modelPath, onBaked);
	}

	private void bake(String modelPath, Runnable onBaked) {
		if (closed || source == null)
			return;
		try {
			Path path = source.toPath().toAbsolutePath();
			String original = Files.readString(path);
			var replacements = new ArrayList<String>();
			String updated = replacePaths(original, replacements, modelPath);
			if (replacements.isEmpty()) {
				if (modelPath != null)
					log.warn("No import paths found for model '{}' in {}", modelPath, path);
				return;
			}
			Path temporary = Files.createTempFile(path.getParent(), "model-uvs-", ".json");
			try {
				Files.writeString(temporary, updated, StandardCharsets.UTF_8);
				if (!Files.readString(path).equals(original))
					throw new IOException("Overrides changed during import; refusing to overwrite them");
				Path backup = Files.createTempFile(path.getParent(), "model-overrides-", ".bak");
				Files.writeString(backup, original, StandardCharsets.UTF_8);
				Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				for (var replacement : replacements)
					log.info("{}: {}", path, replacement);
				log.info("Baked {} UV model paths into {}. Backup: {}", replacements.size(), path, backup);
			} finally {
				Files.deleteIfExists(temporary);
			}
			reloadBakedOverrides();
			if (onBaked != null)
				onBaked.run();
		} catch (Exception ex) {
			log.error("Unable to bake model UVs; overrides were not replaced", ex);
		}
	}

	private void reloadBakedOverrides() {
		try {
			ModelOverride[] definitions = source.loadJson(plugin.getGson(), ModelOverride[].class);
			if (definitions == null)
				throw new IOException("Empty or invalid: " + source);
			overrides.loadModelOverrides(definitions, true);
		} catch (Exception ex) {
			log.error("Unable to reload baked model overrides", ex);
		}
	}

	private static class ObjectSpan {
		int start;
		String description = "UNKNOWN";
		String baseMaterial = "NONE";
		int baseStart, baseEnd;
		String customPath;
		int customStart, customEnd, customLine;
		String uvLocation;
	}

	private static class Replacement {
		final int start, end;
		final String text;

		Replacement(int start, int end, String text) {
			this.start = start;
			this.end = end;
			this.text = text;
		}
	}

	private String replacePaths(String original, ArrayList<String> reports, String targetModelPath) {
		// Validate JSON, but edit only the imported values, preserving all unrelated formatting.
		plugin.getGson().fromJson(original, JsonElement.class);
		var objects = new ArrayDeque<ObjectSpan>();
		var edits = new ArrayList<Replacement>();
		String newline = original.contains("\r\n") ? "\r\n" : "\n";
		var tokens = JSON_TOKEN.matcher(original);
		String previousString = null;
		int previousStringStart = 0;
		int propertyStart = 0;
		String property = null;
		int line = 1;
		int scanned = 0;
		while (tokens.find()) {
			while (scanned < tokens.start())
				if (original.charAt(scanned++) == '\n')
					line++;
			String token = tokens.group();
			if (token.startsWith("/"))
				continue;
			if (token.equals("{")) {
				var object = new ObjectSpan();
				object.start = tokens.end();
				objects.push(object);
			} else if (token.equals("}")) {
				var object = objects.pop();
				if (object.uvLocation != null)
					reports.add("Replaced UV path for '" + object.description + "' " + object.uvLocation);
				if (object.customPath != null && matchesModelPath(targetModelPath, object.customPath)) {
					var imported = loadCustom(MODEL_EXPORTS.toPath().resolve(object.customPath).normalize(),
						object.baseMaterial, false);
					int lineStart = original.lastIndexOf('\n', object.customStart - 1) + 1;
					int indentEnd = lineStart;
					while (indentEnd < object.customStart && (original.charAt(indentEnd) == ' ' || original.charAt(indentEnd) == '\t'))
						indentEnd++;
					String indent = original.substring(lineStart, indentEnd);
					var replacement = new StringBuilder();
					for (var entry : imported.getAsJsonObject("custom").entrySet()) {
						if (replacement.length() > 0)
							replacement.append(',').append(newline).append(indent);
						replacement.append(plugin.getGson().toJson(entry.getKey())).append(": ")
							.append(format(entry.getValue(), indent, newline));
					}
					edits.add(new Replacement(object.customStart, object.customEnd, replacement.toString()));
					String base = imported.get("material").getAsString();
					if (!base.equals(object.baseMaterial)) {
						String value = format(imported.get("material"), "", newline);
						if (object.baseEnd > 0) {
							edits.add(new Replacement(object.baseStart, object.baseEnd, value));
						} else {
							edits.add(new Replacement(object.start, object.start, " \"material\": " + value + ","));
						}
					}
					reports.add("Replaced custom model for '" + object.description + "' from '" + object.customPath + "' at line " + object.customLine);
				}
			} else if (token.equals(":")) {
				property = previousString;
				propertyStart = previousStringStart;
				continue;
			} else if (token.startsWith("\"")) {
				String value = plugin.getGson().fromJson(token, String.class);
				if (!objects.isEmpty()) {
					var object = objects.peek();
					if ("description".equals(property)) {
						object.description = value;
					} else if ("material".equals(property) || "baseMaterial".equals(property)) {
						object.baseMaterial = value;
						object.baseStart = tokens.start();
						object.baseEnd = tokens.end();
					} else if ("model".equals(property)) {
						object.customPath = value;
						object.customStart = propertyStart;
						object.customEnd = tokens.end();
						object.customLine = line;
					} else if ("uvs".equals(property)) {
						if (matchesModelPath(targetModelPath, object.customPath)) {
							var uvs = loadUvs(MODEL_EXPORTS.toPath().resolve(value).normalize());
							edits.add(new Replacement(tokens.start(), tokens.end(), format(uvs, "", newline)));
							object.uvLocation = "from '" + value + "' at line " + line;
						}
					}
				}
				previousString = value;
				previousStringStart = tokens.start();
				property = null;
				continue;
			}
			property = previousString = null;
		}
		edits.sort(Comparator.comparingInt(edit -> edit.start));
		var result = new StringBuilder();
		int copied = 0;
		for (var edit : edits) {
			result.append(original, copied, edit.start).append(edit.text);
			copied = edit.end;
		}
		return result.append(original, copied, original.length()).toString();
	}

	private static boolean matchesModelPath(String target, String candidate) {
		if (target == null)
			return true;
		if (candidate == null)
			return false;
		return Path.of(target.replace('\\', '/')).normalize()
			.equals(Path.of(candidate.replace('\\', '/')).normalize());
	}

	private String format(JsonElement json, String indent, String newline) {
		if (json.isJsonNull() || json.isJsonPrimitive())
			return json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber() ?
				new BigDecimal(json.getAsString()).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() :
				plugin.getGson().toJson(json);
		boolean inline = json.isJsonArray();
		if (inline)
			for (var value : json.getAsJsonArray())
				inline &= value.isJsonPrimitive() || value.isJsonNull();
		String childIndent = indent + "  ";
		String separator = inline ? " " : newline + childIndent;
		var result = new StringBuilder(json.isJsonArray() ? "[" : "{");
		boolean first = true;
		if (json.isJsonArray()) {
			for (var value : json.getAsJsonArray()) {
				if (!first)
					result.append(',');
				first = false;
				result.append(separator).append(format(value, childIndent, newline));
			}
		} else {
			for (var entry : json.getAsJsonObject().entrySet()) {
				if (!first)
					result.append(',');
				first = false;
				result.append(separator).append(plugin.getGson().toJson(entry.getKey())).append(": ")
					.append(format(entry.getValue(), childIndent, newline));
			}
		}
		if (!first)
			result.append(inline ? " " : newline + indent);
		return result.append(json.isJsonArray() ? "]" : "}").toString();
	}

}
