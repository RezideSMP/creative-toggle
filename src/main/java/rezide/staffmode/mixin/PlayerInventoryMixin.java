package rezide.staffmode.mixin;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.GameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rezide.staffmode.StaffMode;
import rezide.staffmode.DiscordBotManager;

import java.util.UUID;

@Mixin(PlayerInventory.class)
public class PlayerInventoryMixin {

    @Inject(method = "insertStack(ILnet/minecraft/item/ItemStack;)Z", at = @At("HEAD"))
    private void onInsertStack(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        // Get the PlayerInventory instance
        PlayerInventory inventory = (PlayerInventory) (Object) this;

        // Ensure this is a server-side player inventory
        if (inventory.player instanceof ServerPlayerEntity player) {
            UUID uuid = player.getUuid();
            GameMode mode = player.interactionManager.getGameMode();

            // Check if the player is in creative mode and staff mode
            if (mode == GameMode.CREATIVE && StaffMode.isPlayerInStaffMode(uuid)) {
                String playerName = player.getGameProfile().getName();
                String itemName = stack.getName().getString();
                int itemCount = stack.getCount();

                String message = String.format("🛡️ Player **%s** inserted %d x %s into inventory in staff mode.", playerName, itemCount, itemName);
                DiscordBotManager.sendMessageToChannel(StaffMode.getConfig().getAdminLogChannelId(), message);
            }
        }
    }

    // You would add similar @Injects for other methods like addStack, removeStack, setStack
    // to capture all types of inventory modifications.
    // Be aware that 'addStack' also has an overload without a slot parameter,
    // and 'removeStack' has multiple overloads.
    // Each will require its own @Inject.

    // Example for removeStack(int slot, int amount)
    @Inject(method = "removeStack(II)Lnet/minecraft/item/ItemStack;", at = @At("HEAD"), cancellable = true)
    private void onRemoveStack(int slot, int amount, CallbackInfoReturnable<ItemStack> cir) {
        PlayerInventory inventory = (PlayerInventory) (Object) this;
        if (inventory.player instanceof ServerPlayerEntity player) {
            UUID uuid = player.getUuid();
            GameMode mode = player.interactionManager.getGameMode();

            if (mode == GameMode.CREATIVE && StaffMode.isPlayerInStaffMode(uuid)) {
                ItemStack removedStack = inventory.getStack(slot).copy(); // Get a copy before it's removed
                String playerName = player.getGameProfile().getName();
                String itemName = removedStack.getName().getString();
                int currentItemCount = removedStack.getCount(); // Get current count for logging purposes

                String message = String.format("🛡️ Player **%s** attempting to remove %d x %s from slot %d in staff mode (current count: %d).",
                        playerName, amount, itemName, slot, currentItemCount);
                DiscordBotManager.sendMessageToChannel(StaffMode.getConfig().getAdminLogChannelId(), message);
            }
        }
    }
}