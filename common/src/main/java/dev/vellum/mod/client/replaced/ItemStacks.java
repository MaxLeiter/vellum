package dev.vellum.mod.client.replaced;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** Item stacks from element attributes: {@code <item>}, {@code <model item>} and an {@code <entity>}'s equipment. */
final class ItemStacks {
    private ItemStacks() {}

    /**
     * The stack of item {@code id} (empty when the id is blank or unknown). {@code components} is SNBT for its data
     * components, as in {@code /give}, parsed with the world's registries; malformed SNBT falls back to the plain item.
     */
    static ItemStack parse(@Nullable String id, int count, @Nullable String components) {
        Identifier item = id == null ? null : Identifier.tryParse(id.strip());
        if (item == null) return ItemStack.EMPTY;
        var level = Minecraft.getInstance().level;
        if (components != null && level != null) {
            try {
                CompoundTag tag = new CompoundTag();
                tag.putString("id", item.toString());
                tag.putInt("count", count);
                tag.put("components", TagParser.parseCompoundFully(components));
                var parsed = ItemStack.CODEC.parse(level.registryAccess().createSerializationContext(NbtOps.INSTANCE), tag).result();
                if (parsed.isPresent()) return parsed.get();
            } catch (CommandSyntaxException ignored) {
                // Malformed SNBT: the plain item.
            }
        }
        return BuiltInRegistries.ITEM.getOptional(item).map(i -> new ItemStack(i, count)).orElse(ItemStack.EMPTY);
    }
}
