package rezide.staffmode.mixin;

import com.mojang.brigadier.ParseResults;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.GameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rezide.staffmode.StaffMode;
import rezide.staffmode.DiscordBotManager;

import java.util.UUID;

@Mixin(CommandManager.class)
public class CommandLoggerMixin {

    @Inject(method = "execute", at = @At("HEAD"))
    private void onExecute(ParseResults<ServerCommandSource> parseResults, String command, CallbackInfo ci) {
        // Get the source from the ParseResults
        ServerCommandSource source = parseResults.getContext().getSource(); // Corrected line

        if (source.getEntity() instanceof ServerPlayerEntity player) {
            UUID uuid = player.getUuid();
            GameMode mode = player.interactionManager.getGameMode();

            if (mode == GameMode.CREATIVE && StaffMode.isPlayerInStaffMode(uuid)) {
                String playerName = player.getGameProfile().getName();
                String message = String.format("🛡️ Player **%s** executed command in staff mode: `%s`", playerName, command);
                DiscordBotManager.sendMessageToChannel(StaffMode.getConfig().getAdminLogChannelId(), message);
            }
        }
    }
}