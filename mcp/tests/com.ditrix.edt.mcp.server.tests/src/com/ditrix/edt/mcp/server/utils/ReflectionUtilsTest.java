/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.lang.reflect.Method;

import org.junit.Test;

/**
 * Tests for {@link ReflectionUtils}.
 * Verifies method invocation, field access, and method finding via reflection.
 */
public class ReflectionUtilsTest
{
    // ========== invokeMethod ==========

    @Test
    public void testInvokeMethod() throws Exception
    {
        SampleObject obj = new SampleObject("hello");
        Object result = ReflectionUtils.invokeMethod(obj, "getValue");
        assertEquals("hello", result);
    }

    @Test
    public void testInvokeMethodNoArgs() throws Exception
    {
        SampleObject obj = new SampleObject("test");
        Object result = ReflectionUtils.invokeMethod(obj, "getLength");
        assertEquals(4, result);
    }

    @Test(expected = NoSuchMethodException.class)
    public void testInvokeMethodNotFound() throws Exception
    {
        SampleObject obj = new SampleObject("x");
        ReflectionUtils.invokeMethod(obj, "nonExistentMethod");
    }

    // ========== getFieldValue ==========

    @Test
    public void testGetFieldValue() throws Exception
    {
        SampleObject obj = new SampleObject("secret");
        Object result = ReflectionUtils.getFieldValue(obj, "value");
        assertEquals("secret", result);
    }

    @Test
    public void testGetFieldValueInherited() throws Exception
    {
        ChildObject child = new ChildObject("inherited", 42);
        // Should find field from parent class
        Object result = ReflectionUtils.getFieldValue(child, "value");
        assertEquals("inherited", result);
    }

    @Test
    public void testGetFieldValueChildField() throws Exception
    {
        ChildObject child = new ChildObject("x", 99);
        Object result = ReflectionUtils.getFieldValue(child, "number");
        assertEquals(99, result);
    }

    @Test
    public void testGetFieldValueNotFound() throws Exception
    {
        SampleObject obj = new SampleObject("test");
        Object result = ReflectionUtils.getFieldValue(obj, "nonExistentField");
        assertNull(result);
    }

    // ========== findMethod ==========

    @Test
    public void testFindMethod()
    {
        Method method = ReflectionUtils.findMethod(SampleObject.class, "getValue");
        assertNotNull(method);
        assertEquals("getValue", method.getName());
    }

    @Test
    public void testFindMethodWithParams()
    {
        Method method = ReflectionUtils.findMethod(SampleObject.class, "setValue", String.class);
        assertNotNull(method);
    }

    @Test
    public void testFindMethodInherited()
    {
        Method method = ReflectionUtils.findMethod(ChildObject.class, "getValue");
        assertNotNull("Should find method in parent class", method);
    }

    @Test
    public void testFindMethodNotFound()
    {
        Method method = ReflectionUtils.findMethod(SampleObject.class, "bogus");
        assertNull(method);
    }

    @Test
    public void testFindMethodWrongParams()
    {
        Method method = ReflectionUtils.findMethod(SampleObject.class, "getValue", String.class);
        assertNull("Should not find method with wrong parameter types", method);
    }

    // ========== Test helper classes ==========

    @SuppressWarnings("unused")
    private static class SampleObject
    {
        private String value;

        SampleObject(String value)
        {
            this.value = value;
        }

        public String getValue()
        {
            return value;
        }

        public void setValue(String value)
        {
            this.value = value;
        }

        public int getLength()
        {
            return value.length();
        }
    }

    @SuppressWarnings("unused")
    private static class ChildObject extends SampleObject
    {
        private int number;

        ChildObject(String value, int number)
        {
            super(value);
            this.number = number;
        }

        public int getNumber()
        {
            return number;
        }
    }
}
