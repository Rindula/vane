package org.oddlama.vane.core;

import static org.oddlama.vane.util.Util.ms_to_ticks;
import static org.oddlama.vane.util.Util.read_json_from_url;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Properties;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.logging.Level;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.json.JSONException;
import org.oddlama.vane.annotation.VaneModule;
import org.oddlama.vane.annotation.config.ConfigBoolean;
import org.oddlama.vane.annotation.item.VaneItemv2;
import org.oddlama.vane.annotation.lang.LangMessage;
import org.oddlama.vane.core.config.recipes.RecipeList;
import org.oddlama.vane.core.config.recipes.ShapedRecipeDefinition;
import org.oddlama.vane.core.functional.Consumer1;
import org.oddlama.vane.core.itemv2.CustomItemRegistry;
import org.oddlama.vane.core.itemv2.CustomModelDataRegistry;
import org.oddlama.vane.core.itemv2.ExistingItemConverter;
import org.oddlama.vane.core.itemv2.VanillaFunctionalityInhibitor;
import org.oddlama.vane.core.lang.TranslatedMessage;
import org.oddlama.vane.core.menu.MenuManager;
import org.oddlama.vane.core.misc.AuthMultiplexer;
import org.oddlama.vane.core.misc.CommandHider;
import org.oddlama.vane.core.misc.EntityMoveProcessor;
import org.oddlama.vane.core.misc.HeadLibrary;
import org.oddlama.vane.core.misc.LootChestProtector;
import org.oddlama.vane.core.module.Context;
import org.oddlama.vane.core.module.Module;
import org.oddlama.vane.core.module.ModuleComponent;
import org.oddlama.vane.core.resourcepack.ResourcePackDistributor;
import org.oddlama.vane.core.resourcepack.ResourcePackGenerator;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;

@VaneModule(name = "core", bstats = 8637, config_version = 6, lang_version = 3, storage_version = 1)
public class Core extends Module<Core> {
	/** The base offset for any model data used by vane plugins. */
	// "vane" = 0x76616e65, but the value will be saved as float (json...), so only -2^24 - 2^24 can accurately be represented.
	// therefore we use 0x76616e as the base value.
	public static final int ITEM_DATA_BASE_OFFSET = 0x76616e;
	/** The amount of reserved model data id's per section (usually one section per plugin). */
	public static final int ITEM_DATA_SECTION_SIZE = 0x10000; // 0x10000 = 65k
	/** The amount of reserved model data id's per section (usually one section per plugin). */
	public static final int ITEM_VARIANT_SECTION_SIZE = (1 << 6); // 65k total → 1024 (items) * 64 (variants per item)

	/** Use sparingly. */
	private static Core INSTANCE = null;
	public static Core instance() {
		return INSTANCE;
	}

	private CustomModelDataRegistry model_data_registry;
	private CustomItemRegistry item_registry;
	public ExistingItemConverter existing_item_converter;

	/** Returns the item model data given the section and id */
	public static int model_data(int section, int item_id, int variant_id) {
		return (
			ITEM_DATA_BASE_OFFSET + section * ITEM_DATA_SECTION_SIZE + item_id * ITEM_VARIANT_SECTION_SIZE + variant_id
		);
	}

	@LangMessage
	public TranslatedMessage lang_command_not_a_player;

	@LangMessage
	public TranslatedMessage lang_command_permission_denied;

	@LangMessage
	public TranslatedMessage lang_invalid_time_format;

	// Module registry
	private SortedSet<Module<?>> vane_modules = new TreeSet<>((a, b) -> a.get_name().compareTo(b.get_name()));

	public final ResourcePackDistributor resource_pack_distributor;

	public void register_module(Module<?> module) {
		vane_modules.add(module);
	}

	public void unregister_module(Module<?> module) {
		vane_modules.remove(module);
	}

	public SortedSet<Module<?>> get_modules() {
		return Collections.unmodifiableSortedSet(vane_modules);
	}

	// Vane global command catch-all permission
	public Permission permission_command_catchall = new Permission(
		"vane.*.commands.*",
		"Allow access to all vane commands (ONLY FOR ADMINS!)",
		PermissionDefault.FALSE
	);

	public MenuManager menu_manager;

	// core-config
	@ConfigBoolean(
		def = true,
		desc = "Let the client translate messages using the generated resource pack. This allows every player to select their preferred language, and all plugin messages will also be translated. Disabling this won't allow you to skip generating the resource pack, as it will be needed for custom item textures."
	)
	public boolean config_client_side_translations;

	@ConfigBoolean(def = true, desc = "Send update notices to OPped player when a new version of vane is available.")
	public boolean config_update_notices;

	public String current_version = null;
	public String latest_version = null;

	public Core() {
		if (INSTANCE != null) {
			throw new IllegalStateException("Cannot instanciate Core twice.");
		}
		INSTANCE = this;

		// Create global command catch-all permission
		register_permission(permission_command_catchall);

		// Components
		new HeadLibrary(this);
		new EntityMoveProcessor(this);
		new AuthMultiplexer(this);
		new LootChestProtector(this);
		new VanillaFunctionalityInhibitor(this);
		new org.oddlama.vane.core.commands.Vane(this);
		new org.oddlama.vane.core.commands.CustomItem(this);
		menu_manager = new MenuManager(this);
		resource_pack_distributor = new ResourcePackDistributor(this);
		new CommandHider(this);
		model_data_registry = new CustomModelDataRegistry();
		item_registry = new CustomItemRegistry();
		existing_item_converter = new ExistingItemConverter(this);
		item_registry.register(new ItemTest(this));
	}

	@Override
	public void on_enable() {
		if (config_update_notices) {
			// Now, and every hour after that check if a new version is available.
			// OPs will get a message about this when they join.
			schedule_task_timer(this::check_for_update, 1l, ms_to_ticks(2 * 60l * 60l * 1000l));
		}
	}

	@Override
	public void on_disable() {
	}

	public File generate_resource_pack() {
		try {
			var file = new File("vane-resource-pack.zip");
			var pack = new ResourcePackGenerator();
			pack.set_description("Vane plugin resource pack");
			pack.set_icon_png(getResource("pack.png"));

			for (var m : vane_modules) {
				m.generate_resource_pack(pack);
			}

			pack.write(file);
			return file;
		} catch (Exception e) {
			log.log(Level.SEVERE, "Error while generating resourcepack", e);
			return null;
		}
	}

	public void for_all_module_components(final Consumer1<ModuleComponent<?>> f) {
		for (var m : vane_modules) {
			m.for_each_module_component(f);
		}
	}

	@VaneItemv2(name = "test", base = Material.COMPASS, model_data = 12345, version = 1)
	private class ItemTest extends org.oddlama.vane.core.itemv2.CustomItem<Core> {
		public ItemTest(Context<Core> context) {
			super(context);
		}

		@Override
		public RecipeList default_recipes() {
			return RecipeList.of(new ShapedRecipeDefinition("recipe1")
					.shape(" a ", "b b", " x ")
					.add_ingredient('a', "minecraft:stick")
					.add_ingredient('b', "minecraft:stick{Enchantments:[{id:knockback,lvl:1000}]}")
					.add_ingredient('x', "vane_core:test")
					.result("vane_core:test"));
		}
	}

	public CustomItemRegistry item_registry() {
		return item_registry;
	}

	public CustomModelDataRegistry model_data_registry() {
		return model_data_registry;
	}

	public void check_for_update() {
		if (current_version == null) {
			try {
				Properties properties = new Properties();
				properties.load(Core.class.getResourceAsStream("/vane-core.properties"));
				current_version = "v" + properties.getProperty("version");
			} catch (IOException e) {
				log.severe("Could not load current version from included properties file: " + e.toString());
				return;
			}
		}

		try {
			final var json = read_json_from_url("https://api.github.com/repos/oddlama/vane/releases/latest");
			latest_version = json.getString("tag_name");
			if (latest_version != null && !latest_version.equals(current_version)) {
				log.warning(
					"A newer version of vane is available online! (current=" +
					current_version +
					", new=" +
					latest_version +
					")"
				);
				log.warning("Please update as soon as possible to get the latest features and fixes.");
				log.warning("Get the latest release here: https://github.com/oddlama/vane/releases/latest");
			}
		} catch (IOException | JSONException e) {
			log.warning("Could not check for updates: " + e.toString());
		}
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
	public void on_player_join_send_update_notice(PlayerJoinEvent event) {
		if (!config_update_notices) {
			return;
		}

		// Send update message if new version is available and player is OP.
		if (latest_version != null && !latest_version.equals(current_version) && event.getPlayer().isOp()) {
			// This message is intentionally not translated to ensure it will
			// be displayed correctly and so that everyone understands it.
			event
				.getPlayer()
				.sendMessage(
					Component
						.text("A new version of vane ", NamedTextColor.GREEN)
						.append(Component.text("(" + latest_version + ")", NamedTextColor.AQUA))
						.append(Component.text(" is available!", NamedTextColor.GREEN))
				);
			event
				.getPlayer()
				.sendMessage(
					Component.text("Please update soon to get the latest features.", NamedTextColor.GREEN)
				);
			event
				.getPlayer()
				.sendMessage(
					Component
						.text("Click here to go to the download page", NamedTextColor.AQUA)
						.clickEvent(ClickEvent.openUrl("https://github.com/oddlama/vane/releases/latest"))
				);
		}
	}
}
