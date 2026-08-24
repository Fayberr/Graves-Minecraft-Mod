package net.fayber.graves;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.CustomData;

/**
 * The Grave Key: a marked tripwire hook that lets its holder open any grave,
 * bypassing robbing rules. Given out via {@code /graves key}.
 */
public final class GraveKeyItem {
    private GraveKeyItem() {}

    /** Builds one Grave Key stack with its marker component and styling. */
    public static ItemStack create() {
        ItemStack stack = new ItemStack(Items.TRIPWIRE_HOOK);

        CompoundTag gravesTag = new CompoundTag();
        gravesTag.putBoolean("grave_key", true);
        CompoundTag root = new CompoundTag();
        root.put("graves", gravesTag);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));

        stack.set(DataComponents.ITEM_NAME, Component.literal("Grave Key"));
        stack.set(DataComponents.RARITY, Rarity.EPIC);
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        stack.set(DataComponents.MAX_STACK_SIZE, 16);
        return stack;
    }
}
