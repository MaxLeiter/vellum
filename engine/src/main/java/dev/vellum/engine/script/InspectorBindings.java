package dev.vellum.engine.script;

import dev.vellum.engine.inspect.Inspection;
import dev.vellum.shadow.rhino.ScriptableObject;

/**
 * The {@code inspector} global of an inspector page ({@link dev.vellum.engine.host.Host#inspection}): elements of the
 * inspected page are numbers, and everything else is plain objects and arrays.
 * <ul>
 *   <li>{@code state()}: {@code {stamp, selected, picking, url}}; the stamp changes when the tree may have</li>
 *   <li>{@code tree()}: {@code {id, label, text?, kids}} from {@code <html>} down</li>
 *   <li>{@code details(id)}: attributes, inline style, text, path, box and computed values</li>
 *   <li>{@code select(id)}, {@code hover(id)} ({@code -1} for none), {@code pick(on)}</li>
 *   <li>{@code setAttribute(id, name, value)} (null removes it), {@code setText(id, text)}, {@code remove(id)}</li>
 * </ul>
 */
final class InspectorBindings {
    private InspectorBindings() {}

    static void install(RhinoScriptRuntime rt, Inspection inspection) {
        ScriptableObject inspector = (ScriptableObject) rt.js.newObject();
        rt.global.defineProperty("inspector", inspector, ScriptableObject.DONTENUM | ScriptableObject.READONLY);
        new Members<>(rt, inspector, self -> inspection)
                .method("state", (i, a) -> i.state())
                .method("tree", (i, a) -> i.tree())
                .method("details", (i, a) -> i.details(id(a)))
                .action("select", (i, a) -> i.select(id(a)))
                .action("hover", (i, a) -> i.hover(id(a)))
                .action("pick", (i, a) -> i.pick(a.bool(0)))
                .method("setAttribute", (i, a) -> i.setAttribute(id(a), a.str(1), a.get(2) == null || !a.has(2) ? null : a.str(2)))
                .method("setText", (i, a) -> i.setText(id(a), a.str(1)))
                .method("remove", (i, a) -> i.remove(id(a)));
    }

    private static int id(Args a) {
        return (int) a.num(0, -1);
    }
}
