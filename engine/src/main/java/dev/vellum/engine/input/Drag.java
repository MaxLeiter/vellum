package dev.vellum.engine.input;

/**
 * A pointer drag that captured the pointer on mousedown (range thumb, scrollbar thumb, text selection): it receives
 * every pointer position until the button is released, including re-sent positions each tick so drags auto-scroll.
 */
@FunctionalInterface
interface Drag {
    /** A no-op drag that only holds the capture (a scrollbar track click). */
    Drag HOLD = (x, y) -> {};

    /** The pointer is at (x, y) in viewport px. */
    void move(float x, float y);

    /** The button was released. */
    default void end() {}
}
