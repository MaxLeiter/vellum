package dev.vellum.engine.layout;

/**
 * A set of adjoining vertical margins that collapse together (CSS 2.1 §8.3.1): the result is the largest positive
 * margin plus the most negative one.
 */
record MarginSet(float positive, float negative) {
    static final MarginSet EMPTY = new MarginSet(0, 0);

    MarginSet with(float margin) {
        return margin >= 0 ? (margin > positive ? new MarginSet(margin, negative) : this)
                : (margin < negative ? new MarginSet(positive, margin) : this);
    }

    MarginSet with(MarginSet other) {
        if (other.positive <= positive && other.negative >= negative) return this;
        return new MarginSet(Math.max(positive, other.positive), Math.min(negative, other.negative));
    }

    float resolve() {
        return positive + negative;
    }
}
