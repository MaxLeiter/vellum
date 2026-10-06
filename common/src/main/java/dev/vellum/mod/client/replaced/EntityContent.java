package dev.vellum.mod.client.replaced;

import dev.vellum.engine.dom.Element;
import dev.vellum.mod.client.render.McCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
//? if >=26 {
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.nbt.StringTag;
//?} else
//import net.minecraft.world.item.DyeColor;
import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * {@code <entity>}: a live entity render, fitted to the content box ({@link EntityPortrait}): standing on its bottom,
 * or with {@code -mc-entity-focus: eyes} its head and shoulders filling it, either placed by {@code object-position}.
 * <ul>
 *   <li>{@code <entity player>}: the local player;</li>
 *   <li>{@code <entity type="minecraft:pig">}: a client-side entity of that type, created once per world;</li>
 *   <li>{@code <entity id="123">}: an entity in the world by network id.</li>
 * </ul>
 * {@code -mc-yaw}, {@code -mc-pitch} and {@code -mc-model-scale} turn, view and size it; {@code rotatable} lets the
 * pointer turn it; {@code follow-mouse} turns its head toward the pointer (like the inventory's player), as far as
 * {@code -mc-gaze-reach} and {@code -mc-gaze-limit} let it; {@code walk} (optionally a speed, 0.7 by default) swings
 * its limbs. Created entities play their idle animations and take {@code baby}, {@code variant} and {@code color}
 * (the {@code <type>/variant} and {@code <type>/color} components, e.g. {@code variant="minecraft:black"} on a cat,
 * {@code color="pink"} on a sheep), {@code components} (SNBT of any entity components) and equipment by slot
 * ({@code mainhand="minecraft:iron_sword"}, {@code offhand}, {@code head}, {@code chest}, {@code legs}, {@code feet},
 * {@code body}, {@code saddle}). Attributes are read when they change.
 */
final class EntityContent extends TurnableContent {
    /** Attributes that make up a created entity: when one changes, it is created again. */
    private static final Set<String> RECREATE = Set.of("type", "baby", "variant", "color", "components");

    private boolean player, followMouse;
    private float walk;
    /** The world entity's network id, or null for a created entity of {@link #type}. */
    private @Nullable Integer id;
    private @Nullable Identifier type;
    private @Nullable Entity created;
    private @Nullable Level createdIn;

    EntityContent(Element element) {
        super(element);
        load();
    }

    @Override
    public float intrinsicWidth() {
        return 48;
    }

    @Override
    public float intrinsicHeight() {
        return 48;
    }

    @Override
    public void attributeChanged(String name) {
        if (RECREATE.contains(name) || EquipmentSlot.CODEC.byName(name) != null) createdIn = null;
        load();
    }

    @Override
    public void dispose() {
        created = null;
        createdIn = null;
    }

    /** Posed by the element's style and attributes: the one place that decides how it stands. */
    @Override
    protected void draw(McCanvas canvas, float x, float y, float width, float height) {
        int tint = tint();
        if (!canvas.sceneVisible(tint, x, y, width, height)) return;
        Entity entity = entity();
        if (entity == null) return;
        EntityPortrait.Pose pose = new EntityPortrait.Pose(yaw(), pitch(), followMouse, modelScale(), walk);
        EntityPortrait.draw(canvas, entity, pose, element.computedStyle(), tint, x, y, width, height);
    }

    private void load() {
        player = element.hasAttribute("player");
        followMouse = element.hasAttribute("follow-mouse");
        walk = element.hasAttribute("walk") ? element.numberAttribute("walk", 0.7F) : 0;
        float networkId = element.numberAttribute("id", Float.NaN);
        id = Float.isNaN(networkId) ? null : (int) networkId;
        type = Identifier.tryParse(attr("type", "minecraft:pig"));
    }

    private @Nullable Entity entity() {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) return null;
        if (player) return mc.player;
        if (id != null) return level.getEntity(id);
        if (createdIn != level) { // once per world (and after its attributes change), even when the type is unknown
            created = type == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(type).map(t -> create(t, level)).orElse(null);
            createdIn = level;
        }
        return created;
    }

    private @Nullable Entity create(EntityType<?> type, Level level) {
        Entity entity = EntityPortrait.create(type, level);
        if (entity == null) return null;
        if (entity instanceof Mob mob) mob.setBaby(element.hasAttribute("baby"));
        if (entity instanceof LivingEntity living) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                String item = element.getAttribute(slot.getSerializedName());
                if (item != null) living.setItemSlot(slot, ItemStacks.parse(item, 1, null));
            }
        }
        applyComponents(entity);
        return entity;
    }

    /**
     * Sets the entity components from {@code components}, {@code variant} and {@code color}. Entities have data
     * components from Minecraft 1.21.5 on: on 1.21.1, {@code variant} and {@code color} go into the entity's NBT under
     * the names it saves them by ({@code variant}: cats, frogs, wolves, paintings; {@code Type}: foxes, mooshrooms;
     * {@code Color}: sheep, shulkers; {@code CollarColor}: wolves, cats), and {@code components} is ignored.
     */
    private void applyComponents(Entity entity) {
        //? if >=26 {
        String snbt = element.getAttribute("components");
        CompoundTag parsed = snbt == null ? null : ItemStacks.snbt(snbt, "<entity components>");
        CompoundTag components = parsed != null ? parsed : new CompoundTag();
        String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        for (String name : new String[] {"variant", "color"}) {
            String value = element.getAttribute(name);
            if (value != null) components.put(typeId + "/" + name, StringTag.valueOf(value.strip()));
        }
        if (components.isEmpty()) return;
        ItemStacks.decode(DataComponentMap.CODEC, components, "<entity components>").ifPresent(map -> map.forEach(c -> set(entity, c)));
        //?} else {
        /*String variant = element.getAttribute("variant"), color = element.getAttribute("color");
        if (variant == null && color == null) return;
        CompoundTag tag = entity.saveWithoutId(new CompoundTag());
        if (variant != null) {
            Identifier id = Identifier.tryParse(variant.strip());
            tag.putString("variant", variant.strip());
            tag.putString("Type", id == null ? variant.strip() : id.getPath());
        }
        DyeColor dye = color == null ? null : DyeColor.byName(color.strip(), null);
        if (dye != null) {
            tag.putByte("Color", (byte) dye.getId());
            tag.putByte("CollarColor", (byte) dye.getId());
        }
        entity.load(tag);
        *///?}
    }

    //? if >=26 {
    private static <T> void set(Entity entity, TypedDataComponent<T> component) {
        entity.setComponent(component.type(), component.value());
    }
    //?}
}
