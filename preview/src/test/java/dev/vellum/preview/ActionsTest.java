package dev.vellum.preview;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Viewport;
import dev.vellum.preview.host.PreviewHost;
import dev.vellum.preview.render.MinecraftAssets;
import dev.vellum.preview.render.MinecraftFont;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code --actions}: a script of input and shots run against a page through the previewer's rendering path. */
class ActionsTest {
    @TempDir
    Path dir;

    @Test
    void scriptsDriveThePageAndSaveShots() throws IOException {
        Path page = dir.resolve("page.html");
        Files.writeString(page, """
                <body style="margin: 0">
                <button id=b onclick="this.textContent = 'clicked'">go</button>
                <input id=i><p id=o></p>
                <div id=s style="height: 40px; overflow: auto; scroll-behavior: auto"><div style="height: 200px"></div></div>
                <script>
                  document.getElementById('i').addEventListener('keydown', e => {
                    if (e.key === 'Enter' && e.shiftKey) document.getElementById('o').textContent = document.getElementById('i').value;
                  });
                </script>""");
        MinecraftAssets assets = MinecraftAssets.open(List.of(), Optional.empty());
        MinecraftFont font = new MinecraftFont(assets);
        PageScene scene = new PageScene(new PreviewHost(assets, font), PreviewHost.pageUrl(page), null,
                new Viewport(200, 120, 2));
        Actions actions = Actions.parse("""
                # click, type, a key with a modifier, the wheel over a selector, a shot
                wait 2
                click #b
                click #i
                type hi there
                key Shift+Enter
                wheel #s 24
                shot done
                """);
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        actions.run(scene, new FrameRenderer(assets, font), 200, 120, 2, dir, new PrintStream(log));

        Document doc = scene.document();
        assertEquals("clicked", doc.getElementById("b").textContent());
        assertEquals("hi there", doc.getElementById("o").textContent());
        assertEquals(24, doc.getElementById("s").scrollTop(), 1e-3);
        assertTrue(Files.size(dir.resolve("done.png")) > 0);
        assertTrue(log.toString().contains("done.png"), log.toString());
    }

    /** Selectors aim where the element shows, as {@code VellumAutomation} does in game, not at its border box's centre. */
    @Test
    void selectorsAimAtThePartThatShows() throws IOException {
        Path page = dir.resolve("clipped.html");
        Files.writeString(page, """
                <body style="margin: 0">
                <div style="height: 20px; overflow: hidden">
                  <button id=tall style="display: block; height: 60px" onclick="this.textContent = 'clicked'">go</button>
                </div>
                <button id=below style="display: block; height: 40px" onclick="this.textContent = 'missed'">no</button>
                <div style="position: relative"><p id=covered>x</p><i style="position: absolute; inset: 0"></i></div>""");
        MinecraftAssets assets = MinecraftAssets.open(List.of(), Optional.empty());
        MinecraftFont font = new MinecraftFont(assets);
        PageScene scene = new PageScene(new PreviewHost(assets, font), PreviewHost.pageUrl(page), null,
                new Viewport(200, 120, 2));
        FrameRenderer renderer = new FrameRenderer(assets, font);
        Actions.parse("click #tall").run(scene, renderer, 200, 120, 2, dir, new PrintStream(new ByteArrayOutputStream()));
        Document doc = scene.document();
        assertEquals("clicked", doc.getElementById("tall").textContent(), "the middle of its 20 px that show");
        assertEquals("no", doc.getElementById("below").textContent());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Actions.parse("move #covered")
                .run(scene, renderer, 200, 120, 2, dir, new PrintStream(new ByteArrayOutputStream())));
        assertTrue(e.getMessage().contains("covers it"), e.getMessage());
    }

    @Test
    void malformedScriptsNameTheLine() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Actions.parse("wait 2\n\njump 3"));
        assertEquals("line 3: unknown action 'jump'", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Actions.parse("wait soon"));
        assertThrows(IllegalArgumentException.class, () -> Actions.parse("wheel 24"));
        assertThrows(IllegalArgumentException.class, () -> Actions.parse("shot"));
    }
}
