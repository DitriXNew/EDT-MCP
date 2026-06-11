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
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.ReturnValuesReuse;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.tools.impl.CreateMetadataTool.CommonModuleFlags;
import com.ditrix.edt.mcp.server.tools.impl.CreateMetadataTool.CommonModuleKind;

/**
 * Lightweight contract tests for {@link CreateMetadataTool}: tool metadata and JSON schema,
 * without needing the Eclipse/EDT runtime. The {@code execute()} path requires a live workbench
 * and BM model, so the create / duplicate / property-rejection behaviour is covered by the E2E suite.
 */
public class CreateMetadataToolTest
{
    @Test
    public void testNameConstant()
    {
        assertEquals("create_metadata", new CreateMetadataTool().getName()); //$NON-NLS-1$
        assertEquals(CreateMetadataTool.NAME, new CreateMetadataTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new CreateMetadataTool().getResponseType());
    }

    @Test
    public void testDescriptionPointsToGuide()
    {
        String desc = new CreateMetadataTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
        assertTrue("description should point to get_tool_guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('create_metadata')")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new CreateMetadataTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"fqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"properties\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"expectedNotExists\"")); //$NON-NLS-1$
        // The ё->е normalization toggle must be declared (execute() reads it; schema parity).
        assertTrue("schema must declare the normalizeYo toggle", //$NON-NLS-1$
            schema.contains("\"normalizeYo\"")); //$NON-NLS-1$
        // Create-time-only, type-specific options.
        assertTrue(schema.contains("\"commonModuleKind\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"serverCall\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"privileged\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"returnValuesReuse\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"targetNamespace\"")); //$NON-NLS-1$
        // Form-object create flag (execute() reads it; schema parity).
        assertTrue("schema must declare the setAsDefault form-object flag", //$NON-NLS-1$
            schema.contains("\"setAsDefault\"")); //$NON-NLS-1$
    }

    @Test
    public void testSetAsDefaultIsOptional()
    {
        // setAsDefault is a form-object-create flag, defaults false -> must not be required.
        String schema = new CreateMetadataTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        String tail = schema.substring(requiredIdx);
        assertFalse("setAsDefault must not be required (defaults false)", //$NON-NLS-1$
            tail.contains("\"setAsDefault\"")); //$NON-NLS-1$
    }

    @Test
    public void testOutputSchemaDeclaresSetAsDefault()
    {
        // Output parity: a form-object create echoes setAsDefault in the result payload.
        String schema = new CreateMetadataTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue("output schema must declare setAsDefault", //$NON-NLS-1$
            schema.contains("\"setAsDefault\"")); //$NON-NLS-1$
    }

    @Test
    public void testCommonModuleKindIsDeclaredAsAClosedEnum()
    {
        String schema = new CreateMetadataTool().getInputSchema();
        // The kind must be a closed JSON-Schema enum carrying every canonical kind token.
        int kindIdx = schema.indexOf("\"commonModuleKind\""); //$NON-NLS-1$
        assertTrue("schema must declare commonModuleKind", kindIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(kindIdx);
        assertTrue("commonModuleKind must be a closed enum", tail.contains("\"enum\"")); //$NON-NLS-1$ //$NON-NLS-2$
        for (CommonModuleKind k : CommonModuleKind.values())
        {
            assertTrue("enum must list the '" + k.token() + "' kind", //$NON-NLS-1$ //$NON-NLS-2$
                schema.contains("\"" + k.token() + "\"")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // returnValuesReuse is likewise a closed enum.
        assertTrue("returnValuesReuse must offer DuringSession", //$NON-NLS-1$
            schema.contains("\"DuringSession\"")); //$NON-NLS-1$
    }

    @Test
    public void testNormalizeYoIsOptional()
    {
        String schema = new CreateMetadataTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        String tail = schema.substring(requiredIdx);
        assertFalse("normalizeYo must not be required (defaults true)", //$NON-NLS-1$
            tail.contains("\"normalizeYo\"")); //$NON-NLS-1$
    }

    @Test
    public void testNewOptionalParametersAreNotRequired()
    {
        String schema = new CreateMetadataTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        String tail = schema.substring(requiredIdx);
        assertFalse("commonModuleKind must not be required", tail.contains("\"commonModuleKind\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("targetNamespace must not be required", tail.contains("\"targetNamespace\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ── Pure CommonModule flag-resolution (no workbench / BM model needed) ──────────────────────

    private static Map<String, String> params(String... kv)
    {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2)
        {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    public void testResolveDefaultsToServerKind()
    {
        // No commonModuleKind -> the default 'Server' canonical combo (the validator-accepted one).
        CommonModuleFlags f = CommonModuleFlags.resolve(params());
        assertEquals(CommonModuleKind.SERVER, f.kind);
        assertTrue("default Server module must be server-side", f.server); //$NON-NLS-1$
        assertFalse("default Server module is not a server call", f.serverCall); //$NON-NLS-1$
        assertTrue("default Server module sets external connection", f.externalConnection); //$NON-NLS-1$
        assertTrue("default Server module sets client-ordinary", f.clientOrdinaryApplication); //$NON-NLS-1$
        assertEquals(ReturnValuesReuse.DONT_USE, f.returnValuesReuse);
    }

    @Test
    public void testResolveServerCallKindSetsServerCallCombo()
    {
        // ServerCall is the canonical server + server-call combo with no client flags.
        CommonModuleFlags f = CommonModuleFlags.resolve(params("commonModuleKind", "ServerCall")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(CommonModuleKind.SERVER_CALL, f.kind);
        assertTrue("ServerCall must be a server module", f.server); //$NON-NLS-1$
        assertTrue("ServerCall must set the server-call flag", f.serverCall); //$NON-NLS-1$
        assertFalse("ServerCall sets no client flags", f.clientManagedApplication); //$NON-NLS-1$
        assertFalse("ServerCall sets no client flags", f.clientOrdinaryApplication); //$NON-NLS-1$
        assertFalse("ServerCall must not set external connection", f.externalConnection); //$NON-NLS-1$
    }

    @Test
    public void testResolveServerCallCachedYieldsDuringSession()
    {
        // ServerCall + DuringSession -> the cached server-call combo (a validator-accepted variant).
        CommonModuleFlags f = CommonModuleFlags.resolve(
            params("commonModuleKind", "ServerCall", "returnValuesReuse", "DuringSession")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals(ReturnValuesReuse.DURING_SESSION, f.returnValuesReuse);
        assertTrue(f.serverCall);
    }

    @Test
    public void testResolveServerCallOnClientKindIsRejected()
    {
        // An illegal flag combo (serverCall on a pure client kind) must throw BEFORE any model
        // access - the validator would otherwise reject the arbitrary flag set.
        try
        {
            CommonModuleFlags.resolve(params("commonModuleKind", "ClientManaged", "serverCall", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            fail("serverCall on a client kind must be rejected"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException e)
        {
            assertTrue("message must name serverCall", e.getMessage().contains("serverCall")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testResolveServerCallOnGlobalKindIsRejectedNamingGlobal()
    {
        // The former dedicated Global+serverCall branch was dead code (the non-server-kind
        // check above it throws first); its specificity is folded INTO that first check:
        // for kind 'Global' the message must name Global explicitly and explain why.
        try
        {
            CommonModuleFlags.resolve(params("commonModuleKind", "Global", "serverCall", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            fail("serverCall on the Global kind must be rejected"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException e)
        {
            assertTrue("message must name serverCall", e.getMessage().contains("serverCall")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("message must name the Global kind", e.getMessage().contains("'Global'")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("message must explain the Global incompatibility", //$NON-NLS-1$
                e.getMessage().contains("cannot be a server-call target")); //$NON-NLS-1$
        }
    }

    @Test
    public void testResolvePrivilegedOnNonServerKindIsRejected()
    {
        try
        {
            CommonModuleFlags.resolve(params("commonModuleKind", "Global", "privileged", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            fail("privileged on a non-Server kind must be rejected"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException e)
        {
            assertTrue("message must name privileged", e.getMessage().contains("privileged")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testResolveUnknownKindIsRejected()
    {
        try
        {
            CommonModuleFlags.resolve(params("commonModuleKind", "NotAKind")); //$NON-NLS-1$ //$NON-NLS-2$
            fail("an unknown commonModuleKind must be rejected"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException e)
        {
            assertTrue("message must echo the bad token", e.getMessage().contains("NotAKind")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testResolveDuringRequestHasNoCanonicalCombo()
    {
        try
        {
            CommonModuleFlags.resolve(params("returnValuesReuse", "DuringRequest")); //$NON-NLS-1$ //$NON-NLS-2$
            fail("DuringRequest has no standards-compliant combo and must be rejected"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException e)
        {
            assertTrue("message must mention DuringRequest", //$NON-NLS-1$
                e.getMessage().contains("DuringRequest")); //$NON-NLS-1$
        }
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new CreateMetadataTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("fqn must be required", tail.contains("\"fqn\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOptionalParametersNotRequired()
    {
        String schema = new CreateMetadataTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        String tail = schema.substring(requiredIdx);
        assertFalse("properties must not be required", tail.contains("\"properties\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("expectedNotExists must not be required", //$NON-NLS-1$
            tail.contains("\"expectedNotExists\"")); //$NON-NLS-1$
    }

    @Test
    public void testGuideCarriesKeyDetail()
    {
        String guide = new CreateMetadataTool().getGuide();
        assertNotNull(guide);
        assertFalse("guide must be non-empty", guide.isEmpty()); //$NON-NLS-1$
        // bilingual synonym detail retained
        assertTrue("guide should keep the language CODE detail", guide.contains("language CODE")); //$NON-NLS-1$ //$NON-NLS-2$
        // member kinds documented
        assertTrue("guide should list member kinds", guide.contains("EnumValue")); //$NON-NLS-1$ //$NON-NLS-2$
        // nested-object members (e.g. a tabular-section attribute) are now supported and documented
        assertTrue("guide should document nested-object members", //$NON-NLS-1$
            guide.contains("tabular-section attribute")); //$NON-NLS-1$
    }
}
