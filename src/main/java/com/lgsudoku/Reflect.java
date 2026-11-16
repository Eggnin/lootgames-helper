package com.lgsudoku;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tiny reflection helper shared by the sudoku and minesweeper bridges.
 *
 * <p>
 * LootGames' own classes are never obfuscated (only Minecraft classes are), so member names are
 * stable across GTNH builds. Everything is cached per {@code Class#member} pair.
 */
final class Reflect {

    private static final Map<String, Method> METHODS = new ConcurrentHashMap<String, Method>();
    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<String, Field>();
    private static final Map<String, Boolean> CLASSES = new ConcurrentHashMap<String, Boolean>();

    private Reflect() {}

    /** @return the class, or {@code null} when it is not on the classpath (mod not installed). */
    static Class<?> findClass(String name) {
        Boolean known = CLASSES.get(name);
        if (known == null) {
            try {
                Class.forName(name, false, Reflect.class.getClassLoader());
                known = Boolean.TRUE;
            } catch (Throwable t) {
                known = Boolean.FALSE;
            }
            CLASSES.put(name, known);
        }
        return known ? load(name) : null;
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, Reflect.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    static Object call(Object target, String name) {
        if (target == null) {
            return null;
        }
        Class<?> type = target.getClass();
        String key = type.getName() + '#' + name;
        Method method = METHODS.get(key);
        if (method == null) {
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Method found = c.getDeclaredMethod(name);
                    found.setAccessible(true);
                    method = found;
                    break;
                } catch (NoSuchMethodException ignored) {}
            }
            if (method == null) {
                return null;
            }
            METHODS.put(key, method);
        }
        try {
            return method.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    static Object read(Object target, String name) {
        if (target == null) {
            return null;
        }
        Class<?> type = target.getClass();
        String key = type.getName() + '#' + name;
        Field field = FIELDS.get(key);
        if (field == null) {
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field found = c.getDeclaredField(name);
                    found.setAccessible(true);
                    field = found;
                    break;
                } catch (NoSuchFieldException ignored) {}
            }
            if (field == null) {
                return null;
            }
            FIELDS.put(key, field);
        }
        try {
            return field.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    static int toInt(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    static boolean toBool(Object value) {
        return value instanceof Boolean && (Boolean) value;
    }
}
