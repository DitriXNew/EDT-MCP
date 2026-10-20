/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;

import org.eclipse.swt.graphics.ImageData;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.ditrix.edt.mcp.server.utils.EditorScreenshotHelper.CaptureResult;
import com.ditrix.edt.mcp.server.utils.NativeRenderModeProbe;
import com.ditrix.edt.mcp.server.utils.NativeRenderModeProbe.NativeRenderMode;
import com.ditrix.edt.mcp.server.utils.ReflectionUtils;

/** Refusal diagnostics through the existing render gate and image-read paths, without a workbench. */
@RunWith(Parameterized.class)
public class GetFormScreenshotRenderRefusalTest
{
    private final String path;

    @Parameters(name = "{0}")
    public static Collection<Object[]> paths()
    {
        return Arrays.asList(new Object[][] {
            { "refresh" }, { "requested form" }, { "active editor" }, { "rendered without image" } //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        });
    }

    public GetFormScreenshotRenderRefusalTest(String path)
    {
        this.path = path;
    }

    @Test
    public void testBufferedOnDiagnosesTmpdirInsteadOfAddingFlag() throws Exception
    {
        try (RenderModeScope ignored = new RenderModeScope(true))
        {
            String error = refusal();

            assertFalse(error, error.contains("-DnativeFormBufferedLayoutRender=true")); //$NON-NLS-1$
            assertFalse(error, error.contains("Ensure EDT runs with buffered native render")); //$NON-NLS-1$
            assertTrue(error, error.contains("EDT could not produce the offscreen form image")); //$NON-NLS-1$
            assertTrue(error, error.contains("Windows")); //$NON-NLS-1$
            assertTrue(error, error.contains("-Djava.io.tmpdir")); //$NON-NLS-1$
            assertTrue(error, error.contains("trailing separator")); //$NON-NLS-1$
            assertTrue(error, error.contains("AccessDeniedException on dtgraphics-form.bin")); //$NON-NLS-1$
            assertTrue(error, error.contains("backslash")); //$NON-NLS-1$
            assertTrue(error, error.contains("-Djava.io.tmpdir=C:\\edt_tmp\\")); //$NON-NLS-1$
            assertTrue(error, error.contains("restart EDT")); //$NON-NLS-1$
        }
    }

    @Test
    public void testBufferedOffKeepsFlagAdviceAndExplainsDesignerSideEffect() throws Exception
    {
        try (RenderModeScope ignored = new RenderModeScope(false))
        {
            String error = refusal();

            assertTrue(error, error.contains("Ensure EDT runs with buffered native render")); //$NON-NLS-1$
            assertTrue(error, error.contains("-DnativeFormBufferedLayoutRender=true")); //$NON-NLS-1$
            assertTrue(error, error.contains("on Windows")); //$NON-NLS-1$
            assertTrue(error, error.contains("switches the designer to offscreen images")); //$NON-NLS-1$
            assertTrue(error, error.contains("gray")); //$NON-NLS-1$
            assertTrue(error, error.contains("try again")); //$NON-NLS-1$
        }
    }

    /** Keeps the existing JSON error envelope and the render-unavailable sentinels under test. */
    private String refusal() throws Exception
    {
        CaptureResult result;
        if ("refresh".equals(path) || "requested form".equals(path)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Method gate = GetFormScreenshotTool.class.getDeclaredMethod("checkRenderGate", //$NON-NLS-1$
                boolean.class, boolean.class, boolean.class, String.class);
            gate.setAccessible(true);
            result = (CaptureResult)gate.invoke(null, false, "refresh".equals(path), true, "CommonForm.Test"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        else
        {
            Method capture = GetFormScreenshotTool.class.getDeclaredMethod("captureImage", //$NON-NLS-1$
                Object.class, Object.class, boolean.class, boolean.class);
            capture.setAccessible(true);
            result = (CaptureResult)capture.invoke(null, new RepresentationWithoutImage(), new ViewerWithoutControl(),
                "rendered without image".equals(path), false); //$NON-NLS-1$
        }

        assertNotNull(result);
        assertFalse(result.isSuccess());
        JsonObject payload = JsonParser.parseString(result.getError()).getAsJsonObject();
        assertEquals("only success and error belong in a refusal", 2, payload.size()); //$NON-NLS-1$
        assertFalse(payload.get("success").getAsBoolean()); //$NON-NLS-1$
        String error = payload.get("error").getAsString(); //$NON-NLS-1$
        if ("refresh".equals(path)) //$NON-NLS-1$
        {
            assertTrue(error, error.contains("refresh=true was requested")); //$NON-NLS-1$
            assertTrue(error, error.contains("stale (pre-edit) content")); //$NON-NLS-1$
            assertTrue(error, error.contains("refresh=false")); //$NON-NLS-1$
        }
        else
        {
            assertTrue(error, error.contains("Form image data is not available")); //$NON-NLS-1$
        }
        return error;
    }

    private static final class RepresentationWithoutImage
    {
        @SuppressWarnings("unused") // invoked reflectively by readFormImageData
        private ImageData getFormImageData()
        {
            return null;
        }
    }

    public static final class ViewerWithoutControl
    {
        public Object getControl()
        {
            return null;
        }
    }

    /**
     * Changes only the build JVM's EDT flags and restores both even after a failed assertion.
     * Disabling native render for the OFF case also works on Unix, where buffering is otherwise on.
     * No render service singleton or workbench is opened.
     */
    private static final class RenderModeScope implements AutoCloseable
    {
        private final Class<?> service;
        private final String[] flags = { "NATIVE_FORM_LAYOUT_RENDER", "NATIVE_FORM_BUFFERED_LAYOUT_RENDER" }; //$NON-NLS-1$ //$NON-NLS-2$
        private final boolean[] original = new boolean[flags.length];

        RenderModeScope(boolean enabled) throws Exception
        {
            service = Class.forName("com._1c.g5.v8.dt.form.layout.service.NativeRenderService"); //$NON-NLS-1$
            for (int i = 0; i < flags.length; i++)
            {
                Field field = service.getDeclaredField(flags[i]);
                field.setAccessible(true);
                original[i] = field.getBoolean(null);
            }
            try
            {
                for (String flag : flags)
                {
                    assertTrue(flag, ReflectionUtils.forceStaticFinalBoolean(service, flag, enabled));
                }
                assertEquals(enabled ? NativeRenderMode.ON : NativeRenderMode.OFF,
                    NativeRenderModeProbe.getBufferedRenderMode());
            }
            catch (AssertionError failure)
            {
                close();
                throw failure;
            }
        }

        @Override
        public void close()
        {
            boolean restored = true;
            for (int i = 0; i < flags.length; i++)
            {
                restored &= ReflectionUtils.forceStaticFinalBoolean(service, flags[i], original[i]);
            }
            assertTrue("restore the build JVM's EDT render flags", restored); //$NON-NLS-1$
        }
    }
}
