/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.Test;

import com._1c.g5.designer.ssh.client.IDesignerSession;
import com._1c.g5.designer.ssh.client.operation.IDesignerOperation;
import com._1c.g5.designer.ssh.client.operation.IExtensionProperties;
import com._1c.g5.designer.ssh.client.operation.IExtensionPropertiesStep;
import com._1c.g5.designer.ssh.client.operation.IExtensionStep;
import com._1c.g5.designer.ssh.client.operation.IExtensionsQuery;
import com.ditrix.edt.mcp.server.utils.InfobaseExtensionLifecycle.Extension;

/**
 * Pins how the extension inventory is read from the two Designer agents EDT talks to (#916).
 *
 * <p>The texts below are verbatim answers of {@code config extensions properties get --all-extensions},
 * captured from a scratch file infobase holding two extensions: {@code ExtActive} (version 25.12, active,
 * safe mode and protection off) and {@code ExtInactive} (no version, inactive, safe mode and protection on).
 * The Designer agent of {@code 1cv8 DESIGNER /AgentMode} 8.3.27.2424 answers with the documented fields. The
 * agent built into the standalone server ({@code ibsrv}, 8.3.27.2424 and 8.5.4.1683) puts the active flag into
 * {@code version} and the version into {@code active}; EDT's client reads that leniently, so the version
 * becomes the text {@code "true"}/{@code "false"} and the active flag becomes {@code false} for every
 * extension. EDT connects to a standalone-server infobase through that built-in agent.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class DesignerExtensionInventoryTest
{
    /** Standalone server (ibsrv) 8.3.27.2424, ssh gate, JSON output; identical on 8.5.4.1683. */
    static final String STANDALONE_SERVER_ANSWER = "[{\"body\":[{\"body\":{\"active\":\"25.12\","
        + "\"hash-sum\":\"METdfbHfKPy6WIQDoPOT8XxZe9A=\",\"name\":\"ExtActive\",\"purpose\":\"customization\","
        + "\"safe-mode\":false,\"scope\":\"infobase\",\"security-profile-name\":\"\",\"unsafe-action-protection\":false,"
        + "\"used-in-distributed-infobase\":false,\"version\":true},\"type\":\"extension-properties\"},"
        + "{\"body\":{\"active\":\"\",\"hash-sum\":\"qXbMxjlImBZGrO0zNO0BtzcX3ng=\",\"name\":\"ExtInactive\","
        + "\"purpose\":\"add-on\",\"safe-mode\":true,\"scope\":\"infobase\",\"security-profile-name\":\"\","
        + "\"unsafe-action-protection\":true,\"used-in-distributed-infobase\":false,\"version\":false},"
        + "\"type\":\"extension-properties\"}],\"type\":\"success\"}]";

    /** 1cv8 DESIGNER /AgentMode 8.3.27.2424 on the same infobase, JSON output. */
    static final String DESIGNER_AGENT_ANSWER = "[{\"type\":\"success\",\"message\":\"\",\"body\":["
        + "{\"type\":\"extension-properties\",\"body\":{\"name\":\"ExtActive\",\"version\":\"25.12\",\"active\":true,"
        + "\"purpose\":\"customization\",\"safe-mode\":false,\"security-profile-name\":\"\","
        + "\"unsafe-action-protection\":false,\"used-in-distributed-infobase\":false,\"scope\":\"infobase\","
        + "\"hash-sum\":\"METdfbHfKPy6WIQDoPOT8XxZe9A=\"}},"
        + "{\"type\":\"extension-properties\",\"body\":{\"name\":\"ExtInactive\",\"version\":\"\",\"active\":false,"
        + "\"purpose\":\"add-on\",\"safe-mode\":true,\"security-profile-name\":\"\","
        + "\"unsafe-action-protection\":true,\"used-in-distributed-infobase\":false,\"scope\":\"infobase\","
        + "\"hash-sum\":\"qXbMxjlImBZGrO0zNO0BtzcX3ng=\"}}]}]";

    @Test public void edtDecodesTheStandaloneServerAnswerWithActiveInTheVersionField() throws Exception
    {
        List<IExtensionProperties> decoded = decode(STANDALONE_SERVER_ANSWER);
        assertEquals(2, decoded.size());
        assertEquals("ExtActive", decoded.get(0).getName());
        assertEquals("true", decoded.get(0).getVersion());
        assertFalse(decoded.get(0).isActive());
        assertEquals("false", decoded.get(1).getVersion());
        assertFalse(decoded.get(1).isActive());
    }

    @Test public void standaloneServerInventoryRestoresTheActiveFlagAndReportsNoVersion() throws Exception
    {
        List<Extension> states = read(decode(STANDALONE_SERVER_ANSWER));
        assertEquals(List.of(
            new Extension("ExtActive", null, true, false, false, "METdfbHfKPy6WIQDoPOT8XxZe9A="),
            new Extension("ExtInactive", null, false, true, true, "qXbMxjlImBZGrO0zNO0BtzcX3ng=")), states);
    }

    @Test public void designerAgentInventoryIsTakenAsReported() throws Exception
    {
        List<Extension> states = read(decode(DESIGNER_AGENT_ANSWER));
        assertEquals(List.of(
            new Extension("ExtActive", "25.12", true, false, false, "METdfbHfKPy6WIQDoPOT8XxZe9A="),
            new Extension("ExtInactive", "", false, true, true, "qXbMxjlImBZGrO0zNO0BtzcX3ng=")), states);
    }

    @Test public void aBooleanLookingVersionNextToARealVersionIsNotRewritten() throws Exception
    {
        List<Extension> states = read(List.of(properties("Odd", "true", false), properties("Plain", "2.1", false)));
        assertEquals("true", states.get(0).version());
        assertFalse(states.get(0).active());
        assertEquals("2.1", states.get(1).version());
    }

    @Test public void anActiveRecordIsNeverTheSwappedShape() throws Exception
    {
        // In the swapped answer every active flag is parsed from a version text, which is never "true".
        List<Extension> states = read(List.of(properties("Odd", "false", true), properties("Other", "true", false)));
        assertEquals("false", states.get(0).version());
        assertTrue(states.get(0).active());
        assertEquals("true", states.get(1).version());
        assertFalse(states.get(1).active());
    }

    @Test public void anEmptyInventoryStaysEmpty() throws Exception
    {
        assertEquals(List.of(), read(List.of()));
    }

    private static IExtensionProperties properties(String name, String version, boolean active)
    {
        IExtensionProperties properties = mock(IExtensionProperties.class);
        when(properties.getName()).thenReturn(name);
        when(properties.getVersion()).thenReturn(version);
        when(properties.isActive()).thenReturn(active);
        when(properties.getHashSum()).thenReturn("hash-" + name);
        return properties;
    }

    /** Reads through the production backend over a session whose inventory returns the given records. */
    private static List<Extension> read(List<IExtensionProperties> inventory) throws Exception
    {
        IDesignerSession session = mock(IDesignerSession.class);
        IExtensionsQuery extensions = mock(IExtensionsQuery.class);
        IExtensionPropertiesStep properties = mock(IExtensionPropertiesStep.class);
        IExtensionStep get = mock(IExtensionStep.class);
        IDesignerOperation<List<IExtensionProperties>> all = mock(IDesignerOperation.class);
        when(session.extensions()).thenReturn(extensions);
        when(extensions.properties()).thenReturn(properties);
        when(properties.get()).thenReturn(get);
        when(get.allExtensions()).thenReturn(all);
        when(all.exec()).thenReturn(inventory);
        return new DesignerExtensionBackend(session, new NullProgressMonitor()).read();
    }

    /** Decodes an agent answer exactly as EDT's Designer client does for this command. */
    private static List<IExtensionProperties> decode(String answer) throws Exception
    {
        ClassLoader loader = IExtensionProperties.class.getClassLoader();
        String responses = "com._1c.g5.internal.designer.ssh.client.responses.";
        Class<?> decoderType = loader.loadClass("com._1c.g5.internal.designer.ssh.client.DesignerResponseDecoder");
        Class<? extends Enum> typeEnum = (Class<? extends Enum>)loader.loadClass(responses + "DesignerResponseType");
        Class<?> propertiesResponse = loader.loadClass(responses + "ExtensionPropertiesResponse");
        Class<?> successResponse = loader.loadClass(responses + "ExtensionPropertiesSuccessResponse");
        Object decoder = decoderType.getConstructor().newInstance();
        Map types = Map.of(Enum.valueOf(typeEnum, "EXTENSION_PROPERTIES"), propertiesResponse,
            Enum.valueOf(typeEnum, "SUCCESS"), successResponse);
        List<?> decoded = (List<?>)decoderType.getMethod("decode", CharSequence.class, Map.class)
            .invoke(decoder, answer, types);
        List<IExtensionProperties> result = new ArrayList<>();
        Method nested = successResponse.getMethod("getExtensionPropertiesResponses");
        Method properties = propertiesResponse.getMethod("getProperties");
        for (Object response : decoded)
        {
            if (successResponse.isInstance(response))
            {
                for (Object item : (List<?>)nested.invoke(response))
                {
                    result.add((IExtensionProperties)properties.invoke(item));
                }
            }
        }
        return result;
    }
}
