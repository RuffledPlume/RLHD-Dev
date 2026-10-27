package rs117.hd;

import com.google.inject.Provides;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.swing.*;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import rs117.hd.model.ModelExporter;
import rs117.hd.model.ModelImporter;
import rs117.hd.utils.DevInject;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import java.lang.reflect.Field;

import static rs117.hd.utils.ResourcePath.path;

@Slf4j
@Singleton
@PluginDescriptor(
	name = "117 HD Developer",
	description = "development and profiling tools for 117 HD",
	tags = {"117", "hd", "developer", "development", "profiler"}
)
public class DeveloperPlugin extends Plugin {
	public static final ResourcePath MODEL_EXPORTS = Props.getFile(
		"rlhd.model-exports-path", () -> path("..", "model-exports"));

	@Inject
	private ClientThread clientThread;

	@Inject
	private PluginManager pluginManager;

	@DevInject
	private HdPlugin plugin;

	@DevInject
	private ModelExporter modelExporter;

	@DevInject
	private ModelImporter modelImporter;

	@Provides
	DeveloperConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(DeveloperConfig.class);
	}

	@Override
	protected void startUp() {
		clientThread.invoke(() -> {
			if (!initializeInjection()) {
				SwingUtilities.invokeLater(() -> {
					try {
						pluginManager.setPluginEnabled(this, false);
						pluginManager.stopPlugin(this);
					} catch (Throwable ex) {
						log.error("Error while stopping 117HD:", ex);
					}
				});
				return false;
			}

			modelImporter.startup();
			modelExporter.startup();

			return true;
		});
	}

	@Override
	protected void shutDown() {
		clientThread.invoke(() -> {
			modelImporter.shutdown();
			modelExporter.shutdown();
		});
	}

	private boolean initializeInjection() {
		if (plugin != null)
			return true;

		var optional = pluginManager.getPlugins().stream()
				.filter(p -> p instanceof HdPlugin)
				.findFirst();
		if (optional.isEmpty())
			return false;

		HdPlugin hdPlugin = (HdPlugin) optional.get();
		injector = hdPlugin.getInjector().createChildInjector(binder -> {
			binder.bind(DeveloperPlugin.class).toInstance(this);
			binder.install(this);
		});

		try {
			for (Class<?> c = getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
				for (Field field : c.getDeclaredFields()) {
					if (!field.isAnnotationPresent(DevInject.class))
						continue;
					field.setAccessible(true);
					field.set(this, injector.getInstance(field.getType()));
				}
			}
		} catch (Exception e) {
			log.error("Failed to inject dev fields", e);
			return false;
		}

		return true;
	}
}
