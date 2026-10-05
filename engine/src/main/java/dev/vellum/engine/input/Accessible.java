package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * What the narrator says about an element ({@link Narration}): what kind of control it is, its accessible name, its
 * value and state, and a hint to read after them. Hosts phrase it their own way (Minecraft as vanilla narrates its
 * widgets: "Done button"); {@link #describe()} is a plain English form for logs and tests.
 *
 * @param element  the element
 * @param role     what kind of control it is; {@link Role#GENERIC} for an element read by its name alone
 * @param name     its accessible name, never null ({@link Narration} lists where it comes from); empty only for a
 *                 control nothing names
 * @param value    a slider's value, a text field's text (none for a password), a select's chosen option, or the item
 *                 in a {@code <slot>} or {@code <item>}; null for none
 * @param checked  whether a checkbox or radio is checked; null for other roles
 * @param hint     what to read after it: its {@code aria-describedby} text, else the title that applies to it when that
 *                 is not its name (vanilla reads a widget's tooltip so); null for none
 * @param disabled whether it is disabled ({@code disabled}, a disabled fieldset, {@code aria-disabled="true"})
 * @param focused  whether it has keyboard focus
 * @param position its place in the tab order counting from 1, or 0 when it is not a tab stop
 * @param count    how many tab stops the page has (0 when it is not one)
 */
public record Accessible(Element element, Role role, String name, String value, Boolean checked, String hint,
                         boolean disabled, boolean focused, int position, int count) {
    /**
     * Roles the narrator tells apart: each has its own phrasing in Minecraft. An element's role is its {@code role}
     * attribute's first token when it names one of these (or a close kind: {@code switch} is a checkbox, {@code menuitem}
     * a button, {@code searchbox} a text box, {@code listbox} a combo box), else the role its tag implies.
     */
    public enum Role {
        /** Anything else: read by its name. */
        GENERIC(""),
        /** {@code <button>}, {@code <summary>}, button inputs. */
        BUTTON("button"),
        /** {@code <a href>}. */
        LINK("link"),
        /** {@code <input type=checkbox>}. */
        CHECKBOX("checkbox"),
        /** {@code <input type=radio>}. */
        RADIO("radio button"),
        /** {@code <input type=range>}. */
        SLIDER("slider"),
        /** Text inputs and {@code <textarea>}. */
        TEXTBOX("text field"),
        /** {@code <select>}. */
        COMBOBOX("combo box"),
        /** Only by {@code role="tab"}. */
        TAB("tab"),
        /** {@code <img>} with an {@code alt}. */
        IMG("image"),
        /** {@code <item>}. */
        ITEM("item"),
        /** {@code <slot>}. */
        SLOT("slot");

        private final String word;

        Role(String word) {
            this.word = word;
        }

        /** The role in plain English ("" for {@link #GENERIC}), as {@link Accessible#describe()} says it. */
        public String word() {
            return word;
        }
    }

    /**
     * In plain English, its parts joined by commas and the hint after a full stop: {@code Reply 1, button},
     * {@code Show hints, checkbox, checked}, {@code Volume, slider, 50}, {@code Iron Sword, item. Buy for 6 emeralds}.
     * For logs, the previewer and tests; hosts phrase {@link Accessible} their own way.
     */
    public String describe() {
        List<String> parts = new ArrayList<>(4);
        if (!name.isEmpty()) parts.add(name);
        if (role != Role.GENERIC) parts.add(role.word());
        if (checked != null) parts.add(checked ? "checked" : "not checked");
        if (value != null && !value.isEmpty() && !value.equals(name)) parts.add(value);
        if (disabled) parts.add("disabled");
        String text = String.join(", ", parts);
        return hint == null ? text : text + ". " + hint;
    }
}
