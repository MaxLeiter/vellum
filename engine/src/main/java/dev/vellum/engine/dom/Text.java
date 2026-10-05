package dev.vellum.engine.dom;

/** A text node. */
public final class Text extends Node {
    private String data;

    Text(Document ownerDocument, String data) {
        super(ownerDocument);
        this.data = data == null ? "" : data;
    }

    @Override
    public String nodeName() { return "#text"; }

    public String data() { return data; }

    public void setData(String data) {
        data = data == null ? "" : data;
        if (data.equals(this.data)) return;
        boolean emptinessFlipped = data.isEmpty() != this.data.isEmpty();
        this.data = data;
        ownerDocument.textChanged(this, emptinessFlipped);
    }

    @Override
    Node cloneShallow() {
        return new Text(ownerDocument, data);
    }

    @Override
    void collectText(StringBuilder sb) {
        sb.append(data);
    }

    @Override
    public void setTextContent(String text) {
        setData(text);
    }

    @Override
    public String toString() {
        return "#text \"" + (data.length() > 30 ? data.substring(0, 30) + "..." : data) + "\"";
    }
}
