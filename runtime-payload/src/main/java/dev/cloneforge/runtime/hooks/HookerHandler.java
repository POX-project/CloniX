package dev.cloneforge.runtime.hooks;

/**
 * Handler installed for a given LSPlant hook target. Implementations receive every invocation
 * of the hooked method and may delegate to the original by calling
 * {@link Hooker#invokeBackup(Object[])}.
 *
 * Argument contract (LSPlant v6.4):
 * <ul>
 *   <li>static target:  {@code args = [a0, a1, ...]}</li>
 *   <li>instance target: {@code args = [receiver, a0, a1, ...]}</li>
 *   <li>{@link #like} describes what the hook is for; handlers may consult the
 *       {@code target} via {@link Hooker#getTarget()}.</li>
 * </ul>
 * Exceptions thrown by implementations propagate to the app code exactly as if the original
 * method body threw them; returning schedules the return value.
 */
public interface HookerHandler {

    /** Factory helper: a handler that ignores the hook and calls the original directly. */
    static HookerHandler passthrough() {
        return new HookerHandler() {
            @Override
            public Object like() { return this; }

            @Override
            public Object onHook(Hooker hooker, Object[] args) throws Throwable {
                return hooker.invokeBackup(args);
            }
        };
    }

    /** Minimal label used in logs (may return this handler itself). */
    default Object like() { return this; }

    /**
     * Handles the hook invocation.
     *
     * @param hooker the owning {@link Hooker} (use {@link Hooker#invokeBackup} to delegate)
     * @param args   invocation arguments (see contract above)
     * @return the value to return from the hooked call
     */
    Object onHook(Hooker hooker, Object[] args) throws Throwable;
}
