package dev.mcaddons.core;

/** Drawing commands produced by plugin "hud" handlers; the adapter turns them into real draws. */
public sealed interface HudOp {
    record Text(int x, int y, String text, int color, boolean shadow) implements HudOp {}
    record Rect(int x, int y, int w, int h, int color) implements HudOp {}
    record Item(int x, int y, String item) implements HudOp {}
    // world-space (mc.render.*)
    record Line(double x1, double y1, double z1, double x2, double y2, double z2, int color, float width) implements HudOp {}
    record Box(double x1, double y1, double z1, double x2, double y2, double z2, int color, int fill) implements HudOp {}
    record WorldText(String text, double x, double y, double z, int color, float scale) implements HudOp {}
}
