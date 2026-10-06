package dev.vellum.mod.client.replaced;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Codec;
import dev.vellum.engine.dom.Element;
import dev.vellum.mod.Constants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * Item stacks and data components from element attributes: {@code <item>}, {@code <model item>} and an
 * {@code <entity>}'s equipment and components. Malformed SNBT and components that don't decode are logged and left
 * out.
 */
final class ItemStacks {
    private ItemStacks() {}

    /** The stack {@code element} names: item {@code idAttr}, with its {@code count} and {@code components}. */
    static ItemStack of(Element element, String idAttr) {
        return parse(element.getAttribute(idAttr), Math.max(1, (int) element.numberAttribute("count", 1)),
                element.getAttribute("components"));
    }

    /**
     * The stack of item {@code id} (empty when the id is blank or unknown). {@code components} is SNBT for its data
     * components, as in {@code /give}, parsed with the world's registries; when it is malformed, the plain item.
     */
    static ItemStack parse(@Nullable String id, int count, @Nullable String components) {
        Identifier item = id == null ? null : Identifier.tryParse(id.strip());
        if (item == null) return ItemStack.EMPTY;
        CompoundTag parsed = components == null ? null : snbt(components, "<item components>");
        if (parsed != null) {
            CompoundTag tag = new CompoundTag();
            tag.putString("id", item.toString());
            tag.putInt("count", count);
            tag.put("components", parsed);
            Optional<ItemStack> stack = decode(ItemStack.CODEC, tag, "<item components>");
            if (stack.isPresent()) return stack.get();
        }
        return BuiltInRegistries.ITEM.getOptional(item).map(i -> new ItemStack(i, count)).orElse(ItemStack.EMPTY);
    }

    /** {@code text} parsed as an SNBT compound, or null (logged) when it is not one. {@code what} names it in the log. */
    static @Nullable CompoundTag snbt(String text, String what) {
        try {
            //? if >=26 {
            return TagParser.parseCompoundFully(text);
            //?} else
            //return TagParser.parseTag(text);
        } catch (CommandSyntaxException e) {
            Constants.LOG.warn("Vellum: {} is not SNBT: {}", what, e.getMessage());
            return null;
        }
    }

    /** {@code tag} decoded with the world's registries, as much of it as decodes (errors are logged); empty without a world. */
    static <T> Optional<T> decode(Codec<T> codec, Tag tag, String what) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return Optional.empty();
        return codec.parse(level.registryAccess().createSerializationContext(NbtOps.INSTANCE), tag)
                .resultOrPartial(error -> Constants.LOG.warn("Vellum: {}: {}", what, error));
    }
}
