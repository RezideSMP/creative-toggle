package rezide.creativetoggle;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.literal;
import static rezide.creativetoggle.DiscordBotManager.*;

public class CreativeToggle implements ModInitializer {
	public static final String MOD_ID = "creative-toggle";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final Map<UUID, ItemStack[]> savedSurvivalInventories = new HashMap<>();
	private static final Map<UUID, GameMode> originalGameModes = new HashMap<>();

	private static final long ADMIN_LOG_CHANNEL_ID = 1391142811469873303L;

	@Override
	public void onInitialize() {
		LOGGER.info("Creative Toggle initialized!");

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			registerCommands(dispatcher, registryAccess);
		});

		// Register event listener for player join
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			updatePlayerCount(server);
		});

		// Register event listener for player disconnect
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			revertPlayerToSurvival(handler.player);
		});

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			LOGGER.info("Minecraft server started. Starting Discord bot and sending initial player count.");
			DiscordBotManager.startBot(); // Start the bot here!
			updatePlayerCount(server);
		});

		// Register event listener for server stopping
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			LOGGER.info("Server is stopping. Reverting all creative players to survival...");
			for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
				revertPlayerToSurvival(player);
			}
			// Send 0 players when server stops and then shut down the bot
			DiscordBotManager.currentPlayerCount.set(0); // Update internal count
			DiscordBotManager.updateBotPresence(); // Update presence one last time
			DiscordBotManager.stopBot(); // Stop the bot here!
		});
	}

	private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
		dispatcher.register(literal("staffmode")
				.requires(source -> source.hasPermissionLevel(1))
				.executes(CreativeToggle::executeCreativeToggle));
	}

	private static int executeCreativeToggle(CommandContext<ServerCommandSource> context) {
		ServerPlayerEntity player;
		try {
			player = context.getSource().getPlayer();
		} catch (Exception e) {
			context.getSource().sendError(Text.literal("§cThis command can only be used by a player."));
			return 0;
		}

		UUID uuid = player.getUuid();
		GameMode currentMode = player.interactionManager.getGameMode();
		String playerName = player.getName().getString(); // Get the player's name

		if (currentMode == GameMode.CREATIVE && savedSurvivalInventories.containsKey(uuid)) {
			player.sendMessage(Text.literal("§eSwitching back to Survival..."), false);
			LOGGER.info("Player {} switching to Survival", player.getName().getString());
			updatePlayerCount(player.getServer()); // Update Discord bot's player count in Creative mode
			sendMessageToChannel(ADMIN_LOG_CHANNEL_ID,String.format("Player %s has exited staff mode and switched to Survival.", playerName));

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

			player.sendMessage(Text.literal("§aYou are now in Survival mode."), false);
		} else if (currentMode == GameMode.SURVIVAL) {
			player.sendMessage(Text.literal("§eSwitching to Creative..."), false);
			LOGGER.info("Player {} switching to Creative", player.getName().getString());

			ItemStack[] inventoryCopy = new ItemStack[player.getInventory().size()];
			for (int i = 0; i < inventoryCopy.length; i++) {
				inventoryCopy[i] = player.getInventory().getStack(i).copy();
			}

			savedSurvivalInventories.put(uuid, inventoryCopy);
			originalGameModes.put(uuid, currentMode);

			player.getInventory().clear();
			player.changeGameMode(GameMode.CREATIVE);

			player.sendMessage(Text.literal("§aYou are now in Creative mode."), false);
			player.sendMessage(Text.literal("§7Use /ctoggle again to return to Survival."), false);
		} else {
			player.sendMessage(Text.literal("§cYou must be in Survival or have toggled from it to use this command."), false);
			player.sendMessage(Text.literal("§cYour current mode: " + currentMode.getName()), false);
		}

		return Command.SINGLE_SUCCESS;
	}

	private static void revertPlayerToSurvival(ServerPlayerEntity player) {
		UUID uuid = player.getUuid();
		if (player.interactionManager.getGameMode() == GameMode.CREATIVE && savedSurvivalInventories.containsKey(uuid)) {
			LOGGER.info("Reverting player {} to Survival mode due to disconnect/server stopping.", player.getName().getString());

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
		}
	}

	// New method to get and send player count to the internal bot via HTTP
	private static void updatePlayerCount(MinecraftServer server) {
		int playerCount = server.getCurrentPlayerCount();
		CreativeToggle.LOGGER.info("Current player count: {}", playerCount);
		// Simulate sending to the internal HTTP server that DiscordBotManager runs
		// This effectively calls DiscordBotManager.handlePlayerCountUpdate
		new Thread(() -> {
			try {
				java.net.URL url = new java.net.URL("http://localhost:" + DiscordBotManager.HTTP_PORT + "/updatePlayerCount");
				java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
				connection.setRequestMethod("POST");
				connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
				connection.setDoOutput(true);

				String payload = "count=" + playerCount;

				try (java.io.OutputStream os = connection.getOutputStream()) {
					byte[] input = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
					os.write(input, 0, input.length);
				}

				int responseCode = connection.getResponseCode();
				if (responseCode != java.net.HttpURLConnection.HTTP_OK) {
					CreativeToggle.LOGGER.error("Failed to send player count to internal bot HTTP server. Response code: {}", responseCode);
				}
				connection.disconnect();
			} catch (Exception e) {
				CreativeToggle.LOGGER.error("Error sending player count to internal bot HTTP server: {}", e.getMessage());
			}
		}, "PlayerCountSender-Internal").start();
	}
}
