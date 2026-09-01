/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Reflective access to EDT's native form render state without a bundle dependency on EDT internals.
 */
public final class NativeRenderModeProbe
{
    private static final String NATIVE_RENDER_SERVICE_CLASS =
        "com._1c.g5.v8.dt.form.layout.service.NativeRenderService"; //$NON-NLS-1$
    private static final String IS_NATIVE_RENDER_METHOD = "isNativeRender"; //$NON-NLS-1$
    private static final String IS_BUFFERED_RENDER_METHOD = "isBufferedRender"; //$NON-NLS-1$
    private static final String BUFFERED_FLAG_FIELD = "NATIVE_FORM_BUFFERED_LAYOUT_RENDER"; //$NON-NLS-1$
    private static final String BUFFERED_PROPERTY_NAME = "nativeFormBufferedLayoutRender"; //$NON-NLS-1$
    private static final AtomicBoolean PROBE_FAILURE_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean STARTUP_MODES_CAPTURE_ATTEMPTED = new AtomicBoolean();

    private static volatile NativeRenderMode startupNativeRenderMode = NativeRenderMode.UNKNOWN;
    private static volatile NativeRenderMode startupBufferedRenderMode = NativeRenderMode.UNKNOWN;
    private static volatile boolean startupModesCaptured;

    /** Tri-state result of probing EDT's native form render mode. */
    public enum NativeRenderMode
    {
        ON,
        OFF,
        UNKNOWN
    }

    private NativeRenderModeProbe()
    {
        // Utility class
    }

    /**
     * Captures EDT's native and buffered form render modes once, before this plugin can mutate
     * either mode at runtime: the only mutator is {@link #ensureBufferedNativeRenderMode()},
     * reached from a tool call, which cannot run before this bundle has activated.
     *
     * <p>The bundle is lazily activated, so this is the state at MCP ACTIVATION rather than at
     * JVM start. The distinction is only theoretical for these two flags: both are private
     * static finals initialised from system properties at class-init, and no EDT code writes
     * them afterwards - the sole reflective writer is ours. Reflection failures are logged once
     * and leave both snapshots as {@link NativeRenderMode#UNKNOWN}; they never escape into
     * plugin startup.
     */
    public static void captureStartupModes()
    {
        if (!STARTUP_MODES_CAPTURE_ATTEMPTED.compareAndSet(false, true))
        {
            return;
        }

        try
        {
            NativeRenderServiceAccessor accessor = createAccessor();
            NativeRenderMode nativeMode = toMode(accessor.isNativeRender());
            NativeRenderMode bufferedMode = toMode(accessor.isBufferedRender());
            startupNativeRenderMode = nativeMode;
            startupBufferedRenderMode = bufferedMode;
            startupModesCaptured = true;
        }
        catch (Exception | LinkageError e)
        {
            logProbeFailure(
                "Could not capture EDT form render modes at startup via NativeRenderService", e); //$NON-NLS-1$
        }
    }

    /**
     * Returns the native form render mode captured at plugin startup.
     *
     * @return the startup mode, or {@code UNKNOWN} when capture was not attempted or failed
     */
    public static NativeRenderMode getStartupNativeRenderMode()
    {
        return startupModesCaptured ? startupNativeRenderMode : NativeRenderMode.UNKNOWN;
    }

    /**
     * Returns the buffered form render mode captured at plugin startup.
     *
     * @return the startup mode, or {@code UNKNOWN} when capture was not attempted or failed
     */
    public static NativeRenderMode getStartupBufferedRenderMode()
    {
        return startupModesCaptured ? startupBufferedRenderMode : NativeRenderMode.UNKNOWN;
    }

    /**
     * Returns EDT's effective native form render mode. Reflection failures are logged once and
     * reported as {@link NativeRenderMode#UNKNOWN}; they never escape into a caller.
     *
     * @return the effective native render mode, or {@code UNKNOWN} when EDT's internal API changed
     */
    public static NativeRenderMode getNativeRenderMode()
    {
        try
        {
            return toMode(createAccessor().isNativeRender());
        }
        catch (Exception | LinkageError e)
        {
            logProbeFailure("Could not determine EDT native form render mode via NativeRenderService", e); //$NON-NLS-1$
            return NativeRenderMode.UNKNOWN;
        }
    }

    /**
     * Returns EDT's effective buffered form render mode. Reflection failures are logged once and
     * reported as {@link NativeRenderMode#UNKNOWN}; they never escape into a caller.
     *
     * @return the effective buffered render mode, or {@code UNKNOWN} when EDT's internal API changed
     */
    public static NativeRenderMode getBufferedRenderMode()
    {
        try
        {
            return toMode(createAccessor().isBufferedRender());
        }
        catch (Exception | LinkageError e)
        {
            logProbeFailure("Could not determine EDT buffered form render mode via NativeRenderService", e); //$NON-NLS-1$
            return NativeRenderMode.UNKNOWN;
        }
    }

    /**
     * Preserves the screenshot helper's existing buffered-native-render setup using the same
     * reflective accessor as {@link #getNativeRenderMode()}.
     */
    static void ensureBufferedNativeRenderMode()
    {
        try
        {
            System.setProperty(BUFFERED_PROPERTY_NAME, "true"); //$NON-NLS-1$

            NativeRenderServiceAccessor accessor = createAccessor();
            boolean nativeRender = accessor.isNativeRender();
            boolean bufferedBefore = accessor.isBufferedRender();

            if (nativeRender && !bufferedBefore)
            {
                forceBufferedRenderFlag(accessor.getServiceClass());
            }

            boolean bufferedAfter = accessor.isBufferedRender();
            if (!bufferedAfter)
            {
                Activator.logWarning("Buffered native render is still disabled. " + //$NON-NLS-1$
                    "Restart EDT with VM option: -DnativeFormBufferedLayoutRender=true"); //$NON-NLS-1$
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("Failed to ensure buffered native render mode: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static NativeRenderServiceAccessor createAccessor() throws ReflectiveOperationException
    {
        Class<?> serviceClass = Class.forName(NATIVE_RENDER_SERVICE_CLASS);
        Method isNativeRenderMethod = serviceClass.getMethod(IS_NATIVE_RENDER_METHOD);
        Method isBufferedRenderMethod = serviceClass.getMethod(IS_BUFFERED_RENDER_METHOD);
        return new NativeRenderServiceAccessor(serviceClass, isNativeRenderMethod, isBufferedRenderMethod);
    }

    private static NativeRenderMode toMode(boolean enabled)
    {
        return enabled ? NativeRenderMode.ON : NativeRenderMode.OFF;
    }

    private static void logProbeFailure(String message, Throwable failure)
    {
        if (PROBE_FAILURE_LOGGED.compareAndSet(false, true))
        {
            Activator.logWarning(message + ": " + failure.getClass().getSimpleName() //$NON-NLS-1$
                + ": " + failure.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Forces the static buffered-render flag field to {@code true}, first via a plain reflective
     * field set and, if that fails, via {@link ReflectionUtils#forceStaticFinalBoolean}.
     */
    private static void forceBufferedRenderFlag(Class<?> serviceClass)
    {
        try
        {
            Field bufferedField = serviceClass.getDeclaredField(BUFFERED_FLAG_FIELD);
            bufferedField.setAccessible(true); // NOSONAR reflective access is required (EDT internals)
            bufferedField.setBoolean(null, true); // NOSONAR reflective access is required (EDT internals)
        }
        catch (Exception e)
        {
            ReflectionUtils.forceStaticFinalBoolean(serviceClass, BUFFERED_FLAG_FIELD, true);
        }
    }

    private static final class NativeRenderServiceAccessor
    {
        private final Class<?> serviceClass;
        private final Method isNativeRenderMethod;
        private final Method isBufferedRenderMethod;

        NativeRenderServiceAccessor(Class<?> serviceClass, Method isNativeRenderMethod,
            Method isBufferedRenderMethod)
        {
            this.serviceClass = serviceClass;
            this.isNativeRenderMethod = isNativeRenderMethod;
            this.isBufferedRenderMethod = isBufferedRenderMethod;
        }

        Class<?> getServiceClass()
        {
            return serviceClass;
        }

        boolean isNativeRender() throws ReflectiveOperationException
        {
            return invokeBoolean(isNativeRenderMethod);
        }

        boolean isBufferedRender() throws ReflectiveOperationException
        {
            return invokeBoolean(isBufferedRenderMethod);
        }

        private static boolean invokeBoolean(Method method) throws ReflectiveOperationException
        {
            Object value = method.invoke(null);
            if (!(value instanceof Boolean))
            {
                throw new IllegalStateException(method.getName() + " did not return a boolean"); //$NON-NLS-1$
            }
            return (Boolean)value;
        }
    }
}
