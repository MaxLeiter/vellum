package dev.vellum.engine.script;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.testing.TestHost;

/**
 * A scripted test page: parses HTML with the Rhino runtime (running its scripts and DOMContentLoaded), and reads
 * script values back through {@code console.log}. Time is driven with {@link #advance}; frames (restyle, layout)
 * are never run.
 */
final class Page {
    final TestHost host = new TestHost();
    final Document doc;

    Page(String html) {
        this(html, true);
    }

    /** With {@code failOnError} false, errors (also those while loading) are collected in {@code host.errors}. */
    Page(String html, boolean failOnError) {
        host.failOnError = failOnError;
        host.scripting = Scripting.rhino();
        doc = Document.parse(host, "test:page.html", html);
    }

    /** A page whose body is {@code body} followed by a script. */
    static Page withScript(String body, String script) {
        return new Page("<body>" + body + "<script>" + script + "</script></body>");
    }

    /** Runs {@code code} as a classic script. */
    void run(String code) {
        doc.scripts().evaluate(code, "test:eval.js");
    }

    /** {@code String(expression)} evaluated in the page. */
    String eval(String expression) {
        int before = host.logs.size();
        run("console.log(String(" + expression + "))");
        if (host.logs.size() == before) throw new AssertionError("No value for " + expression + "; errors: " + host.errors);
        return host.logs.get(before).substring("INFO: ".length());
    }

    String errors() {
        return String.join("\n", host.errors);
    }

    Element byId(String id) {
        return doc.getElementById(id);
    }

    /** Runs timers and animation frames due at {@code ms}. */
    void advance(double ms) {
        doc.scheduler().run(ms);
    }
}
