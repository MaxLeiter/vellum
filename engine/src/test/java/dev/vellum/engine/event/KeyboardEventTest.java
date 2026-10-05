package dev.vellum.engine.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeyboardEventTest {
    private static int keyCode(String code) {
        return new KeyboardEvent("keydown", "", code, false, Modifiers.NONE).keyCode;
    }

    @Test
    void keyCodeIsTheLegacyCodeOfThePhysicalKey() {
        assertEquals(65, keyCode("KeyA"));
        assertEquals(90, keyCode("KeyZ"));
        assertEquals(48, keyCode("Digit0"));
        assertEquals(105, keyCode("Numpad9"));
        assertEquals(116, keyCode("F5"));
        assertEquals(123, keyCode("F12"));
        assertEquals(13, keyCode("Enter"));
        assertEquals(32, keyCode("Space"));
        assertEquals(16, keyCode("ShiftRight"));
        assertEquals(191, keyCode("Slash"));
        assertEquals(0, keyCode("Unidentified"));
        assertEquals(0, keyCode("F13"));
        assertEquals(0, keyCode(""));
    }
}
