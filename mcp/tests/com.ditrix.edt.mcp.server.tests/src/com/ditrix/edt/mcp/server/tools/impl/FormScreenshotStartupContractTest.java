/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.ditrix.edt.mcp.server.utils.EditorScreenshotHelper;
import com.ditrix.edt.mcp.server.utils.NativeRenderModeProbe;
import com.ditrix.edt.mcp.server.utils.NativeRenderModeProbe.NativeRenderMode;
import com.ditrix.edt.mcp.server.utils.ReflectionUtils;

/** A screenshot must never rewrite EDT's launch configuration or its static final flags. */
public class FormScreenshotStartupContractTest
{
    @Test
    public void testScreenshotBytecodeHasNoBufferedModeMutationCall() throws Exception
    {
        // Check the built call site without opening a native form editor in the headless test JVM.
        String bytes = classBytes(GetFormScreenshotTool.class);
        // Positive controls: calls the class is known to make must be visible in the same constant pool.
        assertTrue(bytes.contains("resolveFormEditor"));
        assertTrue(bytes.contains("getBufferedRenderMode"));
        assertFalse(bytes.contains("ensureBufferedNativeRenderMode"));
    }

    @Test
    public void testRenderProbeBytecodeOnlyReadsTheModes() throws Exception
    {
        String bytes = classBytes(NativeRenderModeProbe.class);
        // Positive control: the probe's own read of the buffered mode lands in its constant pool.
        assertTrue(bytes.contains("isBufferedRender"));
        assertFalse(bytes.contains("setBoolean"));
        assertFalse(bytes.contains("setProperty"));
        assertFalse(bytes.contains("sun.misc.Unsafe"));
    }

    @Test
    public void testRenderProbeAndScreenshotHelperHaveNoModeWriter()
    {
        for (Class<?> type : new Class<?>[] { NativeRenderModeProbe.class, EditorScreenshotHelper.class })
        {
            for (Method method : type.getDeclaredMethods())
            {
                assertFalse(type.getName(), method.getName().equals("ensureBufferedNativeRenderMode"));
                assertFalse(type.getName(), method.getName().equals("forceBufferedRenderFlag"));
            }
        }
    }

    @Test
    public void testReflectionUtilityHasNoStaticFinalBooleanPatcher() throws Exception
    {
        String bytes = classBytes(ReflectionUtils.class);
        // Positive control: the field read the utility keeps is visible in the same constant pool.
        assertTrue(bytes.contains("getDeclaredField"));
        assertFalse(bytes.contains("sun.misc.Unsafe"));
        for (Method method : ReflectionUtils.class.getDeclaredMethods())
        {
            assertFalse(method.getName(), method.getName().equals("forceStaticFinalBoolean"));
        }
    }

    @Test
    public void testDescriptionWarnsAboutDefaultOptInAndSessionWideDesignerEffect()
    {
        String description = new GetFormScreenshotTool().getDescription();
        for (String clause : new String[] { "ATTENTION", "disabled by default",
            "-DnativeFormBufferedLayoutRender=true", "1cedt.ini", "grey rectangle", "EDT behaviour",
            "whole session", "restart", "on Windows", "Parameters and examples: get_tool_guide('get_form_screenshot')." })
        {
            assertTrue(clause + " missing from description", description.contains(clause));
        }
    }

    @Test
    public void testStatusSchemaDescribesOnlyStartupAndRequestedStates()
    {
        String schema = new GetServerStatusTool().getOutputSchema();
        assertFalse(schema, schema.contains("forcedAtRuntime"));
        JsonObject flags = JsonParser.parseString(new GetServerStatusTool().execute(Map.of()))
            .getAsJsonObject().getAsJsonObject("formRenderFlags");
        for (String flag : new String[] { "nativeFormLayoutRender", "nativeFormBufferedLayoutRender" })
        {
            JsonObject state = flags.getAsJsonObject(flag);
            assertTrue(state.has("atStartup"));
            assertFalse(state.has("forcedAtRuntime"));
        }
    }

    @Test
    public void testWindowsWithoutBufferExplainsLaunchRestartAndBufferPath()
    {
        String advice = GetFormScreenshotTool.renderFailureAdvice(NativeRenderMode.OFF, NativeRenderMode.OFF, true);
        for (String clause : new String[] { "-DnativeFormBufferedLayoutRender=true", "-vmargs", "1cedt.ini",
            "restart EDT", "trailing", "dtgraphics-form.bin", "screenshot sessions" })
        {
            assertTrue(clause + " missing from refusal", advice.contains(clause));
        }
    }

    private static String classBytes(Class<?> type) throws Exception
    {
        try (InputStream stream = type.getResourceAsStream(type.getSimpleName() + ".class"))
        {
            assertNotNull(type.getName(), stream);
            return new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
