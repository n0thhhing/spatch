package spatch;

import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import org.robolectric.shadows.ShadowLooper;

/** Reads and calls private members of SpatchUi and of the screen objects it delegates to. */
final class Reflect {
    private Reflect() {}

    static Object get(Object target, String name) throws Exception {
        if (target instanceof Class) return field((Class<?>) target, name).get(null);
        for (Object owner : owners(target)) {
            Field field = find(owner.getClass(), name);
            if (field != null) { field.setAccessible(true); return field.get(owner); }
        }
        throw new NoSuchFieldException(name);
    }

    static void set(Object target, String name, Object value) throws Exception {
        for (Object owner : owners(target)) {
            Field field = find(owner.getClass(), name);
            if (field != null) { field.setAccessible(true); field.set(owner, value); return; }
        }
        throw new NoSuchFieldException(name);
    }

    static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        for (Object owner : owners(target)) {
            try {
                Method method = owner.getClass().getDeclaredMethod(name, types);
                method.setAccessible(true);
                return method.invoke(owner, args);
            } catch (NoSuchMethodException next) {
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Field find(Class<?> type, String name) {
        try { return type.getDeclaredField(name); } catch (NoSuchFieldException none) { return null; }
    }

    private static java.util.List<Object> owners(Object target) throws Exception {
        java.util.List<Object> owners = new java.util.ArrayList<>();
        owners.add(target);
        if (target instanceof SpatchUi) {
            for (Field field : SpatchUi.class.getDeclaredFields()) {
                if (field.getType().getPackage() == SpatchUi.class.getPackage() && !field.getType().isMemberClass() && !field.getType().equals(SpatchUi.class) && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    Object value = field.get(target);
                    if (value != null) owners.add(value);
                }
            }
        }
        return owners;
    }

    static void settle() throws Exception {
        settle(20, 25);
    }

    static void settle(int iterations, int millis) throws Exception {
        for (int i = 0; i < iterations; i++) {
            Thread.sleep(25);
            ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(millis));
        }
    }

    static <T> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            T found = find(((ViewGroup) view).getChildAt(i), type);
            if (found != null) return found;
        }
        return null;
    }
}
