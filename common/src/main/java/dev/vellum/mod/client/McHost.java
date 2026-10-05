package dev.vellum.mod.client;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.FontMetrics;
import dev.vellum.engine.host.Host;
import dev.vellum.engine.host.ReplacedContent;
import dev.vellum.engine.script.ScriptRuntime;
import dev.vellum.engine.script.Scripting;
import dev.vellum.engine.style.Cursor;
import dev.vellum.mod.Constants;
import dev.vellum.mod.client.render.McFontMetrics;
import dev.vellum.mod.client.replaced.McReplaced;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * The engine's {@link Host} in Minecraft, one per document: resources, fonts, Minecraft elements, sounds, clipboard
 * and translations here; closing, navigation, messages and the cursor go to the document's {@link DocumentDriver}.
 */
final class McHost implements Host {
    private final DocumentDriver driver;
    private final Set<String> reported = new HashSet<>();

    McHost(DocumentDriver driver) {
        this.driver = driver;
    }

    @Override
    public FontMetrics fonts() {
        return McFontMetrics.INSTANCE;
    }

    @Override
    public @Nullable String loadText(String url) {
        return VellumResources.loadText(url);
    }

    @Override
    public @Nullable ReplacedContent createReplaced(Element element) {
        return McReplaced.create(element);
    }

    @Override
    public boolean isReplacedTag(String tag) {
        return McReplaced.TAGS.contains(tag);
    }

    @Override
    public @Nullable ScriptRuntime createScriptRuntime(Document document) {
        // Initial data is delivered before any script runs, so pages can read vellum.data at load.
        ScriptRuntime runtime = Scripting.rhino().apply(document);
        String data = driver.data();
        if (data != null) runtime.receive("data", data);
        return runtime;
    }

    @Override
    public void log(LogLevel level, String message) {
        switch (level) {
            case DEBUG -> Constants.LOG.debug("[{}] {}", driver.name(), message);
            case INFO -> Constants.LOG.info("[{}] {}", driver.name(), message);
            case WARN -> Constants.LOG.warn("[{}] {}", driver.name(), message);
            case ERROR -> Constants.LOG.error("[{}] {}", driver.name(), message);
        }
    }

    /** Script and resource errors: the page keeps working, so log each distinct message once. */
    @Override
    public void reportError(String message, Throwable error) {
        if (reported.add(message)) Constants.LOG.error("[{}] {}", driver.name(), message, error);
    }

    @Override
    public void setCursor(Cursor cursor) {
        driver.setCursor(cursor);
    }

    @Override
    public void playSound(String id, float volume, float pitch) {
        Identifier sound = Identifier.tryParse(id);
        if (sound == null) return;
        SoundEvent event = BuiltInRegistries.SOUND_EVENT.getOptional(sound).orElseGet(() -> SoundEvent.createVariableRangeEvent(sound));
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(event, pitch, volume));
    }

    @Override
    public String getClipboard() {
        return Minecraft.getInstance().keyboardHandler.getClipboard();
    }

    @Override
    public void setClipboard(String text) {
        Minecraft.getInstance().keyboardHandler.setClipboard(text);
    }

    @Override
    public void close() {
        driver.requestClose();
    }

    @Override
    public void send(String channel, String json) {
        driver.send(channel, json);
    }

    @Override
    public String translate(String key, String... args) {
        return I18n.get(key, (Object[]) args);
    }

    @Override
    public boolean prefersReducedMotion() {
        return VellumConfig.reducedMotion;
    }

    @Override
    public void navigate(String url) {
        driver.navigate(url);
    }
}
