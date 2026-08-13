/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import com.ditrix.edt.mcp.server.protocol.McpProtocolHandler;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Proves both consumer contracts of the bridge: the string-name/reflection lookup,
 * and the JDK-typed OSGi alias a consumer uses when it cannot see this package.
 */
public class EdtMcpBridgeTest
{
    private static final String INTERFACE_NAME =
        "com.ditrix.edt.mcp.server.bridge.IEdtMcpBridge"; //$NON-NLS-1$
    private static final String IMPLEMENTATION_NAME =
        "com.ditrix.edt.mcp.server.bridge.EdtMcpBridge"; //$NON-NLS-1$
    private static final String PROBE_TOOL_NAME = "bridge_echo_probe"; //$NON-NLS-1$

    private final AtomicReference<String> receivedValue = new AtomicReference<>();

    @Before
    public void setUp()
    {
        McpToolRegistry.getInstance().clear();
        McpToolRegistry.getInstance().register(new EchoProbeTool(receivedValue));
    }

    @After
    public void tearDown()
    {
        McpToolRegistry.getInstance().clear();
    }

    @Test
    public void testListToolsThroughStringNamedInterfaceAndReflection() throws Exception
    {
        Object service = newReflectiveService();
        Method listTools = service.getClass().getMethod("listTools"); //$NON-NLS-1$
        String json = (String) listTools.invoke(service);

        JsonArray tools = JsonParser.parseString(json).getAsJsonArray();
        assertEquals(1, tools.size());
        JsonObject tool = tools.get(0).getAsJsonObject();
        assertEquals(PROBE_TOOL_NAME, tool.get("name").getAsString()); //$NON-NLS-1$
        assertEquals("Bridge reflection probe", tool.get("description").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, tool.size());
    }

    @Test
    public void testCallToolThroughReflectionUsesNormalMcpDispatch() throws Exception
    {
        Object service = newReflectiveService();
        Method callTool = service.getClass().getMethod("callTool", //$NON-NLS-1$
            String.class, String.class);
        String bridgeJson = (String) callTool.invoke(service, PROBE_TOOL_NAME,
            "{\"value\":\"hello\"}"); //$NON-NLS-1$

        assertEquals("hello", receivedValue.get()); //$NON-NLS-1$
        JsonObject response = JsonParser.parseString(bridgeJson).getAsJsonObject();
        assertEquals("2.0", response.get("jsonrpc").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1L, response.get("id").getAsLong()); //$NON-NLS-1$
        JsonObject result = response.getAsJsonObject("result"); //$NON-NLS-1$
        assertEquals("echo:hello", result.getAsJsonArray("content").get(0) //$NON-NLS-1$ //$NON-NLS-2$
            .getAsJsonObject().get("text").getAsString()); //$NON-NLS-1$

        // Byte-for-byte equality with the regular handler proves this is not a
        // second raw IMcpTool.execute dispatcher hidden in the bridge.
        String directRequest = "{\"jsonrpc\":\"2.0\",\"id\":1," //$NON-NLS-1$
            + "\"method\":\"tools/call\",\"params\":{" //$NON-NLS-1$
            + "\"name\":\"bridge_echo_probe\",\"arguments\":{\"value\":\"hello\"}}}"; //$NON-NLS-1$
        String directJson = new McpProtocolHandler().processRequest(directRequest);
        assertEquals(directJson, bridgeJson);
    }

    @Test
    public void testInvalidArgsJsonReturnsActionableJsonRpcError() throws Exception
    {
        Object service = newReflectiveService();
        Method callTool = service.getClass().getMethod("callTool", //$NON-NLS-1$
            String.class, String.class);
        String json = (String) callTool.invoke(service, PROBE_TOOL_NAME, "[]"); //$NON-NLS-1$
        JsonObject response = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(-32602, response.getAsJsonObject("error").get("code").getAsInt()); //$NON-NLS-1$ //$NON-NLS-2$
        String message = response.getAsJsonObject("error").get("message").getAsString(); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(message.contains("argsJson")); //$NON-NLS-1$
        assertTrue(message.contains("Pass an object")); //$NON-NLS-1$
    }

    @Test
    public void testJdkFunctionTypesDelegateToTheContractMethods() throws Exception
    {
        Object service = newReflectiveService();

        // A consumer that cannot see this package (a JShell snippet, another
        // plugin) holds the bridge through these JDK types instead of reflection.
        assertTrue(service instanceof BiFunction);
        assertTrue(service instanceof Supplier);

        @SuppressWarnings("unchecked")
        BiFunction<String, String, String> callTool = (BiFunction<String, String, String>)service;
        @SuppressWarnings("unchecked")
        Supplier<String> listTools = (Supplier<String>)service;

        Method callToolMethod = service.getClass().getMethod("callTool", //$NON-NLS-1$
            String.class, String.class);
        Method listToolsMethod = service.getClass().getMethod("listTools"); //$NON-NLS-1$

        assertEquals(listToolsMethod.invoke(service), listTools.get());
        assertEquals(callToolMethod.invoke(service, PROBE_TOOL_NAME, "{\"value\":\"typed\"}"), //$NON-NLS-1$
            callTool.apply(PROBE_TOOL_NAME, "{\"value\":\"typed\"}")); //$NON-NLS-1$
        assertEquals("typed", receivedValue.get()); //$NON-NLS-1$
    }

    @Test
    // BiFunction.class is Class<BiFunction>, so an OSGi lookup by that type is raw
    // by construction - exactly as it is in a consumer's snippet.
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void testOsgiRegistrationIsFilterableUnderTheJdkTypeAlias() throws Exception
    {
        Bundle bundle = FrameworkUtil.getBundle(EdtMcpBridgeTest.class);
        assumeNotNull(bundle);
        BundleContext context = bundle.getBundleContext();
        assumeNotNull(context);

        String filter = '(' + IEdtMcpBridge.SERVICE_PROPERTY + '='
            + IEdtMcpBridge.SERVICE_PROPERTY_VALUE + ')';
        Collection<ServiceReference<BiFunction>> references =
            context.getServiceReferences(BiFunction.class, filter);
        assertEquals("the bridge must be findable by BiFunction + service property", //$NON-NLS-1$
            1, references.size());

        // The service found this way must be the live bridge, not a lookalike:
        // it dispatches into the same registry the probe tool was registered in.
        BiFunction<String, String, String> mcp =
            (BiFunction<String, String, String>)context.getService(references.iterator().next());
        String json = mcp.apply(PROBE_TOOL_NAME, "{\"value\":\"osgi\"}"); //$NON-NLS-1$
        assertEquals("osgi", receivedValue.get()); //$NON-NLS-1$
        assertEquals("echo:osgi", JsonParser.parseString(json).getAsJsonObject() //$NON-NLS-1$
            .getAsJsonObject("result").getAsJsonArray("content").get(0) //$NON-NLS-1$ //$NON-NLS-2$
            .getAsJsonObject().get("text").getAsString()); //$NON-NLS-1$

        assertEquals(1, context.getServiceReferences(Supplier.class, filter).size());
    }

    private static Object newReflectiveService() throws Exception
    {
        // This deliberately mirrors the Workmate/JShell consumer: neither type is
        // imported; both are resolved from stable string names.
        Class<?> contract = Class.forName(INTERFACE_NAME);
        Object service = Class.forName(IMPLEMENTATION_NAME).getConstructor().newInstance();
        assertNotNull(service);
        assertTrue(contract.isInstance(service));
        assertTrue(java.lang.reflect.Modifier.isPublic(service.getClass().getModifiers()));
        return service;
    }

    private static final class EchoProbeTool implements IMcpTool
    {
        private final AtomicReference<String> received;

        private EchoProbeTool(AtomicReference<String> received)
        {
            this.received = received;
        }

        @Override
        public String getName()
        {
            return PROBE_TOOL_NAME;
        }

        @Override
        public String getDescription()
        {
            return "Bridge reflection probe"; //$NON-NLS-1$
        }

        @Override
        public String getInputSchema()
        {
            return "{\"type\":\"object\",\"properties\":{" //$NON-NLS-1$
                + "\"value\":{\"type\":\"string\"}},\"required\":[\"value\"]}"; //$NON-NLS-1$
        }

        @Override
        public String execute(Map<String, String> params)
        {
            String value = params.get("value"); //$NON-NLS-1$
            received.set(value);
            return "echo:" + value; //$NON-NLS-1$
        }

        @Override
        public ResponseType getResponseType()
        {
            return ResponseType.TEXT;
        }
    }
}
