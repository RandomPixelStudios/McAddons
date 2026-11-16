package dev.mcaddons.fabric.client;

import dev.mcaddons.core.HudOp;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Draws mc.render.* operations with Minecraft's gizmo system (per tick, depth-tested). */
final class WorldRender {
    private WorldRender() {}

    static void draw(Minecraft mc, List<HudOp> ops) {
        if (ops.isEmpty()) return;
        try (var c = mc.collectPerTickGizmos()) {
            for (HudOp op : ops) {
                switch (op) {
                    case HudOp.Line l -> {
                        var g = Gizmos.line(new Vec3(l.x1(), l.y1(), l.z1()), new Vec3(l.x2(), l.y2(), l.z2()), l.color(), l.width());
                        if (l.onTop()) g.setAlwaysOnTop();
                    }
                    case HudOp.Box b -> {
                        var g = Gizmos.cuboid(new AABB(b.x1(), b.y1(), b.z1(), b.x2(), b.y2(), b.z2()),
                                b.fill() != 0 ? GizmoStyle.strokeAndFill(b.color(), 2f, b.fill()) : GizmoStyle.stroke(b.color(), 2f));
                        if (b.onTop()) g.setAlwaysOnTop();
                    }
                    case HudOp.WorldText t -> Gizmos.billboardText(t.text(), new Vec3(t.x(), t.y(), t.z()),
                            TextGizmo.Style.forColorAndCentered(t.color()).withScale(t.scale()));
                    default -> {}
                }
            }
        }
    }
}
