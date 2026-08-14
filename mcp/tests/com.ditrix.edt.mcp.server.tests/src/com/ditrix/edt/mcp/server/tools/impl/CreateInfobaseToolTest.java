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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.model.FileConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.IApplicationType;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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

    // ==================== #412: the report follows what the read-back ESTABLISHED ====================
    //
    // The tool polls the application manager after associate() precisely because the binding surfaces
    // asynchronously. These tests drive that read-back through a stubbed IApplicationManager and pin
    // the three outcomes apart: found (bound), measured absent (an error that still says the database
    // exists), and unreadable (success WITHOUT a binding claim - a failed read proves nothing).

    /** Project name used by the read-back tests. */
    private static final String RB_PROJECT = "ReadBackProject"; //$NON-NLS-1$

    /** Infobase display name used by the read-back tests. */
    private static final String RB_INFOBASE = "ReadBackBase"; //$NON-NLS-1$

    /** Connection string shared by the new-infobase reference and its matching application. */
    private static final String RB_CONNECTION = "File=\"C:\\infobases\\ReadBack\";"; //$NON-NLS-1$

    /** A readable connection string that is decidably NOT the new infobase. */
    private static final String OTHER_CONNECTION = "File=\"D:/other\";"; //$NON-NLS-1$

    /** The claim the tool must never make on its own: it is only true when the read-back found the app. */
    private static final String BOUND_CLAIM = "bound to project"; //$NON-NLS-1$

    @Test
    public void testOutputSchemaDeclaresBoundToProject()
    {
        // The binding fact must be machine-readable, not only prose (#412).
        String schema = new CreateInfobaseTool().getOutputSchema();
        assertTrue("outputSchema must declare boundToProject", //$NON-NLS-1$
            schema.contains("\"boundToProject\"")); //$NON-NLS-1$
    }

    @Test
    public void testMeasuredMissingApplicationIsAnErrorNotABoundClaim() throws Exception
    {
        // Every read-back completes and none lists the new application: absence is MEASURED. The tool
        // must not report success, and must not claim the infobase is bound to the project.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplications(project)).thenReturn(Collections.emptyList());

        JsonObject json = readBackResult(mgr, project, false, false, null);

        assertFalse("a measured missing application must not be reported as success", //$NON-NLS-1$
            json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue("the established fact must be machine-readable", //$NON-NLS-1$
            json.has("boundToProject")); //$NON-NLS-1$
        assertFalse("boundToProject must say false, not be omitted", //$NON-NLS-1$
            json.get("boundToProject").getAsBoolean()); //$NON-NLS-1$
        assertFalse("the tool must not claim a binding it just measured to be absent", //$NON-NLS-1$
            json.get("error").getAsString().contains(BOUND_CLAIM)); //$NON-NLS-1$
    }

    @Test
    public void testMeasuredMissingApplicationErrorStillReportsTheDatabase() throws Exception
    {
        // The refusal must not read as "nothing happened": the database WAS created, and the payload
        // has to say so - what happened to it, where it is, and under which name.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplications(project)).thenReturn(Collections.emptyList());

        JsonObject json = readBackResult(mgr, project, false, false, null);

        assertEquals("created", json.get("action").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(RB_INFOBASE, json.get("infobaseName").getAsString()); //$NON-NLS-1$
        assertEquals(readBackDir().toString(), json.get("infobaseFile").getAsString()); //$NON-NLS-1$
        assertTrue("the applications read-back is the evidence and must be echoed", //$NON-NLS-1$
            json.has("applications")); //$NON-NLS-1$
        String error = json.get("error").getAsString(); //$NON-NLS-1$
        assertTrue("the error must say the database files are intact", //$NON-NLS-1$
            error.contains("do NOT create them again")); //$NON-NLS-1$
        assertTrue("the error must name the call that settles it", //$NON-NLS-1$
            error.contains("get_applications('" + RB_PROJECT + "')")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the error must name what is now blocked", //$NON-NLS-1$
            error.contains("create_launch_config")); //$NON-NLS-1$
    }

    @Test
    public void testMeasuredMissingApplicationSaysTheExistingDatabaseIsUntouchedOnRegister()
        throws Exception
    {
        // mode='register' did not create anything, so the refusal must not claim it did.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplications(project)).thenReturn(Collections.emptyList());

        JsonObject json = readBackResult(mgr, project, false, true, null);

        assertEquals("registered", json.get("action").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        String error = json.get("error").getAsString(); //$NON-NLS-1$
        assertTrue("register mode must say the existing database is untouched", //$NON-NLS-1$
            error.contains("The existing database is untouched")); //$NON-NLS-1$
        assertFalse("register mode must not claim files were created", //$NON-NLS-1$
            error.contains("were created and are intact")); //$NON-NLS-1$
    }

    @Test
    public void testUnreadableApplicationsAreUnverifiedNotAbsent() throws Exception
    {
        // The read-back itself fails: absence was NOT established. A false refusal costs more than a
        // missing claim, so this stays a success - but it must not claim the binding either, and the
        // machine-readable flag must be ABSENT rather than guessing false.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplications(project)).thenThrow(new ApplicationException("index is cold")); //$NON-NLS-1$

        JsonObject json = readBackResult(mgr, project, false, false, null);

        assertTrue("a failed read does not disprove the binding - keep the call successful", //$NON-NLS-1$
            json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse("boundToProject must be ABSENT: neither answer was established", //$NON-NLS-1$
            json.has("boundToProject")); //$NON-NLS-1$
        String message = json.get("message").getAsString(); //$NON-NLS-1$
        assertFalse("the tool must not claim a binding it could not read", //$NON-NLS-1$
            message.contains(BOUND_CLAIM));
        assertTrue("the message must name the state", message.contains("UNVERIFIED")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the message must name the call that settles it", //$NON-NLS-1$
            message.contains("get_applications('" + RB_PROJECT + "')")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFoundApplicationReportsTheBindingAndTheApplicationId() throws Exception
    {
        // The read-back finds the new application: this is the ONE case that may claim the binding.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        List<IApplication> found = Collections.singletonList(matchingInfobaseApp("app-42")); //$NON-NLS-1$
        when(mgr.getApplications(project)).thenReturn(found);

        JsonObject json = readBackResult(mgr, project, false, false, null);

        assertTrue(json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue("a found application must be reported as bound", //$NON-NLS-1$
            json.get("boundToProject").getAsBoolean()); //$NON-NLS-1$
        assertEquals("app-42", json.get("applicationId").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the success message may state the binding here", //$NON-NLS-1$
            json.get("message").getAsString().contains(BOUND_CLAIM)); //$NON-NLS-1$
    }

    @Test
    public void testBoundMessageIsUnchangedForExistingCallers() throws Exception
    {
        // The whole point of the three-way report is that the HEALTHY case is untouched: a caller
        // that gets its application today must see byte-for-byte the same message.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        List<IApplication> found = Collections.singletonList(matchingInfobaseApp("app-1")); //$NON-NLS-1$
        when(mgr.getApplications(project)).thenReturn(found);

        JsonObject json = readBackResult(mgr, project, false, false, null);

        assertEquals("Infobase '" + RB_INFOBASE + "' created at '" + readBackDir() //$NON-NLS-1$ //$NON-NLS-2$
            + "' and bound to project '" + RB_PROJECT //$NON-NLS-1$
            + "'. Use update_database to push the configuration into the infobase.", //$NON-NLS-1$
            json.get("message").getAsString()); //$NON-NLS-1$
    }

    @Test
    public void testFoundApplicationWithoutIdIsStillReportedAsBound() throws Exception
    {
        // The binding is established by the MATCH, not by the id: an application the platform gave no
        // id is still an application of the project, and dropping that fact was the same defect.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        List<IApplication> found = Collections.singletonList(matchingInfobaseApp(null));
        when(mgr.getApplications(project)).thenReturn(found);

        JsonObject json = readBackResult(mgr, project, false, false, null);

        assertTrue(json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue("a matched application without an id is still bound", //$NON-NLS-1$
            json.get("boundToProject").getAsBoolean()); //$NON-NLS-1$
        assertFalse("there is no id to echo", json.has("applicationId")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testSetDefaultNoteIsAboutSetDefaultNotTheMissingApplication() throws Exception
    {
        // The old note blamed the MISSING APPLICATION on the setDefault flag ("could not be set as
        // default yet"), which reads as cosmetic. setDefault talks about setDefault only.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplications(project)).thenReturn(Collections.emptyList());

        JsonObject json = readBackResult(mgr, project, true, false, null);

        String error = json.get("error").getAsString(); //$NON-NLS-1$
        assertTrue("setDefault must report its own outcome", //$NON-NLS-1$
            error.contains("setDefault was not applied")); //$NON-NLS-1$
        assertFalse("the missing application must not be dressed up as a setDefault hiccup", //$NON-NLS-1$
            error.contains("could not be set as default yet")); //$NON-NLS-1$
        assertFalse("the note must read as a sentence, not be glued on with '.;'", //$NON-NLS-1$
            error.contains(".;")); //$NON-NLS-1$
        verify(mgr, never()).setDefaultApplication(any(IProject.class), any(IApplication.class));
    }

    @Test
    public void testSetDefaultUsesTheApplicationTheReadBackFound() throws Exception
    {
        // setDefault now runs AFTER the read-back and on the application it found - it no longer does
        // its own earlier lookup that could miss an application the re-poll then sees.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        IApplication app = matchingInfobaseApp("app-7"); //$NON-NLS-1$
        List<IApplication> found = Collections.singletonList(app);
        when(mgr.getApplications(project)).thenReturn(found);

        JsonObject json = readBackResult(mgr, project, true, false, null);

        verify(mgr).setDefaultApplication(project, app);
        assertFalse("nothing failed, so no setDefault note", //$NON-NLS-1$
            json.get("message").getAsString().contains("setDefault")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testStandaloneServerMeasuredMissingApplicationIsAnErrorToo() throws Exception
    {
        // The mirror path surfaces through the SAME provision-delegate listener, so it has the same
        // "requested, never appeared" state and must report it the same way - while still handing back
        // the endpoint EDT resolved for the server it did register.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplications(project)).thenReturn(Collections.emptyList());

        String raw = CreateInfobaseTool.buildStandaloneServerResult(readBackContext(mgr, project), 8314,
            "http://localhost:8314/base", false, false, //$NON-NLS-1$
            new CreateInfobaseTool.Credentials(null, null, null));
        JsonObject json = JsonParser.parseString(raw).getAsJsonObject();

        assertFalse("a measured missing application must not be reported as success", //$NON-NLS-1$
            json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(json.get("boundToProject").getAsBoolean()); //$NON-NLS-1$
        assertFalse("the tool must not claim a binding it just measured to be absent", //$NON-NLS-1$
            json.get("error").getAsString().contains(BOUND_CLAIM)); //$NON-NLS-1$
        assertEquals("standaloneServer", json.get("applicationKind").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(8314, json.get("port").getAsInt()); //$NON-NLS-1$
        assertEquals("http://localhost:8314/base", json.get("webUrl").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNoApplicationListAtAllIsUnverifiedNotAbsent() throws Exception
    {
        // A null snapshot is not an empty snapshot: it inspected nothing, so it cannot MEASURE the
        // application to be absent. Counting it as a clean empty read would turn "we do not know"
        // into a refusal.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplications(project)).thenReturn(null);

        JsonObject json = readBackResult(mgr, project, false, false, null);

        assertTrue("a missing snapshot must not refuse the call", //$NON-NLS-1$
            json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse("boundToProject must be ABSENT: nothing was established", //$NON-NLS-1$
            json.has("boundToProject")); //$NON-NLS-1$
        assertFalse("an empty applications echo would claim the project has none - " //$NON-NLS-1$
            + "a read that produced no snapshot never said that", json.has("applications")); //$NON-NLS-1$
        assertTrue(json.get("message").getAsString().contains("UNVERIFIED")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testLaterReadFailureKeepsTheSnapshotTheEarlierReadProduced() throws Exception
    {
        // A read that failed on a LATER poll must not erase what the earlier, successful read saw:
        // the echo is evidence, and throwing it away would leave the caller with less than we know.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        List<IApplication> others = Collections.singletonList(unrelatedApp("other-app")); //$NON-NLS-1$
        when(mgr.getApplications(project)).thenReturn(others)
            .thenThrow(new ApplicationException("index went cold")); //$NON-NLS-1$

        JsonObject json = readBackResult(mgr, project, false, false, null);

        assertTrue(json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(json.has("boundToProject")); //$NON-NLS-1$
        assertTrue("the earlier snapshot must survive the later failure", //$NON-NLS-1$
            json.has("applications")); //$NON-NLS-1$
        assertTrue("the reason must be that the read-back could not COMPLETE", //$NON-NLS-1$
            json.get("message").getAsString() //$NON-NLS-1$
                .contains("the application read-back could not be completed")); //$NON-NLS-1$
        assertEquals(1, json.get("applications").getAsJsonArray().size()); //$NON-NLS-1$
    }

    @Test
    public void testApplicationAppearingOnALaterPollIsStillBound() throws Exception
    {
        // The whole point of the bounded re-poll: the application surfaces asynchronously. A first
        // empty read must not decide the outcome.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        List<IApplication> found = Collections.singletonList(matchingInfobaseApp("late-app")); //$NON-NLS-1$
        when(mgr.getApplications(project)).thenReturn(Collections.emptyList()).thenReturn(found);

        JsonObject json = readBackResult(mgr, project, false, false, null);

        assertTrue("a late application is still a bound application", //$NON-NLS-1$
            json.get("boundToProject").getAsBoolean()); //$NON-NLS-1$
        assertEquals("late-app", json.get("applicationId").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInterruptedReadBackDoesNotBlameAReadFailure() throws Exception
    {
        // Interrupted before the budget was spent: the reads that ran saw nothing, but nothing FAILED
        // either - so the message must not send the caller to the EDT error log for an entry that was
        // never written.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplications(project)).thenReturn(Collections.emptyList());

        JsonObject json;
        Thread.currentThread().interrupt();
        try
        {
            json = readBackResult(mgr, project, false, false, null);
        }
        finally
        {
            Thread.interrupted(); // clear the flag the poll restored, so later tests are unaffected
        }

        assertTrue("an interrupted read-back establishes no absence", //$NON-NLS-1$
            json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(json.has("boundToProject")); //$NON-NLS-1$
        String message = json.get("message").getAsString(); //$NON-NLS-1$
        assertTrue("the message must name the real reason", //$NON-NLS-1$
            message.contains("interrupted before its budget was spent")); //$NON-NLS-1$
        assertFalse("nothing failed, so do not promise an EDT log entry", //$NON-NLS-1$
            message.contains("EDT error log")); //$NON-NLS-1$
    }

    @Test
    public void testUnreadableApplicationIdentityIsUnverifiedNotAbsence() throws Exception
    {
        // An infobase application whose connection string cannot be read is NOT evidence that our
        // infobase is missing - it is evidence that we could not tell. Folding that into "no match"
        // would re-create exactly the reported defect, with the verdict flipped: a confident
        // boundToProject:false while the application may well be there.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        List<IApplication> opaque = Collections.singletonList(opaqueInfobaseApp());
        when(mgr.getApplications(project)).thenReturn(opaque);

        JsonObject json = readBackResult(mgr, project, false, false, null);

        assertTrue("an uncomparable identity must not produce a refusal", //$NON-NLS-1$
            json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse("boundToProject must be ABSENT: the comparison established nothing", //$NON-NLS-1$
            json.has("boundToProject")); //$NON-NLS-1$
        String message = json.get("message").getAsString(); //$NON-NLS-1$
        assertTrue("the message must say what could not be done", //$NON-NLS-1$
            message.contains("could not be compared with the new infobase")); //$NON-NLS-1$
        assertFalse(message.contains(BOUND_CLAIM));
    }

    @Test
    public void testStandaloneServerFoundApplicationReportsTheBinding() throws Exception
    {
        // The standalone success path has its own message builder, so it needs its own proof that a
        // found application is reported as bound (and carries the endpoint).
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        List<IApplication> found = Collections.singletonList(matchingServerApp("ServerApplication.X")); //$NON-NLS-1$
        when(mgr.getApplications(project)).thenReturn(found);

        String raw = CreateInfobaseTool.buildStandaloneServerResult(readBackContext(mgr, project), 8314,
            "http://localhost:8314/base", false, false, //$NON-NLS-1$
            new CreateInfobaseTool.Credentials(null, null, null));
        JsonObject json = JsonParser.parseString(raw).getAsJsonObject();

        assertTrue(json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(json.get("boundToProject").getAsBoolean()); //$NON-NLS-1$
        assertEquals("ServerApplication.X", json.get("applicationId").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.get("message").getAsString().contains(BOUND_CLAIM)); //$NON-NLS-1$
        assertEquals(8314, json.get("port").getAsInt()); //$NON-NLS-1$
    }

    @Test
    public void testUnreadableConnectionStringIsUnverifiedNotAbsence() throws Exception
    {
        // The other half of "identity could not be read": the application HAS an infobase reference,
        // but reading its connection string throws. Same rule - we could not tell, so we do not say.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        List<IApplication> opaque = Collections.singletonList(infobaseAppWithRef(throwingRef()));
        when(mgr.getApplications(project)).thenReturn(opaque);

        JsonObject json = readBackResult(mgr, project, false, false, null);

        assertTrue(json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(json.has("boundToProject")); //$NON-NLS-1$
        assertTrue(json.get("message").getAsString() //$NON-NLS-1$
            .contains("an identity could not be read")); //$NON-NLS-1$
    }

    @Test
    public void testDifferentInfobaseIsADecidableMissAndRefuses() throws Exception
    {
        // The opposite of the two tests above: a READABLE identity that is decidably a different
        // infobase is exactly the case the refusal is for. Without this, "undecidable" could swallow
        // the whole feature and nothing would notice.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        List<IApplication> others =
            Collections.singletonList(infobaseAppWithRef(fileRef(OTHER_CONNECTION)));
        when(mgr.getApplications(project)).thenReturn(others);

        JsonObject json = readBackResult(mgr, project, false, false, null);

        assertFalse("a decidably different infobase is a measured absence", //$NON-NLS-1$
            json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(json.get("boundToProject").getAsBoolean()); //$NON-NLS-1$
        assertEquals("the other application must still be echoed as evidence", //$NON-NLS-1$
            1, json.get("applications").getAsJsonArray().size()); //$NON-NLS-1$
    }

    @Test
    public void testAnEarlierUndecidableReadDoesNotPoisonALaterCompleteOne() throws Exception
    {
        // Each poll is a WHOLE snapshot of the project's applications, so an earlier poll that could
        // not identify something is irrelevant once a later poll compared every application. The
        // undecidable flag is therefore per-poll, and the last poll decides.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        List<IApplication> opaque = Collections.singletonList(opaqueInfobaseApp());
        List<IApplication> readable =
            Collections.singletonList(infobaseAppWithRef(fileRef(OTHER_CONNECTION)));
        when(mgr.getApplications(project)).thenReturn(opaque).thenReturn(readable);

        JsonObject json = readBackResult(mgr, project, false, false, null);

        assertFalse("the later complete comparison decides", //$NON-NLS-1$
            json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(json.get("boundToProject").getAsBoolean()); //$NON-NLS-1$
    }

    @Test
    public void testBoundApplicationWithoutIdDoesNotSendTheCallerToUpdateDatabase() throws Exception
    {
        // update_database is addressed BY applicationId, so advising it without one is advice that
        // cannot be followed.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        List<IApplication> found = Collections.singletonList(matchingInfobaseApp(null));
        when(mgr.getApplications(project)).thenReturn(found);

        String message = readBackResult(mgr, project, false, false, null)
            .get("message").getAsString(); //$NON-NLS-1$

        assertFalse("there is no id to chain with", message.contains("Use update_database")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the message must say how to get one", //$NON-NLS-1$
            message.contains("look it up with get_applications")); //$NON-NLS-1$
    }

    @Test
    public void testMissingConnectionStringIsUnverifiedNotAbsence() throws Exception
    {
        // The third undecidable shape: the reference exists but carries no connection string at all.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getConnectionString()).thenReturn(null);
        List<IApplication> opaque = Collections.singletonList(infobaseAppWithRef(ref));
        when(mgr.getApplications(project)).thenReturn(opaque);

        JsonObject json = readBackResult(mgr, project, false, false, null);

        assertTrue(json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(json.has("boundToProject")); //$NON-NLS-1$
    }

    @Test
    public void testRefusalCarriesItsTextInErrorOnly() throws Exception
    {
        // The refusal's text lives in `error` - a second `message` copy could drift from it, and a
        // caller reading only `message` would see nothing at all.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplications(project)).thenReturn(Collections.emptyList());

        JsonObject json = readBackResult(mgr, project, false, false, null);

        assertTrue(json.has("error")); //$NON-NLS-1$
        assertFalse("no second copy of the text to drift from `error`", //$NON-NLS-1$
            json.has("message")); //$NON-NLS-1$
    }

    @Test
    public void testSetDefaultUnderUnverifiedDoesNotAssumeTheApplicationIsMissing() throws Exception
    {
        // UNVERIFIED established nothing, so the setDefault note must not tell the caller to wait for
        // an application that may well be there already.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplications(project)).thenThrow(new ApplicationException("index is cold")); //$NON-NLS-1$

        String message = readBackResult(mgr, project, true, false, null)
            .get("message").getAsString(); //$NON-NLS-1$

        assertTrue("setDefault must report its own outcome", //$NON-NLS-1$
            message.contains("setDefault was not applied")); //$NON-NLS-1$
        assertFalse("nothing established the application to be missing", //$NON-NLS-1$
            message.contains("once get_applications lists it")); //$NON-NLS-1$
        verify(mgr, never()).setDefaultApplication(any(IProject.class), any(IApplication.class));
    }

    @Test
    public void testSetDefaultFailureIsReportedInsteadOfSwallowed() throws Exception
    {
        // The caller ASKED for the default to be set and the failure was established - saying nothing
        // (the old behaviour on this path) is the same class of silence this issue is about.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        List<IApplication> found = Collections.singletonList(matchingInfobaseApp("app-9")); //$NON-NLS-1$
        when(mgr.getApplications(project)).thenReturn(found);
        org.mockito.Mockito.doThrow(new ApplicationException("no default for you")) //$NON-NLS-1$
            .when(mgr).setDefaultApplication(any(IProject.class), any(IApplication.class));

        String message = readBackResult(mgr, project, true, false, null)
            .get("message").getAsString(); //$NON-NLS-1$

        assertTrue("the established failure must be reported", //$NON-NLS-1$
            message.contains("setDefault failed: no default for you")); //$NON-NLS-1$
        assertTrue("the binding itself still holds", message.contains(BOUND_CLAIM)); //$NON-NLS-1$
    }

    @Test
    public void testStandaloneServerUnreadableApplicationsAreUnverified() throws Exception
    {
        // The standalone path assembles its own UNVERIFIED message, so it needs its own proof.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplications(project)).thenThrow(new ApplicationException("index is cold")); //$NON-NLS-1$

        String raw = CreateInfobaseTool.buildStandaloneServerResult(readBackContext(mgr, project), 8314,
            "http://localhost:8314/base", false, false, //$NON-NLS-1$
            new CreateInfobaseTool.Credentials(null, null, null));
        JsonObject json = JsonParser.parseString(raw).getAsJsonObject();

        assertTrue(json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse("boundToProject must be ABSENT: nothing was established", //$NON-NLS-1$
            json.has("boundToProject")); //$NON-NLS-1$
        String message = json.get("message").getAsString(); //$NON-NLS-1$
        assertTrue(message.contains("UNVERIFIED")); //$NON-NLS-1$
        assertFalse(message.contains(BOUND_CLAIM));
        assertTrue("the resolved endpoint is still reported", //$NON-NLS-1$
            message.contains("web port 8314")); //$NON-NLS-1$
    }

    @Test
    public void testUnreadableApplicationTypeIsUnverifiedNotAbsence() throws Exception
    {
        // "I could not see what this application is" is not "this is not the one". If the entry with
        // the unreadable type IS the new infobase, calling it a miss produces exactly the false
        // refusal this issue is about - with the verdict flipped.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        List<IApplication> untyped = Collections.singletonList(infobaseAppWithType(null));
        when(mgr.getApplications(project)).thenReturn(untyped);

        assertUnverified(readBackResult(mgr, project, false, false, null));
    }

    @Test
    public void testUnreadableApplicationTypeIdIsUnverifiedNotAbsence() throws Exception
    {
        // Same rule one level down: the type object is there but yields no id.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        IApplicationType type = mock(IApplicationType.class);
        when(type.getId()).thenReturn(null);
        List<IApplication> untyped = Collections.singletonList(infobaseAppWithType(type));
        when(mgr.getApplications(project)).thenReturn(untyped);

        assertUnverified(readBackResult(mgr, project, false, false, null));
    }

    @Test
    public void testUnreadableApplicationDuringTheReadIsUnverifiedNotAnInternalError() throws Exception
    {
        // A stale application that THROWS while being read (here: while its echo entry is built).
        // The promised third outcome has to actually happen: not an exception out of the tool, and
        // not a refusal.
        //
        // It is also CONFINED to that one application: the rest of the snapshot is still read and
        // echoed. That is what separates "one entry is opaque" from "the read failed" - two states
        // that would otherwise be indistinguishable, and only one of them keeps the evidence.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        IApplication hostile = mock(IApplication.class);
        when(hostile.getId()).thenReturn("hostile"); //$NON-NLS-1$
        when(hostile.getName()).thenReturn("Hostile"); //$NON-NLS-1$
        when(hostile.getType()).thenThrow(new IllegalStateException("proxy is detached")); //$NON-NLS-1$
        List<IApplication> snapshot = Arrays.asList(hostile, unrelatedApp("readable")); //$NON-NLS-1$
        when(mgr.getApplications(project)).thenReturn(snapshot);

        // Must not throw: the read-back has to CLASSIFY the failure, not propagate it.
        JsonObject json = readBackResult(mgr, project, false, false, null);

        assertUnverified(json);
        assertTrue("the rest of the snapshot must survive one opaque entry", //$NON-NLS-1$
            json.has("applications")); //$NON-NLS-1$
        assertEquals("the readable application is still there; only the opaque one is missing", //$NON-NLS-1$
            1, json.get("applications").getAsJsonArray().size()); //$NON-NLS-1$
        assertEquals("readable", json.get("applications").getAsJsonArray().get(0) //$NON-NLS-1$ //$NON-NLS-2$
            .getAsJsonObject().get("id").getAsString()); //$NON-NLS-1$
        assertTrue("the reason must be the failed COMPARISON, not a failed listing", //$NON-NLS-1$
            json.get("message").getAsString() //$NON-NLS-1$
                .contains("could not be compared with the new infobase")); //$NON-NLS-1$
    }

    @Test
    public void testUnrenderableApplicationIsUnverifiedNotAbsence() throws Exception
    {
        // An application that cannot even be rendered was still an application: reporting the read
        // as complete-and-empty would state exactly what it failed to check. The listing succeeded,
        // so the (empty) echo is honest - but the outcome must be unverified, and the message has to
        // say that one application could not be read, or the empty echo would be read as "none".
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        IApplication hostile = mock(IApplication.class);
        when(hostile.getId()).thenThrow(new IllegalStateException("proxy is detached")); //$NON-NLS-1$
        when(mgr.getApplications(project)).thenReturn(Collections.singletonList(hostile));

        JsonObject json = readBackResult(mgr, project, false, false, null);

        assertUnverified(json);
        assertTrue("the message must not leave an empty echo to speak for itself", //$NON-NLS-1$
            json.get("message").getAsString() //$NON-NLS-1$
                .contains("could not be compared with the new infobase")); //$NON-NLS-1$
    }

    @Test
    public void testStandaloneServerUnreadableTypeIsUnverifiedNotAbsence() throws Exception
    {
        // The mirror path shares the type gate, so it must inherit the same answer.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        IApplication untyped = mock(IApplication.class);
        when(untyped.getId()).thenReturn("srv"); //$NON-NLS-1$
        when(untyped.getName()).thenReturn(RB_INFOBASE);
        when(untyped.getType()).thenReturn(null);
        when(mgr.getApplications(project)).thenReturn(Collections.singletonList(untyped));

        String raw = CreateInfobaseTool.buildStandaloneServerResult(readBackContext(mgr, project), 8314,
            "http://localhost:8314/base", false, false, //$NON-NLS-1$
            new CreateInfobaseTool.Credentials(null, null, null));

        assertUnverified(JsonParser.parseString(raw).getAsJsonObject());
    }

    @Test
    public void testStandaloneServerUnreadableNameIsUnverifiedNotAbsence() throws Exception
    {
        // The other half of the server's identity: right type, unreadable name.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        IApplicationType type = mock(IApplicationType.class);
        when(type.getId()).thenReturn("com.e1c.g5.dt.applications.type.wst-server"); //$NON-NLS-1$
        IApplication nameless = mock(IApplication.class);
        when(nameless.getId()).thenReturn("srv"); //$NON-NLS-1$
        when(nameless.getName()).thenReturn(null);
        when(nameless.getType()).thenReturn(type);
        when(mgr.getApplications(project)).thenReturn(Collections.singletonList(nameless));

        String raw = CreateInfobaseTool.buildStandaloneServerResult(readBackContext(mgr, project), 8314,
            "http://localhost:8314/base", false, false, //$NON-NLS-1$
            new CreateInfobaseTool.Credentials(null, null, null));

        assertUnverified(JsonParser.parseString(raw).getAsJsonObject());
    }

    @Test
    public void testUnreadableIdOfTheFoundApplicationStillReportsTheBinding() throws Exception
    {
        // The MATCH established the binding; losing the id echo afterwards must not turn a
        // successful call into an internal failure.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        IApplicationType type = mock(IApplicationType.class);
        when(type.getId()).thenReturn("com.e1c.g5.dt.applications.type.infobase"); //$NON-NLS-1$
        InfobaseReference reference = fileRef(RB_CONNECTION);
        IInfobaseApplication app = mock(IInfobaseApplication.class);
        when(app.getName()).thenReturn(RB_INFOBASE);
        when(app.getType()).thenReturn(type);
        when(app.getInfobase()).thenReturn(reference);
        when(app.getId()).thenReturn("app-x").thenThrow(new IllegalStateException("detached")); //$NON-NLS-1$ //$NON-NLS-2$
        List<IApplication> found = Collections.singletonList(app);
        when(mgr.getApplications(project)).thenReturn(found);

        JsonObject json = readBackResult(mgr, project, false, false, null);

        assertTrue(json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue("the match established the binding", //$NON-NLS-1$
            json.get("boundToProject").getAsBoolean()); //$NON-NLS-1$
        assertEquals("the id echoed by the first (successful) read is the one reported", //$NON-NLS-1$
            "app-x", json.get("applicationId").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testCredentialStoreFailureIsANoteNotAFailedCreation() throws Exception
    {
        // The credentials note is documented as never failing the registration - the server is
        // already registered, so a store that throws must not undo that. The promise has to hold by
        // construction, not by the store happening not to throw.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        IApplication hostile = mock(IApplication.class);
        IApplicationType type = mock(IApplicationType.class);
        when(type.getId()).thenReturn("com.e1c.g5.dt.applications.type.wst-server"); //$NON-NLS-1$
        when(hostile.getName()).thenReturn(RB_INFOBASE);
        when(hostile.getType()).thenReturn(type);
        // The store reads the application; a stale one throws instead of answering.
        when(hostile.getId()).thenReturn("srv") //$NON-NLS-1$
            .thenThrow(new IllegalStateException("proxy is detached")); //$NON-NLS-1$
        when(mgr.getApplications(project)).thenReturn(Collections.singletonList(hostile));

        String raw = CreateInfobaseTool.buildStandaloneServerResult(readBackContext(mgr, project), 8314,
            "http://localhost:8314/base", false, true, //$NON-NLS-1$
            new CreateInfobaseTool.Credentials("Admin", "secret", null)); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject json = JsonParser.parseString(raw).getAsJsonObject();

        assertTrue("a failed credentials store must not undo the registration", //$NON-NLS-1$
            json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue("and it must be reported, not swallowed", //$NON-NLS-1$
            json.get("message").getAsString().contains("credentials were NOT stored")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testComparisonItselfThrowingIsUnverified() throws Exception
    {
        // The echo renders fine and the COMPARISON is what throws - the path the previous test could
        // not reach, because there the identity failed while being rendered.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        IApplicationType type = mock(IApplicationType.class);
        when(type.getId()).thenReturn("com.e1c.g5.dt.applications.type.infobase"); //$NON-NLS-1$
        IInfobaseApplication app = mock(IInfobaseApplication.class);
        when(app.getId()).thenReturn("rendersFine"); //$NON-NLS-1$
        when(app.getName()).thenReturn("RendersFine"); //$NON-NLS-1$
        when(app.getType()).thenReturn(type);
        when(app.getInfobase()).thenThrow(new IllegalStateException("proxy is detached")); //$NON-NLS-1$
        when(mgr.getApplications(project)).thenReturn(Collections.singletonList(app));

        JsonObject json = readBackResult(mgr, project, false, false, null);

        assertUnverified(json);
        assertTrue("the entry rendered, so the echo keeps it", json.has("applications")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, json.get("applications").getAsJsonArray().size()); //$NON-NLS-1$
    }

    @Test
    public void testReportedApplicationIdIsTheOneEchoed() throws Exception
    {
        // The id is captured from the echo entry during the same guarded read, so the two can never
        // disagree - and reading it cannot fail later, after the binding was already established.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        InfobaseReference reference = fileRef(RB_CONNECTION);
        IApplicationType type = mock(IApplicationType.class);
        when(type.getId()).thenReturn("com.e1c.g5.dt.applications.type.infobase"); //$NON-NLS-1$
        IInfobaseApplication app = mock(IInfobaseApplication.class);
        when(app.getName()).thenReturn(RB_INFOBASE);
        when(app.getType()).thenReturn(type);
        when(app.getInfobase()).thenReturn(reference);
        // A second read of the id answers DIFFERENTLY: only an implementation that reports the id it
        // echoed can pass, so re-reading the application later would be caught here.
        when(app.getId()).thenReturn("app-echo").thenReturn("different"); //$NON-NLS-1$ //$NON-NLS-2$
        List<IApplication> found = Collections.singletonList(app);
        when(mgr.getApplications(project)).thenReturn(found);

        JsonObject json = readBackResult(mgr, project, false, false, null);

        assertEquals("app-echo", json.get("applicationId").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(json.get("applications").getAsJsonArray().get(0).getAsJsonObject() //$NON-NLS-1$
            .get("id").getAsString(), json.get("applicationId").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAMatchSurvivesAListingThatFailsAfterIt() throws Exception
    {
        // The listing yields our application and THEN breaks. The match is positive evidence that the
        // later failure cannot undo - and the echo reported must be the one the match came from, or
        // the result would name an application missing from its own evidence.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        IApplication matching = matchingInfobaseApp("app-then-boom"); //$NON-NLS-1$
        when(mgr.getApplications(project)).thenReturn(new java.util.AbstractList<IApplication>()
        {
            @Override
            public IApplication get(int index)
            {
                if (index == 0)
                {
                    return matching;
                }
                throw new IllegalStateException("the listing broke after the first entry"); //$NON-NLS-1$
            }

            @Override
            public int size()
            {
                return 2;
            }
        });

        JsonObject json = readBackResult(mgr, project, false, false, null);

        assertTrue("a positive match is not undone by a later failure", //$NON-NLS-1$
            json.get("boundToProject").getAsBoolean()); //$NON-NLS-1$
        assertEquals("app-then-boom", json.get("applicationId").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the echo must be the snapshot the match came from", //$NON-NLS-1$
            json.has("applications")); //$NON-NLS-1$
        assertEquals("app-then-boom", json.get("applications").getAsJsonArray().get(0) //$NON-NLS-1$ //$NON-NLS-2$
            .getAsJsonObject().get("id").getAsString()); //$NON-NLS-1$
    }

    // -------------------- #412 helpers --------------------

    /** The infobase directory the read-back tests report (absolute, so it is platform-independent). */
    private static Path readBackDir()
    {
        return Paths.get("infobases", "ReadBack").toAbsolutePath(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The identity/read-back inputs shared by the result builders. */
    private static CreateInfobaseTool.ResultContext readBackContext(IApplicationManager mgr, IProject project)
    {
        return new CreateInfobaseTool.ResultContext(RB_PROJECT, readBackDir(), RB_INFOBASE, mgr, project);
    }

    /** Runs the file-infobase result builder against the stubbed manager and parses its JSON. */
    private static JsonObject readBackResult(IApplicationManager mgr, IProject project, boolean setDefault,
            boolean register, String credNote)
    {
        String raw = CreateInfobaseTool.buildSuccessResult(readBackContext(mgr, project),
            infobaseRef(), setDefault, register, credNote);
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    /** The new infobase's reference: a FILE reference carrying {@link #RB_CONNECTION}. */
    private static InfobaseReference infobaseRef()
    {
        return fileRef(RB_CONNECTION);
    }

    /**
     * The one assertion every "could not read an identity" case has to satisfy: the call SUCCEEDS
     * (a failed comparison is not evidence of a missing application), it makes no binding claim
     * either way, and it is not the refusal - that separation is the entire point of the change.
     */
    private static void assertUnverified(JsonObject json)
    {
        assertTrue("an inability to compare must not refuse the call", //$NON-NLS-1$
            json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse("unverified must never become the refusal", json.has("error")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("boundToProject must be ABSENT: nothing was established", //$NON-NLS-1$
            json.has("boundToProject")); //$NON-NLS-1$
        String message = json.get("message").getAsString(); //$NON-NLS-1$
        assertTrue("the message must name the state", message.contains("UNVERIFIED")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("and must not claim the binding", message.contains(BOUND_CLAIM)); //$NON-NLS-1$
    }

    /** An infobase-named application carrying the given (possibly null) type. */
    private static IInfobaseApplication infobaseAppWithType(IApplicationType type)
    {
        InfobaseReference reference = fileRef(RB_CONNECTION);
        IInfobaseApplication app = mock(IInfobaseApplication.class);
        when(app.getId()).thenReturn("untyped"); //$NON-NLS-1$
        when(app.getName()).thenReturn("Untyped"); //$NON-NLS-1$
        when(app.getType()).thenReturn(type);
        when(app.getInfobase()).thenReturn(reference);
        return app;
    }

    /** A FILE infobase reference carrying the given connection string. */
    private static InfobaseReference fileRef(String connectionString)
    {
        FileConnectionString connection = mock(FileConnectionString.class);
        when(connection.asConnectionString()).thenReturn(connectionString);
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getConnectionString()).thenReturn(connection);
        return ref;
    }

    /** A reference whose connection string cannot be read at all. */
    private static InfobaseReference throwingRef()
    {
        FileConnectionString connection = mock(FileConnectionString.class);
        when(connection.asConnectionString()).thenThrow(new IllegalStateException("detached")); //$NON-NLS-1$
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getConnectionString()).thenReturn(connection);
        return ref;
    }

    /** An infobase application of the right TYPE holding the given reference. */
    private static IInfobaseApplication infobaseAppWithRef(InfobaseReference reference)
    {
        IApplicationType type = mock(IApplicationType.class);
        when(type.getId()).thenReturn("com.e1c.g5.dt.applications.type.infobase"); //$NON-NLS-1$
        IInfobaseApplication app = mock(IInfobaseApplication.class);
        when(app.getId()).thenReturn("other-infobase"); //$NON-NLS-1$
        when(app.getName()).thenReturn("Other"); //$NON-NLS-1$
        when(app.getType()).thenReturn(type);
        when(app.getInfobase()).thenReturn(reference);
        return app;
    }

    /** An infobase application of the right TYPE whose identity cannot be read at all. */
    private static IInfobaseApplication opaqueInfobaseApp()
    {
        IApplicationType type = mock(IApplicationType.class);
        when(type.getId()).thenReturn("com.e1c.g5.dt.applications.type.infobase"); //$NON-NLS-1$
        IInfobaseApplication app = mock(IInfobaseApplication.class);
        when(app.getId()).thenReturn("opaque"); //$NON-NLS-1$
        when(app.getName()).thenReturn("Opaque"); //$NON-NLS-1$
        when(app.getType()).thenReturn(type);
        when(app.getInfobase()).thenReturn(null);
        return app;
    }

    /** The just-created standalone server: the wst-server type plus the new infobase's name. */
    private static IApplication matchingServerApp(String id)
    {
        IApplicationType type = mock(IApplicationType.class);
        when(type.getId()).thenReturn("com.e1c.g5.dt.applications.type.wst-server"); //$NON-NLS-1$
        IApplication app = mock(IApplication.class);
        when(app.getId()).thenReturn(id);
        when(app.getName()).thenReturn(RB_INFOBASE);
        when(app.getType()).thenReturn(type);
        return app;
    }

    /** An application of another type, which the read-back must NOT match. */
    private static IApplication unrelatedApp(String id)
    {
        IApplicationType type = mock(IApplicationType.class);
        when(type.getId()).thenReturn("com.e1c.g5.dt.applications.type.wst-server"); //$NON-NLS-1$
        IApplication app = mock(IApplication.class);
        when(app.getId()).thenReturn(id);
        when(app.getName()).thenReturn("Unrelated"); //$NON-NLS-1$
        when(app.getType()).thenReturn(type);
        return app;
    }

    /**
     * An application the read-back must match: the infobase application type, holding an infobase
     * reference with the SAME connection string as {@link #infobaseRef()}.
     *
     * @param id the application id, or {@code null} to model a platform that gave it none
     */
    private static IInfobaseApplication matchingInfobaseApp(String id)
    {
        IApplicationType type = mock(IApplicationType.class);
        when(type.getId()).thenReturn("com.e1c.g5.dt.applications.type.infobase"); //$NON-NLS-1$
        InfobaseReference reference = infobaseRef();
        IInfobaseApplication app = mock(IInfobaseApplication.class);
        when(app.getId()).thenReturn(id);
        when(app.getName()).thenReturn(RB_INFOBASE);
        when(app.getType()).thenReturn(type);
        when(app.getInfobase()).thenReturn(reference);
        return app;
    }
}
