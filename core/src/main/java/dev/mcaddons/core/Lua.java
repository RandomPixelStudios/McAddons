package dev.mcaddons.core;

import org.squiddev.cobalt.Constants;
import org.squiddev.cobalt.LuaError;
import org.squiddev.cobalt.LuaTable;
import org.squiddev.cobalt.LuaValue;
import org.squiddev.cobalt.ValueFactory;
import org.squiddev.cobalt.Varargs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Java &lt;-&gt; Lua conversion for plain data (no Java objects ever cross into Lua). */
public final class Lua {
    private Lua() {}

    public static LuaValue of(Object v) {
        if (v == null) return Constants.NIL;
        if (v instanceof LuaValue l) return l;
        if (v instanceof String s) return utf8(s);
        if (v instanceof Boolean b) return ValueFactory.valueOf(b);
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte)
            return ValueFactory.valueOf(((Number) v).longValue());
        if (v instanceof Number n) return ValueFactory.valueOf(n.doubleValue());
        if (v instanceof Map<?, ?> m) {
            LuaTable t = new LuaTable();
            m.forEach((k, val) -> t.rawset(String.valueOf(k), of(val)));
            return t;
        }
        if (v instanceof List<?> list) {
            LuaTable t = new LuaTable();
            for (int i = 0; i < list.size(); i++) t.rawset(i + 1, of(list.get(i)));
            return t;
        }
        if (v instanceof Host.PlayerInfo p) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.name()); m.put("uuid", p.uuid()); m.put("dimension", p.dimension());
            m.put("x", p.x()); m.put("y", p.y()); m.put("z", p.z());
            m.put("yaw", p.yaw()); m.put("pitch", p.pitch());
            m.put("health", p.health()); m.put("max_health", p.maxHealth()); m.put("food", p.food());
            m.put("gamemode", p.gamemode()); m.put("held_item", p.heldItem()); m.put("op", p.op());
            return of(m);
        }
        return ValueFactory.valueOf(v.toString());
    }

    /** Converts plain Lua data back to Java (tables become Map or List). Depth-limited. */
    public static Object toJava(LuaValue v, int depth) throws LuaError {
        if (depth > 16) throw new LuaError("table nested too deeply");
        if (v.isNil()) return null;
        if (v instanceof org.squiddev.cobalt.LuaBoolean) return v.toBoolean();
        if (v instanceof org.squiddev.cobalt.LuaNumber) {
            double d = v.toDouble();
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? (Object) (long) d : (Object) d;
        }
        if (v instanceof org.squiddev.cobalt.LuaString ls) return str(ls);
        if (v instanceof LuaTable t) {
            int n = t.length();
            if (n > 0) {
                List<Object> list = new ArrayList<>();
                for (int i = 1; i <= n; i++) list.add(toJava(t.rawget(i), depth + 1));
                return list;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            LuaValue k = Constants.NIL;
            while (true) {
                Varargs next = t.next(k);
                k = next.first();
                if (k.isNil()) break;
                map.put(k instanceof org.squiddev.cobalt.LuaString ks ? str(ks) : k.toString(), toJava(next.arg(2), depth + 1));
            }
            return map;
        }
        throw new LuaError("cannot store value of type " + v.typeName());
    }

    /** Lua strings are byte strings; plugins are UTF-8 source files. */
    public static org.squiddev.cobalt.LuaString utf8(String s) {
        return ValueFactory.valueOf(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static String str(org.squiddev.cobalt.LuaString s) {
        byte[] b = new byte[s.length()];
        s.copyTo(b, 0);
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Like checkString() but decodes UTF-8. */
    public static String checkStr(LuaValue v) throws LuaError {
        return str(v.checkLuaString());
    }

    public static String optStr(LuaValue v, String def) throws LuaError {
        return v.isNil() ? def : checkStr(v);
    }

    public static Varargs args(Object... values) {
        LuaValue[] out = new LuaValue[values.length];
        for (int i = 0; i < values.length; i++) out[i] = of(values[i]);
        return ValueFactory.varargsOf(out);
    }
}
