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

import java.lang.reflect.Method;

import org.eclipse.swt.graphics.ImageData;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.ditrix.edt.mcp.server.utils.EditorScreenshotHelper.CaptureResult;
import com.ditrix.edt.mcp.server.utils.NativeRenderModeProbe.NativeRenderMode;

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

    private static final String FLAG_ADVICE =
        "On Windows, add -DnativeFormBufferedLayoutRender=true after -vmargs in 1cedt.ini " //$NON-NLS-1$
        + "and restart EDT. Use this flag only for screenshot sessions: EDT's own form designer " //$NON-NLS-1$
        + "shows a grey rectangle for the whole session. " + BUFFER_PATH_ADVICE; //$NON-NLS-1$

    @Test
    public void testKnownBufferedOnWindowsGetsOnlyBufferPathAdviceForEveryStartupMode() throws Exception
    {
        // The mode is read once at class initialization and nothing writes it, so a readable live ON is
        // the launch state whatever the startup snapshot says.
        for (NativeRenderMode startup : NativeRenderMode.values())
        {
            assertEquals("live=ON, startup=" + startup, //$NON-NLS-1$
                "EDT could not produce the offscreen form image. " + BUFFER_PATH_ADVICE, //$NON-NLS-1$
                GetFormScreenshotTool.renderFailureAdvice(NativeRenderMode.ON, startup, true));
        }
    }

    @Test
    public void testUnreadableLiveModeFallsBackToTheStartupSnapshot() throws Exception
    {
        assertEquals("EDT could not produce the offscreen form image. " + BUFFER_PATH_ADVICE, //$NON-NLS-1$
            GetFormScreenshotTool.renderFailureAdvice(NativeRenderMode.UNKNOWN, NativeRenderMode.ON, true));
        assertEquals(FLAG_ADVICE,
            GetFormScreenshotTool.renderFailureAdvice(NativeRenderMode.UNKNOWN, NativeRenderMode.OFF, true));
        assertEquals(FLAG_ADVICE,
            GetFormScreenshotTool.renderFailureAdvice(NativeRenderMode.UNKNOWN, NativeRenderMode.UNKNOWN, true));
    }

    @Test
    public void testLiveOffOnWindowsKeepsFlagAdviceForEveryStartupMode() throws Exception
    {
        for (NativeRenderMode startup : NativeRenderMode.values())
        {
            assertEquals("live=OFF, startup=" + startup, FLAG_ADVICE, //$NON-NLS-1$
                GetFormScreenshotTool.renderFailureAdvice(NativeRenderMode.OFF, startup, true));
        }
    }

    @Test
    public void testNoAdviceDescribesALiveModeThatDiffersFromTheLaunch() throws Exception
    {
        // The runtime-force era texts ("EDT was not launched with buffered native render" while the live
        // mode was on, and the conditional "If EDT was not launched with ...") are unreachable now.
        for (NativeRenderMode live : NativeRenderMode.values())
        {
            for (NativeRenderMode startup : NativeRenderMode.values())
            {
                String advice = GetFormScreenshotTool.renderFailureAdvice(live, startup, true);
                assertFalse(advice, advice.contains("was not launched with")); //$NON-NLS-1$
                assertFalse(advice, advice.contains("at runtime")); //$NON-NLS-1$
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
        assertEquals(prefix + currentAdvice() + suffix, refusal(path));
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
}
