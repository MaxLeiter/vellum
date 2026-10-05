package dev.vellum.preview;

import dev.vellum.engine.event.Modifiers;
import dev.vellum.engine.host.FileStamps;
import dev.vellum.engine.input.InputHandler;
import dev.vellum.preview.host.PreviewHost;
import dev.vellum.preview.render.ImageCanvas;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * The previewer window. Renders the scene at the GUI scale (one GUI px is {@code scale} window px) up to 60 times a
 * second, but only when something changed: input, a resize, a reload, or the page itself ({@link Scene#needsFrame}:
 * animations, timers, scrolling). Forwards mouse and keyboard input to the page in GUI coordinates, and reloads the
 * page when one of its files is saved. Keys: F5 reload, F12 inspector, 1-4 GUI scale (unless a text field has
 * focus), Ctrl/Cmd+S screenshot.
 */
final class PreviewWindow {
    /** How often the page's files are checked for changes. */
    private static final int POLL_MS = 100;

    private final Scene scene;
    /** The page, or null for the canvas test (which takes no input). */
    private final PageScene page;
    private final PreviewHost host;
    private final FrameRenderer renderer;
    private final FileStamps files = new FileStamps();
    private final JFrame frame = new JFrame();
    private final View view = new View();
    private final long start = System.nanoTime();
    private int scale;
    private boolean inspecting;
    private float mouseX = -1, mouseY = -1;
    private BufferedImage lastFrame;
    /** The next frame must be rendered whatever the scene says (input, reload, resize, a setting changed). */
    private boolean stale = true;

    PreviewWindow(Scene scene, PreviewHost host, int scale, int width, int height) {
        this.scene = scene;
        this.page = scene instanceof PageScene p ? p : null;
        this.host = host;
        this.renderer = new FrameRenderer(host.assets(), host.fonts());
        this.scale = scale;
        if (page != null) {
            host.onNavigate(url -> SwingUtilities.invokeLater(() -> {
                page.open(host.resolveUrl(page.url(), url));
                pageLoaded();
            }));
        }
        view.setPreferredSize(new Dimension(width * scale, height * scale));
        installInput();
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.add(view);
        frame.pack();
        frame.setLocationRelativeTo(null);
        pageLoaded();
    }

    void show() {
        frame.setVisible(true);
        view.requestFocusInWindow();
        new Timer(16, e -> renderFrame()).start();
        new Timer(POLL_MS, e -> {
            if (files.changed(host.loadedFiles())) reload();
        }).start();
    }

    private void renderFrame() {
        int width = view.getWidth(), height = view.getHeight();
        if (width <= 0 || height <= 0) return;
        double now = (System.nanoTime() - start) / 1e6;
        boolean resized = lastFrame == null || lastFrame.getWidth() != width || lastFrame.getHeight() != height;
        if (!stale && !resized && !scene.needsFrame(now)) return;
        stale = false;
        lastFrame = renderer.render(scene, width, height, scale, now, inspecting ? this::paintInspector : null);
        view.repaint();
    }

    private void paintInspector(ImageCanvas canvas) {
        if (page != null) Inspector.paint(canvas, host.fonts(), page.document(), mouseX, mouseY);
    }

    private void reload() {
        if (page == null) return;
        page.reload();
        pageLoaded();
    }

    private void pageLoaded() {
        stale = true;
        updateTitle();
    }

    private void updateTitle() {
        String name = page != null ? page.url() : "canvas test";
        frame.setTitle("Vellum Preview - " + name + " - GUI scale " + scale + (inspecting ? " - inspector" : ""));
    }

    private void saveScreenshot() {
        if (lastFrame == null) return;
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path file = Path.of("vellum-preview-" + stamp + ".png").toAbsolutePath();
        try {
            ImageIO.write(lastFrame, "png", file.toFile());
            System.out.println("Saved " + file);
        } catch (IOException e) {
            System.err.println("Cannot save " + file + ": " + e.getMessage());
        }
    }

    // ---- Input ----

    private void installInput() {
        view.setFocusable(true);
        view.setFocusTraversalKeysEnabled(false); // Tab goes to the page
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) { pointer(e); }
            @Override public void mouseDragged(MouseEvent e) { pointer(e); }

            @Override
            public void mouseExited(MouseEvent e) {
                mouseX = mouseY = -1;
                input(in -> in.mouseMove(-1, -1, modifiers(e)));
            }

            @Override
            public void mousePressed(MouseEvent e) {
                view.requestFocusInWindow();
                pointer(e);
                input(in -> in.mouseDown(mouseX, mouseY, button(e), modifiers(e)));
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                pointer(e);
                input(in -> in.mouseUp(mouseX, mouseY, button(e), modifiers(e)));
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                float delta = (float) e.getPreciseWheelRotation() * InputHandler.WHEEL_NOTCH;
                // Shift turns the wheel sideways, as in browsers (and macOS trackpads send it that way).
                input(in -> in.wheel(mouseX, mouseY, e.isShiftDown() ? delta : 0, e.isShiftDown() ? 0 : delta, modifiers(e)));
            }
        };
        view.addMouseListener(mouse);
        view.addMouseMotionListener(mouse);
        view.addMouseWheelListener(mouse);
        view.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (shortcut(e)) {
                    stale = true;
                    return;
                }
                DomKeys.Key key = DomKeys.of(e);
                input(in -> in.keyDown(key.key(), key.code(), modifiers(e)));
            }

            @Override
            public void keyReleased(KeyEvent e) {
                DomKeys.Key key = DomKeys.of(e);
                input(in -> in.keyUp(key.key(), key.code(), modifiers(e)));
            }

            @Override
            public void keyTyped(KeyEvent e) {
                char c = e.getKeyChar();
                if (Character.isISOControl(c) || e.isControlDown() || e.isMetaDown()) return;
                input(in -> in.charTyped(String.valueOf(c)));
            }
        });
    }

    /** Previewer keys, handled before the page sees them. */
    private boolean shortcut(KeyEvent e) {
        boolean command = (e.getModifiersEx() & Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()) != 0;
        int vk = e.getKeyCode();
        if (vk == KeyEvent.VK_F5) {
            reload();
        } else if (vk == KeyEvent.VK_F12) {
            inspecting = !inspecting;
            updateTitle();
        } else if (vk == KeyEvent.VK_S && command) {
            saveScreenshot();
        } else if (vk >= KeyEvent.VK_1 && vk <= KeyEvent.VK_4 && !command && (page == null || !page.wantsKeyboard())) {
            scale = vk - KeyEvent.VK_0;
            updateTitle();
        } else {
            return false;
        }
        return true;
    }

    private void input(Consumer<InputHandler> event) {
        stale = true;
        if (page != null) page.input(event);
    }

    private void pointer(MouseEvent e) {
        mouseX = e.getX() / (float) scale;
        mouseY = e.getY() / (float) scale;
        input(in -> in.mouseMove(mouseX, mouseY, modifiers(e)));
    }

    private static int button(MouseEvent e) {
        return switch (e.getButton()) {
            case MouseEvent.BUTTON2 -> 1;
            case MouseEvent.BUTTON3 -> 2;
            default -> 0;
        };
    }

    private static Modifiers modifiers(InputEvent e) {
        return new Modifiers(e.isShiftDown(), e.isControlDown(), e.isAltDown(), e.isMetaDown());
    }

    /** Shows the last frame 1:1, nearest-neighbour so HiDPI displays keep pixels sharp. */
    private final class View extends JComponent {
        @Override
        protected void paintComponent(Graphics g) {
            if (lastFrame == null) return;
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2.drawImage(lastFrame, 0, 0, null);
        }
    }
}
