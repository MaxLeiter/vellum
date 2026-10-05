package dev.vellum.engine.script;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.replaced.CanvasContent;
import dev.vellum.engine.replaced.Context2D;
import dev.vellum.shadow.rhino.Context;
import dev.vellum.shadow.rhino.Scriptable;
import dev.vellum.shadow.rhino.typedarrays.NativeArrayBufferView;

/**
 * Canvas scripting: {@code canvas.getContext('2d')} (one {@link Context2D} per canvas; null for other context types
 * and other elements), {@code canvas.width}/{@code height}, and the 2D context's members. Image data is a plain
 * {@code {width, height, data}} object whose {@code data} is a {@code Uint8ClampedArray} of RGBA bytes, row by row;
 * {@code putImageData} also takes plain arrays.
 */
final class CanvasBindings {
    private static final String CONTEXT = "context2d";
    /** Image data is limited to the largest canvas. */
    private static final long MAX_PIXELS = (long) CanvasContent.MAX_SIZE * CanvasContent.MAX_SIZE;

    /** Bytes of an image data array: a typed array's own buffer, or a copy of a plain array. */
    private record Bytes(byte[] bytes, int offset) {}

    private final RhinoScriptRuntime rt;
    private final HostClass<Context2D> context;

    CanvasBindings(RhinoScriptRuntime rt) {
        this.rt = rt;
        rt.dom.elementMembers()
                .method("getContext", (e, a) -> e.tagName().equals("canvas") && a.str(0).equals("2d") ? wrap(e) : null)
                .prop("width", e -> size(e, true), (e, v) -> e.setAttribute("width", String.valueOf((int) Js.num(v))))
                .prop("height", e -> size(e, false), (e, v) -> e.setAttribute("height", String.valueOf((int) Js.num(v))));
        context = new HostClass<>(rt, "CanvasRenderingContext2D", Context2D.class, null, null).expose("CanvasRenderingContext2D");
        context.members()
                .get("canvas", Context2D::canvas)
                .prop("fillStyle", Context2D::fillStyle, (c, v) -> c.setFillStyle(Js.str(v)))
                .prop("strokeStyle", Context2D::strokeStyle, (c, v) -> c.setStrokeStyle(Js.str(v)))
                .prop("lineWidth", Context2D::lineWidth, (c, v) -> c.setLineWidth((float) Js.num(v)))
                .prop("globalAlpha", Context2D::globalAlpha, (c, v) -> c.setGlobalAlpha((float) Js.num(v)))
                .action("save", (c, a) -> c.save())
                .action("restore", (c, a) -> c.restore())
                .action("fillRect", (c, a) -> c.fillRect(f(a, 0), f(a, 1), f(a, 2), f(a, 3)))
                .action("strokeRect", (c, a) -> c.strokeRect(f(a, 0), f(a, 1), f(a, 2), f(a, 3)))
                .action("clearRect", (c, a) -> c.clearRect(f(a, 0), f(a, 1), f(a, 2), f(a, 3)))
                .method("createImageData", (c, a) -> a.get(0) instanceof Scriptable like
                        ? imageData(intProperty(like, "width"), intProperty(like, "height"))
                        : imageData(i(a, 0), i(a, 1)))
                .method("getImageData", this::getImageData)
                .action("putImageData", this::putImageData)
                .action("drawImage", this::drawImage);
    }

    /** The canvas's context wrapper, created on first use. */
    private HostObject wrap(Element canvas) {
        HostObject w = rt.dom.wrap(canvas);
        if (w.getAssociatedValue(CONTEXT) instanceof HostObject ctx) return ctx;
        return (HostObject) w.associateValue(CONTEXT, context.wrap(new Context2D(canvas)));
    }

    /** A canvas's pixel size (its attribute until it has pixels); other elements read the attribute. */
    private static int size(Element e, boolean width) {
        if (e.replaced instanceof CanvasContent canvas) return width ? canvas.surface().width() : canvas.surface().height();
        float fallback = !e.tagName().equals("canvas") ? 0 : width ? CanvasContent.DEFAULT_WIDTH : CanvasContent.DEFAULT_HEIGHT;
        return (int) e.numberAttribute(width ? "width" : "height", fallback);
    }

    // ---- Image data ----

    private Scriptable imageData(int width, int height) {
        checkSize(width, height);
        Scriptable data = Context.getCurrentContext().newObject(rt.global, "Uint8ClampedArray", new Object[] {width * height * 4});
        Scriptable image = rt.js.newObject();
        image.put("width", image, width);
        image.put("height", image, height);
        image.put("data", image, data);
        return image;
    }

    private Object getImageData(Context2D c, Args a) {
        int x = i(a, 0), y = i(a, 1), w = i(a, 2), h = i(a, 3);
        // Negative sizes count from the other edge, as in browsers.
        if (w < 0) x += w;
        if (h < 0) y += h;
        Scriptable image = imageData(Math.abs(w), Math.abs(h));
        Bytes bytes = bytes(Js.property(image, "data"), Math.abs(w * h) * 4);
        c.getImageData(x, y, Math.abs(w), Math.abs(h), bytes.bytes, bytes.offset);
        return image;
    }

    /** {@code putImageData(image, dx, dy[, dirtyX, dirtyY, dirtyWidth, dirtyHeight])}. */
    private void putImageData(Context2D c, Args a) {
        if (!(a.get(0) instanceof Scriptable image)) throw Js.typeError("putImageData needs an ImageData");
        int width = intProperty(image, "width"), height = intProperty(image, "height");
        checkSize(width, height);
        Bytes bytes = bytes(Js.property(image, "data"), width * height * 4);
        boolean dirty = a.has(3);
        c.putImageData(bytes.bytes, bytes.offset, width, height, i(a, 1), i(a, 2),
                dirty ? i(a, 3) : 0, dirty ? i(a, 4) : 0, dirty ? i(a, 5) : width, dirty ? i(a, 6) : height);
    }

    /** RGBA bytes of an image data array of at least {@code length}. */
    private static Bytes bytes(Object data, int length) {
        if (data instanceof NativeArrayBufferView view && view.getByteLength() >= length) {
            return new Bytes(view.getBuffer().getBuffer(), view.getByteOffset());
        }
        if (!(data instanceof Scriptable array)) throw Js.typeError("Image data needs a data array");
        byte[] copy = new byte[length];
        for (int i = 0; i < length; i++) {
            Object v = array.get(i, array);
            double n = v == Scriptable.NOT_FOUND ? 0 : Js.num(v);
            copy[i] = (byte) (Double.isNaN(n) ? 0 : Math.clamp(Math.round(n), 0, 255));
        }
        return new Bytes(copy, 0);
    }

    private static void checkSize(int width, int height) {
        if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
            throw Js.error("RangeError", "Image data of " + width + "×" + height + " pixels");
        }
    }

    // ---- drawImage ----

    /** {@code drawImage(canvas, dx, dy)}, {@code (canvas, dx, dy, dw, dh)} or {@code (canvas, sx, sy, sw, sh, dx, dy, dw, dh)}. */
    private void drawImage(Context2D c, Args a) {
        Element source = Js.unwrap(a.get(0), Element.class);
        if (!source.tagName().equals("canvas")) throw Js.typeError("drawImage draws canvases only");
        Context2D from = (Context2D) wrap(source).target;
        float sw = size(source, true), sh = size(source, false);
        switch (a.length()) {
            case 3 -> c.drawImage(from, 0, 0, sw, sh, f(a, 1), f(a, 2), sw, sh);
            case 5 -> c.drawImage(from, 0, 0, sw, sh, f(a, 1), f(a, 2), f(a, 3), f(a, 4));
            case 9 -> c.drawImage(from, f(a, 1), f(a, 2), f(a, 3), f(a, 4), f(a, 5), f(a, 6), f(a, 7), f(a, 8));
            default -> throw Js.typeError("drawImage takes 3, 5 or 9 arguments");
        }
    }

    // ---- Arguments ----

    private static float f(Args a, int i) {
        return (float) Js.num(a.get(i));
    }

    private static int i(Args a, int i) {
        double n = Js.num(a.get(i));
        return Double.isNaN(n) ? 0 : (int) Math.max(Integer.MIN_VALUE / 4, Math.min(Integer.MAX_VALUE / 4, n));
    }

    private static int intProperty(Scriptable object, String name) {
        double n = Js.num(Js.property(object, name));
        return Double.isNaN(n) ? 0 : (int) n;
    }
}
