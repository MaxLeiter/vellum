package dev.vellum.preview;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.event.Modifiers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A script of input for a page, run headless ({@code --actions <file>}): one action a line; lines starting with
 * {@code #} are comments. Frames are rendered 16 ms apart from t = 0, as in a snapshot, and every input action is
 * followed by one, so the next action and the next shot see what it did. Targets are viewport points in GUI px
 * ({@code 120 40}) or a CSS selector (the centre of the part of the first match that shows, as painted, where
 * {@code VellumAutomation} aims in game). See {@code preview/README.md}.
 */
final class Actions {
    /** One line of the script: an action name, its arguments split at white space, and the text after the name. */
    record Step(int line, String action, List<String> args, String rest) {}

    /** A target resolved to a viewport point. */
    private record Point(float x, float y) {}

    private static final double FRAME_MS = 16;

    private final List<Step> steps;

    private Actions(List<Step> steps) {
        this.steps = steps;
    }

    /** Parses a script; throws {@link IllegalArgumentException} naming the line of the first malformed action. */
    static Actions parse(String script) {
        List<Step> steps = new ArrayList<>();
        String[] lines = script.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String text = lines[i].strip();
            if (text.isEmpty() || text.startsWith("#")) continue; // a comment (# inside a line is a selector's)
            List<String> words = Arrays.asList(text.split("\\s+"));
            Step step = new Step(i + 1, words.getFirst().toLowerCase(Locale.ROOT),
                    List.copyOf(words.subList(1, words.size())), text.substring(words.getFirst().length()).strip());
            String problem = check(step);
            if (problem != null) throw new IllegalArgumentException("line " + step.line + ": " + problem);
            steps.add(step);
        }
        return new Actions(steps);
    }

    /** What is wrong with a step, or null. */
    private static String check(Step s) {
        int n = s.args.size();
        return switch (s.action) {
            case "wait", "bench" -> n == 1 && count(s.args.getFirst()) > 0 ? null
                    : s.action + " takes a number of frames";
            case "move", "click" -> n > 0 ? null : s.action + " takes a point (x y) or a selector";
            case "wheel" -> n > 1 && isNumber(s.args.getLast()) ? null : "wheel takes a target and a distance in px";
            case "key" -> n == 1 ? null : "key takes one key, like Enter, ArrowDown, a or Shift+Tab";
            case "type" -> n > 0 ? null : "type takes text";
            case "shot" -> n == 1 ? null : "shot takes a file name (without .png)";
            default -> "unknown action '" + s.action + "'";
        };
    }

    /**
     * Runs the script against {@code scene}, rendered at {@code width}×{@code height} GUI px at GUI scale
     * {@code scale}; shots go to {@code shots}. Returns the last frame.
     */
    BufferedImage run(PageScene scene, FrameRenderer renderer, int width, int height, int scale, Path shots,
                      PrintStream out) throws IOException {
        Runner r = new Runner(scene, renderer, width, height, scale);
        for (Step step : steps) {
            try {
                r.run(step, shots, out);
            } catch (IllegalStateException e) {
                throw new IllegalStateException("line " + step.line + ": " + e.getMessage(), e);
            }
        }
        return r.image;
    }

    /** The clock and the last frame while a script runs. */
    private static final class Runner {
        final PageScene scene;
        final FrameRenderer renderer;
        final int width, height, scale;
        double now;
        BufferedImage image;

        Runner(PageScene scene, FrameRenderer renderer, int width, int height, int scale) {
            this.scene = scene;
            this.renderer = renderer;
            this.width = width;
            this.height = height;
            this.scale = scale;
            render();
        }

        void run(Step s, Path shots, PrintStream out) throws IOException {
            List<String> args = s.args;
            switch (s.action) {
                case "wait" -> frames(count(args.getFirst()));
                case "move" -> {
                    Point p = target(args);
                    scene.input(in -> in.mouseMove(p.x, p.y, Modifiers.NONE));
                    frames(1);
                }
                case "click" -> {
                    Point p = target(args);
                    scene.input(in -> {
                        in.mouseMove(p.x, p.y, Modifiers.NONE);
                        in.mouseDown(p.x, p.y, 0, Modifiers.NONE);
                        in.mouseUp(p.x, p.y, 0, Modifiers.NONE);
                    });
                    frames(1);
                }
                case "wheel" -> {
                    Point p = target(args.subList(0, args.size() - 1));
                    float dy = Float.parseFloat(args.getLast());
                    scene.input(in -> in.wheel(p.x, p.y, 0, dy, Modifiers.NONE));
                    frames(1);
                }
                case "key" -> {
                    key(args.getFirst());
                    frames(1);
                }
                case "type" -> {
                    s.rest.codePoints().forEach(cp -> {
                        String ch = Character.toString(cp);
                        String code = DomKeys.codeOf(ch);
                        scene.input(in -> {
                            in.keyDown(ch, code, Modifiers.NONE);
                            in.charTyped(ch);
                            in.keyUp(ch, code, Modifiers.NONE);
                        });
                    });
                    frames(1);
                }
                case "shot" -> {
                    Path file = shots.resolve(args.getFirst() + ".png");
                    ImageIO.write(image, "png", file.toFile());
                    out.println("Wrote " + file.toAbsolutePath());
                }
                case "bench" -> {
                    int n = count(args.getFirst());
                    long start = System.nanoTime();
                    frames(n);
                    double ms = (System.nanoTime() - start) / 1e6 / n;
                    out.printf(Locale.ROOT, "bench: %d frames, %.3f ms a frame%n", n, ms);
                }
                default -> throw new IllegalStateException("unknown action " + s.action);
            }
        }

        /** {@code Shift+Tab}, {@code Ctrl+a}: modifiers joined to a DOM key name with {@code +}. */
        private void key(String spec) {
            String[] parts = spec.split("\\+");
            String key = parts[parts.length - 1];
            boolean shift = false, ctrl = false, alt = false, meta = false;
            for (int i = 0; i < parts.length - 1; i++) {
                switch (parts[i].toLowerCase(Locale.ROOT)) {
                    case "shift" -> shift = true;
                    case "ctrl", "control" -> ctrl = true;
                    case "alt" -> alt = true;
                    case "meta", "cmd" -> meta = true;
                    default -> throw new IllegalStateException("unknown modifier " + parts[i]);
                }
            }
            Modifiers mods = new Modifiers(shift, ctrl, alt, meta);
            String code = DomKeys.codeOf(key);
            scene.input(in -> {
                in.keyDown(key, code, mods);
                in.keyUp(key, code, mods);
            });
        }

        /**
         * Two numbers are a point; anything else is a selector, aimed where pointer input reaches its first match
         * ({@link Document#pointerTarget}: the centre of the part that shows, scrolled into view if none does).
         */
        private Point target(List<String> args) {
            if (args.size() == 2 && isNumber(args.get(0)) && isNumber(args.get(1))) {
                return new Point(Float.parseFloat(args.get(0)), Float.parseFloat(args.get(1)));
            }
            String selector = String.join(" ", args);
            Document doc = scene.document();
            Element e = doc == null ? null : doc.querySelector(selector);
            if (e == null || e.box == null) throw new IllegalStateException("nothing is shown for " + selector);
            float[] at = doc.pointerTarget(e);
            if (at == null) throw new IllegalStateException("the pointer can't reach " + selector + ": something covers it");
            return new Point(at[0], at[1]);
        }

        private void frames(int n) {
            for (int i = 0; i < n; i++) {
                now += FRAME_MS;
                render();
            }
        }

        private void render() {
            image = renderer.render(scene, width * scale, height * scale, scale, now, null);
        }
    }

    private static int count(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean isNumber(String s) {
        try {
            Float.parseFloat(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
