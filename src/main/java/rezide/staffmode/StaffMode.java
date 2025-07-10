package rezide.staffmode;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.GameMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream; // Added import for BufferedInputStream
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class StaffMode implements ModInitializer {
	public static final String MOD_ID = "staff-mode";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final Map<UUID, ItemStack[]> savedSurvivalInventories = new HashMap<>();
	private static final Map<UUID, GameMode> originalGameModes = new HashMap<>();
	private static final Map<UUID, Boolean> wasOriginallyOp = new HashMap<>();

	private static StaffModeConfig config;
	private static File dataFile; // File to save/load data

	@Override
	public void onInitialize() {
		LOGGER.info("Staff Mode initialized!");

		config = StaffModeConfig.getInstance();

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			registerCommands(dispatcher, registryAccess);
		});

		// Command logging: All commands executed by players in creative mode will be logged to the admin channel.
		// This needs to be handled carefully. The current implementation would prevent the actual command from running.
		// It's better to use a mixin for command interception if you want to log *all* commands without re-registering.
		// For now, I'll remove the re-registration of "execute" as it's problematic.
		// If you want command logging, consider a different approach like a server-side event or mixin into CommandManager.
       /*
       CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
          dispatcher.register(literal("execute").then(argument("command", StringArgumentType.greedyString())
                .executes(context -> {
                   ServerCommandSource source = context.getSource();
                   if (source.isExecutedByPlayer() && source.getPlayer().interactionManager.getGameMode() == GameMode.CREATIVE) {
                      String fullCommand = StringArgumentType.getString(context, "command");
                      String playerName = source.getPlayer().getName().getString();
                      String discordMessage = String.format("Player **%s** executed command in staff mode: `/%s`", playerName, fullCommand);
                      DiscordBotManager.sendMessageToChannel(config.getAdminLogChannelId(), discordMessage); // Send to admin channel
                   }
                   // This executes the original command. Be careful if you intend to just log.
                   // You might need to parse and execute it via the dispatcher again, or use a mixin.
                   // For simplicity, removing the problematic re-registration of "execute".
                   return 0;
                })
          ));
       });
       */


		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			server.execute(() -> {
				updatePlayerCount(server);
				// When a player joins, if they were in staff mode and server crashed, revert them.
				// This handles cases where they might have reconnected before data was loaded, or if data was corrupted.
				if (handler.player.interactionManager.getGameMode() == GameMode.CREATIVE && savedSurvivalInventories.containsKey(handler.player.getUuid())) {
					LOGGER.warn("Player {} rejoined in creative mode with saved data. Forcing revert to survival.", handler.player.getName().getString());
					revertPlayerToSurvival(handler.player);
				} else if (handler.player.interactionManager.getGameMode() == GameMode.CREATIVE && !savedSurvivalInventories.containsKey(handler.player.getUuid())) {
					// If a player joins in creative mode but we have no saved data for them (e.g., manual GM change or crash before staff mode was properly saved)
					LOGGER.warn("Player {} joined in creative mode without previous staff mode data. Reverting to survival with empty inventory.", handler.player.getName().getString());
					handler.player.getInventory().clear(); // Clear inventory as we have no saved one
					handler.player.changeGameMode(GameMode.SURVIVAL); // Revert to survival
					handler.player.sendMessage(Text.literal("§cYou were reverted to Survival mode as no staff mode data was found."), false);
				}
			});
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			// Revert player to survival on disconnect, and then save the data.
			// This ensures current state is saved for active staff members.
			revertPlayerToSurvival(handler.player);
			server.execute(() -> {
				updatePlayerCount(server);
			});
			// Data is saved on server stopping and after toggle. No need to save on every disconnect unless explicitly desired
			// to ensure maximum persistence in case of sudden crashes, but it adds overhead.
			// For simplicity and common practice, saving on server stop/start and toggle is usually sufficient.
			// saveData(server);
		});

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			LOGGER.info("Minecraft server started. Starting Discord bot and sending initial player count.");
			// Corrected: Use server.getSavePath() for getting the world directory
			File creativeToggleDataDir = server.getSavePath(WorldSavePath.ROOT).resolve(MOD_ID).toFile();
			if (!creativeToggleDataDir.exists()) {
				creativeToggleDataDir.mkdirs(); // Ensure the directory exists
			}
			dataFile = new File(creativeToggleDataDir, "staff_mode_data.nbt");
			loadData(server); // Load data when server starts

			// Start Discord bot *after* data is loaded.
			DiscordBotManager.startBot(config.getDiscordBotToken(), config.getDiscordBotHttpPort(), server, config);
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			LOGGER.info("Server is stopping. Reverting all creative players to survival and saving data...");
			for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
				revertPlayerToSurvival(player);
			}
			saveData(server); // Save data when server is gracefully stopping
			DiscordBotManager.currentPlayerCount.set(0);
			DiscordBotManager.updateBotPresence();
			DiscordBotManager.stopBot();
		});
	}

	private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
		dispatcher.register(literal("staffmode")
				.requires(source -> source.hasPermissionLevel(1))
				.executes(context -> executeCreativeToggle(context, "No reason provided."))
				.then(argument("reason", StringArgumentType.greedyString())
						.executes(context -> executeCreativeToggle(context, StringArgumentType.getString(context, "reason")))
				)
		);
	}

	private static int executeCreativeToggle(CommandContext<ServerCommandSource> context, String reason) {
		ServerPlayerEntity player;
		try {
			player = context.getSource().getPlayer();
		} catch (Exception e) {
			context.getSource().sendError(Text.literal("§cThis command can only be used by a player."));
			return 0;
		}

		MinecraftServer server = context.getSource().getServer();
		UUID uuid = player.getUuid();
		GameMode currentMode = player.interactionManager.getGameMode();
		String playerName = player.getName().getString();
		GameProfile playerProfile = player.getGameProfile();

		if (currentMode == GameMode.CREATIVE && savedSurvivalInventories.containsKey(uuid)) {
			player.sendMessage(Text.literal("§eExiting staff mode (Switching back to Survival)..."), false);
			LOGGER.info("Player {} exiting staff mode", playerName);

			player.getInventory().clear();
			ItemStack[] savedItems = savedSurvivalInventories.get(uuid);
			for (int i = 0; i < savedItems.length; i++) {
				// In 1.20.5+/1.21.1, ItemStack.isEmpty() implicitly handles null as well.
				if (!savedItems[i].isEmpty()) {
					player.getInventory().setStack(i, savedItems[i]);
				}
			}
			player.getInventory().updateItems();
			player.changeGameMode(originalGameModes.get(uuid));

			savedSurvivalInventories.remove(uuid);
			originalGameModes.remove(uuid);

			if (wasOriginallyOp.containsKey(uuid)) {
				boolean originallyOp = wasOriginallyOp.get(uuid);
				// Only revoke OP if they weren't OP originally AND are currently OP (to avoid issues if server ops changed status)
				if (!originallyOp && server.getPlayerManager().isOperator(playerProfile)) {
					server.getPlayerManager().removeFromOperators(playerProfile);
					player.sendMessage(Text.literal("§aYour operator status has been revoked."), false);
				}
				wasOriginallyOp.remove(uuid);
			}

			player.sendMessage(Text.literal("§aYou are now in Survival mode."), false);

			String discordMessage = String.format("Player **%s** has exited staff mode (switched to Survival). Reason: `%s`", playerName, reason);
			DiscordBotManager.sendMessageToChannel(config.getAdminLogChannelId(), discordMessage); // Send to admin channel
			saveData(server); // Save data after a player exits staff mode

		} else if (currentMode == GameMode.SURVIVAL) {
			player.sendMessage(Text.literal("§eEntering staff mode (Switching to Creative)..."), false);
			LOGGER.info("Player {} entering staff mode", playerName);

			ItemStack[] inventoryCopy = new ItemStack[player.getInventory().size()];
			for (int i = 0; i < inventoryCopy.length; i++) {
				inventoryCopy[i] = player.getInventory().getStack(i).copy();
			}

			savedSurvivalInventories.put(uuid, inventoryCopy);
			originalGameModes.put(uuid, currentMode);

			boolean playerIsOp = server.getPlayerManager().isOperator(playerProfile);
			wasOriginallyOp.put(uuid, playerIsOp);

			// Only grant OP if they aren't already OP
			if (!playerIsOp) {
				server.getPlayerManager().addToOperators(playerProfile);
				player.sendMessage(Text.literal("§aYou have been granted temporary operator status (level 4)."), false);
			} else {
				player.sendMessage(Text.literal("§aYou are already an operator. Entering staff mode."), false);
			}

			player.getInventory().clear();
			player.changeGameMode(GameMode.CREATIVE);

			player.sendMessage(Text.literal("§aYou are now in Creative mode."), false);
			player.sendMessage(Text.literal("§7Use /staffmode again to return to Survival."), false);

			String discordMessage = String.format("Player **%s** has entered staff mode (switched to Creative). Reason: `%s`", playerName, reason);
			DiscordBotManager.sendMessageToChannel(config.getAdminLogChannelId(), discordMessage); // Send to admin channel
			saveData(server); // Save data after a player enters staff mode

		} else {
			player.sendMessage(Text.literal("§cYou must be in Survival or have toggled from it to use this command."), false);
			player.sendMessage(Text.literal("§cYour current mode: " + currentMode.getName()), false);
		}

		return Command.SINGLE_SUCCESS;
	}

	private static void revertPlayerToSurvival(ServerPlayerEntity player) {
		UUID uuid = player.getUuid();
		String playerName = player.getName().getString();
		MinecraftServer server = player.getServer();
		GameProfile playerProfile = player.getGameProfile();

		if (player.interactionManager.getGameMode() == GameMode.CREATIVE && savedSurvivalInventories.containsKey(uuid)) {
			LOGGER.info("Reverting player {} to Survival mode due to disconnect/server stopping.", playerName);

			player.getInventory().clear();
			ItemStack[] savedItems = savedSurvivalInventories.get(uuid);
			// Null check on savedItems array itself in case of corrupted data
			if (savedItems != null) {
				for (int i = 0; i < savedItems.length; i++) {
					// In 1.20.5+/1.21.1, ItemStack.isEmpty() implicitly handles null as well.
					if (!savedItems[i].isEmpty()) {
						player.getInventory().setStack(i, savedItems[i]);
					}
				}
			}
			player.getInventory().updateItems();
			player.changeGameMode(originalGameModes.get(uuid));

			savedSurvivalInventories.remove(uuid);
			originalGameModes.remove(uuid);

			if (wasOriginallyOp.containsKey(uuid)) {
				boolean originallyOp = wasOriginallyOp.get(uuid);
				// Only revoke OP if they weren't OP originally AND are currently OP
				if (!originallyOp && server.getPlayerManager().isOperator(playerProfile)) {
					server.getPlayerManager().removeFromOperators(playerProfile);
					LOGGER.info("Player {}'s operator status revoked.", playerName);
				}
				wasOriginallyOp.remove(uuid);
			}

			String discordMessage = String.format("Player **%s** was reverted to Survival mode due to disconnect or server stopping.", playerName);
			DiscordBotManager.sendMessageToChannel(config.getAdminLogChannelId(), discordMessage); // Send to admin channel
		}
	}

	private static void updatePlayerCount(MinecraftServer server) {
		int playerCount = server.getCurrentPlayerCount();
		StaffMode.LOGGER.info("Current player count: {}", playerCount);
		DiscordBotManager.updatePlayerCountViaHttp(playerCount); // New helper method
	}

	public static boolean isPlayerInStaffMode(UUID uuid) {
		return savedSurvivalInventories.containsKey(uuid) && originalGameModes.containsKey(uuid);
	}

	public static StaffModeConfig getConfig() {
		return config;
	}

	// --- Persistence Methods ---

	private static void saveData(MinecraftServer server) {
		if (dataFile == null) {
			LOGGER.error("Data file not initialized. Cannot save data.");
			return;
		}

		LOGGER.info("Saving Staff Mode data...");
		NbtCompound rootTag = new NbtCompound();

		RegistryWrapper.WrapperLookup lookup = server.getRegistryManager();

		// Save savedSurvivalInventories
		NbtList inventoryListTag = new NbtList();
		for (Map.Entry<UUID, ItemStack[]> entry : savedSurvivalInventories.entrySet()) {
			NbtCompound playerEntryTag = new NbtCompound();
			playerEntryTag.putString("UUID", entry.getKey().toString());

			NbtList itemsTag = new NbtList();
			for (ItemStack stack : entry.getValue()) {
				if (!stack.isEmpty()) {
					NbtCompound itemNbt = new NbtCompound();
					stack.encode(lookup, itemNbt);
					itemsTag.add(itemNbt);
				}
			}
			playerEntryTag.put("Inventory", itemsTag);
			inventoryListTag.add(playerEntryTag);
		}
		rootTag.put("SavedInventories", inventoryListTag);

		// Save originalGameModes
		NbtList gameModeListTag = new NbtList();
		for (Map.Entry<UUID, GameMode> entry : originalGameModes.entrySet()) {
			NbtCompound playerEntryTag = new NbtCompound();
			playerEntryTag.putString("UUID", entry.getKey().toString());
			playerEntryTag.putString("GameMode", entry.getValue().getName());
			gameModeListTag.add(playerEntryTag);
		}
		rootTag.put("OriginalGameModes", gameModeListTag);

		// Save wasOriginallyOp
		NbtList opListTag = new NbtList();
		for (Map.Entry<UUID, Boolean> entry : wasOriginallyOp.entrySet()) {
			NbtCompound playerEntryTag = new NbtCompound();
			playerEntryTag.putString("UUID", entry.getKey().toString());
			playerEntryTag.putBoolean("IsOp", entry.getValue());
			opListTag.add(playerEntryTag);
		}
		rootTag.put("WasOriginallyOp", opListTag);

		try (FileOutputStream fos = new FileOutputStream(dataFile)) {
			NbtIo.writeCompressed(rootTag, fos);
			LOGGER.info("Staff Mode data saved successfully.");
		} catch (IOException e) {
			LOGGER.error("Failed to save Staff Mode data: {}", e.getMessage());
		}
	}

	private static void loadData(MinecraftServer server) {
		if (dataFile == null) {
			LOGGER.error("Data file not initialized. Cannot load data.");
			return;
		}
		if (!dataFile.exists()) {
			LOGGER.info("No Staff Mode data file found. Starting with empty data.");
			return;
		}

		LOGGER.info("Loading Staff Mode data...");
		try (FileInputStream fis = new FileInputStream(dataFile);
			 BufferedInputStream bis = new BufferedInputStream(fis)) { // Wrapped in BufferedInputStream
			NbtCompound rootTag = NbtIo.readCompressed(bis, NbtSizeTracker.ofUnlimitedBytes()); // Read from BufferedInputStream

			RegistryWrapper.WrapperLookup lookup = server.getRegistryManager();

			// Load savedSurvivalInventories
			if (rootTag.contains("SavedInventories")) {
				NbtList inventoryListTag = rootTag.getList("SavedInventories", NbtCompound.COMPOUND_TYPE);
				savedSurvivalInventories.clear();
				for (int i = 0; i < inventoryListTag.size(); i++) {
					NbtCompound playerEntryTag = inventoryListTag.getCompound(i);
					UUID uuid = UUID.fromString(playerEntryTag.getString("UUID"));
					NbtList itemsTag = playerEntryTag.getList("Inventory", NbtCompound.COMPOUND_TYPE);
					ItemStack[] savedItems = new ItemStack[41];
					for (int j = 0; j < itemsTag.size(); j++) {
						if (j < savedItems.length) {
							Optional<ItemStack> itemStackOptional = ItemStack.fromNbt(lookup, itemsTag.getCompound(j));
							savedItems[j] = itemStackOptional.orElse(ItemStack.EMPTY);
						}
					}
					savedSurvivalInventories.put(uuid, savedItems);
				}
			}

			// Load originalGameModes
			if (rootTag.contains("OriginalGameModes")) {
				NbtList gameModeListTag = rootTag.getList("OriginalGameModes", NbtCompound.COMPOUND_TYPE);
				originalGameModes.clear();
				for (int i = 0; i < gameModeListTag.size(); i++) {
					NbtCompound playerEntryTag = gameModeListTag.getCompound(i);
					UUID uuid = UUID.fromString(playerEntryTag.getString("UUID"));
					GameMode gameMode = GameMode.byName(playerEntryTag.getString("GameMode"));
					if (gameMode != null) {
						originalGameModes.put(uuid, gameMode);
					} else {
						LOGGER.warn("Invalid GameMode found for player {}. Skipping.", uuid);
					}
				}
			}

			// Load wasOriginallyOp
			if (rootTag.contains("WasOriginallyOp")) {
				NbtList opListTag = rootTag.getList("WasOriginallyOp", NbtCompound.COMPOUND_TYPE);
				wasOriginallyOp.clear();
				for (int i = 0; i < opListTag.size(); i++) {
					NbtCompound playerEntryTag = opListTag.getCompound(i);
					UUID uuid = UUID.fromString(playerEntryTag.getString("UUID"));
					boolean isOp = playerEntryTag.getBoolean("IsOp");
					wasOriginallyOp.put(uuid, isOp);
				}
			}

			LOGGER.info("Staff Mode data loaded successfully. {} players in staff mode found.", savedSurvivalInventories.size());
		} catch (IOException e) {
			LOGGER.error("Failed to load Staff Mode data: {}", e.getMessage());
			savedSurvivalInventories.clear();
			originalGameModes.clear();
			wasOriginallyOp.clear();
		}
	}
}