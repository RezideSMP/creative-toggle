package rezide.creativetoggle;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.item.ItemStack;
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

public class CreativeToggle implements ModInitializer {
	public static final String MOD_ID = "creative-toggle";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final Map<UUID, ItemStack[]> savedSurvivalInventories = new HashMap<>();
	private static final Map<UUID, GameMode> originalGameModes = new HashMap<>();

	@Override
	public void onInitialize() {
		LOGGER.info("Creative Toggle initialized!");

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			registerCommands(dispatcher, registryAccess);
		});
	}

	private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
		dispatcher.register(literal("ctoggle")
				.requires(source -> source.hasPermissionLevel(2))
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

		if (currentMode == GameMode.CREATIVE && savedSurvivalInventories.containsKey(uuid)) {
			player.sendMessage(Text.literal("§eSwitching back to Survival..."), false);
			LOGGER.info("Player {} switching to Survival", player.getPlayerListName());

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
			LOGGER.info("Player {} switching to Creative", player.getPlayerListName());

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
}
