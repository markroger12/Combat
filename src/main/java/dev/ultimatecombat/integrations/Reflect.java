package dev.ultimatecombat.integrations;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small reflection helper for integrations with plugins whose API is not available at compile
 * time. Methods are resolved by name and argument count/types against the runtime classes, and
 * cached. Failures surface as {@link IllegalStateException} so the calling hook can disable itself.
 */
final class Reflect {

    private static final Map<String, Method> CACHE = new ConcurrentHashMap<>();

    private Reflect() {
    }

    static Class<?> type(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Class not found: " + name, e);
        }
    }

    static boolean present(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /**
     * Invokes a public instance method.
     */
    static Object call(Object target, String name, Object... args) {
        if (target == null) {
            throw new IllegalStateException("Cannot call " + name + " on null");
        }
        return invoke(find(target.getClass(), name, false, args), target, args);
    }

    /**
     * Invokes a public static method.
     */
    static Object callStatic(Class<?> type, String name, Object... args) {
        return invoke(find(type, name, true, args), null, args);
    }

    static Object staticField(Class<?> type, String name) {
        try {
            return type.getField(name).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Field not found: " + type.getName() + "." + name, e);
        }
    }

    static Object field(Object target, String name) {
        try {
            return target.getClass().getField(name).get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Field not found: " + target.getClass().getName() + "." + name, e);
        }
    }

    private static Object invoke(Method method, Object target, Object[] args) {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(method.getName() + " failed: " + cause, cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access " + method, e);
        }
    }

    private static Method find(Class<?> type, String name, boolean wantStatic, Object[] args) {
        StringBuilder key = new StringBuilder(type.getName()).append('#').append(name);
        for (Object a : args) {
            key.append(',').append(a == null ? "null" : a.getClass().getName());
        }
        return CACHE.computeIfAbsent(key.toString(), k -> {
            for (Method m : type.getMethods()) {
                if (!m.getName().equals(name) || Modifier.isStatic(m.getModifiers()) != wantStatic
                        || m.getParameterCount() != args.length) {
                    continue;
                }
                if (compatible(m.getParameterTypes(), args)) {
                    m.setAccessible(true);
                    return m;
                }
            }
            throw new IllegalStateException("Method not found: " + type.getName() + "." + name + " with " + args.length + " argument(s)");
        });
    }

    private static boolean compatible(Class<?>[] params, Object[] args) {
        for (int i = 0; i < params.length; i++) {
            Object a = args[i];
            Class<?> p = wrap(params[i]);
            if (a == null) {
                if (params[i].isPrimitive()) {
                    return false;
                }
            } else if (!p.isInstance(a)) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> wrap(Class<?> c) {
        if (!c.isPrimitive()) {
            return c;
        }
        if (c == boolean.class) return Boolean.class;
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == double.class) return Double.class;
        if (c == float.class) return Float.class;
        if (c == short.class) return Short.class;
        if (c == byte.class) return Byte.class;
        if (c == char.class) return Character.class;
        return Void.class;
    }
}
