package com.aicode.studio.termux.shared.terminal.io.extrakeys;

import android.view.KeyEvent;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ExtraKeysConstants {

    /** Defines the repetitive keys that can be passed to {@link ExtraKeysView#setRepetitiveKeys(List)}. */
    public static List<String> PRIMARY_REPETITIVE_KEYS = Arrays.asList(
        "UP", "DOWN", "LEFT", "RIGHT",
        "BKSP", "DEL",
        "PGUP", "PGDN");



    /** Defines the {@link KeyEvent} for common keys. */
    public static Map<String, Integer> PRIMARY_KEY_CODES_FOR_STRINGS = new HashMap<String, Integer>() {{
        put("SPACE", KeyEvent.KEYCODE_SPACE);
        put("ESC", KeyEvent.KEYCODE_ESCAPE);
        put("TAB", KeyEvent.KEYCODE_TAB);
        put("HOME", KeyEvent.KEYCODE_MOVE_HOME);
        put("END", KeyEvent.KEYCODE_MOVE_END);
        put("PGUP", KeyEvent.KEYCODE_PAGE_UP);
        put("PGDN", KeyEvent.KEYCODE_PAGE_DOWN);
        put("INS", KeyEvent.KEYCODE_INSERT);
        put("DEL", KeyEvent.KEYCODE_FORWARD_DEL);
        put("BKSP", KeyEvent.KEYCODE_DEL);
        put("UP", KeyEvent.KEYCODE_DPAD_UP);
        put("LEFT", KeyEvent.KEYCODE_DPAD_LEFT);
        put("RIGHT", KeyEvent.KEYCODE_DPAD_RIGHT);
        put("DOWN", KeyEvent.KEYCODE_DPAD_DOWN);
        put("ENTER", KeyEvent.KEYCODE_ENTER);
        put("F1", KeyEvent.KEYCODE_F1);
        put("F2", KeyEvent.KEYCODE_F2);
        put("F3", KeyEvent.KEYCODE_F3);
        put("F4", KeyEvent.KEYCODE_F4);
        put("F5", KeyEvent.KEYCODE_F5);
        put("F6", KeyEvent.KEYCODE_F6);
        put("F7", KeyEvent.KEYCODE_F7);
        put("F8", KeyEvent.KEYCODE_F8);
        put("F9", KeyEvent.KEYCODE_F9);
        put("F10", KeyEvent.KEYCODE_F10);
        put("F11", KeyEvent.KEYCODE_F11);
        put("F12", KeyEvent.KEYCODE_F12);
    }};



    /**
     * HashMap that implements Python dict.get(key, default) function.
     * Default java.util .get(key) is then the same as .get(key, null);
     */
    static class CleverMap<K,V> extends HashMap<K,V> {
        V get(K key, V defaultValue) {
            if (containsKey(key))
                return get(key);
            else
                return defaultValue;
        }
    }

    public static class ExtraKeyDisplayMap extends CleverMap<String, String> {}



    /*
     * Multiple maps are available to quickly change
     * the style of the keys.
     */

    public static class EXTRA_KEY_DISPLAY_MAPS {
        /**
         * Keys are displayed in a natural looking way, like "\u2192" for "RIGHT"
         */
        public static final ExtraKeyDisplayMap CLASSIC_ARROWS_DISPLAY = new ExtraKeyDisplayMap() {{
            // classic arrow keys
            put("LEFT",  "\u2190"); // U+2190 LEFTWARDS ARROW
            put("RIGHT", "\u2192"); // U+2192 RIGHTWARDS ARROW
            put("UP",    "\u2191"); // U+2191 UPWARDS ARROW
            put("DOWN",  "\u2193"); // U+2193 DOWNWARDS ARROW
        }};

        public static final ExtraKeyDisplayMap WELL_KNOWN_CHARACTERS_DISPLAY = new ExtraKeyDisplayMap() {{
            // well known characters // https://en.wikipedia.org/wiki/{Enter_key, Tab_key, Delete_key}
            put("ENTER",    "\u21b2"); // U+21B2 DOWNWARDS ARROW WITH TIP LEFTWARDS
            put("TAB",      "\u21b9"); // U+21B9 LEFTWARDS ARROW TO BAR OVER RIGHTWARDS ARROW TO BAR
            put("BKSP",     "\u232b"); // U+232B ERASE TO THE LEFT
            put("DEL",      "\u2326"); // U+2326 ERASE TO THE RIGHT
            put("DRAWER",   "\u2630"); // U+2630 TRIGRAM FOR HEAVEN
            put("KEYBOARD", "\u2328"); // U+2328 KEYBOARD
            put("PASTE",    "\u2398"); // U+2398
        }};

        public static final ExtraKeyDisplayMap LESS_KNOWN_CHARACTERS_DISPLAY = new ExtraKeyDisplayMap() {{
            // https://en.wikipedia.org/wiki/{Home_key, End_key, Page_Up_and_Page_Down_keys}
            // home key can mean "goto the beginning of line" or "goto first page" depending on context, hence the diagonal
            put("HOME", "\u21f1"); // U+21F1 NORTH WEST ARROW TO CORNER
            put("END",  "\u21f2"); // U+21F2 SOUTH EAST ARROW TO CORNER
            put("PGUP", "\u21d1"); // U+21D1 UPWARDS DOUBLE ARROW
            put("PGDN", "\u21d3"); // U+21D3 DOWNWARDS DOUBLE ARROW
        }};

        public static final ExtraKeyDisplayMap ARROW_TRIANGLE_VARIATION_DISPLAY = new ExtraKeyDisplayMap() {{
            // alternative to classic arrow keys
            put("LEFT",  "\u25c0 "); // U+25C0 BLACK LEFT-POINTING TRIANGLE
            put("RIGHT", "\u25b6");  // U+25B6 BLACK RIGHT-POINTING TRIANGLE
            put("UP",    "\u25b2");  // U+25B2 BLACK UP-POINTING TRIANGLE
            put("DOWN",  "\u25bc");  // U+25BC BLACK DOWN-POINTING TRIANGLE
        }};

        public static final ExtraKeyDisplayMap NOT_KNOWN_ISO_CHARACTERS = new ExtraKeyDisplayMap() {{
            // Control chars that are more clear as text // https://en.wikipedia.org/wiki/{Function_key, Alt_key, Control_key, Esc_key}
            // put("FN", "FN"); // no ISO character exists
            put("CTRL", "\u25c7"); // U+25C7 WHITE DIAMOND
            put("ALT",  "\u2387"); // U+2387 ALTERNATIVE KEY SYMBOL
            put("ESC",  "\u238b"); // U+238B BROKEN CIRCLE WITH NORTHWEST ARROW
        }};

        public static final ExtraKeyDisplayMap NICER_LOOKING_DISPLAY = new ExtraKeyDisplayMap() {{
            // nicer looking for most cases
            put("-", "\u2015"); // U+2015 HORIZONTAL BAR
        }};

        /**
         * Full Iso
         */
        public static final ExtraKeyDisplayMap FULL_ISO_CHAR_DISPLAY = new ExtraKeyDisplayMap() {{
            putAll(CLASSIC_ARROWS_DISPLAY);
            putAll(WELL_KNOWN_CHARACTERS_DISPLAY);
            putAll(LESS_KNOWN_CHARACTERS_DISPLAY); // NEW
            putAll(NICER_LOOKING_DISPLAY);
            putAll(NOT_KNOWN_ISO_CHARACTERS); // NEW
        }};

        /**
         * Only arrows
         */
        public static final ExtraKeyDisplayMap ARROWS_ONLY_CHAR_DISPLAY = new ExtraKeyDisplayMap() {{
            putAll(CLASSIC_ARROWS_DISPLAY);
            // putAll(wellKnownCharactersDisplay); // REMOVED
            // putAll(lessKnownCharactersDisplay); // REMOVED
            putAll(NICER_LOOKING_DISPLAY);
        }};

        /**
         * Classic symbols and less known symbols
         */
        public static final ExtraKeyDisplayMap LOTS_OF_ARROWS_CHAR_DISPLAY = new ExtraKeyDisplayMap() {{
            putAll(CLASSIC_ARROWS_DISPLAY);
            putAll(WELL_KNOWN_CHARACTERS_DISPLAY);
            putAll(LESS_KNOWN_CHARACTERS_DISPLAY); // NEW
            putAll(NICER_LOOKING_DISPLAY);
        }};

        /**
         * Some classic symbols everybody knows
         */
        public static final ExtraKeyDisplayMap DEFAULT_CHAR_DISPLAY = new ExtraKeyDisplayMap() {{
            putAll(CLASSIC_ARROWS_DISPLAY);
            putAll(WELL_KNOWN_CHARACTERS_DISPLAY);
            putAll(NICER_LOOKING_DISPLAY);
            // all other characters are displayed as themselves
        }};

    }



    /**
     * Aliases for the keys
     */
    public static final ExtraKeyDisplayMap CONTROL_CHARS_ALIASES = new ExtraKeyDisplayMap() {{
        put("ESCAPE", "ESC");
        put("CONTROL", "CTRL");
        put("SHFT", "SHIFT");
        put("RETURN", "ENTER"); // Technically different keys, but most applications won't see the difference
        put("FUNCTION", "FN");
        // no alias for ALT

        // Directions are sometimes written as first and last letter for brevety
        put("LT", "LEFT");
        put("RT", "RIGHT");
        put("DN", "DOWN");
        // put("UP", "UP"); well, "UP" is already two letters

        put("PAGEUP", "PGUP");
        put("PAGE_UP", "PGUP");
        put("PAGE UP", "PGUP");
        put("PAGE-UP", "PGUP");

        // no alias for HOME
        // no alias for END

        put("PAGEDOWN", "PGDN");
        put("PAGE_DOWN", "PGDN");
        put("PAGE-DOWN", "PGDN");

        put("DELETE", "DEL");
        put("BACKSPACE", "BKSP");

        // easier for writing in termux.properties
        put("BACKSLASH", "\\");
        put("QUOTE", "\"");
        put("APOSTROPHE", "'");
    }};

}
