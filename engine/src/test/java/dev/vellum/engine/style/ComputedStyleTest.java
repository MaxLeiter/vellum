package dev.vellum.engine.style;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hand-written comparisons and inheritance of {@link ComputedStyle} must cover exactly what {@link Prop}'s flags
 * say, and every field: these tests change one property or field at a time and check each method's answer.
 */
class ComputedStyleTest {
    /** Fields that are not properties, with whether they are inherited. */
    private static final Map<String, Boolean> ENGINE_FIELDS = Map.of("lineHeightFactor", true,
            "isFlexOrGridItemHint", false);

    @Test
    void comparisonsAndInheritanceFollowPropFlags() {
        ComputedStyle initial = new ComputedStyle();
        for (Prop p : Prop.values()) {
            ComputedStyle changed = new ComputedStyle();
            p.set(changed, different(p.get(initial), switch (p) {
                case Z_INDEX -> Integer.class; // null for auto
                case CONTENT -> String.class;
                default -> List.class;
            }));
            assertFalse(Objects.equals(p.get(initial), p.get(changed)), p + " did not change");
            assertFalse(initial.sameAs(changed), p + ": sameAs");
            assertEquals(!p.affectsLayout, initial.sameLayout(changed), p + ": sameLayout");
            assertEquals(!p.inherited, initial.sameInherited(changed), p + ": sameInherited");
            ComputedStyle child = ComputedStyle.inheritFrom(changed);
            assertEquals(p.inherited, Objects.equals(p.get(child), p.get(changed)), p + ": copyInheritedFrom");
        }
    }

    @Test
    void sameAsCoversEveryField() throws IllegalAccessException {
        ComputedStyle initial = new ComputedStyle();
        initial.zIndexAuto = false; // so both z-index fields show through Prop.Z_INDEX
        for (Field f : ComputedStyle.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            ComputedStyle changed = initial.copy();
            f.set(changed, different(f.get(initial), f.getType()));
            assertFalse(initial.sameAs(changed), f.getName() + ": sameAs");
            boolean isProp = false;
            for (Prop p : Prop.values()) isProp |= !Objects.equals(p.get(initial), p.get(changed));
            if (isProp) continue;
            assertTrue(ENGINE_FIELDS.containsKey(f.getName()), f.getName() + " is neither a Prop nor a known field");
            boolean inherited = ENGINE_FIELDS.get(f.getName());
            assertTrue(initial.sameLayout(changed), f.getName() + ": sameLayout");
            assertEquals(!inherited, initial.sameInherited(changed), f.getName() + ": sameInherited");
            assertEquals(inherited, Objects.equals(f.get(ComputedStyle.inheritFrom(changed)), f.get(changed)),
                    f.getName() + ": copyInheritedFrom");
        }
    }

    /** A value of the same type as {@code v} that is not equal to it; {@code nullType} is the type when v is null. */
    private static Object different(Object v, Class<?> nullType) {
        if (v == null) return nullType == Integer.class ? 5 : nullType == String.class ? "x" : List.of();
        return switch (v) {
            case Float f -> Float.isNaN(f) ? 1.5f : f + 1.5f;
            case Integer i -> i + 1;
            case Boolean b -> !b;
            case Enum<?> e -> e.getDeclaringClass().getEnumConstants()[(e.ordinal() + 1) % e.getDeclaringClass().getEnumConstants().length];
            case Length l -> Length.px(l.px + 123);
            case GridLine g -> GridLine.line(g.value() + 3);
            case String s -> s + "x";
            case List<?> l -> l.isEmpty() ? List.of("x") : List.of();
            case Map<?, ?> m -> m.isEmpty() ? Map.of("--x", "1") : Map.of();
            default -> throw new AssertionError("no different value for " + v.getClass());
        };
    }
}
