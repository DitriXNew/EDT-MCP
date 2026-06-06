/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IVariable;
import org.junit.Test;

/**
 * Tests for {@link VariableSerializer#serializeVariable}.
 * <p>
 * The serializer works against the Eclipse debug model interfaces
 * ({@link IVariable} / {@link IValue}), which are mocked here — no live debug
 * session is needed. Covers the DTO shape for a normal value, a null value, a
 * value-read failure, the {@link VariableSerializer#MAX_VALUE_LENGTH}
 * truncation path, and the hasChildren -&gt; expandHint hint.
 */
public class VariableSerializerTest
{
    @Test
    public void testNormalVariable() throws Exception
    {
        IValue value = mock(IValue.class);
        when(value.getReferenceTypeName()).thenReturn("String"); //$NON-NLS-1$
        when(value.getValueString()).thenReturn("hello"); //$NON-NLS-1$
        when(value.hasVariables()).thenReturn(false);

        IVariable var = mock(IVariable.class);
        when(var.getName()).thenReturn("greeting"); //$NON-NLS-1$
        when(var.getValue()).thenReturn(value);

        Map<String, Object> dto = VariableSerializer.serializeVariable(var, null);

        assertEquals("greeting", dto.get("name")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("String", dto.get("type")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("hello", dto.get("value")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.FALSE, dto.get("hasChildren")); //$NON-NLS-1$
        assertFalse("no expandHint when no children", dto.containsKey("expandHint")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("not truncated", dto.containsKey("truncated")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNullNameBecomesEmptyString() throws Exception
    {
        IValue value = mock(IValue.class);
        when(value.getReferenceTypeName()).thenReturn("Number"); //$NON-NLS-1$
        when(value.getValueString()).thenReturn("1"); //$NON-NLS-1$
        when(value.hasVariables()).thenReturn(false);

        IVariable var = mock(IVariable.class);
        when(var.getName()).thenReturn(null);
        when(var.getValue()).thenReturn(value);

        Map<String, Object> dto = VariableSerializer.serializeVariable(var, null);
        assertEquals("", dto.get("name")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNullValueIsUndefined() throws Exception
    {
        IVariable var = mock(IVariable.class);
        when(var.getName()).thenReturn("x"); //$NON-NLS-1$
        when(var.getValue()).thenReturn(null);

        Map<String, Object> dto = VariableSerializer.serializeVariable(var, null);

        assertEquals("Undefined", dto.get("type")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(dto.get("value")); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, dto.get("hasChildren")); //$NON-NLS-1$
    }

    @Test
    public void testGetValueThrowsIsReportedAsError() throws Exception
    {
        IVariable var = mock(IVariable.class);
        when(var.getName()).thenReturn("boom"); //$NON-NLS-1$
        when(var.getValue()).thenThrow(new RuntimeException("no debug context")); //$NON-NLS-1$

        Map<String, Object> dto = VariableSerializer.serializeVariable(var, null);

        assertEquals("boom", dto.get("name")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("<unknown>", dto.get("type")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(dto.get("value").toString().contains("no debug context")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.FALSE, dto.get("hasChildren")); //$NON-NLS-1$
    }

    @Test
    public void testLongValueIsTruncated() throws Exception
    {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < VariableSerializer.MAX_VALUE_LENGTH + 50; i++)
        {
            big.append('a');
        }
        String full = big.toString();

        IValue value = mock(IValue.class);
        when(value.getReferenceTypeName()).thenReturn("String"); //$NON-NLS-1$
        when(value.getValueString()).thenReturn(full);
        when(value.hasVariables()).thenReturn(false);

        IVariable var = mock(IVariable.class);
        when(var.getName()).thenReturn("blob"); //$NON-NLS-1$
        when(var.getValue()).thenReturn(value);

        Map<String, Object> dto = VariableSerializer.serializeVariable(var, null);

        assertEquals(VariableSerializer.MAX_VALUE_LENGTH, dto.get("value").toString().length()); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, dto.get("truncated")); //$NON-NLS-1$
        assertEquals(full.length(), dto.get("fullLength")); //$NON-NLS-1$
    }

    @Test
    public void testSerializeFrameDoesNotProbeLazyHasVariables() throws Exception
    {
        // Regression: some 1C BSL stack frames populate their internal variable array
        // lazily; hasVariables() reads that array's length and throws an NPE
        // ("this.variables is null") if called BEFORE getVariables() realizes it.
        // serializeFrame must therefore NOT gate on hasVariables() — it must go
        // straight to getVariables() (the populating call). We simulate the platform
        // bug by making hasVariables() blow up while getVariables() returns vars.
        IValue value = mock(IValue.class);
        when(value.getReferenceTypeName()).thenReturn("Number"); //$NON-NLS-1$
        when(value.getValueString()).thenReturn("42"); //$NON-NLS-1$
        when(value.hasVariables()).thenReturn(false);

        IVariable var = mock(IVariable.class);
        when(var.getName()).thenReturn("answer"); //$NON-NLS-1$
        when(var.getValue()).thenReturn(value);

        IStackFrame frame = mock(IStackFrame.class);
        when(frame.hasVariables()).thenThrow(new NullPointerException(
            "Cannot read the array length because \"this.variables\" is null")); //$NON-NLS-1$
        when(frame.getVariables()).thenReturn(new IVariable[] {var});

        List<Map<String, Object>> out = VariableSerializer.serializeFrame(frame, null);

        assertEquals("the lazy frame's variables must be serialized, not lost to an NPE", //$NON-NLS-1$
            1, out.size());
        assertEquals("answer", out.get(0).get("name")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testSerializeFrameNullVariablesYieldsEmptyList() throws Exception
    {
        // Defensive: a frame whose getVariables() returns null (not an empty array)
        // must serialize to an empty list, never an NPE.
        IStackFrame frame = mock(IStackFrame.class);
        when(frame.getVariables()).thenReturn(null);

        List<Map<String, Object>> out = VariableSerializer.serializeFrame(frame, null);

        assertTrue("null variables array must yield an empty list", out.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testHasChildrenAddsExpandHint() throws Exception
    {
        IValue value = mock(IValue.class);
        when(value.getReferenceTypeName()).thenReturn("Structure"); //$NON-NLS-1$
        when(value.getValueString()).thenReturn("Structure"); //$NON-NLS-1$
        when(value.hasVariables()).thenReturn(true);

        IVariable var = mock(IVariable.class);
        when(var.getName()).thenReturn("data"); //$NON-NLS-1$
        when(var.getValue()).thenReturn(value);

        Map<String, Object> dto = VariableSerializer.serializeVariable(var, null);

        assertEquals(Boolean.TRUE, dto.get("hasChildren")); //$NON-NLS-1$
        assertEquals("data", dto.get("expandHint")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
