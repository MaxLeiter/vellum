# Narration

What Minecraft's narrator reads from a page. [All docs](../index.md)


With Minecraft's narrator on (Ctrl+B), a Vellum screen narrates as a vanilla screen does. When it opens, the narrator
reads the screen's title. Then, after a key or button press (200 ms) or once the pointer has rested (750 ms), it
reads the focused element, or else the one under the pointer, in the words vanilla uses for its widgets:
"Reply 1: About the letter button. Left click to activate". It reads only what changed since it last spoke.

The title is the page's `<title>`. A script that sets `document.title` changes it, and `Screen.getTitle()` returns
it too. A page without one is "Vellum" (a container screen keeps its menu's title). A link to another page in the same
screen reads the new page's title.

The element under the pointer is read when it is a control, a link, a slot, a tab stop or an `<item tooltip>`, or
when it has a `title`, `title-json` or `aria-label`. Pointing at text inside such an element reads the element.
Plain text and decoration are not read, and neither is anything past an element with an empty `title`.

What an element is called, its name, is the first of these that says something:

1. `aria-label`;
2. the text of the elements `aria-labelledby` names (ids, separated by spaces);
3. an image's `alt`, a button input's `value`, the item of an `<item>` or `<slot>`, a control's `<label>`;
4. its text content, cut to about 100 characters (not for fields, images and slots);
5. its own `title`, or the text of its `title-json`;
6. a text field's `placeholder`.

After the name the narrator reads the text of the elements `aria-describedby` names, else the title that applies to
the element when it is not the name. A button with a title reads its label, then its tooltip, as vanilla reads a
widget's tooltip. Anything inside `aria-hidden="true"` is never read.

| Element | Read as |
|---|---|
| `<button>`, `<summary>`, button inputs, `role="button"` | "Done button" |
| `<a href>`, `role="link"` | "Map link" |
| `<input type=checkbox>`, `role="checkbox"` or `"switch"` with `aria-checked` | "Checkbox: Show hints: ON" |
| `<input type=radio>`, `role="radio"` with `aria-checked` | "Radio button: Easy: OFF" |
| `<input type=range>`, `role="slider"` with `aria-valuetext` or `aria-valuenow` | "Volume: 40 slider" |
| text inputs, `<textarea>`, `role="textbox"` | "Name edit box: Steve" (a password's text is not read) |
| `<select>` | "Difficulty: Hard button" |
| `role="tab"` | "Quests tab. Selected tab 2 out of 3", in vanilla's order (a title is read before any position): its place among the tabs of its `role="tablist"` (or its parent), or `aria-posinset` and `aria-setsize` |
| `<item>`, `<slot>` | "Item: Iron Sword"; a labelled slot "Fuel. Item: Coal". An empty slot nothing labels is not read. |
| anything else | its name |

Controls add how to use them, as vanilla's do ("Left click to activate", "Press Enter to activate", "Drag the slider
to change its value"); a checkbox says "Press Space to check" when focused, since Space toggles it. A disabled control
says nothing about its use. When the page has more than one tab stop, the narrator says where the element is among
them ("Screen element 2 out of 5").

Text is read as it is laid out: whitespace collapsed, hidden text left out (`aria-hidden`, `display: none`,
`visibility: hidden`, scripts), an image read as its `alt` and an item as its name. Each block (a block, flex or grid
box, or a line ended by `<br>`) is a sentence of its own, so a speaker's name above their words reads as "speaker.
what they say".

## Live regions

An element with `aria-live="polite"`, `role="status"` or `role="log"` reads its text when it changes, after whatever
the narrator is saying. One with `aria-live="assertive"` or `role="alert"` cuts the narrator off; when several
announce in the same frame, the first cuts in and the rest follow it, assertive ones first. `aria-live="off"`
silences a role. A log reads only what it gained: its new children, so a conversation where each line is one element
holding the speaker and what they say reads each new line as "speaker. what they say". A region inside another speaks
for itself and is left out of the outer one's text. Changes made in the same frame are read once, as the text they
end with.

Unlike a browser, a page also reads its regions when it opens, after its title (a log, its last entry), and so does a
region added later. A conversation then opens with what was last said.

```html
<head>
  <title>Emperor Cualius</title>
  <style>.speaker { display: block }</style>
</head>
<body>
  <div class="log" role="log">
    <p class="line"><b class="speaker">Emperor Cualius</b>Greetings, stranger! Have you done what I asked?</p>
  </div>
  <button aria-label="Reply 1: About the letter">1 About “Word to Candacona”…</button>
  <div class="flourish" aria-hidden="true">~ ~ ~</div>
</body>
```

The narrator says "Emperor Cualius" as the screen opens, then "Emperor Cualius. Greetings, stranger! Have you done
what I asked?". With the pointer resting on the reply it says "Reply 1: About the letter button. Left click to
activate". When a script appends `<p class="line"><b class="speaker">Emperor Cualius</b>Then take it to Candacona,
and quickly.</p>` to the log, it says "Emperor Cualius. Then take it to Candacona, and quickly.". The flourish is never
read.

A HUD overlay reads its live regions too, also in the HUD layer. Over a screen it is interactive over, it reads the
element under the pointer itself once the pointer has rested for 750 ms, since the screen under it knows nothing of
the overlay. It never reads a focused element: keys stay with the screen. The previewer prints what the narrator would
be given with `--narrate` (preview/README.md).

Text a script reveals a few letters at a time is read a few letters at a time; put the whole line in at once and
reveal it with CSS. A slot whose item changes is read again when the pointer next rests on it, but a live region
holding it does not announce the change.
