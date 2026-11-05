/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

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

    /** Looks up an optional public API once, including inherited interface methods. */
    public static Method publicMethodOrNull(Class<?> type, String name, Class<?>... parameterTypes)
    {
        try
        {
            return type.getMethod(name, parameterTypes);
        }
        catch (NoSuchMethodException e)
        {
            return null;
        }
    }

    /** Invokes a resolved API while preserving the platform failure across the reflection boundary. */
    public static Object invoke(Method method, Object target, Object... arguments) throws Exception
    {
        try
        {
            return method.invoke(target, arguments);
        }
        catch (InvocationTargetException e)
        {
            if (e.getCause() instanceof Exception cause)
            {
                throw cause;
            }
            if (e.getCause() instanceof Error cause)
            {
                throw cause;
            }
            throw new IllegalStateException("EDT API invocation failed: " + method.getName(), e.getCause());
        }
    }

    /**
     * Invokes a no-argument public method on the target object.
     *
     * @param target the object to invoke the method on
     * @param methodName the method name
     * @return the invocation result
     * @throws Exception if invocation fails
     */
    public static Object invokeMethod(Object target, String methodName) throws Exception // NOSONAR propagates checked exceptions across the reflective boundary by design
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
    public static Object getFieldValue(Object target, String fieldName) throws Exception // NOSONAR propagates checked exceptions across the reflective boundary by design
    {
        Class<?> type = target.getClass();
        while (type != null)
        {
            try
            {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true); // NOSONAR reflective access is required (EDT internals, no Require-Bundle)
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
}
