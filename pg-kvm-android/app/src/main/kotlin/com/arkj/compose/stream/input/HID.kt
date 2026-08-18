package com.arkj.compose.stream.input

/**
 * USB HID keycodes and modifier bitmask constants.
 * Mirrors the iOS HID.swift definitions — must match ESP32 firmware exactly.
 */
object HID {

    // MARK: - Modifier bitmask

    const val MOD_LEFT_CTRL: UByte = 0x01u
    const val MOD_LEFT_SHIFT: UByte = 0x02u
    const val MOD_LEFT_ALT: UByte = 0x04u
    const val MOD_LEFT_GUI: UByte = 0x08u
    const val MOD_RIGHT_CTRL: UByte = 0x10u
    const val MOD_RIGHT_SHIFT: UByte = 0x20u
    const val MOD_RIGHT_ALT: UByte = 0x40u
    const val MOD_RIGHT_GUI: UByte = 0x80u

    // MARK: - USB HID keycodes

    const val KEY_A: UByte = 0x04u
    const val KEY_B: UByte = 0x05u
    const val KEY_C: UByte = 0x06u
    const val KEY_D: UByte = 0x07u
    const val KEY_E: UByte = 0x08u
    const val KEY_F: UByte = 0x09u
    const val KEY_G: UByte = 0x0Au
    const val KEY_H: UByte = 0x0Bu
    const val KEY_I: UByte = 0x0Cu
    const val KEY_J: UByte = 0x0Du
    const val KEY_K: UByte = 0x0Eu
    const val KEY_L: UByte = 0x0Fu
    const val KEY_M: UByte = 0x10u
    const val KEY_N: UByte = 0x11u
    const val KEY_O: UByte = 0x12u
    const val KEY_P: UByte = 0x13u
    const val KEY_Q: UByte = 0x14u
    const val KEY_R: UByte = 0x15u
    const val KEY_S: UByte = 0x16u
    const val KEY_T: UByte = 0x17u
    const val KEY_U: UByte = 0x18u
    const val KEY_V: UByte = 0x19u
    const val KEY_W: UByte = 0x1Au
    const val KEY_X: UByte = 0x1Bu
    const val KEY_Y: UByte = 0x1Cu
    const val KEY_Z: UByte = 0x1Du

    const val KEY_1: UByte = 0x1Eu
    const val KEY_2: UByte = 0x1Fu
    const val KEY_3: UByte = 0x20u
    const val KEY_4: UByte = 0x21u
    const val KEY_5: UByte = 0x22u
    const val KEY_6: UByte = 0x23u
    const val KEY_7: UByte = 0x24u
    const val KEY_8: UByte = 0x25u
    const val KEY_9: UByte = 0x26u
    const val KEY_0: UByte = 0x27u

    const val KEY_ENTER: UByte = 0x28u
    const val KEY_ESCAPE: UByte = 0x29u
    const val KEY_BACKSPACE: UByte = 0x2Au
    const val KEY_TAB: UByte = 0x2Bu
    const val KEY_SPACE: UByte = 0x2Cu

    const val KEY_MINUS: UByte = 0x2Du
    const val KEY_EQUAL: UByte = 0x2Eu
    const val KEY_LEFT_BRACKET: UByte = 0x2Fu
    const val KEY_RIGHT_BRACKET: UByte = 0x30u
    const val KEY_BACKSLASH: UByte = 0x31u
    const val KEY_SEMICOLON: UByte = 0x33u
    const val KEY_QUOTE: UByte = 0x34u
    const val KEY_GRAVE: UByte = 0x35u
    const val KEY_COMMA: UByte = 0x36u
    const val KEY_PERIOD: UByte = 0x37u
    const val KEY_SLASH: UByte = 0x38u

    const val KEY_CAPS_LOCK: UByte = 0x39u

    const val KEY_F1: UByte = 0x3Au
    const val KEY_F2: UByte = 0x3Bu
    const val KEY_F3: UByte = 0x3Cu
    const val KEY_F4: UByte = 0x3Du
    const val KEY_F5: UByte = 0x3Eu
    const val KEY_F6: UByte = 0x3Fu
    const val KEY_F7: UByte = 0x40u
    const val KEY_F8: UByte = 0x41u
    const val KEY_F9: UByte = 0x42u
    const val KEY_F10: UByte = 0x43u
    const val KEY_F11: UByte = 0x44u
    const val KEY_F12: UByte = 0x45u

    const val KEY_PRINT_SCREEN: UByte = 0x46u
    const val KEY_SCROLL_LOCK: UByte = 0x47u
    const val KEY_PAUSE: UByte = 0x48u
    const val KEY_INSERT: UByte = 0x49u
    const val KEY_HOME: UByte = 0x4Au
    const val KEY_PAGE_UP: UByte = 0x4Bu
    const val KEY_DELETE: UByte = 0x4Cu
    const val KEY_END: UByte = 0x4Du
    const val KEY_PAGE_DOWN: UByte = 0x4Eu

    const val KEY_RIGHT: UByte = 0x4Fu
    const val KEY_LEFT: UByte = 0x50u
    const val KEY_DOWN: UByte = 0x51u
    const val KEY_UP: UByte = 0x52u

    // MARK: - Mouse button bitmask

    const val MOUSE_LEFT: UByte = 0x01u
    const val MOUSE_RIGHT: UByte = 0x02u
    const val MOUSE_MIDDLE: UByte = 0x04u

    // MARK: - Character → HID mapping

    private val letterKeys = listOf(
        KEY_A, KEY_B, KEY_C, KEY_D, KEY_E, KEY_F, KEY_G, KEY_H, KEY_I, KEY_J,
        KEY_K, KEY_L, KEY_M, KEY_N, KEY_O, KEY_P, KEY_Q, KEY_R, KEY_S, KEY_T,
        KEY_U, KEY_V, KEY_W, KEY_X, KEY_Y, KEY_Z
    )

    private val digitKeys = listOf(
        KEY_1, KEY_2, KEY_3, KEY_4, KEY_5, KEY_6, KEY_7, KEY_8, KEY_9, KEY_0
    )

    private val shiftDigitMap = mapOf(
        '!' to KEY_1, '@' to KEY_2, '#' to KEY_3, '$' to KEY_4,
        '%' to KEY_5, '^' to KEY_6, '&' to KEY_7, '*' to KEY_8,
        '(' to KEY_9, ')' to KEY_0
    )

    private val punctuationMap: Map<Char, Pair<UByte, UByte>> = mapOf(
        '-' to (0x00u.toUByte() to KEY_MINUS),
        '_' to (MOD_LEFT_SHIFT to KEY_MINUS),
        '=' to (0x00u.toUByte() to KEY_EQUAL),
        '+' to (MOD_LEFT_SHIFT to KEY_EQUAL),
        '[' to (0x00u.toUByte() to KEY_LEFT_BRACKET),
        '{' to (MOD_LEFT_SHIFT to KEY_LEFT_BRACKET),
        ']' to (0x00u.toUByte() to KEY_RIGHT_BRACKET),
        '}' to (MOD_LEFT_SHIFT to KEY_RIGHT_BRACKET),
        '\\' to (0x00u.toUByte() to KEY_BACKSLASH),
        '|' to (MOD_LEFT_SHIFT to KEY_BACKSLASH),
        ';' to (0x00u.toUByte() to KEY_SEMICOLON),
        ':' to (MOD_LEFT_SHIFT to KEY_SEMICOLON),
        '\'' to (0x00u.toUByte() to KEY_QUOTE),
        '"' to (MOD_LEFT_SHIFT to KEY_QUOTE),
        '`' to (0x00u.toUByte() to KEY_GRAVE),
        '~' to (MOD_LEFT_SHIFT to KEY_GRAVE),
        ',' to (0x00u.toUByte() to KEY_COMMA),
        '<' to (MOD_LEFT_SHIFT to KEY_COMMA),
        '.' to (0x00u.toUByte() to KEY_PERIOD),
        '>' to (MOD_LEFT_SHIFT to KEY_PERIOD),
        '/' to (0x00u.toUByte() to KEY_SLASH),
        '?' to (MOD_LEFT_SHIFT to KEY_SLASH),
    )

    /**
     * Represents a single keyboard action: a modifier mask and a HID keycode.
     */
    data class HIDCommand(val modifiers: UByte, val keycode: UByte)

    /**
     * Map a character to its HID command (modifiers + keycode).
     * Returns null for unmappable characters (e.g. emoji, non-US characters).
     */
    fun mapCharacterToHID(char: Char): HIDCommand? {
        return when {
            char.isLetter() -> {
                val idx = char.uppercaseChar() - 'A'
                if (idx in 0..25) {
                    val keycode = letterKeys[idx]
                    val modifiers = if (char.isUpperCase()) MOD_LEFT_SHIFT else 0x00u
                    HIDCommand(modifiers, keycode)
                } else null
            }
            char.isDigit() -> {
                val idx = char - '0'
                val keycode = digitKeys[idx]
                HIDCommand(0x00u, keycode)
            }
            char == ' ' -> HIDCommand(0x00u, KEY_SPACE)
            char == '\t' -> HIDCommand(0x00u, KEY_TAB)
            char == '\n' -> HIDCommand(0x00u, KEY_ENTER)
            char in shiftDigitMap -> {
                val keycode = shiftDigitMap[char]!!
                HIDCommand(MOD_LEFT_SHIFT, keycode)
            }
            char in punctuationMap -> {
                val (mod, key) = punctuationMap[char]!!
                HIDCommand(mod, key)
            }
            else -> null
        }
    }

    /**
     * Map a JS KeyboardEvent code string to a HID keycode.
     * Returns null for unmappable keys.
     */
    fun mapCodeToHID(code: String): UByte? {
        return when (code) {
            // Letters
            "KeyA" -> KEY_A; "KeyB" -> KEY_B; "KeyC" -> KEY_C; "KeyD" -> KEY_D
            "KeyE" -> KEY_E; "KeyF" -> KEY_F; "KeyG" -> KEY_G; "KeyH" -> KEY_H
            "KeyI" -> KEY_I; "KeyJ" -> KEY_J; "KeyK" -> KEY_K; "KeyL" -> KEY_L
            "KeyM" -> KEY_M; "KeyN" -> KEY_N; "KeyO" -> KEY_O; "KeyP" -> KEY_P
            "KeyQ" -> KEY_Q; "KeyR" -> KEY_R; "KeyS" -> KEY_S; "KeyT" -> KEY_T
            "KeyU" -> KEY_U; "KeyV" -> KEY_V; "KeyW" -> KEY_W; "KeyX" -> KEY_X
            "KeyY" -> KEY_Y; "KeyZ" -> KEY_Z
            // Digits
            "Digit1" -> KEY_1; "Digit2" -> KEY_2; "Digit3" -> KEY_3; "Digit4" -> KEY_4
            "Digit5" -> KEY_5; "Digit6" -> KEY_6; "Digit7" -> KEY_7; "Digit8" -> KEY_8
            "Digit9" -> KEY_9; "Digit0" -> KEY_0
            // Special keys
            "Enter" -> KEY_ENTER; "Escape" -> KEY_ESCAPE; "Backspace" -> KEY_BACKSPACE
            "Tab" -> KEY_TAB; "Space" -> KEY_SPACE
            // Punctuation
            "Minus" -> KEY_MINUS; "Equal" -> KEY_EQUAL
            "BracketLeft" -> KEY_LEFT_BRACKET; "BracketRight" -> KEY_RIGHT_BRACKET
            "Backslash" -> KEY_BACKSLASH; "Semicolon" -> KEY_SEMICOLON
            "Quote" -> KEY_QUOTE; "Backquote" -> KEY_GRAVE
            "Comma" -> KEY_COMMA; "Period" -> KEY_PERIOD; "Slash" -> KEY_SLASH
            // F-keys
            "F1" -> KEY_F1; "F2" -> KEY_F2; "F3" -> KEY_F3; "F4" -> KEY_F4
            "F5" -> KEY_F5; "F6" -> KEY_F6; "F7" -> KEY_F7; "F8" -> KEY_F8
            "F9" -> KEY_F9; "F10" -> KEY_F10; "F11" -> KEY_F11; "F12" -> KEY_F12
            // Navigation
            "ArrowRight" -> KEY_RIGHT; "ArrowLeft" -> KEY_LEFT
            "ArrowDown" -> KEY_DOWN; "ArrowUp" -> KEY_UP
            "Home" -> KEY_HOME; "End" -> KEY_END
            "PageUp" -> KEY_PAGE_UP; "PageDown" -> KEY_PAGE_DOWN
            "Insert" -> KEY_INSERT; "Delete" -> KEY_DELETE
            // Modifier keys (as regular keys)
            "CapsLock" -> KEY_CAPS_LOCK
            "PrintScreen" -> KEY_PRINT_SCREEN
            "ScrollLock" -> KEY_SCROLL_LOCK
            "Pause" -> KEY_PAUSE
            else -> null
        }
    }

    /**
     * Map a JS KeyboardEvent key string to modifier bitmask.
     */
    fun mapKeyToModifier(key: String): UByte? {
        return when (key) {
            "Control" -> MOD_LEFT_CTRL
            "Shift" -> MOD_LEFT_SHIFT
            "Alt" -> MOD_LEFT_ALT
            "Meta" -> MOD_LEFT_GUI
            else -> null
        }
    }
}