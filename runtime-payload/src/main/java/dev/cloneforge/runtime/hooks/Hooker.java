package dev.cloneforge.runtime.hooks;

import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/**
 * The LSPlant "hooker object" and the hook/unhook facade (LSPlant v6.4 contract).
 *
 * Upstream LSPlant is C++-only: {@code lsplant::Hook(env, target, hooker_object,
 * callback_method)} stores {@code hooker_object} in the generated {@code LSPHooker_}'s static
 * {@code hooker} field, and every invocation of the hooked method is re-dispatched as
 * {@code hooker_object.callback(Object[] args)} — where this public {@code callback(Object[])}
 * MUST be declared on the hooker object's own class (LSPlant resolves the callback by the
 * name/signature of the {@code Method} that was passed in; it verifies
 * {@code hooker_object instanceof declaring-class}).
 *
 * Argument contract (verified against LSPlant v6.4 test/{Hooker,Replacement,UnitTest}):
 * <ul>
 *   <li>static  target: {@code args = [a0, a1, ... — the target's real arguments]}</li>
 *   <li>instance target: {@code args = [<this>, a0, a1, ...]} — receiver first</li>
 *   <li>{@link #backup} is the returned original {@code Method} (entrypoint-patched by
 *       LSPlant); invoke it via {@link #invokeBackup} which handles the instance/static split
 *       and unwraps {@link InvocationTargetException}.</li>
 * </ul>
 *
 * Threading: a new {@link Hooker} instance is created per hook (LSPlant stores the hooker in
 * a static field of each generated class, so instances never collide).
 */
public final class Hooker {

    private static final String TAG = "CloneForgeRuntime";

    /** The original method LSPlant generated for this hook (call through reflection). */
    public Method backup;

    /** The target method this hook replaced (used for static/instance dispatch + unhook). */
    public final Method target;

    private final HookerHandler handler;

    Hooker(Method target, HookerHandler handler) {
        this.target = target;
        this.handler = handler;
    }

    /**
     * LSPlant callback — MUST be public, declared exactly here, signature
     * {@code Object callback(Object[])}. Handler errors never propagate into app code: on a
     * handler failure we transparently fall through to the original method.
     */
    public Object callback(Object[] args) throws Throwable {
        HookerHandler h = handler;
        if (h == null) {
            return invokeBackup(args);
        }
        try {
            return h.onHook(this, args);
        } catch (InvocationTargetException ite) {
            throw ite; // handler deliberately forwarded the original's own exception
        } catch (Throwable t) {
            Log.w(TAG, "Hook handler failed; falling through to original method", t);
            return invokeBackup(args);
        }
    }

    /** The hooked target (for {@link #unhook()} callers that did not keep the reference). */
    public Method getTarget() {
        return target;
    }

    /**
     * Invokes the original (backup) method. Handles the static/instance split
     * ({@code args[0]} is the receiver for instance targets) and unwraps
     * {@code InvocationTargetException} so app code observes the original's real exception.
     */
    public Object invokeBackup(Object[] args) throws Throwable {
        Method b = backup;
        if (b == null) {
            return null;
        }
        try {
            if (Modifier.isStatic(target.getModifiers())) {
                return b.invoke(null, args);
            }
            if (args == null || args.length == 0) {
                return b.invoke(null);
            }
            Object receiver = args[0];
            Object[] real = (args.length == 1) ? new Object[0]
                    : Arrays.copyOfRange(args, 1, args.length);
            return b.invoke(receiver, real);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            throw (cause != null) ? cause : ite;
        }
    }

    /**
     * Hooks {@code target} so every call is routed through {@code handler}. Never throws —
     * returns null on any failure (target already hooked, LSPlant unavailable, ...).
     */
    public static Hooker hook(Method target, HookerHandler handler) {
        if (target == null) {
            return null;
        }
        try {
            Hooker h = new Hooker(target, handler);
            Method callback = Hooker.class.getDeclaredMethod("callback", Object[].class);
            Object backup = LsplantNative.hook(target, h, callback);
            if (backup instanceof Method) {
                h.backup = (Method) backup;
                Log.i(TAG, "LSPlant hook installed: " + target.getDeclaringClass().getName()
                        + "#" + target.getName());
                return h;
            }
            Log.w(TAG, "LSPlant hook failed (null backup): " + target.getDeclaringClass().getName()
                    + "#" + target.getName());
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "LSPlant hook threw: " + target.getName(), t);
            return null;
        }
    }

    /**
     * Convenience: hooks every declared method with the given name (any parameter list) and
     * returns how many actually landed.
     */
    public static int hookAll(Class<?> declaringClass, String methodName, HookerHandler handler) {
        if (declaringClass == null || methodName == null) {
            return 0;
        }
        int count = 0;
        try {
            for (Method m : declaringClass.getDeclaredMethods()) {
                if (methodName.equals(m.getName())) {
                    if (hook(m, handler) != null) {
                        count++;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "hookAll(" + declaringClass.getName() + "#" + methodName + ") failed", t);
        }
        return count;
    }

    /** Removes this hook (restores the original method). Never throws. */
    public boolean unhook() {
        try {
            return target != null && LsplantNative.unhook(target);
        } catch (Throwable t) {
            Log.w(TAG, "LSPlant unhook failed for " + target, t);
            return false;
        }
    }
}
