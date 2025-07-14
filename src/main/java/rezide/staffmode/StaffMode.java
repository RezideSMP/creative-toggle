package rezide.staffmode;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static net.minecraft.command.argument.GameProfileArgumentType.gameProfile;
import static net.minecraft.command.argument.GameProfileArgumentType.getProfileArgument;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

import net.minecraft.inventory.SimpleInventory;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.NamedScreenHandlerFactory; // Changed import
import net.minecraft.entity.player.PlayerInventory; // Added import
import net.minecraft.entity.player.PlayerEntity; // Added import

public class StaffMode implements ModInitializer {
	public static final String MOD_ID = "staff-mode";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final Map<UUID, ItemStack[]> savedSurvivalInventories = new HashMap<>();
	private static final Map<UUID, GameMode> originalGameModes = new HashMap<>();
	private static final Map<UUID, Boolean> wasOriginallyOp = new HashMap<>();
	private static final Map<UUID, Deque<PlayerInventorySnapshot>> inventoryHistory = new HashMap<>();
	private static final int MAX_INVENTORY_HISTORY = 30;

	// Map to temporarily store the UUID of the player whose history an admin is currently viewing
	private static final Map<UUID, UUID> viewingHistoryOfPlayer = new HashMap<>();

	private static StaffModeConfig config; // Make sure this is declared here or elsewhere accessible
	private static File dataFile;
	private static File inventoryHistoryDir;


	@Override
	public void onInitialize() {
		LOGGER.info("Staff Mode initialized!");

		config = StaffModeConfig.getInstance();

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			registerCommands(dispatcher, registryAccess);
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			server.execute(() -> {
				updatePlayerCount(server);
				if (handler.player.interactionManager.getGameMode() == GameMode.CREATIVE && !isPlayerInStaffMode(handler.player.getUuid())) {
					LOGGER.warn("Player {} joined in creative mode without staff mode data. Forcing revert to survival.", handler.player.getName().getString());
					handler.player.getInventory().clear();
					handler.player.changeGameMode(GameMode.SURVIVAL);
					handler.player.sendMessage(Text.literal("§cYou were reverted to Survival mode as no staff mode data was found."), false);
					if (server.getPlayerManager().isOperator(handler.player.getGameProfile())) {
						server.getPlayerManager().removeFromOperators(handler.player.getGameProfile());
						handler.player.sendMessage(Text.literal("§aYour operator status has been revoked."), false);
					}
				} else if (handler.player.interactionManager.getGameMode() != GameMode.CREATIVE && isPlayerInStaffMode(handler.player.getUuid())) {
					LOGGER.warn("Player {} joined not in creative mode but had staff mode data. Forcing revert to survival with saved inventory.", handler.player.getName().getString());
					revertPlayerToSurvival(handler.player);
				}
			});
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			// Clean up viewing state on disconnect
			viewingHistoryOfPlayer.remove(handler.player.getUuid());
			revertPlayerToSurvival(handler.player);
			server.execute(() -> {
				updatePlayerCount(server);
			});
		});

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			LOGGER.info("Minecraft server started. Starting Discord bot and sending initial player count.");
			File creativeToggleDataDir = server.getSavePath(WorldSavePath.ROOT).resolve(MOD_ID).toFile();
			if (!creativeToggleDataDir.exists()) {
				creativeToggleDataDir.mkdirs();
			}
			dataFile = new File(creativeToggleDataDir, "staff_mode_data.nbt");
			inventoryHistoryDir = new File(creativeToggleDataDir, "inventory_history");
			if (!inventoryHistoryDir.exists()) {
				inventoryHistoryDir.mkdirs();
			}

			loadData(server);
			loadInventoryHistory(server);

			// Ensure DiscordBotManager and config are handled correctly (assuming it's in your project)
			// If DiscordBotManager is not implemented, you'll get further errors.
			DiscordBotManager.startBot(config.getDiscordBotToken(), config.getDiscordBotHttpPort(), server, config);
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			LOGGER.info("Server is stopping. Reverting all creative players to survival and saving data...");
			for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
				revertPlayerToSurvival(player);
			}
			saveData(server);
			saveAllInventoryHistory(server);
			// Ensure DiscordBotManager is handled correctly
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

		// Command to list and open history in a chest GUI
		dispatcher.register(literal("inventoryhistory")
				.requires(source -> source.hasPermissionLevel(2))
				.then(argument("player", gameProfile())
						.executes(context -> listInventoryHistory(context, getProfileArgument(context, "player")))
				)
		);

		// Command to open the chest GUI for a specific snapshot
		dispatcher.register(literal("inventoryhistory")
				.requires(source -> source.hasPermissionLevel(2))
				.then(argument("player", gameProfile())
						.then(argument("index", IntegerArgumentType.integer(0, MAX_INVENTORY_HISTORY - 1))
								.executes(context -> openInventoryHistoryChest(context, getProfileArgument(context, "player"), IntegerArgumentType.getInteger(context, "index")))
						)
				)
		);

		// Command to restore inventory from a viewed snapshot
		dispatcher.register(literal("restoreinventory")
				.requires(source -> source.hasPermissionLevel(2))
				.then(argument("index", IntegerArgumentType.integer(0, MAX_INVENTORY_HISTORY - 1))
						.executes(context -> restoreInventoryHistory(context, IntegerArgumentType.getInteger(context, "index")))
				)
		);
	}

	private static int listInventoryHistory(CommandContext<ServerCommandSource> context, Collection<GameProfile> profiles) {
		ServerPlayerEntity adminPlayer = context.getSource().getPlayer();
		if (adminPlayer == null) {
			context.getSource().sendError(Text.literal("§cThis command can only be used by a player."));
			return 0;
		}

		if (profiles.isEmpty()) {
			adminPlayer.sendMessage(Text.literal("§cNo player found with that name."), false);
			return 0;
		}

		GameProfile targetProfile = profiles.iterator().next();
		UUID targetUuid = targetProfile.getId();
		String targetPlayerName = targetProfile.getName();

		Deque<PlayerInventorySnapshot> history = inventoryHistory.get(targetUuid);

		if (history == null || history.isEmpty()) {
			adminPlayer.sendMessage(Text.literal("§eNo inventory history found for " + targetPlayerName + "."), false);
			return 0;
		}

		adminPlayer.sendMessage(Text.literal("§bInventory History for §a" + targetPlayerName + "§b:"), false);
		int index = 0;
		for (PlayerInventorySnapshot snapshot : history) {
			adminPlayer.sendMessage(Text.literal(String.format("§7[%d] §fReason: §e%s, §fTime: §a%s", index, snapshot.reason, snapshot.timestamp)), false);
			index++;
		}
		adminPlayer.sendMessage(Text.literal(String.format("§7To view a snapshot: §b/inventoryhistory %s <index>§7.", targetPlayerName)), false);
		adminPlayer.sendMessage(Text.literal(String.format("§7To restore a snapshot (after viewing with /inventoryhistory): §b/restoreinventory <index>§7.", targetPlayerName)), false);


		// Store which player's history the admin is currently viewing
		viewingHistoryOfPlayer.put(adminPlayer.getUuid(), targetUuid);

		return Command.SINGLE_SUCCESS;
	}

	private static int openInventoryHistoryChest(CommandContext<ServerCommandSource> context, Collection<GameProfile> profiles, int index) {
		ServerPlayerEntity adminPlayer = context.getSource().getPlayer();
		if (adminPlayer == null) {
			context.getSource().sendError(Text.literal("§cThis command can only be used by a player."));
			return 0;
		}

		if (profiles.isEmpty()) {
			adminPlayer.sendMessage(Text.literal("§cNo player found with that name."), false);
			return 0;
		}

		GameProfile targetProfile = profiles.iterator().next();
		UUID targetUuid = targetProfile.getId();
		String targetPlayerName = targetProfile.getName();

		Deque<PlayerInventorySnapshot> history = inventoryHistory.get(targetUuid);

		if (history == null || history.isEmpty() || index >= history.size() || index < 0) {
			adminPlayer.sendMessage(Text.literal("§cInvalid history index for " + targetPlayerName + ". Use /inventoryhistory " + targetPlayerName + " to see available indices."), false);
			return 0;
		}

		PlayerInventorySnapshot snapshotToView = history.stream().skip(index).findFirst().orElse(null);

		if (snapshotToView == null) {
			adminPlayer.sendMessage(Text.literal("§cError: Could not retrieve snapshot at index " + index + " for " + targetPlayerName + "."), false);
			return 0;
		}

		SimpleInventory tempInventory = new SimpleInventory(27);
		for (int i = 0; i < snapshotToView.inventory.length && i < tempInventory.size(); i++) {
			if (!snapshotToView.inventory[i].isEmpty()) {
				tempInventory.setStack(i, snapshotToView.inventory[i].copy());
			}
		}

		// --- FIX IS HERE ---
		adminPlayer.openHandledScreen(new NamedScreenHandlerFactory() {
			@Override
			public GenericContainerScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
				return new GenericContainerScreenHandler(
						net.minecraft.screen.ScreenHandlerType.GENERIC_9X3,
						syncId,
						playerInventory,
						tempInventory,
						3
				);
			}

			@Override
			public Text getDisplayName() {
				return Text.literal("History of " + targetPlayerName + " [" + index + "] " + snapshotToView.timestamp);
			}
		});
		// --- END FIX ---


		adminPlayer.sendMessage(Text.literal(String.format("§aOpened inventory snapshot for §e%s§a at index §b%d§a. (Reason: %s, Time: %s)",
				targetPlayerName, index, snapshotToView.reason, snapshotToView.timestamp)), false);

		viewingHistoryOfPlayer.put(adminPlayer.getUuid(), targetUuid);

		return Command.SINGLE_SUCCESS;
	}


	private static int restoreInventoryHistory(CommandContext<ServerCommandSource> context, int index) {
		ServerPlayerEntity adminPlayer = context.getSource().getPlayer();
		if (adminPlayer == null) {
			context.getSource().sendError(Text.literal("§cThis command can only be used by a player."));
			return 0;
		}

		UUID targetPlayerUuid = viewingHistoryOfPlayer.get(adminPlayer.getUuid());
		if (targetPlayerUuid == null) {
			adminPlayer.sendMessage(Text.literal("§cYou are not currently viewing any player's inventory history. Use /inventoryhistory <player> first."), false);
			return 0;
		}

		ServerPlayerEntity targetPlayer = context.getSource().getServer().getPlayerManager().getPlayer(targetPlayerUuid);
		if (targetPlayer == null) {
			adminPlayer.sendMessage(Text.literal("§cThe target player is not online to restore their inventory. Only online players can have their inventory restored."), false);
			viewingHistoryOfPlayer.remove(adminPlayer.getUuid());
			return 0;
		}

		Deque<PlayerInventorySnapshot> history = inventoryHistory.get(targetPlayerUuid);

		if (history == null || history.isEmpty() || index >= history.size() || index < 0) {
			adminPlayer.sendMessage(Text.literal("§cInvalid history index. Please check /inventoryhistory " + targetPlayer.getName().getString() + " again."), false);
			return 0;
		}

		PlayerInventorySnapshot snapshotToRestore = history.stream().skip(index).findFirst().orElse(null);

		if (snapshotToRestore == null) {
			adminPlayer.sendMessage(Text.literal("§cError: Could not retrieve snapshot at index " + index + " for " + targetPlayer.getName().getString() + "."), false);
			return 0;
		}

		addInventorySnapshot(targetPlayer, "pre_restore_inventory_by_" + adminPlayer.getName().getString() + "_from_snapshot_" + snapshotToRestore.timestamp + "_" + snapshotToRestore.reason);
		targetPlayer.getInventory().clear();
		for (int i = 0; i < snapshotToRestore.inventory.length; i++) {
			if (!snapshotToRestore.inventory[i].isEmpty()) {
				targetPlayer.getInventory().setStack(i, snapshotToRestore.inventory[i]);
			}
		}
		targetPlayer.getInventory().updateItems();
		targetPlayer.sendMessage(Text.literal("§aYour inventory has been restored by " + adminPlayer.getName().getString() + " to a previous state."), false);
		adminPlayer.sendMessage(Text.literal("§aSuccessfully restored §e" + targetPlayer.getName().getString() + "§a's inventory to state at §b" + snapshotToRestore.timestamp + " §a(Reason: " + snapshotToRestore.reason + ")."), false);

		String discordMessage = String.format("Admin **%s** restored Player **%s**'s inventory to snapshot from `%s` (Reason: `%s`).",
				adminPlayer.getName().getString(), targetPlayer.getName().getString(), snapshotToRestore.timestamp, snapshotToRestore.reason);
		DiscordBotManager.sendMessageToChannel(config.getAdminLogChannelId(), discordMessage);

		return Command.SINGLE_SUCCESS;
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

		if (isPlayerInStaffMode(uuid) && currentMode != GameMode.CREATIVE) {
			player.sendMessage(Text.literal("§cDetected manual game mode change while in staff mode. Reverting to creative..."), false);
			player.changeGameMode(GameMode.CREATIVE);
			String discordMessage = String.format("Player **%s** attempted manual game mode change while in staff mode. Forced back to Creative.", playerName);
			DiscordBotManager.sendMessageToChannel(config.getAdminLogChannelId(), discordMessage);
			return Command.SINGLE_SUCCESS;
		} else if (!isPlayerInStaffMode(uuid) && currentMode == GameMode.CREATIVE) {
			player.sendMessage(Text.literal("§cDetected creative mode without staff mode enabled. Reverting to survival and clearing inventory as safeguard..."), false);
			player.getInventory().clear();
			player.changeGameMode(GameMode.SURVIVAL);
			if (server.getPlayerManager().isOperator(playerProfile)) {
				server.getPlayerManager().removeFromOperators(playerProfile);
				player.sendMessage(Text.literal("§aYour operator status has been revoked."), false);
			}
			String discordMessage = String.format("Player **%s** was found in creative mode without staff mode enabled. Forced back to Survival and cleared inventory.", playerName);
			DiscordBotManager.sendMessageToChannel(config.getAdminLogChannelId(), discordMessage);
			return Command.SINGLE_SUCCESS;
		}

		if (currentMode == GameMode.CREATIVE && savedSurvivalInventories.containsKey(uuid)) {
			player.sendMessage(Text.literal("§eExiting staff mode (Switching back to Survival)..."), false);
			LOGGER.info("Player {} exiting staff mode", playerName);

			addInventorySnapshot(player, "exit_staff_mode_creative");

			player.getInventory().clear();
			ItemStack[] savedItems = savedSurvivalInventories.get(uuid);
			for (int i = 0; i < savedItems.length; i++) {
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
				if (!originallyOp && server.getPlayerManager().isOperator(playerProfile)) {
					server.getPlayerManager().removeFromOperators(playerProfile);
					player.sendMessage(Text.literal("§aYour operator status has been revoked."), false);
				}
				wasOriginallyOp.remove(uuid);
			}

			player.sendMessage(Text.literal("§aYou are now in Survival mode."), false);

			String discordMessage = String.format("Player **%s** has exited staff mode (switched to Survival). Reason: `%s`", playerName, reason);
			DiscordBotManager.sendMessageToChannel(config.getAdminLogChannelId(), discordMessage);
			saveData(server);

		} else if (currentMode == GameMode.SURVIVAL) {
			player.sendMessage(Text.literal("§eEntering staff mode (Switching to Creative)..."), false);
			LOGGER.info("Player {} entering staff mode", playerName);

			addInventorySnapshot(player, "pre_staff_mode_survival");

			ItemStack[] inventoryCopy = new ItemStack[player.getInventory().size()];
			for (int i = 0; i < inventoryCopy.length; i++) {
				inventoryCopy[i] = player.getInventory().getStack(i).copy();
			}

			savedSurvivalInventories.put(uuid, inventoryCopy);
			originalGameModes.put(uuid, currentMode);

			boolean playerIsOp = server.getPlayerManager().isOperator(playerProfile);
			wasOriginallyOp.put(uuid, playerIsOp);

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
			DiscordBotManager.sendMessageToChannel(config.getAdminLogChannelId(), discordMessage);
			saveData(server);

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

		if (isPlayerInStaffMode(uuid) && player.interactionManager.getGameMode() == GameMode.CREATIVE) {
			LOGGER.info("Reverting player {} to Survival mode due to disconnect/server stopping from STAFF MODE.", playerName);

			addInventorySnapshot(player, "revert_staff_mode_disconnect");

			player.getInventory().clear();
			ItemStack[] savedItems = savedSurvivalInventories.get(uuid);
			if (savedItems != null) {
				for (int i = 0; i < savedItems.length; i++) {
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
				if (!originallyOp && server.getPlayerManager().isOperator(playerProfile)) {
					server.getPlayerManager().removeFromOperators(playerProfile);
					LOGGER.info("Player {}'s operator status revoked.", playerName);
				}
				wasOriginallyOp.remove(uuid);
			}

			String discordMessage = String.format("Player **%s** was reverted to Survival mode due to disconnect or server stopping.", playerName);
			DiscordBotManager.sendMessageToChannel(config.getAdminLogChannelId(), discordMessage);
			saveData(server);
		} else if (isPlayerInStaffMode(uuid) && player.interactionManager.getGameMode() != GameMode.CREATIVE) {
			LOGGER.warn("Player {} had staff mode data but was not in creative mode on disconnect. Forcing revert with saved inventory.", playerName);

			player.getInventory().clear();
			ItemStack[] savedItems = savedSurvivalInventories.get(uuid);
			if (savedItems != null) {
				for (int i = 0; i < savedItems.length; i++) {
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
				if (!originallyOp && server.getPlayerManager().isOperator(playerProfile)) {
					server.getPlayerManager().removeFromOperators(playerProfile);
					LOGGER.info("Player {}'s operator status revoked.", playerName);
				}
				wasOriginallyOp.remove(uuid);
			}
			String discordMessage = String.format("Player **%s** (manual GM change) was reverted to Survival mode due to disconnect or server stopping.", playerName);
			DiscordBotManager.sendMessageToChannel(config.getAdminLogChannelId(), discordMessage);
			saveData(server);
		}
	}

	private static void updatePlayerCount(MinecraftServer server) {
		int playerCount = server.getCurrentPlayerCount();
		StaffMode.LOGGER.info("Current player count: {}", playerCount);
		DiscordBotManager.updatePlayerCountViaHttp(playerCount);
	}

	public static boolean isPlayerInStaffMode(UUID uuid) {
		return savedSurvivalInventories.containsKey(uuid) && originalGameModes.containsKey(uuid);
	}

	public static StaffModeConfig getConfig() {
		return config;
	}

	private static class PlayerInventorySnapshot {
		public final ItemStack[] inventory;
		public final String timestamp;
		public final String reason;

		public PlayerInventorySnapshot(ItemStack[] inventory, String reason) {
			this.inventory = inventory;
			this.timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
			this.reason = reason;
		}

		public NbtCompound toNbt(RegistryWrapper.WrapperLookup lookup) {
			NbtCompound tag = new NbtCompound();
			tag.putString("Timestamp", timestamp);
			tag.putString("Reason", reason);
			NbtList itemsTag = new NbtList();
			for (ItemStack stack : inventory) {
				if (!stack.isEmpty()) {
					NbtCompound itemNbt = new NbtCompound();
					stack.encode(lookup, itemNbt);
					itemsTag.add(itemNbt);
				}
			}
			tag.put("Inventory", itemsTag);
			return tag;
		}

		public static PlayerInventorySnapshot fromNbt(RegistryWrapper.WrapperLookup lookup, NbtCompound tag) {
			String timestamp = tag.getString("Timestamp");
			String reason = tag.getString("Reason");
			NbtList itemsTag = tag.getList("Inventory", NbtCompound.COMPOUND_TYPE);
			ItemStack[] loadedInventory = new ItemStack[41];
			for (int i = 0; i < itemsTag.size(); i++) {
				if (i < loadedInventory.length) {
					Optional<ItemStack> itemStackOptional = ItemStack.fromNbt(lookup, itemsTag.getCompound(i));
					loadedInventory[i] = itemStackOptional.orElse(ItemStack.EMPTY);
				}
			}
			PlayerInventorySnapshot snapshot = new PlayerInventorySnapshot(loadedInventory, reason);
			try {
				java.lang.reflect.Field timestampField = snapshot.getClass().getDeclaredField("timestamp");
				timestampField.setAccessible(true);
				timestampField.set(snapshot, timestamp);
			} catch (NoSuchFieldException | IllegalAccessException e) {
				LOGGER.error("Failed to set timestamp for loaded inventory snapshot: {}", e.getMessage());
			}
			return snapshot;
		}
	}

	private static void addInventorySnapshot(ServerPlayerEntity player, String reason) {
		UUID uuid = player.getUuid();
		Deque<PlayerInventorySnapshot> history = inventoryHistory.computeIfAbsent(uuid, k -> new ArrayDeque<>());

		ItemStack[] currentInventory = new ItemStack[player.getInventory().size()];
		for (int i = 0; i < currentInventory.length; i++) {
			currentInventory[i] = player.getInventory().getStack(i).copy();
		}

		history.addFirst(new PlayerInventorySnapshot(currentInventory, reason));

		while (history.size() > MAX_INVENTORY_HISTORY) {
			history.removeLast();
		}
		LOGGER.info("Added inventory snapshot for {}. Reason: {}", player.getName().getString(), reason);
		savePlayerInventoryHistory(player.getServer(), player.getUuid());
	}

	private static void saveData(MinecraftServer server) {
		if (dataFile == null) {
			LOGGER.error("Data file not initialized. Cannot save data.");
			return;
		}

		LOGGER.info("Saving Staff Mode data...");
		NbtCompound rootTag = new NbtCompound();

		RegistryWrapper.WrapperLookup lookup = server.getRegistryManager();

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

		NbtList gameModeListTag = new NbtList();
		for (Map.Entry<UUID, GameMode> entry : originalGameModes.entrySet()) {
			NbtCompound playerEntryTag = new NbtCompound();
			playerEntryTag.putString("UUID", entry.getKey().toString());
			playerEntryTag.putString("GameMode", entry.getValue().getName());
			gameModeListTag.add(playerEntryTag);
		}
		rootTag.put("OriginalGameModes", gameModeListTag);

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
			 BufferedInputStream bis = new BufferedInputStream(fis)) {
			NbtCompound rootTag = NbtIo.readCompressed(bis, NbtSizeTracker.ofUnlimitedBytes());

			RegistryWrapper.WrapperLookup lookup = server.getRegistryManager();

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

	private static File getHistoryFileForPlayer(UUID playerUuid) {
		return new File(inventoryHistoryDir, playerUuid.toString() + ".nbt");
	}

	private static void savePlayerInventoryHistory(MinecraftServer server, UUID playerUuid) {
		File playerHistoryFile = getHistoryFileForPlayer(playerUuid);
		Deque<PlayerInventorySnapshot> history = inventoryHistory.get(playerUuid);

		if (history == null || history.isEmpty()) {
			if (playerHistoryFile.exists()) {
				playerHistoryFile.delete();
			}
			return;
		}

		NbtCompound rootTag = new NbtCompound();
		NbtList historyListTag = new NbtList();
		RegistryWrapper.WrapperLookup lookup = server.getRegistryManager();

		for (PlayerInventorySnapshot snapshot : history) {
			historyListTag.add(snapshot.toNbt(lookup));
		}
		rootTag.put("History", historyListTag);

		try (FileOutputStream fos = new FileOutputStream(playerHistoryFile)) {
			NbtIo.writeCompressed(rootTag, fos);
		} catch (IOException e) {
			LOGGER.error("Failed to save inventory history for {}: {}", playerUuid, e.getMessage());
		}
	}

	private static void saveAllInventoryHistory(MinecraftServer server) {
		LOGGER.info("Saving all player inventory histories...");
		for (UUID uuid : inventoryHistory.keySet()) {
			savePlayerInventoryHistory(server, uuid);
		}
		LOGGER.info("All player inventory histories saved.");
	}

	private static void loadInventoryHistory(MinecraftServer server) {
		LOGGER.info("Loading all player inventory histories...");
		if (inventoryHistoryDir == null || !inventoryHistoryDir.exists()) {
			LOGGER.warn("Inventory history directory not found or not initialized.");
			return;
		}

		File[] historyFiles = inventoryHistoryDir.listFiles((dir, name) -> name.endsWith(".nbt"));
		if (historyFiles == null) {
			LOGGER.warn("No inventory history files found.");
			return;
		}

		RegistryWrapper.WrapperLookup lookup = server.getRegistryManager();

		for (File file : historyFiles) {
			try (FileInputStream fis = new FileInputStream(file);
				 BufferedInputStream bis = new BufferedInputStream(fis)) {
				NbtCompound rootTag = NbtIo.readCompressed(bis, NbtSizeTracker.ofUnlimitedBytes());
				NbtList historyListTag = rootTag.getList("History", NbtCompound.COMPOUND_TYPE);

				UUID playerUuid = UUID.fromString(file.getName().replace(".nbt", ""));
				Deque<PlayerInventorySnapshot> history = new ArrayDeque<>();

				for (int i = 0; i < historyListTag.size(); i++) {
					NbtCompound snapshotTag = historyListTag.getCompound(i);
					history.add(PlayerInventorySnapshot.fromNbt(lookup, snapshotTag));
				}
				inventoryHistory.put(playerUuid, history);
				LOGGER.debug("Loaded history for {}: {} snapshots.", playerUuid, history.size());
			} catch (IOException | IllegalArgumentException e) {
				LOGGER.error("Failed to load inventory history from file {}: {}", file.getName(), e.getMessage());
			}
		}
		LOGGER.info("Finished loading all player inventory histories. Loaded histories for {} players.", inventoryHistory.size());
	}
}