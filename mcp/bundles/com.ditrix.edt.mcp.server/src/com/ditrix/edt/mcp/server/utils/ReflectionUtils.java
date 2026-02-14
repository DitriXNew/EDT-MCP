/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Utility class for common reflection operations used in tools that interact
 * with internal EDT APIs via reflection.
 */
public final class ReflectionUtils
{
    private ReflectionUtils()
    {
        // Utility class
    }

    /**
     * Invokes a no-argument public method on the target object.
     *
     * @param target the object to invoke the method on
     * @param methodName the method name
     * @return the invocation result
     * @throws Exception if invocation fails
     */
    public static Object invokeMethod(Object target, String methodName) throws Exception
    {
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }

    /**
     * Gets a field value by walking up the class hierarchy.
     *
     * @param target the object to read the field from
     * @param fieldName the field name
     * @return the field value, or {@code null} if not found
     * @throws Exception if access fails
     */
    public static Object getFieldValue(Object target, String fieldName) throws Exception
    {
        Class<?> type = target.getClass();
        while (type != null)
        {
            try
            {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            }
            catch (NoSuchFieldException ex)
            {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    /**
     * Finds a method by name and parameter types, searching up the class hierarchy.
     *
     * @param clazz the class to start searching from
     * @param name the method name
     * @param paramTypes the parameter types
     * @return the method, or {@code null} if not found
     */
    public static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes)
    {
        Class<?> type = clazz;
        while (type != null)
        {
            try
            {
                return type.getDeclaredMethod(name, paramTypes);
            }
            catch (NoSuchMethodException e)
            {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    /**
     * Attempts to force-set a static final boolean field using {@code sun.misc.Unsafe}.
     * This is a last-resort mechanism for patching internal platform flags.
     *
     * @param targetClass the class containing the field
     * @param fieldName the static final boolean field name
     * @param value the value to set
     * @return {@code true} if successful
     */
    public static boolean forceStaticFinalBoolean(Class<?> targetClass, String fieldName, boolean value)
    {
        try
        {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe"); //$NON-NLS-1$
            Field theUnsafeField = unsafeClass.getDeclaredField("theUnsafe"); //$NON-NLS-1$
            theUnsafeField.setAccessible(true);
            Object unsafe = theUnsafeField.get(null);

            Field targetField = targetClass.getDeclaredField(fieldName);
            targetField.setAccessible(true);

            Method staticFieldBase = unsafeClass.getMethod("staticFieldBase", Field.class); //$NON-NLS-1$
            Method staticFieldOffset = unsafeClass.getMethod("staticFieldOffset", Field.class); //$NON-NLS-1$
            Method putBooleanVolatile =
                unsafeClass.getMethod("putBooleanVolatile", Object.class, long.class, boolean.class); //$NON-NLS-1$

            Object fieldBase = staticFieldBase.invoke(unsafe, targetField);
            long fieldOffset = (Long)staticFieldOffset.invoke(unsafe, targetField);
            putBooleanVolatile.invoke(unsafe, fieldBase, fieldOffset, value);

            return true;
        }
        catch (Exception e)
        {
            Activator.logWarning("Unsafe patch for static final boolean failed: " + e.getMessage()); //$NON-NLS-1$
            return false;
        }
    }
}
