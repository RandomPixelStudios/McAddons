package dev.mcaddons.core;

/** Drawing commands produced by plugin "hud" handlers; the adapter turns them into real draws. */
public sealed interface HudOp {
    record Text(int x, int y, String text, int color, boolean shadow) implements HudOp {}
    record Rect(int x, int y, int w, int h, int color) implements HudOp {}
    record Item(int x, int y, String item) implements HudOp {}
}
