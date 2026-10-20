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

import org.eclipse.swt.graphics.ImageData;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.ditrix.edt.mcp.server.utils.EditorScreenshotHelper.CaptureResult;
import com.ditrix.edt.mcp.server.utils.NativeRenderModeProbe;
import com.ditrix.edt.mcp.server.utils.NativeRenderModeProbe.NativeRenderMode;
import com.ditrix.edt.mcp.server.utils.ReflectionUtils;

/** Pure advice decisions and the existing render gate and image-read refusals, without a workbench. */
public class GetFormScreenshotRenderRefusalTest
{
    private static final String BUFFER_PATH_ADVICE =
        "A common cause on Windows is -Djava.io.tmpdir without a trailing " //$NON-NLS-1$
        + "separator: check the EDT log for AccessDeniedException on dtgraphics-form.bin. End " //$NON-NLS-1$
        + "-Djava.io.tmpdir with a backslash (for example, -Djava.io.tmpdir=C:\\edt_tmp\\) " //$NON-NLS-1$
        + "and restart EDT."; //$NON-NLS-1$

    @Test
    public void testUnixIsNeutralForEveryLiveAndStartupMode() throws Exception
    {
        String expected = "EDT could not produce the offscreen form image. Check the EDT log for errors around " //$NON-NLS-1$
            + "the offscreen buffer (dtgraphics-form.bin) and ensure EDT has a working display."; //$NON-NLS-1$
        for (NativeRenderMode live : NativeRenderMode.values())
        {
            for (NativeRenderMode startup : NativeRenderMode.values())
            {
                assertEquals("Unix, live=" + live + ", startup=" + startup, expected, //$NON-NLS-1$ //$NON-NLS-2$
                    GetFormScreenshotTool.renderFailureAdvice(live, startup, false));
            }
        }
    }

    @Test
    public void testBufferedOnWindowsStartupOnKeepsBufferPathAdvice() throws Exception
    {
        assertEquals("EDT could not produce the offscreen form image. " + BUFFER_PATH_ADVICE, //$NON-NLS-1$
            GetFormScreenshotTool.renderFailureAdvice(NativeRenderMode.ON, NativeRenderMode.ON, true));
    }

    @Test
    public void testBufferedOnWindowsStartupOffNamesTheLaunchFlagFirst() throws Exception
    {
        String expected = "EDT could not produce the offscreen form image. EDT was not launched with " //$NON-NLS-1$
            + "buffered native render, and switching it on at runtime may not reach the renderer: " //$NON-NLS-1$
            + "on Windows, add -DnativeFormBufferedLayoutRender=true after -vmargs in 1cedt.ini " //$NON-NLS-1$
            + "and restart EDT. If the image is still missing, check the buffer path. " + BUFFER_PATH_ADVICE; //$NON-NLS-1$
        assertEquals(expected,
            GetFormScreenshotTool.renderFailureAdvice(NativeRenderMode.ON, NativeRenderMode.OFF, true));
    }

    @Test
    public void testBufferedOnWindowsUnknownStartupIsConditional() throws Exception
    {
        String advice = GetFormScreenshotTool.renderFailureAdvice(NativeRenderMode.ON, NativeRenderMode.UNKNOWN, true);
        assertFalse(advice, advice.contains("EDT was not launched with buffered native render")); //$NON-NLS-1$
        assertEquals("EDT could not produce the offscreen form image. If EDT was not launched with " //$NON-NLS-1$
            + "-DnativeFormBufferedLayoutRender=true after -vmargs in 1cedt.ini, add it and restart EDT " //$NON-NLS-1$
            + "(switching it on at runtime may not reach the renderer); otherwise check the buffer path. " //$NON-NLS-1$
            + BUFFER_PATH_ADVICE, advice);
    }

    @Test
    public void testLiveOffOrUnknownOnWindowsKeepsFlagAdviceForEveryStartupMode() throws Exception
    {
        String expected = "Ensure EDT runs with buffered native render (on Windows, VM option " //$NON-NLS-1$
            + "-DnativeFormBufferedLayoutRender=true; this switches the designer to offscreen images " //$NON-NLS-1$
            + "and can leave it gray if no buffer is available) and try again."; //$NON-NLS-1$
        for (NativeRenderMode live : new NativeRenderMode[] { NativeRenderMode.OFF, NativeRenderMode.UNKNOWN })
        {
            for (NativeRenderMode startup : NativeRenderMode.values())
            {
                assertEquals("live=" + live + ", startup=" + startup, expected, //$NON-NLS-1$ //$NON-NLS-2$
                    GetFormScreenshotTool.renderFailureAdvice(live, startup, true));
            }
        }
    }

    @Test
    public void testRefreshRefusalUsesCurrentAdvice() throws Exception
    {
        assertRefusalUsesCurrentAdvice("refresh", //$NON-NLS-1$
            "refresh=true was requested but the form could not be re-rendered in time, so no " //$NON-NLS-1$
            + "screenshot was taken: returning the previously rendered image would silently " //$NON-NLS-1$
            + "show stale (pre-edit) content. ", //$NON-NLS-1$
            " Or call with refresh=false to accept the last rendered image."); //$NON-NLS-1$
    }

    @Test
    public void testRequestedFormRefusalUsesCurrentAdvice() throws Exception
    {
        assertRefusalUsesCurrentAdvice("requested form", //$NON-NLS-1$
            "Could not render the requested form 'CommonForm.Test' in time, so no screenshot was taken. " //$NON-NLS-1$
            + "Form image data is not available: its WYSIWYG representation produced no image within the wait budget. ", //$NON-NLS-1$
            ""); //$NON-NLS-1$
    }

    @Test
    public void testActiveEditorRefusalUsesCurrentAdvice() throws Exception
    {
        assertRefusalUsesCurrentAdvice("active editor", //$NON-NLS-1$
            "Form image data is not available: the form did not finish rendering in time, " //$NON-NLS-1$
            + "so no image could be captured. ", ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRenderedWithoutImageRefusalUsesCurrentAdvice() throws Exception
    {
        assertRefusalUsesCurrentAdvice("rendered without image", "Form image data is not available. ", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** Each refusal must preserve its envelope and include exactly the advice selected in this JVM. */
    private static void assertRefusalUsesCurrentAdvice(String path, String prefix, String suffix) throws Exception
    {
        for (boolean enabled : new boolean[] { false, true })
        {
            for (NativeRenderMode startup : NativeRenderMode.values())
            {
                try (RenderModeScope ignored = new RenderModeScope(enabled, startup))
                {
                    assertEquals(prefix + currentAdvice() + suffix, refusal(path));
                }
            }
        }
    }

    private static String currentAdvice() throws Exception
    {
        Method advice = GetFormScreenshotTool.class.getDeclaredMethod("renderFailureAdvice"); //$NON-NLS-1$
        advice.setAccessible(true);
        return (String)advice.invoke(null);
    }

    /** Keeps the existing JSON error envelope and the render-unavailable sentinels under test. */
    private static String refusal(String path) throws Exception
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
        private final Field startupCaptured;
        private final Field startupBuffered;
        private final Object originalCaptured;
        private final Object originalBuffered;

        RenderModeScope(boolean enabled, NativeRenderMode startup) throws Exception
        {
            startupCaptured = NativeRenderModeProbe.class.getDeclaredField("startupModesCaptured"); //$NON-NLS-1$
            startupBuffered = NativeRenderModeProbe.class.getDeclaredField("startupBufferedRenderMode"); //$NON-NLS-1$
            startupCaptured.setAccessible(true);
            startupBuffered.setAccessible(true);
            originalCaptured = startupCaptured.get(null);
            originalBuffered = startupBuffered.get(null);
            startupCaptured.set(null, Boolean.TRUE);
            startupBuffered.set(null, startup);
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
            try
            {
                startupCaptured.set(null, originalCaptured);
                startupBuffered.set(null, originalBuffered);
            }
            catch (IllegalAccessException e)
            {
                throw new AssertionError("restore the startup render snapshot", e); //$NON-NLS-1$
            }
            boolean restored = true;
            for (int i = 0; i < flags.length; i++)
            {
                restored &= ReflectionUtils.forceStaticFinalBoolean(service, flags[i], original[i]);
            }
            assertTrue("restore the build JVM's EDT render flags", restored); //$NON-NLS-1$
        }
    }
}
