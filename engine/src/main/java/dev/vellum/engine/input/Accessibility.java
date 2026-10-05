package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Node;
import dev.vellum.engine.dom.Text;
import dev.vellum.engine.host.Host;
import dev.vellum.engine.input.Accessible.Role;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Display;
import dev.vellum.engine.style.Visibility;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * How elements read to the narrator: roles, accessible names, values and states, and the text of content. A small
 * version of ARIA's accessible name computation; {@link Narration} documents the rules pages see.
 *
 * <p>Text is read as {@code innerText} lays it out: whitespace collapsed, hidden content left out, and each block
 * (a block, flex or grid box, or a {@code <br>}) a line of its own. Lines are joined into sentences with ". ", unless
 * a line already ends with one, so a speaker's name above their words reads as "speaker. what they say".
 */
final class Accessibility {
    /** A name taken from an element's content is cut to about this many characters. */
    static final int MAX_NAME = 100;
    /** Elements whose content is never read. */
    private static final Set<String> UNREAD = Set.of("script", "style", "template", "head", "title", "noscript");
    /** Form fields: their content is not their name, and text being read leaves them out. */
    private static final Set<String> FIELDS = Set.of("input", "select", "textarea");

    private Accessibility() {}

    /**
     * What {@code e} reads as, given the page's tab order; null when it is hidden ({@link #hidden}) or has nothing to
     * say (an element, image or slot that nothing names).
     */
    static Accessible describe(Element e, List<Element> tabOrder) {
        if (e == null || hidden(e)) return null;
        Role role = role(e);
        String name = name(e, role), value = value(e, role);
        boolean silent = role == Role.GENERIC || role == Role.IMG || role == Role.ITEM || role == Role.SLOT;
        if (silent && name.isEmpty() && (value == null || value.isEmpty())) return null;
        int position = tabOrder.indexOf(e) + 1;
        return new Accessible(e, role, name, value, checked(e, role), hint(e, name), disabled(e), e.isFocused(),
                position, position > 0 ? tabOrder.size() : 0);
    }

    /**
     * The element the narrator reads while the pointer is on {@code target}: the nearest one, from it up, that is
     * interactive (a control, a link, a slot, a tab stop, an {@code <item tooltip>}) or has a title or
     * {@code aria-label}. None inside {@code aria-hidden}, none above {@code body}, and none past an element whose
     * empty title says "no tooltip here".
     */
    static Element hoverTarget(Element target) {
        if (target == null || hidden(target)) return null;
        for (Element e = target; e != null; e = e.parentElement()) {
            if (e.tagName().equals("body") || e.tagName().equals("html")) return null;
            if (interactive(e, role(e)) || labelled(e) || Tooltips.titled(e)) return e;
            if (e.hasAttribute("title") || e.hasAttribute("title-json")) return null;
        }
        return null;
    }

    /** Whether the narrator skips {@code e}: it or an ancestor has {@code aria-hidden="true"}. */
    static boolean hidden(Element e) {
        for (Element p = e; p != null; p = p.parentElement()) if (ariaHidden(p)) return true;
        return false;
    }

    private static boolean ariaHidden(Element e) {
        return "true".equalsIgnoreCase(attr(e, "aria-hidden"));
    }

    // ---- Roles ----

    static Role role(Element e) {
        String explicit = firstToken(e.getAttribute("role"));
        if (explicit != null) {
            Role r = switch (explicit) {
                case "button", "menuitem" -> Role.BUTTON;
                case "link" -> Role.LINK;
                case "checkbox", "switch", "menuitemcheckbox" -> Role.CHECKBOX;
                case "radio", "menuitemradio" -> Role.RADIO;
                case "slider", "spinbutton" -> Role.SLIDER;
                case "textbox", "searchbox" -> Role.TEXTBOX;
                case "combobox", "listbox" -> Role.COMBOBOX;
                case "tab" -> Role.TAB;
                case "img", "image" -> Role.IMG;
                default -> null; // other roles (heading, status...) and none / presentation read by name
            };
            if (r != null) return r;
            if (!explicit.equals("none") && !explicit.equals("presentation")) return Role.GENERIC;
        }
        return switch (e.tagName()) {
            case "button" -> Role.BUTTON;
            case "summary" -> Forms.isDetailsSummary(e) ? Role.BUTTON : Role.GENERIC;
            case "a" -> e.hasAttribute("href") ? Role.LINK : Role.GENERIC;
            case "textarea" -> Role.TEXTBOX;
            case "select" -> Role.COMBOBOX;
            case "img" -> Role.IMG;
            case "item" -> Role.ITEM;
            case "slot" -> Role.SLOT;
            case "input" -> switch (e.inputType()) {
                case "checkbox" -> Role.CHECKBOX;
                case "radio" -> Role.RADIO;
                case "range" -> Role.SLIDER;
                case "hidden" -> Role.GENERIC;
                default -> e.isTextControl() ? Role.TEXTBOX : Role.BUTTON; // submit, reset, button, image, color, file
            };
            default -> Role.GENERIC;
        };
    }

    /** Controls, links, slots and tab stops, and items that show their own tooltip. */
    static boolean interactive(Element e, Role role) {
        return switch (role) {
            case GENERIC, IMG -> e.isFocusable();
            case ITEM -> e.isFocusable() || e.replaced != null && e.replaced.showsTooltip();
            default -> true;
        };
    }

    private static boolean labelled(Element e) {
        return !collapse(attr(e, "aria-label")).isEmpty() || !collapse(attr(e, "aria-labelledby")).isEmpty();
    }

    // ---- Names ----

    /**
     * The accessible name: {@code aria-label}, else the text of the elements {@code aria-labelledby} names, else what
     * the element itself provides (an image's {@code alt}, a button input's {@code value}, the item of an
     * {@code <item>} or {@code <slot>}, a control's {@code <label>}), else its content's text (cut to about
     * {@link #MAX_NAME} characters; not for fields, images and slots), else its own title, else a text field's
     * placeholder. "" when none of these says anything.
     */
    static String name(Element e, Role role) {
        String name = collapse(attr(e, "aria-label"));
        if (name.isEmpty()) name = labelledBy(e);
        if (name.isEmpty()) name = nativeName(e);
        if (name.isEmpty() && namedByContent(e, role)) name = truncate(text(e));
        if (name.isEmpty()) name = titleText(e);
        if (name.isEmpty() && e.isTextControl()) name = collapse(attr(e, "placeholder"));
        return name;
    }

    private static String labelledBy(Element e) {
        List<String> parts = new ArrayList<>(2);
        for (String id : collapse(attr(e, "aria-labelledby")).split(" ")) {
            Element label = id.isEmpty() ? null : e.ownerDocument().getElementById(id);
            String text = label == null ? "" : text(label);
            if (!text.isEmpty()) parts.add(text);
        }
        return String.join(" ", parts);
    }

    private static String nativeName(Element e) {
        if (e.tagName().equals("img") || e.inputType().equals("image")) return collapse(attr(e, "alt"));
        if (e.replaced != null) return collapse(e.replaced.accessibleName());
        if (e.tagName().equals("input") && Forms.isButton(e)) return collapse(attr(e, "value"));
        if (!Forms.isLabelable(e)) return "";
        List<String> parts = new ArrayList<>(1);
        for (Element label : e.ownerDocument().descendants(l -> l.tagName().equals("label"))) {
            if (Forms.labeledControl(label) != e) continue;
            String text = text(label);
            if (!text.isEmpty()) parts.add(text);
        }
        return String.join(" ", parts);
    }

    /** Whether the element's content names it: not for fields, images, replaced content or the page itself. */
    private static boolean namedByContent(Element e, Role role) {
        return switch (role) {
            case TEXTBOX, COMBOBOX, SLIDER, IMG, ITEM, SLOT -> false;
            default -> e.replaced == null && !FIELDS.contains(e.tagName()) && !e.tagName().equals("html")
                    && !e.tagName().equals("body");
        };
    }

    /**
     * The element's own title as text: its {@code title}, else the plain text of its {@code title-json} (through
     * {@link Host#formatText}); line breaks become sentences. "" for none.
     */
    static String titleText(Element e) {
        String title = attr(e, "title");
        if (title != null && !title.isBlank()) return sentences(title);
        String json = attr(e, "title-json");
        if (json == null || json.isBlank()) return "";
        List<Host.TextRun> runs = e.ownerDocument().host().formatText(json);
        if (runs == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Host.TextRun run : runs) sb.append(run.text());
        return sentences(sb.toString());
    }

    /** Text with line breaks, as sentences: {@code "Rauca\na great city"} reads as "Rauca. a great city". */
    private static String sentences(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n")) {
            String l = collapse(line);
            if (!l.isEmpty()) lines.add(l);
        }
        return join(lines);
    }

    /**
     * What to read after the name: the text of the elements {@code aria-describedby} names, else the title that
     * applies to the element ({@link Tooltips#owner}, its own or an ancestor's) unless that is the name; null for none.
     */
    private static String hint(Element e, String name) {
        List<String> parts = new ArrayList<>(1);
        for (String id : collapse(attr(e, "aria-describedby")).split(" ")) {
            Element d = id.isEmpty() ? null : e.ownerDocument().getElementById(id);
            String text = d == null ? "" : text(d);
            if (!text.isEmpty()) parts.add(text);
        }
        if (!parts.isEmpty()) return String.join(" ", parts);
        Element owner = Tooltips.owner(e);
        String title = owner == null ? "" : titleText(owner);
        return title.isEmpty() || title.equals(name) ? null : title;
    }

    // ---- Values and states ----

    private static String value(Element e, Role role) {
        return switch (role) {
            case SLIDER -> {
                String text = collapse(attr(e, "aria-valuetext"));
                if (text.isEmpty()) text = collapse(attr(e, "aria-valuenow"));
                yield !text.isEmpty() ? text : e.inputType().equals("range") ? e.value() : null;
            }
            case TEXTBOX -> e.inputType().equals("password") ? null : e.isTextControl() ? e.value() : text(e);
            case COMBOBOX -> {
                Element option = e.tagName().equals("select") ? e.selectedOption() : null;
                yield option != null ? option.label() : nonEmpty(collapse(attr(e, "aria-valuetext")));
            }
            case ITEM, SLOT -> e.replaced == null ? null : nonEmpty(collapse(e.replaced.accessibleName()));
            default -> null;
        };
    }

    private static Boolean checked(Element e, Role role) {
        if (role != Role.CHECKBOX && role != Role.RADIO) return null;
        return e.isCheckable() ? e.checked() : "true".equalsIgnoreCase(attr(e, "aria-checked"));
    }

    private static boolean disabled(Element e) {
        return e.isDisabled() || "true".equalsIgnoreCase(attr(e, "aria-disabled"));
    }

    // ---- Text ----

    /** The text of {@code root}'s content as the narrator reads it, in sentences. */
    static String text(Element root) {
        return join(lines(root, null));
    }

    /**
     * The lines of {@code root}'s content, each with its whitespace collapsed, leaving out what is not read
     * ({@code aria-hidden}, {@code display: none}, invisible text, scripts and styles, form fields) and the elements
     * {@code skip} accepts. An image reads as its {@code alt}, replaced content as its {@link
     * dev.vellum.engine.host.ReplacedContent#accessibleName() name}.
     */
    static List<String> lines(Node root, Predicate<Element> skip) {
        Lines out = new Lines();
        collect(root, out, skip, true);
        return out.finish();
    }

    /** As {@link #lines}, for a node inside what is read: it is left out itself when it is not read. */
    static List<String> contentLines(Node node, Predicate<Element> skip) {
        Lines out = new Lines();
        collect(node, out, skip, false);
        return out.finish();
    }

    private static void collect(Node node, Lines out, Predicate<Element> skip, boolean root) {
        if (node instanceof Text t) {
            Element parent = t.parentElement();
            ComputedStyle s = parent == null ? null : parent.style;
            if (s == null || s.visibility == Visibility.VISIBLE) out.text(t.data());
            return;
        }
        if (!(node instanceof Element e)) return;
        if (!root && (UNREAD.contains(e.tagName()) || FIELDS.contains(e.tagName()) || ariaHidden(e)
                || skip != null && skip.test(e))) return;
        ComputedStyle s = e.style;
        if (s != null && s.display == Display.NONE) return;
        if (e.tagName().equals("br")) {
            out.newLine();
            return;
        }
        if (e.tagName().equals("img")) {
            out.word(attr(e, "alt"));
            return;
        }
        if (e.replaced != null) {
            out.word(e.replaced.accessibleName());
            return;
        }
        boolean block = s != null && !s.display.isInlineLevel() && s.display != Display.CONTENTS;
        if (block) out.newLine();
        for (int i = 0, n = e.childCount(); i < n; i++) collect(e.childAt(i), out, skip, false);
        if (block) out.newLine();
    }

    /** Lines being collected: text runs into the current line until a block or a {@code <br>} ends it. */
    private static final class Lines {
        private final List<String> lines = new ArrayList<>();
        private final StringBuilder line = new StringBuilder();

        void text(String s) {
            line.append(s);
        }

        /** Text that stands apart from what is around it (an image's alt, an item's name). */
        void word(String s) {
            if (s != null) line.append(' ').append(s).append(' ');
        }

        void newLine() {
            String l = collapse(line);
            line.setLength(0);
            if (!l.isEmpty()) lines.add(l);
        }

        List<String> finish() {
            newLine();
            return lines;
        }
    }

    /** Lines as sentences: joined with ". ", or a space after a line that already ends a sentence. */
    static String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (!sb.isEmpty()) sb.append(endsSentence(sb) ? " " : ". ");
            sb.append(line);
        }
        return sb.toString();
    }

    private static boolean endsSentence(CharSequence s) {
        char c = s.charAt(s.length() - 1);
        return c == '.' || c == '!' || c == '?' || c == ':' || c == ';' || c == '…';
    }

    /** Whitespace (also no-break spaces) collapsed to single spaces and stripped; "" for null. */
    static String collapse(CharSequence s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == ' ') {
                space = !sb.isEmpty();
            } else {
                if (space) sb.append(' ');
                sb.append(c);
                space = false;
            }
        }
        return sb.toString();
    }

    /** A long name cut at a word near {@link #MAX_NAME} characters, with an ellipsis. */
    static String truncate(String s) {
        if (s.length() <= MAX_NAME) return s;
        int cut = s.lastIndexOf(' ', MAX_NAME);
        if (cut < MAX_NAME / 2) cut = Character.isHighSurrogate(s.charAt(MAX_NAME - 1)) ? MAX_NAME - 1 : MAX_NAME;
        return s.substring(0, cut).stripTrailing() + "…";
    }

    /** The first token of a space-separated list, lower case; null for none. */
    static String firstToken(String list) {
        String l = collapse(list);
        if (l.isEmpty()) return null;
        int space = l.indexOf(' ');
        return (space < 0 ? l : l.substring(0, space)).toLowerCase(Locale.ROOT);
    }

    private static String attr(Element e, String name) {
        return e.getAttribute(name);
    }

    private static String nonEmpty(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
