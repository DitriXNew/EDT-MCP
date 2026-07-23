/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link CreateInfobaseTool}.
 * <p>
 * Covers tool metadata, schema parity, and the argument-validation guards that
 * execute BEFORE any workspace or platform-services access. The real create path
 * (platform probe -> background Job -> IInfobaseCreationOperation -> associate) needs
 * a live EDT with a registered 1C platform runtime and is covered by the e2e suite.
 */
public class CreateInfobaseToolTest
{
    @Test
    public void testName()
    {
        assertEquals("create_infobase", new CreateInfobaseTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(CreateInfobaseTool.NAME, new CreateInfobaseTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new CreateInfobaseTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmptyAndContainsToolGuideHint()
    {
        String desc = new CreateInfobaseTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
        assertTrue("description must steer to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('create_infobase')")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaDeclaresAllParameters()
    {
        String schema = new CreateInfobaseTool().getInputSchema();
        assertNotNull(schema);
        assertTrue("schema must declare projectName", schema.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare infobaseFile", schema.contains("\"infobaseFile\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare infobaseName", schema.contains("\"infobaseName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare platform", schema.contains("\"platform\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare setDefault", schema.contains("\"setDefault\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInputSchemaDeclaresStandaloneServerParameters()
    {
        // The autonomous/standalone-server path adds a single input: applicationKind (closed enum).
        // port/publicationName are intentionally NOT inputs: EDT ignores a requested port/publication
        // for a FILE-backed standalone server (auto-allocated; publication base hard-coded to "/"), so
        // offering them as knobs would be misleading. The ACTUAL port is reported in the OUTPUT only.
        String schema = new CreateInfobaseTool().getInputSchema();
        assertNotNull(schema);
        assertTrue("schema must declare applicationKind", //$NON-NLS-1$
            schema.contains("\"applicationKind\"")); //$NON-NLS-1$
        assertTrue("applicationKind must advertise the 'infobase' enum value", //$NON-NLS-1$
            schema.contains("\"infobase\"")); //$NON-NLS-1$
        assertTrue("applicationKind must advertise the 'standaloneServer' enum value", //$NON-NLS-1$
            schema.contains("\"standaloneServer\"")); //$NON-NLS-1$
        // The applicationKind property must be a CLOSED enum (so a client can only pick the two
        // supported kinds). The "enum" keyword must appear in the schema.
        assertTrue("applicationKind must be a closed enum", schema.contains("\"enum\"")); //$NON-NLS-1$ //$NON-NLS-2$
        // port/publicationName must NOT be exposed as inputs (EDT ignores them for FILE-backed servers).
        assertTrue("port must NOT be an input parameter", //$NON-NLS-1$
            !schema.contains("\"port\"")); //$NON-NLS-1$
        assertTrue("publicationName must NOT be an input parameter", //$NON-NLS-1$
            !schema.contains("\"publicationName\"")); //$NON-NLS-1$
    }

    @Test
    public void testStandaloneServerParametersAreNotRequired()
    {
        // Backward-compat: applicationKind MUST be optional so existing callers
        // (no applicationKind => plain file infobase) keep working byte-identically.
        String schema = new CreateInfobaseTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        int open = schema.indexOf('[', requiredIdx);
        int close = schema.indexOf(']', open);
        assertTrue("required array must be present", open >= 0 && close > open); //$NON-NLS-1$
        String requiredBlock = schema.substring(open, close + 1);
        assertTrue("applicationKind must NOT be required", //$NON-NLS-1$
            !requiredBlock.contains("\"applicationKind\"")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaDeclaresCredentialParameters()
    {
        // #194: optional connection credentials so a registered infobase with a user list can
        // authenticate the update agent. All optional (back-compat: a bare create stores none).
        String schema = new CreateInfobaseTool().getInputSchema();
        assertNotNull(schema);
        assertTrue("schema must declare user", schema.contains("\"user\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare password", schema.contains("\"password\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare access", schema.contains("\"access\"")); //$NON-NLS-1$ //$NON-NLS-2$
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        if (requiredIdx >= 0)
        {
            int open = schema.indexOf('[', requiredIdx);
            int close = schema.indexOf(']', open);
            if (open >= 0 && close > open)
            {
                String requiredBlock = schema.substring(open, close + 1);
                assertTrue("user must NOT be required", !requiredBlock.contains("\"user\"")); //$NON-NLS-1$ //$NON-NLS-2$
                assertTrue("password must NOT be required", //$NON-NLS-1$
                    !requiredBlock.contains("\"password\"")); //$NON-NLS-1$
            }
        }
    }

    @Test
    public void testInvalidApplicationKindIsError()
    {
        // An unknown applicationKind value is rejected before any service lookup (headless-safe),
        // with an error naming the bad value and the two allowed kinds.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "AnyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("infobaseFile", "C:/infobases/Any"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("applicationKind", "cluster"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateInfobaseTool().execute(params);
        assertNotNull(result);
        assertTrue("invalid applicationKind must be an error", //$NON-NLS-1$
            result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("error must name the bad value", result.contains("cluster")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must list allowed kinds", //$NON-NLS-1$
            result.contains("infobase") && result.contains("standaloneServer")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // NOTE: the actual standalone-server creation (OSGi lookup of IStandaloneServerService ->
    // findRuntime probe -> background Job -> createServerWithInfobase -> ibcmd -> get_applications
    // read-back) is Tier-2 LIVE: it needs a registered 1C standalone-server runtime (platform
    // >= 8.3.23 with ibsrv/ibcmd) and is verified on the live EDT stand, not in this unit suite.

    @Test
    public void testRequiredParametersInSchema()
    {
        String schema = new CreateInfobaseTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("infobaseFile must be required", tail.contains("\"infobaseFile\"")); //$NON-NLS-1$ //$NON-NLS-2$
        // Optional parameters must NOT be in the required array.
        // The required block is between the first '[' and ']' after "required".
        int open = schema.indexOf('[', requiredIdx);
        int close = schema.indexOf(']', open);
        if (open >= 0 && close > open)
        {
            String requiredBlock = schema.substring(open, close + 1);
            assertTrue("infobaseName must NOT be required", //$NON-NLS-1$
                !requiredBlock.contains("\"infobaseName\"")); //$NON-NLS-1$
            assertTrue("platform must NOT be required", //$NON-NLS-1$
                !requiredBlock.contains("\"platform\"")); //$NON-NLS-1$
            assertTrue("setDefault must NOT be required", //$NON-NLS-1$
                !requiredBlock.contains("\"setDefault\"")); //$NON-NLS-1$
        }
    }

    @Test
    public void testOutputSchemaDeclaresExpectedFields()
    {
        String schema = new CreateInfobaseTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue("outputSchema must declare success", schema.contains("\"success\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare action", schema.contains("\"action\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare applicationId", schema.contains("\"applicationId\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare infobaseFile", schema.contains("\"infobaseFile\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare applications", schema.contains("\"applications\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare applicationKind", //$NON-NLS-1$
            schema.contains("\"applicationKind\"")); //$NON-NLS-1$
        assertTrue("outputSchema must declare webUrl", schema.contains("\"webUrl\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare port", schema.contains("\"port\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGuideExists()
    {
        String guide = new CreateInfobaseTool().getGuide();
        assertNotNull("guide must not be null", guide); //$NON-NLS-1$
        assertTrue("guide must not be empty", guide.length() > 0); //$NON-NLS-1$
        assertTrue("guide must document infobaseFile parameter", guide.contains("infobaseFile")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must mention platform requirement", //$NON-NLS-1$
            guide.toLowerCase().contains("platform")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testMissingProjectNameIsError()
    {
        Map<String, String> params = new HashMap<>();
        params.put("infobaseFile", "C:\\infobases\\test"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateInfobaseTool().execute(params);
        assertTrue("missing projectName must produce an error", //$NON-NLS-1$
            result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingInfobaseFileIsError()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateInfobaseTool().execute(params);
        assertTrue("missing infobaseFile must produce an error", //$NON-NLS-1$
            result.contains("infobaseFile is required")); //$NON-NLS-1$
    }

    @Test
    public void testBothRequiredParamsMissingNamedFirst()
    {
        Map<String, String> params = new HashMap<>();
        // With no params, projectName is checked first.
        String result = new CreateInfobaseTool().execute(params);
        assertTrue("missing both params — projectName checked first", //$NON-NLS-1$
            result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testInvalidModeIsError()
    {
        // An unknown mode value is rejected (headless-safe: validated before any service lookup)
        // with an error naming the bad value and the two allowed modes.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "AnyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("infobaseFile", "C:/infobases/Any"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("mode", "import"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateInfobaseTool().execute(params);
        assertNotNull(result);
        assertTrue("invalid mode must be an error", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must name the bad value", result.contains("import")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must list allowed modes", //$NON-NLS-1$
            result.contains("create") && result.contains("register")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInvalidAccessIsError()
    {
        // An out-of-enum credential access value is rejected early (headless-safe), naming the
        // bad value and the allowed kinds.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "AnyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("infobaseFile", "C:/infobases/Any"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("access", "OOPS"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateInfobaseTool().execute(params);
        assertNotNull(result);
        assertTrue("invalid access must be an error", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must name the bad value", result.contains("OOPS")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must list allowed kinds", //$NON-NLS-1$
            result.contains("INFOBASE") && result.contains("OS")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testStandaloneServerWithCredentialsIsError()
    {
        // #275: credentials remain rejected for a newly created standalone server (mode='create',
        // the default) — pairing them with standaloneServer+create is rejected (not silently
        // dropped). Validated before any platform/service lookup (headless-safe). The message must
        // steer to BOTH supported alternatives: applicationKind='infobase', or mode='register'.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "AnyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("infobaseFile", "C:/infobases/Any"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("applicationKind", "standaloneServer"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("user", "Admin"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateInfobaseTool().execute(params);
        assertNotNull(result);
        assertTrue("credentials with standaloneServer+create must be an error", //$NON-NLS-1$
            result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("error must steer to applicationKind='infobase'", //$NON-NLS-1$
            result.contains("infobase")); //$NON-NLS-1$
        assertTrue("error must steer to mode='register' as the supported standalone-server alternative", //$NON-NLS-1$
            result.contains("register")); //$NON-NLS-1$
    }

    @Test
    public void testStandaloneServerRegisterWithCredentialsPassesValidation()
    {
        // #275: standaloneServer + mode='register' + credentials must NOT be rejected by the
        // credentials guard (that guard now fires only for standaloneServer+create). Execution
        // proceeds into the register-path validation instead, which fails on the missing 1Cv8.1CD at
        // this fake path — proving the credentials guard let it through rather than blocking it.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "AnyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("infobaseFile", "C:/infobases/edt_mcp_no_such_ib_zzz2"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("applicationKind", "standaloneServer"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("mode", "register"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("user", "Admin"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("password", "secret"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateInfobaseTool().execute(params);
        assertNotNull(result);
        assertTrue("must still be an error (no 1Cv8.1CD at the fake path)", //$NON-NLS-1$
            result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("must NOT be the credentials-rejected error", //$NON-NLS-1$
            !result.contains("are supported only with")); //$NON-NLS-1$
        assertTrue("must be the register-path 'no file infobase found' error instead", //$NON-NLS-1$
            result.contains("1Cv8.1CD")); //$NON-NLS-1$
    }

    @Test
    public void testStandaloneServerRegisterIsNoLongerRejected()
    {
        // #271: applicationKind='standaloneServer' + mode='register' is now SUPPORTED (it wraps an
        // EXISTING file infobase with a standalone server). The old "mode='register' is not supported
        // with applicationKind='standaloneServer'" routing rejection must be GONE — a register call now
        // flows into the register-path validation instead. Headless-safe: with a path that has no
        // 1Cv8.1CD the validation fires before any workspace/service lookup.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "AnyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("infobaseFile", "C:/infobases/edt_mcp_no_such_ib_zzz"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("applicationKind", "standaloneServer"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("mode", "register"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateInfobaseTool().execute(params);
        assertNotNull(result);
        assertTrue("standaloneServer+register must no longer be rejected as 'not supported'", //$NON-NLS-1$
            !result.contains("not supported")); //$NON-NLS-1$
    }

    @Test
    public void testStandaloneServerRegisterMissingDatabaseNamesPath()
    {
        // #271: registering a standalone server over a path that holds no 1Cv8.1CD must fail fast with an
        // actionable error that NAMES the path and steers to mode='create' — the SAME check the plain
        // register path uses. Validated before any workspace/service lookup (headless-safe).
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "AnyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("infobaseFile", "C:/infobases/edt_mcp_no_such_ib_zzz"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("applicationKind", "standaloneServer"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("mode", "register"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateInfobaseTool().execute(params);
        assertNotNull(result);
        assertTrue("missing existing infobase must be an error", //$NON-NLS-1$
            result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("error must name the path", //$NON-NLS-1$
            result.contains("edt_mcp_no_such_ib_zzz")); //$NON-NLS-1$
        assertTrue("error must mention the expected 1Cv8.1CD file", //$NON-NLS-1$
            result.contains("1Cv8.1CD")); //$NON-NLS-1$
        assertTrue("error must steer to mode='create'", result.contains("create")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== #273: version-tolerant create-flag resolution ====================
    // StandaloneServerInfobase's create-new-infobase flag setter was RENAMED, with no back-compat
    // alias, between EDT 2025.2 (setCreate) and 2026.1 (setCreateNewInfobase). ssMethodAny/
    // ssSetCreateFlag resolve it version-tolerantly; both are package-private test seams (mirrors
    // StandaloneServerSupport's convention of package-visible statics for testability), exercised
    // here with plain stub classes exposing one name, the other, or neither — no live EDT needed.

    @Test
    public void testSsMethodAnyResolves2025ShapeWhenOnlySetCreatePresent()
    {
        Method m = CreateInfobaseTool.ssMethodAny(StubOnlySetCreate.class, 1, "setCreate", //$NON-NLS-1$
            "setCreateNewInfobase"); //$NON-NLS-1$
        assertNotNull("setCreate must resolve when present", m); //$NON-NLS-1$
        assertEquals("setCreate", m.getName()); //$NON-NLS-1$
    }

    @Test
    public void testSsMethodAnyResolves2026ShapeWhenOnlySetCreateNewInfobasePresent()
    {
        Method m = CreateInfobaseTool.ssMethodAny(StubOnlySetCreateNewInfobase.class, 1, "setCreate", //$NON-NLS-1$
            "setCreateNewInfobase"); //$NON-NLS-1$
        assertNotNull("setCreateNewInfobase must resolve when present", m); //$NON-NLS-1$
        assertEquals("setCreateNewInfobase", m.getName()); //$NON-NLS-1$
    }

    @Test
    public void testSsMethodAnyReturnsNullWhenNeitherNamePresent()
    {
        assertNull(CreateInfobaseTool.ssMethodAny(StubNeitherSetter.class, 1, "setCreate", //$NON-NLS-1$
            "setCreateNewInfobase")); //$NON-NLS-1$
    }

    @Test
    public void testSsSetCreateFlagInvokesSetCreateOn2025Shape() throws Exception
    {
        StubOnlySetCreate stub = new StubOnlySetCreate();
        CreateInfobaseTool.ssSetCreateFlag(stub, true);
        assertTrue("setCreate(true) must have been invoked", stub.created); //$NON-NLS-1$
    }

    @Test
    public void testSsSetCreateFlagInvokesSetCreateNewInfobaseOn2026Shape() throws Exception
    {
        StubOnlySetCreateNewInfobase stub = new StubOnlySetCreateNewInfobase();
        CreateInfobaseTool.ssSetCreateFlag(stub, true);
        assertTrue("setCreateNewInfobase(true) must have been invoked", stub.created); //$NON-NLS-1$
    }

    @Test
    public void testSsSetCreateFlagThrowsNamingBothTriedMethodsWhenNeitherPresent()
    {
        try
        {
            CreateInfobaseTool.ssSetCreateFlag(new StubNeitherSetter(), true);
            fail("must throw when neither setCreate nor setCreateNewInfobase resolves"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            assertTrue("failure message must name setCreate", //$NON-NLS-1$
                e.getMessage().contains("setCreate")); //$NON-NLS-1$
            assertTrue("failure message must name setCreateNewInfobase", //$NON-NLS-1$
                e.getMessage().contains("setCreateNewInfobase")); //$NON-NLS-1$
        }
    }

    // ==================== #273: create-template database ensure (2026.1 second drift layer) ====================
    // On 2026.1 the behaviour delegate CASTS the module config's database to ICreateTemplateDatabase,
    // but createServerWithInfobase builds it with a plain FileDatabase -> live ClassCastException.
    // ssEnsureCreateTemplateDatabase swaps in a FileCreateTemplateDatabase; the DECISION logic
    // (needs-replacement check + directory copy across the getConfigDirectory/getPath rename) is
    // headless-testable below; the bundle class-LOADING step needs the real EDT bundle and stays
    // live-verified (here it degrades best-effort, which is itself asserted).

    @Test
    public void testSsIsCreateTemplateDatabaseTrueForDirectImplementor()
    {
        assertTrue(CreateInfobaseTool.ssIsCreateTemplateDatabase(StubTemplateCapableDatabase.class));
    }

    @Test
    public void testSsIsCreateTemplateDatabaseTrueViaSuperclass()
    {
        // The marker interface arrives through the superclass -> the hierarchy walk must find it.
        assertTrue(CreateInfobaseTool.ssIsCreateTemplateDatabase(StubTemplateCapableSubclass.class));
    }

    @Test
    public void testSsIsCreateTemplateDatabaseTrueViaSuperinterface()
    {
        // The marker interface arrives as a SUPERinterface of an implemented interface.
        assertTrue(CreateInfobaseTool.ssIsCreateTemplateDatabase(StubExtendedTemplateDatabase.class));
    }

    @Test
    public void testSsIsCreateTemplateDatabaseFalseForPlainClass()
    {
        assertFalse(CreateInfobaseTool.ssIsCreateTemplateDatabase(StubFileDatabase2025.class));
        assertFalse(CreateInfobaseTool.ssIsCreateTemplateDatabase(Object.class));
    }

    @Test
    public void testSsIsCreateTemplateDatabaseFalseForNull()
    {
        assertFalse(CreateInfobaseTool.ssIsCreateTemplateDatabase(null));
    }

    @Test
    public void testSsCopyDatabaseDirectoryFrom2025To2026Shape() throws Exception
    {
        // Read via getConfigDirectory (2025.2), write via setPath (2026.1) — the cross-rename copy.
        StubFileDatabase2025 from = new StubFileDatabase2025("C:/data/ib"); //$NON-NLS-1$
        StubFileDatabase2026 to = new StubFileDatabase2026(null);
        CreateInfobaseTool.ssCopyDatabaseDirectory(from, to);
        assertEquals("C:/data/ib", to.getPath()); //$NON-NLS-1$
    }

    @Test
    public void testSsCopyDatabaseDirectoryFrom2026To2025Shape() throws Exception
    {
        // Read via getPath (2026.1), write via setConfigDirectory (2025.2).
        StubFileDatabase2026 from = new StubFileDatabase2026("C:/data/ib2026"); //$NON-NLS-1$
        StubFileDatabase2025 to = new StubFileDatabase2025(null);
        CreateInfobaseTool.ssCopyDatabaseDirectory(from, to);
        assertEquals("C:/data/ib2026", to.getConfigDirectory()); //$NON-NLS-1$
    }

    @Test
    public void testSsCopyDatabaseDirectoryNoOpWhenSourceHasNoAccessor() throws Exception
    {
        // The source exposes neither getConfigDirectory nor getPath -> no-op, no throw.
        StubFileDatabase2026 to = new StubFileDatabase2026("keep"); //$NON-NLS-1$
        CreateInfobaseTool.ssCopyDatabaseDirectory(new Object(), to);
        assertEquals("keep", to.getPath()); //$NON-NLS-1$
    }

    @Test
    public void testSsCopyDatabaseDirectoryNoOpWhenTargetHasNoSetter() throws Exception
    {
        // The target exposes neither setConfigDirectory nor setPath -> no-op, no throw.
        CreateInfobaseTool.ssCopyDatabaseDirectory(new StubFileDatabase2025("C:/data/ib"), //$NON-NLS-1$
            new Object());
    }

    @Test
    public void testSsCopyDatabaseDirectorySkipsNullDirectory() throws Exception
    {
        // A null source directory is not written (the target keeps its value).
        StubFileDatabase2026 to = new StubFileDatabase2026("keep"); //$NON-NLS-1$
        CreateInfobaseTool.ssCopyDatabaseDirectory(new StubFileDatabase2025(null), to);
        assertEquals("keep", to.getPath()); //$NON-NLS-1$
    }

    @Test
    public void testSsEnsureCreateTemplateDatabaseLeavesTemplateCapableDatabaseUntouched()
    {
        // The database already implements an ICreateTemplateDatabase-named interface -> untouched
        // (the 2025.2-compatible / future-proof path): same instance, setDatabase never called.
        StubTemplateCapableDatabase db = new StubTemplateCapableDatabase();
        StubServerConfig cfg = new StubServerConfig(db);
        CreateInfobaseTool.ssEnsureCreateTemplateDatabase(new StubInfobaseWithConfig(cfg));
        assertFalse("setDatabase must not be called for a template-capable database", //$NON-NLS-1$
            cfg.setDatabaseCalled);
        assertSame(db, cfg.getDatabase());
    }

    @Test
    public void testSsEnsureCreateTemplateDatabaseLeavesNullDatabaseAsIs()
    {
        // A null database is left as-is (the delegate then fails with its own honest error).
        StubServerConfig cfg = new StubServerConfig(null);
        CreateInfobaseTool.ssEnsureCreateTemplateDatabase(new StubInfobaseWithConfig(cfg));
        assertFalse(cfg.setDatabaseCalled);
        assertNull(cfg.getDatabase());
    }

    @Test
    public void testSsEnsureCreateTemplateDatabaseToleratesNullConfiguration()
    {
        // getStandaloneServerConfiguration() returning null must be a silent no-op, never a throw.
        CreateInfobaseTool.ssEnsureCreateTemplateDatabase(new StubInfobaseWithConfig(null));
    }

    @Test
    public void testSsEnsureCreateTemplateDatabaseIsBestEffortWhenBundleClassUnavailable()
    {
        // A plain (non-template) database triggers the replacement branch, whose bundle class-load
        // (FileCreateTemplateDatabase via the db's own classloader) cannot succeed headlessly. The
        // whole ensure is BEST-EFFORT: no throw, database left unchanged, setDatabase never called
        // (on 2025.2 a plain FileDatabase still materializes fine — that path must never regress).
        StubFileDatabase2025 db = new StubFileDatabase2025("C:/data/ib"); //$NON-NLS-1$
        StubServerConfig cfg = new StubServerConfig(db);
        CreateInfobaseTool.ssEnsureCreateTemplateDatabase(new StubInfobaseWithConfig(cfg));
        assertFalse("a failed swap must leave the original database in place", //$NON-NLS-1$
            cfg.setDatabaseCalled);
        assertSame(db, cfg.getDatabase());
    }

    // ==================== Stubs (plain classes ssMethodAny/ssSetCreateFlag introspect) ====================

    /** A 2025.2-shaped {@code StandaloneServerInfobase} stub: only {@code setCreate(boolean)}. */
    public static final class StubOnlySetCreate
    {
        boolean created;

        public void setCreate(boolean value)
        {
            created = value;
        }
    }

    /** A 2026.1-shaped {@code StandaloneServerInfobase} stub: only {@code setCreateNewInfobase(boolean)}. */
    public static final class StubOnlySetCreateNewInfobase
    {
        boolean created;

        public void setCreateNewInfobase(boolean value)
        {
            created = value;
        }
    }

    /** A stub exposing NEITHER create-flag setter name (the both-names error path). */
    public static final class StubNeitherSetter
    {
        // deliberately no setCreate / setCreateNewInfobase
    }

    /**
     * #273: stands in for the platform's create-template marker interface — matched by SIMPLE name
     * (the live one is {@code com.e1c...standaloneserver.core.config.ICreateTemplateDatabase}).
     */
    public interface ICreateTemplateDatabase
    {
        // marker
    }

    /** #273: an interface EXTENDING the marker (the superinterface-walk branch). */
    public interface IExtendedTemplateDatabase extends ICreateTemplateDatabase
    {
        // marker
    }

    /** #273: a database that implements the marker interface DIRECTLY (needs no replacement). */
    public static class StubTemplateCapableDatabase implements ICreateTemplateDatabase
    {
        // marker only
    }

    /** #273: a database inheriting the marker via its SUPERCLASS (the hierarchy-walk branch). */
    public static final class StubTemplateCapableSubclass extends StubTemplateCapableDatabase
    {
        // marker only, via the superclass
    }

    /** #273: a database reaching the marker via a superINTERFACE of an implemented interface. */
    public static final class StubExtendedTemplateDatabase implements IExtendedTemplateDatabase
    {
        // marker only, via the extended interface
    }

    /** #273: a 2025.2-shaped plain file database — getConfigDirectory/setConfigDirectory accessors. */
    public static final class StubFileDatabase2025
    {
        private String dir;

        StubFileDatabase2025(String dir)
        {
            this.dir = dir;
        }

        public String getConfigDirectory()
        {
            return dir;
        }

        public void setConfigDirectory(String dir)
        {
            this.dir = dir;
        }
    }

    /** #273: a 2026.1-shaped plain file database — the accessors were RENAMED to getPath/setPath. */
    public static final class StubFileDatabase2026
    {
        private String path;

        StubFileDatabase2026(String path)
        {
            this.path = path;
        }

        public String getPath()
        {
            return path;
        }

        public void setPath(String path)
        {
            this.path = path;
        }
    }

    /** #273: the module config stand-in — getDatabase/setDatabase (setDatabase call is recorded). */
    public static final class StubServerConfig
    {
        private Object database;
        boolean setDatabaseCalled;

        StubServerConfig(Object database)
        {
            this.database = database;
        }

        public Object getDatabase()
        {
            return database;
        }

        public void setDatabase(Object database)
        {
            this.database = database;
            setDatabaseCalled = true;
        }
    }

    /** #273: the StandaloneServerInfobase stand-in exposing its module configuration. */
    public static final class StubInfobaseWithConfig
    {
        private final Object configuration;

        StubInfobaseWithConfig(Object configuration)
        {
            this.configuration = configuration;
        }

        public Object getStandaloneServerConfiguration()
        {
            return configuration;
        }
    }
}
