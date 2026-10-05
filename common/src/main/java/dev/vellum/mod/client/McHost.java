package dev.vellum.mod.client;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.FontMetrics;
import dev.vellum.engine.host.Host;
import dev.vellum.engine.host.PixelSurface;
import dev.vellum.engine.host.ReplacedContent;
import dev.vellum.engine.script.ScriptRuntime;
import dev.vellum.engine.script.Scripting;
import dev.vellum.engine.style.Cursor;
import dev.vellum.mod.Constants;
import dev.vellum.mod.TokenBucket;
import dev.vellum.mod.VellumConfig;
import dev.vellum.mod.client.render.McFontMetrics;
import dev.vellum.mod.client.render.McImages;
import dev.vellum.mod.client.render.McSurface;
import dev.vellum.mod.client.replaced.McReplaced;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The engine's {@link Host} in Minecraft, one per document: resources, fonts, image sizes, canvas textures, Minecraft
 * elements and text, sounds, clipboard and translations here; closing, navigation, messages and the cursor go to the document's {@link DocumentDriver}.
 */
final class McHost implements Host {
    private final DocumentDriver driver;
    private final Set<String> reported = new HashSet<>();
    private @Nullable TokenBucket sounds;
    private double soundRate;

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
    public Map<String, Function<Element, ReplacedContent>> replacedElements() {
        return McReplaced.ELEMENTS;
    }

    @Override
    public float @Nullable [] imageSize(String url) {
        return McImages.textureSize(url);
    }

    @Override
    public float @Nullable [] spriteSize(String id) {
        return McImages.spriteSize(id);
    }

    @Override
    public PixelSurface createSurface(int width, int height) {
        return new McSurface(width, height);
    }

    @Override
    public @Nullable List<TextRun> formatText(String json) {
        return McText.runs(json);
    }

    @Override
    public @Nullable ScriptRuntime createScriptRuntime(Document document) {
        return Scripting.rhino().apply(document);
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

    /**
     * Plays a sound the game knows (registered, or defined by a resource pack's sounds.json), at most
     * {@code client.soundsPerSecond} per page and no louder than {@code client.maxSoundVolume}. Unknown ids are
     * dropped: vanilla would log a warning for each.
     */
    @Override
    public void playSound(String id, float volume, float pitch) {
        Identifier sound = Identifier.tryParse(id);
        var manager = Minecraft.getInstance().getSoundManager();
        if (sound == null || manager.getSoundEvent(sound) == null || !(volume > 0) || !sounds().tryTake()) return;
        SoundEvent event = BuiltInRegistries.SOUND_EVENT.getOptional(sound).orElseGet(() -> SoundEvent.createVariableRangeEvent(sound));
        float loudest = VellumConfig.CLIENT_MAX_SOUND_VOLUME.get().floatValue();
        manager.play(SimpleSoundInstance.forUI(event, Float.isFinite(pitch) ? Math.clamp(pitch, 0.5f, 2f) : 1f, Math.min(volume, loudest)));
    }

    private TokenBucket sounds() {
        double rate = VellumConfig.CLIENT_SOUNDS_PER_SECOND.get();
        if (sounds == null || soundRate != rate) {
            sounds = new TokenBucket(Math.max(1, rate), rate);
            soundRate = rate;
            if (rate == 0) sounds = new TokenBucket(0, 0);
        }
        return sounds;
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
        return VellumConfig.CLIENT_REDUCED_MOTION.get();
    }

    @Override
    public void navigate(String url) {
        driver.navigate(url);
    }
}
