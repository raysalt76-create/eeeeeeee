package interstellarTikiTiks.apexx.client.util;

import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.module.Module;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class ModuleAccess {

    private ModuleAccess() {
    }

    public static Module getModule(String moduleName) {
        if (moduleName == null || moduleName.isBlank()) {
            return null;
        }
        try {
            Apexx apexx = Apexx.getInstance();
            if (apexx == null || apexx.getModuleManager() == null) {
                return null;
            }
            return apexx.getModuleManager().getModule(moduleName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static <T extends Module> T getModule(Class<T> moduleType) {
        if (moduleType == null) {
            return null;
        }
        try {
            Apexx apexx = Apexx.getInstance();
            if (apexx == null || apexx.getModuleManager() == null) {
                return null;
            }
            return apexx.getModuleManager().getModule(moduleType);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static <T extends Module> T getEnabledModule(Class<T> moduleType) {
        T module = getModule(moduleType);
        if (module == null || !module.isEnabled()) {
            return null;
        }
        return module;
    }

    public static <T> T getHook(Class<T> hookType) {
        if (hookType == null) {
            return null;
        }
        try {
            Apexx apexx = Apexx.getInstance();
            if (apexx == null || apexx.getModuleManager() == null) {
                return null;
            }
            return apexx.getModuleManager().getHook(hookType);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static <T> T getEnabledHook(Class<T> hookType) {
        if (hookType == null) {
            return null;
        }
        try {
            Apexx apexx = Apexx.getInstance();
            if (apexx == null || apexx.getModuleManager() == null) {
                return null;
            }
            return apexx.getModuleManager().getEnabledHook(hookType);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static <T> java.util.List<T> getEnabledHooks(Class<T> hookType) {
        if (hookType == null) {
            return java.util.List.of();
        }
        try {
            Apexx apexx = Apexx.getInstance();
            if (apexx == null || apexx.getModuleManager() == null) {
                return java.util.List.of();
            }
            return apexx.getModuleManager().getEnabledHooks(hookType);
        } catch (Throwable ignored) {
            return java.util.List.of();
        }
    }

    public static boolean isEnabled(String moduleName) {
        Module module = getModule(moduleName);
        return module != null && module.isEnabled();
    }

    public static Object readField(Module module, String fieldName) {
        if (module == null || fieldName == null || fieldName.isBlank()) {
            return null;
        }
        try {
            Field field;
            try {
                field = module.getClass().getField(fieldName);
            } catch (NoSuchFieldException ignored) {
                field = module.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
            }
            return field.get(module);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean readBooleanField(Module module, String fieldName, boolean fallback) {
        Object value = readField(module, fieldName);
        if (value instanceof Boolean b) {
            return b;
        }
        return fallback;
    }

    public static Object invoke(Module module, String methodName, Class<?>[] parameterTypes, Object... args) {
        if (module == null || methodName == null || methodName.isBlank()) {
            return null;
        }
        return invokeOn(module, methodName, parameterTypes, args);
    }

    public static Object invokeOn(Object target, String methodName, Class<?>[] parameterTypes, Object... args) {
        if (target == null || methodName == null || methodName.isBlank()) {
            return null;
        }
        try {
            Method method = resolveMethod(target.getClass(), methodName, parameterTypes);
            if (method == null) {
                return null;
            }
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Error error) throw error;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Module callback failed: " + methodName, cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Module callback inaccessible: " + methodName, failure);
        }
    }

    public static boolean invokeBoolean(Module module, String methodName, boolean fallback, Class<?>[] parameterTypes,
            Object... args) {
        Object value = invoke(module, methodName, parameterTypes, args);
        if (value instanceof Boolean b) {
            return b;
        }
        return fallback;
    }

    public static double invokeDouble(Module module, String methodName, double fallback, Class<?>[] parameterTypes,
            Object... args) {
        Object value = invoke(module, methodName, parameterTypes, args);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return fallback;
    }

    public static String invokeString(Module module, String methodName, String fallback, Class<?>[] parameterTypes,
            Object... args) {
        Object value = invoke(module, methodName, parameterTypes, args);
        if (value instanceof String str) {
            return str;
        }
        return fallback;
    }

    public static boolean invokeBooleanOn(Object target, String methodName, boolean fallback, Class<?>[] parameterTypes,
            Object... args) {
        Object value = invokeOn(target, methodName, parameterTypes, args);
        if (value instanceof Boolean b) {
            return b;
        }
        return fallback;
    }

    public static Object invokeStatic(Module module, String className, String methodName, Class<?>[] parameterTypes,
            Object... args) {
        if (className == null || className.isBlank() || methodName == null || methodName.isBlank()) {
            return null;
        }
        try {
            Class<?> clazz = loadClass(module, className);
            if (clazz == null) {
                return null;
            }
            Method method = resolveMethod(clazz, methodName, parameterTypes);
            if (method == null) {
                return null;
            }
            return method.invoke(null, args);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Error error) throw error;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Static module callback failed: " + methodName, cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Static module callback inaccessible: " + methodName, failure);
        }
    }

    public static boolean invokeStaticBoolean(Module module, String className, String methodName, boolean fallback,
            Class<?>[] parameterTypes, Object... args) {
        Object value = invokeStatic(module, className, methodName, parameterTypes, args);
        if (value instanceof Boolean b) {
            return b;
        }
        return fallback;
    }

    public static Class<?> loadClass(Module module, String className) {
        if (className == null || className.isBlank()) {
            return null;
        }
        ClassLoader moduleLoader = module != null ? module.getClass().getClassLoader() : null;
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader fallbackLoader = ModuleAccess.class.getClassLoader();

        Class<?> loaded = tryLoadClass(className, moduleLoader);
        if (loaded != null) {
            return loaded;
        }
        loaded = tryLoadClass(className, contextLoader);
        if (loaded != null) {
            return loaded;
        }
        return tryLoadClass(className, fallbackLoader);
    }

    private static Class<?> tryLoadClass(String className, ClassLoader loader) {
        if (loader == null) {
            return null;
        }
        try {
            return Class.forName(className, false, loader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * No-argument lookups (e.g. the per-tick getLicenseKey poll) resolved once per class and name, hits and
     * misses alike, instead of a getMethod copy -- or a thrown NoSuchMethodException -- on every call.
     */
    private static final ClassValue<java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<Method>>> NO_ARG_METHODS =
            new ClassValue<>() {
                @Override
                protected java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<Method>> computeValue(Class<?> type) {
                    return new java.util.concurrent.ConcurrentHashMap<>();
                }
            };

    private static Method resolveMethod(Class<?> clazz, String methodName, Class<?>[] parameterTypes) {
        if (parameterTypes == null || parameterTypes.length == 0) {
            java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<Method>> cache = NO_ARG_METHODS.get(clazz);
            java.util.Optional<Method> cached = cache.get(methodName);
            if (cached == null) {
                cached = java.util.Optional.ofNullable(lookupMethod(clazz, methodName, new Class<?>[0]));
                cache.put(methodName, cached);
            }
            return cached.orElse(null);
        }
        return lookupMethod(clazz, methodName, parameterTypes);
    }

    private static Method lookupMethod(Class<?> clazz, String methodName, Class<?>[] safeParams) {
        try {
            return clazz.getMethod(methodName, safeParams);
        } catch (NoSuchMethodException ignored) {
            try {
                Method method = clazz.getDeclaredMethod(methodName, safeParams);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignoredAgain) {
                return null;
            }
        }
    }
}
