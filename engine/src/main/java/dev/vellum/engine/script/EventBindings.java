package dev.vellum.engine.script;

import dev.vellum.engine.dom.Node;
import dev.vellum.engine.event.CustomEvent;
import dev.vellum.engine.event.Event;
import dev.vellum.engine.event.EventListener;
import dev.vellum.engine.event.FocusEvent;
import dev.vellum.engine.event.InputEvent;
import dev.vellum.engine.event.KeyboardEvent;
import dev.vellum.engine.event.Modifiers;
import dev.vellum.engine.event.MouseEvent;
import dev.vellum.engine.event.TransitionEvent;
import dev.vellum.engine.event.WheelEvent;
import dev.vellum.shadow.rhino.Callable;
import dev.vellum.shadow.rhino.Scriptable;
import dev.vellum.shadow.rhino.ScriptableObject;

import java.util.function.Function;

/**
 * Events for scripts: one prototype per event class, the {@code Event} and {@code CustomEvent} constructors, and the
 * adapters that turn script listeners into DOM {@link EventListener}s.
 */
final class EventBindings {
    /** Key under which a script function remembers its DOM listener, so removal by function identity works. */
    private static final String LISTENER = "vellum.listener";

    private final RhinoScriptRuntime rt;
    private final HostClass<Event> event;
    private final HostClass<CustomEvent> customEvent;
    private final HostClass<MouseEvent> mouseEvent;
    private final HostClass<WheelEvent> wheelEvent;
    private final HostClass<KeyboardEvent> keyboardEvent;
    private final HostClass<FocusEvent> focusEvent;
    private final HostClass<InputEvent> inputEvent;
    private final HostClass<TransitionEvent> transitionEvent;

    EventBindings(RhinoScriptRuntime rt) {
        this.rt = rt;
        event = new HostClass<>(rt, "Event", Event.class, null,
                a -> new Event(a.str(0), init(a, "bubbles"), init(a, "cancelable"))).expose("Event");
        event.members()
                .get("type", e -> e.type)
                .get("target", Event::target)
                .get("currentTarget", Event::currentTarget)
                .get("eventPhase", e -> e.phase().ordinal()) // NONE, CAPTURING, AT_TARGET, BUBBLING = 0..3
                .get("bubbles", e -> e.bubbles)
                .get("cancelable", e -> e.cancelable)
                .get("defaultPrevented", Event::defaultPrevented)
                .get("timeStamp", e -> e.timeStamp)
                .action("preventDefault", (e, a) -> e.preventDefault())
                .action("stopPropagation", (e, a) -> e.stopPropagation())
                .action("stopImmediatePropagation", (e, a) -> e.stopImmediatePropagation());

        customEvent = new HostClass<>(rt, "CustomEvent", CustomEvent.class, event, a -> new CustomEvent(a.str(0),
                init(a, "bubbles"), init(a, "cancelable"), a.get(1) instanceof Scriptable o ? Js.property(o, "detail") : null))
                .expose("CustomEvent");
        customEvent.members().get("detail", e -> Js.isNullish(e.detail) ? null : e.detail);

        mouseEvent = new HostClass<>(rt, "MouseEvent", MouseEvent.class, event, null).expose("MouseEvent");
        modifiers(mouseEvent.members(), e -> e.modifiers)
                .get("clientX", e -> e.clientX)
                .get("clientY", e -> e.clientY)
                .get("pageX", e -> e.clientX)
                .get("pageY", e -> e.clientY)
                .get("offsetX", e -> e.offsetX)
                .get("offsetY", e -> e.offsetY)
                .get("button", e -> e.button)
                .get("buttons", e -> e.buttons)
                .get("detail", e -> e.detail)
                .get("relatedTarget", e -> e.relatedTarget);

        wheelEvent = new HostClass<>(rt, "WheelEvent", WheelEvent.class, mouseEvent, null).expose("WheelEvent");
        wheelEvent.members()
                .get("deltaX", e -> e.deltaX)
                .get("deltaY", e -> e.deltaY)
                .get("deltaZ", e -> 0)
                .get("deltaMode", e -> 0); // DOM_DELTA_PIXEL

        keyboardEvent = new HostClass<>(rt, "KeyboardEvent", KeyboardEvent.class, event, null).expose("KeyboardEvent");
        modifiers(keyboardEvent.members(), e -> e.modifiers)
                .get("key", e -> e.key)
                .get("code", e -> e.code)
                .get("keyCode", e -> e.keyCode)
                .get("repeat", e -> e.repeat);

        focusEvent = new HostClass<>(rt, "FocusEvent", FocusEvent.class, event, null).expose("FocusEvent");
        focusEvent.members().get("relatedTarget", e -> e.relatedTarget);

        inputEvent = new HostClass<>(rt, "InputEvent", InputEvent.class, event, null).expose("InputEvent");
        inputEvent.members()
                .get("data", e -> e.data)
                .get("inputType", e -> e.inputType);

        transitionEvent = new HostClass<>(rt, "TransitionEvent", TransitionEvent.class, event, null)
                .expose("TransitionEvent", "AnimationEvent");
        transitionEvent.members()
                .get("propertyName", e -> e.name)
                .get("animationName", e -> e.name)
                .get("pseudoElement", e -> e.pseudoElement)
                .get("elapsedTime", e -> e.elapsedSeconds);
    }

    private static <T> Members<T> modifiers(Members<T> members, Function<T, Modifiers> modifiers) {
        return members
                .get("shiftKey", e -> modifiers.apply(e).shift())
                .get("ctrlKey", e -> modifiers.apply(e).ctrl())
                .get("altKey", e -> modifiers.apply(e).alt())
                .get("metaKey", e -> modifiers.apply(e).meta());
    }

    /** A flag of the {@code init} dictionary of {@code new Event(type, init)}. */
    private static boolean init(Args a, String name) {
        return option(a.get(1), name);
    }

    private static boolean option(Object options, String name) {
        return options instanceof Scriptable o && Js.bool(Js.property(o, name));
    }

    /** The event's wrapper, created once per event so every listener of a dispatch sees the same object. */
    HostObject wrap(Event e) {
        if (e.scriptWrapper instanceof HostObject w) return w;
        HostObject w = switch (e) {
            case WheelEvent x -> wheelEvent.wrap(x);
            case MouseEvent x -> mouseEvent.wrap(x);
            case KeyboardEvent x -> keyboardEvent.wrap(x);
            case FocusEvent x -> focusEvent.wrap(x);
            case InputEvent x -> inputEvent.wrap(x);
            case TransitionEvent x -> transitionEvent.wrap(x);
            case CustomEvent x -> customEvent.wrap(x);
            default -> event.wrap(e);
        };
        e.scriptWrapper = w;
        return w;
    }

    // ---- Listeners ----

    /** {@code addEventListener(type, listener, options)}; options is a capture flag or {@code {capture, once}}. */
    void addListener(Node target, Args a) {
        if (a.isNullish(1)) return;
        Object options = a.get(2);
        target.addEventListener(a.str(0), listener(a.get(1)), capture(options), option(options, "once"));
    }

    void removeListener(Node target, Args a) {
        if (!a.isNullish(1)) target.removeEventListener(a.str(0), listener(a.get(1)), capture(a.get(2)));
    }

    boolean dispatch(Node target, Args a) {
        return target.dispatchEvent(Js.unwrap(a.get(0), Event.class));
    }

    private static boolean capture(Object options) {
        return options instanceof Scriptable ? option(options, "capture") : Js.bool(options);
    }

    /**
     * The DOM listener for a script listener (a function, or an object with {@code handleEvent}). It is stored on
     * the script object, so the same function always maps to the same listener and lives exactly as long.
     */
    EventListener listener(Object handler) {
        if (handler instanceof ScriptableObject s && s.getAssociatedValue(LISTENER) instanceof EventListener l) return l;
        EventListener listener = e -> deliver(handler, e);
        if (handler instanceof ScriptableObject s) s.associateValue(LISTENER, listener);
        return listener;
    }

    private void deliver(Object handler, Event e) {
        String what = "Error in '" + e.type + "' listener";
        if (handler instanceof Callable fn) {
            rt.call(what, fn, rt.dom.wrap(e.currentTarget()), e);
        } else if (handler instanceof Scriptable object && Js.property(object, "handleEvent") instanceof Callable fn) {
            rt.call(what, fn, object, e);
        }
    }
}
