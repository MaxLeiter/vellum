package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.ReplacedContent;
import dev.vellum.engine.input.Accessible.Role;
import dev.vellum.engine.input.Narration.Announcement;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Narration}: titles, accessible names and roles, what the pointer and focus read, and live regions. */
class NarrationTest {
    /** An item as Minecraft's: named by its {@code id}, with a tooltip of its own when it has {@code tooltip}. */
    private record Item(Element element) implements ReplacedContent {
        @Override public float intrinsicWidth() { return 16; }
        @Override public float intrinsicHeight() { return 16; }
        @Override public void paint(Canvas canvas, float x, float y, float width, float height) {}
        @Override public boolean showsTooltip() { return element.hasAttribute("tooltip"); }
        @Override public String accessibleName() {
            String id = element.getAttribute("id");
            return id == null || id.isEmpty() ? null : id;
        }
    }

    private static Page load(String html) {
        TestHost host = new TestHost();
        host.replaced.put("item", Item::new);
        host.replaced.put("slot", Item::new); // a slot named by the item it holds
        return host.load(html);
    }

    private static Accessible read(Page page, String selector) {
        return page.input.narration().describe(page.query(selector));
    }

    private static String said(Page page, String selector) {
        Accessible a = read(page, selector);
        return a == null ? null : a.describe();
    }

    // ---- The title ----

    @Test
    void theTitleIsTheDocumentsAndFollowsScripts() {
        Page page = load("<head><title>  Emperor\n  Cualius </title></head><body></body>");
        assertEquals("Emperor Cualius", page.doc.title());
        page.run("document.title = 'Rauca'");
        assertEquals("Rauca", page.doc.title());
        Page none = load("<p>x</p>");
        assertEquals("", none.doc.title());
        none.run("document.title = 'Made'");
        assertEquals("Made", none.doc.title(), "a script's title adds a <title> to the head");
        assertEquals("Made", none.eval("document.title"));
    }

    // ---- Names ----

    @Test
    void namesComeFromLabelsThenContentThenTitle() {
        Page page = load("""
                <span id=who>Reply</span><span id=num>1</span>
                <button id=label aria-label="Reply 1: About the letter" aria-labelledby=who>1 About it</button>
                <button id=by aria-labelledby="who num">x</button>
                <img id=img alt="Rauca" title="A city">
                <input id=named type=checkbox><label for=named>Show  hints</label>
                <label><input id=wrapped type=checkbox checked> Pause the world</label>
                <button id=content>  Buy
                   now </button>
                <div id=titled title="Line one&#10;Line two"></div>
                <input id=field placeholder="Your name">
                <input id=send type=submit value="Send it">""");
        assertEquals("Reply 1: About the letter", read(page, "#label").name(), "aria-label first");
        assertEquals("Reply 1", read(page, "#by").name(), "aria-labelledby, the texts joined");
        assertEquals("Rauca", read(page, "#img").name(), "an image's alt before its title");
        assertEquals("Show hints", read(page, "#named").name(), "a <label for>");
        assertEquals("Pause the world", read(page, "#wrapped").name(), "a label around the control");
        assertEquals("Buy now", read(page, "#content").name(), "content, whitespace collapsed");
        assertEquals("Line one. Line two", read(page, "#titled").name(), "a title's lines read as sentences");
        assertEquals("Your name", read(page, "#field").name());
        assertEquals("Send it", read(page, "#send").name());
    }

    @Test
    void aTitleBesideContentIsReadAfterIt() {
        Page page = load("""
                <button id=ask title="Sells to anyone who asks">Ask</button>
                <div id=row title="Buy for 6 emeralds"><span id=name>Iron Sword</span></div>
                <div title="Map of Rauca"><button id=zoom>Zoom</button></div>
                <button id=described aria-describedby=note title="Not this">Go</button><p id=note>Leaves now</p>""");
        assertEquals("Ask, button. Sells to anyone who asks", said(page, "#ask"));
        assertEquals("Iron Sword. Buy for 6 emeralds", said(page, "#row"));
        assertEquals("Zoom, button. Map of Rauca", said(page, "#zoom"), "the title that applies, as its tooltip");
        assertEquals("Go, button. Leaves now", said(page, "#described"), "aria-describedby before a title");
    }

    @Test
    void longContentIsCutAtAWord() {
        String words = "word ".repeat(40);
        Page page = load("<button id=b>" + words + "</button>");
        String name = read(page, "#b").name();
        assertTrue(name.length() <= Accessibility.MAX_NAME + 1, name);
        assertTrue(name.endsWith("word…"), name);
    }

    @Test
    void blocksReadAsSentences() {
        Page page = load("""
                <style>.speaker { display: block }</style>
                <div id=line tabindex=0><b class=speaker>Emperor Cualius</b> Greetings, stranger!<br>Have you done it?
                  <span aria-hidden="true">(decoration)</span><span style="display: none">hidden</span>
                  <script>var x = 1;</script></div>
                <p id=done tabindex=0>Done.</p>""");
        assertEquals("Emperor Cualius. Greetings, stranger! Have you done it?", read(page, "#line").name());
    }

    // ---- Roles, values and states ----

    @Test
    void rolesComeFromTagsAndTheRoleAttribute() {
        Page page = load("""
                <a id=link href="vellum:x.html">Map</a><a id=plain>Plain</a>
                <input id=box type=checkbox aria-label=Hints checked>
                <input id=radio type=radio aria-label=Easy>
                <input id=range type=range aria-label=Volume value=40>
                <input id=text aria-label=Name value=Steve><input id=pass type=password aria-label=Password value=x>
                <textarea id=area aria-label=Notes>Bring wool</textarea>
                <select id=sel aria-label=Difficulty><option>Easy</option><option selected>Hard</option></select>
                <details><summary id=sum>More</summary>Text</details>
                <div id=tab role="tab presentation" tabindex=0>Quests</div>
                <div id=switch role=switch aria-checked=true tabindex=0>Sound</div>
                <div id=slider role=slider aria-valuetext="Half" aria-label=Speed tabindex=0></div>
                <div id=off role=button aria-disabled=true>Off</div>
                <button id=dis disabled>Dimmed</button>""");
        assertEquals("Map, link", said(page, "#link"));
        assertEquals(Role.GENERIC, read(page, "#plain").role(), "a link without href is not one");
        assertEquals("Hints, checkbox, checked", said(page, "#box"));
        assertEquals("Easy, radio button, not checked", said(page, "#radio"));
        assertEquals("Volume, slider, 40", said(page, "#range"));
        assertEquals("Name, text field, Steve", said(page, "#text"));
        assertNull(read(page, "#pass").value(), "a password is not read");
        assertEquals("Notes, text field, Bring wool", said(page, "#area"));
        assertEquals("Difficulty, combo box, Hard", said(page, "#sel"));
        assertEquals(Role.BUTTON, read(page, "#sum").role());
        assertEquals("Quests, tab", said(page, "#tab"), "the first token");
        assertEquals("Sound, checkbox, checked", said(page, "#switch"));
        assertEquals("Speed, slider, Half", said(page, "#slider"));
        assertEquals("Off, button, disabled", said(page, "#off"));
        assertTrue(read(page, "#dis").disabled());
        page.byId("box").click();
        assertFalse(read(page, "#box").checked(), "state is read when asked");
    }

    @Test
    void itemsAndSlotsReadTheirItem() {
        Page page = load("""
                <item id="Iron Sword"></item>
                <slot id="Coal" aria-label="Fuel"></slot>
                <slot id=""></slot>
                <slot id="" aria-label="Fuel"></slot>""");
        List<Element> slots = page.doc.querySelectorAll("slot");
        assertEquals("Iron Sword, item", said(page, "item"));
        assertEquals("Fuel, slot, Coal", read(page, "slot").describe(), "a label names it, and the item is its value");
        assertNull(page.input.narration().describe(slots.get(1)), "an empty slot nothing names says nothing");
        assertEquals("Fuel, slot", page.input.narration().describe(slots.get(2)).describe());
    }

    // ---- What the pointer and focus read ----

    @Test
    void thePointerReadsInteractiveAndTitledElementsOnly() {
        Page page = load("""
                <style>div, p, button { display: block; height: 20px; margin: 0 }</style>
                <p id=text>Just words</p>
                <button id=reply aria-label="Reply 1: About the letter"><span id=inner>1 About it</span></button>
                <div id=row title="Buy for 6 emeralds"><span id=name>Iron Sword</span></div>
                <div id=muted title=""><span id=quiet>Nothing</span></div>
                <div id=labelled aria-label="Rauca">.</div>
                <div aria-hidden="true"><button id=hidden>Hidden</button></div>
                <div><item id="Iron Sword" tooltip></item><item id="Stick"></item></div>""");
        Narration n = page.input.narration();
        assertNull(n.hovered(), "no pointer yet");
        page.hover(page.byId("text"));
        assertNull(n.hovered(), "plain text is not read");
        page.hover(page.byId("inner"));
        assertSame(page.byId("reply"), n.hovered().element(), "from the hovered element up");
        assertEquals("Reply 1: About the letter, button", n.hovered().describe());
        page.hover(page.byId("name"));
        assertSame(page.byId("row"), n.hovered().element(), "a title makes it worth reading");
        page.hover(page.byId("quiet"));
        assertNull(n.hovered(), "an empty title says there is nothing here");
        page.hover(page.byId("labelled"));
        assertEquals("Rauca", n.hovered().describe());
        page.hover(page.byId("hidden"));
        assertNull(n.hovered(), "aria-hidden hides it and what is in it");
        List<Element> items = page.doc.querySelectorAll("item");
        page.hover(items.get(0));
        assertEquals("Iron Sword, item", n.hovered().describe(), "an item that shows its tooltip");
        page.hover(items.get(1));
        assertNull(n.hovered(), "an item that shows nothing is decoration");
    }

    @Test
    void focusReadsTheFocusedElementWithItsPlaceInTheTabOrder() {
        Page page = load("""
                <button id=a>One</button><button id=b>Two</button>
                <div aria-hidden="true"><button id=c>Three</button></div>""");
        Narration n = page.input.narration();
        assertNull(n.focused());
        page.key("Tab");
        page.key("Tab");
        Accessible two = n.focused();
        assertSame(page.byId("b"), two.element());
        assertTrue(two.focused());
        assertEquals(2, two.position());
        assertEquals(3, two.count(), "aria-hidden content can still take focus");
        page.byId("c").focus();
        assertNull(n.focused(), "but it is never read");
        assertEquals(1, read(page, "#a").position(), "positions count from 1");
    }

    // ---- Live regions ----

    private static List<String> announced(Page page) {
        page.frame();
        return page.input.narration().announcements().stream()
                .map(a -> (a.interrupt() ? "!" : "") + a.text()).toList();
    }

    @Test
    void aPoliteRegionReadsItsNewText() {
        Page page = load("""
                <p id=line aria-live="polite">Greetings, stranger!</p>
                <p id=still>Not live</p>""");
        assertEquals(List.of("Greetings, stranger!"), announced(page), "a page opens reading its regions");
        assertEquals(List.of(), announced(page), "nothing changed");
        page.run("document.getElementById('line').textContent = 'Have you done what I asked?'");
        page.run("document.getElementById('still').textContent = 'Changed'");
        assertEquals(List.of("Have you done what I asked?"), announced(page));
        page.run("var l = document.getElementById('line'); l.textContent = 'One'; l.textContent = 'Two';");
        assertEquals(List.of("Two"), announced(page), "changes in one frame are read once");
        page.run("document.getElementById('line').textContent = ''");
        assertEquals(List.of(), announced(page), "emptied: nothing to read");
        page.run("document.getElementById('line').textContent = 'Two'");
        assertEquals(List.of("Two"), announced(page), "the same text again after it was gone");
    }

    @Test
    void assertiveRegionsAndAlertsInterrupt() {
        Page page = load("""
                <div id=alert role=alert></div><div id=loud aria-live=assertive></div>
                <div id=status role=status></div><div id=quiet role=alert aria-live=off></div>""");
        assertEquals(List.of(), announced(page));
        page.run("['alert', 'loud', 'status', 'quiet'].forEach(id => document.getElementById(id).textContent = id)");
        assertEquals(List.of("!alert", "!loud", "status"), announced(page), "aria-live=off silences a role");
    }

    @Test
    void aLogReadsOnlyItsNewEntries() {
        Page page = load("""
                <style>.line { display: block } .speaker { display: block }</style>
                <div id=log role=log>
                  <div class=line><b class=speaker>Emperor Cualius</b>Greetings, stranger!</div>
                  <div class=line><b class=speaker>You</b>About the letter</div>
                </div>""");
        assertEquals(List.of("You. About the letter"), announced(page), "a log opens reading its last entry");
        page.run("""
                var log = document.getElementById('log');
                log.insertAdjacentHTML('beforeend', '<div class=line><b class=speaker>Emperor Cualius</b>Then go.</div>');
                log.insertAdjacentHTML('beforeend', '<div class=line><b class=speaker>You</b>I will.</div>');""");
        assertEquals(List.of("Emperor Cualius. Then go. You. I will."), announced(page), "speaker. what they say");
        page.run("""
                var log = document.getElementById('log');
                log.firstElementChild.remove();
                log.insertAdjacentHTML('beforeend', '<div class=line>Farewell.</div>');""");
        assertEquals(List.of("Farewell."), announced(page), "entries scrolling off the top are not news");
        page.run("document.getElementById('log').lastElementChild.textContent = 'Farewell, stranger.'");
        assertEquals(List.of("Farewell, stranger."), announced(page), "a changed last entry is read again");
    }

    @Test
    void regionsSpeakForThemselves() {
        Page page = load("""
                <div id=outer aria-live=polite>Score <span id=inner role=status>0</span> points
                  <span aria-hidden="true">(art)</span></div>
                <div aria-hidden="true"><p id=hidden aria-live=polite>Shh</p></div>""");
        assertEquals(List.of("Score points", "0"), announced(page), "the inner region is not part of the outer");
        page.run("document.getElementById('inner').textContent = '5'");
        assertEquals(List.of("5"), announced(page));
        page.run("document.getElementById('hidden').textContent = 'Still hidden'");
        assertEquals(List.of(), announced(page));
        page.run("document.body.insertAdjacentHTML('beforeend', '<p role=alert>Quest updated</p>')");
        assertEquals(List.of("!Quest updated"), announced(page), "a region that appears reads its text");
    }

    @Test
    void hiddenTextIsLeftOut() {
        Page page = load("""
                <style>.gone { display: none }</style>
                <p id=line aria-live=polite>Greetings<span class=gone>!!!</span></p>""");
        assertEquals(List.of("Greetings"), announced(page));
        page.run("document.querySelector('.gone').className = ''");
        assertEquals(List.of("Greetings!!!"), announced(page), "showing text changes what is read");
    }
}
