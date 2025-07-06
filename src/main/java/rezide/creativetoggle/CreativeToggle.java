package rezide.creativetoggle;

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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.literal;
import static net.minecraft.server.command.CommandManager.argument;

public class CreativeToggle implements ModInitializer {
	public static final String MOD_ID = "creative-toggle";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final Map<UUID, ItemStack[]> savedSurvivalInventories = new HashMap<>();
	private static final Map<UUID, GameMode> originalGameModes = new HashMap<>();
	private static final Map<UUID, Boolean> wasOriginallyOp = new HashMap<>();

	private static CreativeToggleConfig config;

	@Override
	public void onInitialize() {
		LOGGER.info("Creative Toggle initialized!");

		config = CreativeToggleConfig.getInstance();

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			registerCommands(dispatcher, registryAccess);
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			server.execute(() -> {
				updatePlayerCount(server);
				if (handler.player.interactionManager.getGameMode() == GameMode.CREATIVE && savedSurvivalInventories.containsKey(handler.player.getUuid())) {
					LOGGER.warn("Player {} rejoined in creative mode with saved data. Forcing revert to survival.", handler.player.getName().getString());
					revertPlayerToSurvival(handler.player);
				}
			});
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			revertPlayerToSurvival(handler.player);
			server.execute(() -> {
				updatePlayerCount(server);
			});
		});

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			LOGGER.info("Minecraft server started. Starting Discord bot and sending initial player count.");
			// Pass the server instance to startBot as well
			DiscordBotManager.startBot(config.getDiscordBotToken(), config.getDiscordBotHttpPort(), server);
			// Removed direct updatePlayerCount here, it's now handled within DiscordBotManager.startBot
			// Removed direct Discord message here, it's now handled within DiscordBotManager.startBot
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			LOGGER.info("Server is stopping. Reverting all creative players to survival...");
			for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
				revertPlayerToSurvival(player);
			}
			DiscordBotManager.currentPlayerCount.set(0);
			DiscordBotManager.updateBotPresence();
			// The stopBot method now handles sending the "Server Stopping" message internally
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
				if (!originallyOp) {
					server.getPlayerManager().removeFromOperators(playerProfile);
					player.sendMessage(Text.literal("§aYour operator status has been revoked."), false);
				}
				wasOriginallyOp.remove(uuid);
			}

			player.sendMessage(Text.literal("§aYou are now in Survival mode."), false);

			String discordMessage = String.format("Player **%s** has exited staff mode (switched to Survival). Reason: `%s`", playerName, reason);
			DiscordBotManager.sendMessageToChannel(config.getAdminLogChannelId(), discordMessage);

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
				if (!originallyOp) {
					server.getPlayerManager().removeFromOperators(playerProfile);
					LOGGER.info("Player {}'s operator status revoked.", playerName);
				}
				wasOriginallyOp.remove(uuid);
			}

			String discordMessage = String.format("Player **%s** was reverted to Survival mode due to disconnect or server stopping.", playerName);
			DiscordBotManager.sendMessageToChannel(config.getAdminLogChannelId(), discordMessage);
		}
	}

	private static void updatePlayerCount(MinecraftServer server) {
		int playerCount = server.getCurrentPlayerCount();
		CreativeToggle.LOGGER.info("Current player count: {}", playerCount);
		// Now sends to the DiscordBotManager directly, which will handle the HTTP request
		DiscordBotManager.updatePlayerCountViaHttp(playerCount); // New helper method
	}
}