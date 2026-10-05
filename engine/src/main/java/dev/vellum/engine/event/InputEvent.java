package dev.vellum.engine.event;

/** {@code beforeinput}, {@code input} and {@code change} on form controls. {@code data} is the inserted text, if any. */
public class InputEvent extends Event {
    public final String data;
    public final String inputType;

    public InputEvent(String type, String data, String inputType) {
        super(type, true, type.equals("beforeinput"));
        this.data = data;
        this.inputType = inputType;
    }
}
