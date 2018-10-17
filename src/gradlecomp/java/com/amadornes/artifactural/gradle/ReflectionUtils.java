package com.amadornes.artifactural.gradle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.UnaryOperator;

public class ReflectionUtils {

    @SuppressWarnings("unchecked")
    public static <T> void alter(Object target, String name, UnaryOperator<T> operator) {
        try {
            int idx = name.lastIndexOf('.');
            if (idx != -1) {
                target = drillField(target, name.substring(0, idx));
                if (target == null) throw new IllegalStateException("Could not find field '" + name + "'");
                name = name.substring(idx + 1);
            }
            Field f = findField(target.getClass(), name);
            if (f == null) throw new IllegalStateException("Could not find '" + name + "'");
            f.set(target, operator.apply((T)f.get(target)));
        } catch (IllegalAccessException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static Object drillField(Object obj, String path) {
        for (String name : path.split("\\.")) {
            if (obj == null) return null;
            Field f = findField(obj.getClass(), name);
            if (f == null) return null;
            try {
                obj = f.get(obj);
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return obj;
    }

    private static Field findField(Class<?> clazz, String name) {
        while (clazz != Object.class) {
            for (Field f : clazz.getDeclaredFields()) {
                if (f.getName().equals(name)) {
                    f.setAccessible(true);
                    return f;
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    /**
     * Invokes a method (can be private).
     */
    @SuppressWarnings("unchecked")
    public static <T> T invoke(Object target, Class<?> type, String name, Object... args) {
        try {
            Method method = type.getDeclaredMethod(name);
            method.setAccessible(true);
            return (T) method.invoke(target, args);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T get(Object target, String name) {
        try {
            int idx = name.lastIndexOf('.');
            if (idx != -1) {
                target = drillField(target, name.substring(0, idx));
                if (target == null) throw new IllegalStateException("Could not find field '" + name + "'");
                name = name.substring(idx + 1);
            }
            Field f = findField(target.getClass(), name);
            if (f == null) throw new IllegalStateException("Could not find '" + name + "'");
            return (T)f.get(target);
        } catch (IllegalAccessException ex) {
            throw new RuntimeException(ex);
        }
    }
}
