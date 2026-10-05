package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.event.InputEvent;
import dev.vellum.engine.layout.Box;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * {@code <input type=range>}: the value constrained by {@code min}, {@code max} and {@code step}, keyboard stepping,
 * and dragging the thumb. One instance per element, kept in {@code element.controlState} and re-read from the
 * attributes when they change. The thumb geometry is shared with {@link Controls#paint}.
 */
final class RangeControl {
    /** Width of the vanilla slider handle sprite. */
    static final float THUMB_WIDTH = 8;

    private final Element element;
    private int domVersion = -1;
    private double min, max;
    /** The step, or NaN for {@code step="any"}. */
    private double step;
    /** The value string {@link #text} last formatted, and the result. */
    private String formattedFrom;
    private String formatted;
    /** {@link #caption}'s inputs and result. */
    private String captionLabel, captionText, caption;
    /** The caption as {@link Controls} draws it. */
    final Label label = new Label();

    private RangeControl(Element element) {
        this.element = element;
    }

    /** The control of a range input, created on first use; it re-reads min, max and step when the DOM changed. */
    static RangeControl of(Element element) {
        RangeControl range = element.controlState instanceof RangeControl r ? r : new RangeControl(element);
        element.controlState = range;
        int version = element.ownerDocument().domVersion();
        if (range.domVersion != version) {
            range.domVersion = version;
            range.min = Forms.number(element.getAttribute("min"), 0);
            range.max = Math.max(range.min, Forms.number(element.getAttribute("max"), 100));
            String s = element.getAttribute("step");
            double st = Forms.number(s, 1);
            range.step = "any".equalsIgnoreCase(s) ? Double.NaN : st > 0 ? st : 1;
            range.formattedFrom = null;
        }
        return range;
    }

    /** The current value: the element's value parsed and constrained, or the midpoint when missing or invalid. */
    double value() {
        return constrain(Forms.number(element.value(), min + (max - min) / 2));
    }

    /** Position of the value within [min, max], 0..1. */
    double fraction() {
        return max == min ? 0 : (value() - min) / (max - min);
    }

    /** The value as the element's value string. */
    String text() {
        String value = element.value();
        if (value != formattedFrom) {
            formattedFrom = value;
            formatted = format(value());
        }
        return formatted;
    }

    /** "label: value", like vanilla sliders' captions. */
    String caption(String label) {
        String text = text();
        if (!label.equals(captionLabel) || text != captionText) {
            captionLabel = label;
            captionText = text;
            caption = label + ": " + text;
        }
        return caption;
    }

    /** Arrow keys step, PageUp/PageDown move a tenth of the range, Home/End jump to the ends; fires input and change. */
    boolean keyDown(String key) {
        double unit = Double.isNaN(step) ? (max - min) / 100 : step;
        double page = Math.max(unit, (max - min) / 10);
        double target = switch (key) {
            case "ArrowRight", "ArrowUp" -> value() + unit;
            case "ArrowLeft", "ArrowDown" -> value() - unit;
            case "PageUp" -> value() + page;
            case "PageDown" -> value() - page;
            case "Home" -> min;
            case "End" -> max;
            default -> Double.NaN;
        };
        if (Double.isNaN(target)) return false;
        if (set(target)) Forms.fireInputAndChange(element);
        return true;
    }

    /**
     * Mousedown at {@code localX} (border-box coordinates): jumps the thumb under the pointer and drags it, firing
     * input while moving and change on release.
     */
    Drag press(float localX) {
        String start = text();
        moveThumb(localX);
        return new Drag() {
            @Override
            public void move(float px, float py) {
                if (element.box != null) moveThumb(Dom.local(element.box, px, py)[0]);
            }

            @Override
            public void end() {
                if (!text().equals(start)) element.dispatchEvent(new InputEvent("change", null, null));
            }
        };
    }

    private void moveThumb(float localX) {
        Box box = element.box;
        if (box != null && !Float.isNaN(localX) && set(min + fractionAt(box, localX) * (max - min))) {
            element.dispatchEvent(new InputEvent("input", null, null));
        }
    }

    /** Sets the value (constrained); returns whether it changed. */
    private boolean set(double v) {
        String s = format(constrain(v));
        if (s.equals(text())) return false;
        element.setValue(s);
        return true;
    }

    /** Clamps to [min, max] and snaps to the nearest step from min (never above max). */
    private double constrain(double v) {
        v = Math.max(min, Math.min(max, v));
        if (Double.isNaN(step)) return v;
        double snapped = min + Math.round((v - min) / step) * step;
        return snapped > max ? snapped - step : snapped;
    }

    /** Left edge of the thumb in border-box coordinates. */
    static float thumbX(Box box, double fraction) {
        return box.contentX() + (float) fraction * Math.max(0, box.contentWidth() - THUMB_WIDTH);
    }

    /** The fraction whose thumb is centred on {@code localX} (border-box coordinates). */
    private static double fractionAt(Box box, float localX) {
        float track = box.contentWidth() - THUMB_WIDTH;
        if (track <= 0) return 0;
        return Math.max(0, Math.min(1, (localX - box.contentX() - THUMB_WIDTH / 2) / track));
    }

    /** Shortest decimal form, without floating-point noise ("0.3", not "0.30000000000000004"). */
    private static String format(double v) {
        return BigDecimal.valueOf(v).setScale(9, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
