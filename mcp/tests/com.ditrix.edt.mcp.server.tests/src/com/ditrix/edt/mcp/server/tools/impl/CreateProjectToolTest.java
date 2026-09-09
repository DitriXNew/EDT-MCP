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
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.emf.ecore.EClass;
import org.junit.Test;

import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.tools.impl.CreateProjectTool.ExternalObjectSpec;

/**
 * Unit tests for {@link CreateProjectTool}.
 * <p>
 * Covers tool name/constant, response type, description (guide pointer,
 * v8codestyle mention), guide non-empty, input schema (all declared params,
 * required array, projectKind enum), output schema (expected fields), and the
 * argument-validation sentinels that return before any EDT API call (headless-safe).
 */
public class CreateProjectToolTest
{
    @Test
    public void testName()
    {
        assertEquals("create_project", new CreateProjectTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(CreateProjectTool.NAME, new CreateProjectTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new CreateProjectTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new CreateProjectTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.trim().isEmpty());
    }

    @Test
    public void testDescriptionPointsToGuide()
    {
        String desc = new CreateProjectTool().getDescription();
        assertTrue("description must steer the agent to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('create_project')")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionMentionsCodestyleOptionality()
    {
        String desc = new CreateProjectTool().getDescription();
        assertTrue("description must mention v8codestyle optionality", //$NON-NLS-1$
            new CreateProjectTool().getGuide().contains("com.e1c.v8codestyle")); //$NON-NLS-1$
    }

    @Test
    public void testGuideNotEmpty()
    {
        String guide = new CreateProjectTool().getGuide();
        assertNotNull(guide);
        assertFalse("getGuide() must be non-empty", guide.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsKeyParams()
    {
        String guide = new CreateProjectTool().getGuide();
        assertTrue("guide must document projectKind", guide.contains("projectKind")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must document baseProjectName", guide.contains("baseProjectName")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must document externalObject", guide.contains("externalObject")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must document normalizeYo", guide.contains("normalizeYo")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must document slow-root verification", //$NON-NLS-1$
            guide.contains("get_metadata_objects") && guide.contains("duplicate-project guard")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must document purpose", guide.contains("purpose")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must document autoSortTopObjects limitation", //$NON-NLS-1$
            guide.contains("autoSortTopObjects")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new CreateProjectTool().getInputSchema();
        assertNotNull(schema);
        assertTrue("schema must declare projectKind", schema.contains("\"projectKind\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare name", schema.contains("\"name\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare projectName", schema.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare version", schema.contains("\"version\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare externalObject", schema.contains("\"externalObject\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare normalizeYo", schema.contains("\"normalizeYo\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare baseProjectName", schema.contains("\"baseProjectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare prefix", schema.contains("\"prefix\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare synonym", schema.contains("\"synonym\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare comment", schema.contains("\"comment\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare purpose", schema.contains("\"purpose\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare compatibilityMode", schema.contains("\"compatibilityMode\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare scriptVariant", schema.contains("\"scriptVariant\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare standardChecks", schema.contains("\"standardChecks\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare commonChecks", schema.contains("\"commonChecks\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare autoSortTopObjects", schema.contains("\"autoSortTopObjects\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInputSchemaProjectKindEnum()
    {
        String schema = new CreateProjectTool().getInputSchema();
        // The projectKind enum must declare all three allowed values
        assertTrue("schema must declare 'configuration' kind", schema.contains("\"configuration\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare 'extension' kind", schema.contains("\"extension\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare 'externalObjects' kind", schema.contains("\"externalObjects\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRequiredArrayMarksOnlyMandatoryParams()
    {
        String schema = new CreateProjectTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectKind must be required", tail.contains("\"projectKind\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("name must be required", tail.contains("\"name\"")); //$NON-NLS-1$ //$NON-NLS-2$
        // Optional params must NOT appear in the required array
        assertFalse("projectName must NOT be required", //$NON-NLS-1$
            tail.contains("\"projectName\",") || tail.contains(",\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("baseProjectName must NOT be required", //$NON-NLS-1$
            tail.contains("\"baseProjectName\",") || tail.contains(",\"baseProjectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("externalObject must NOT be required", //$NON-NLS-1$
            tail.contains("\"externalObject\",") || tail.contains(",\"externalObject\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("normalizeYo must NOT be required", //$NON-NLS-1$
            tail.contains("\"normalizeYo\",") || tail.contains(",\"normalizeYo\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("prefix must NOT be required", //$NON-NLS-1$
            tail.contains("\"prefix\",") || tail.contains(",\"prefix\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("synonym must NOT be required", //$NON-NLS-1$
            tail.contains("\"synonym\",") || tail.contains(",\"synonym\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("purpose must NOT be required", //$NON-NLS-1$
            tail.contains("\"purpose\",") || tail.contains(",\"purpose\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputSchemaDeclaresExpectedFields()
    {
        String schema = new CreateProjectTool().getOutputSchema();
        assertNotNull("outputSchema must not be null", schema); //$NON-NLS-1$
        assertTrue("outputSchema must declare action", schema.contains("\"action\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare project", schema.contains("\"project\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare projectKind", schema.contains("\"projectKind\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare baseProject", schema.contains("\"baseProject\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare state", schema.contains("\"state\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare codestyle", schema.contains("\"codestyle\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare externalObject", schema.contains("\"externalObject\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare externalObjectConfirmed", //$NON-NLS-1$
            schema.contains("\"externalObjectConfirmed\"")); //$NON-NLS-1$
        assertTrue("outputSchema must declare normalized", schema.contains("\"normalized\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ────────── Argument-validation sentinels (return before any EDT API call) ──────────

    @Test
    public void testExternalObjectResolvesBothEnglishRootKinds()
    {
        assertExternalObjectResolution("ExternalDataProcessor.MyProc", //$NON-NLS-1$
            MdClassPackage.Literals.EXTERNAL_DATA_PROCESSOR, "MyProc", "ExternalDataProcessor.MyProc"); //$NON-NLS-1$ //$NON-NLS-2$
        assertExternalObjectResolution("ExternalReport.MyReport", //$NON-NLS-1$
            MdClassPackage.Literals.EXTERNAL_REPORT, "MyReport", "ExternalReport.MyReport"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testExternalObjectResolvesBothRussianRootKinds()
    {
        assertExternalObjectResolution(
            "\u0412\u043D\u0435\u0448\u043D\u044F\u044F\u041E\u0431\u0440\u0430\u0431\u043E\u0442\u043A\u0430.MyProc", //$NON-NLS-1$
            MdClassPackage.Literals.EXTERNAL_DATA_PROCESSOR, "MyProc", "ExternalDataProcessor.MyProc"); //$NON-NLS-1$ //$NON-NLS-2$
        assertExternalObjectResolution(
            "\u0412\u043D\u0435\u0448\u043D\u0438\u0439\u041E\u0442\u0447\u0435\u0442.MyReport", //$NON-NLS-1$
            MdClassPackage.Literals.EXTERNAL_REPORT, "MyReport", "ExternalReport.MyReport"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testExternalObjectYoNameIsStoredNormalizedByDefaultAndReported()
    {
        ExternalObjectSpec spec = CreateProjectTool.resolveExternalObject(
            "ExternalReport.\u0412\u0441\u0451"); //$NON-NLS-1$
        assertNull(spec.error);
        assertEquals("\u0412\u0441\u0435", spec.objectName); //$NON-NLS-1$
        assertEquals("ExternalReport.\u0412\u0441\u0435", spec.canonicalFqn); //$NON-NLS-1$

        MdObject root = createRootFromSpec(spec);
        assertEquals("the Name handed to EDT must already be normalized", //$NON-NLS-1$
            "\u0412\u0441\u0435", root.getName()); //$NON-NLS-1$

        String result = CreateProjectTool.buildExternalObjectsSlowResponse(
            "NoSuchYoProjectForCreateProjectToolTest", "YoProject", Version.LATEST, null, spec); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("result must report the canonical stored root FQN", //$NON-NLS-1$
            result.contains("ExternalReport.\u0412\u0441\u0435")); //$NON-NLS-1$
        assertTrue("result must report the rewritten Name through MdNameNormalizer.Report", //$NON-NLS-1$
            result.contains("\"normalized\":[\"name\"]")); //$NON-NLS-1$
    }

    @Test
    public void testExternalObjectYoNameIsKeptWhenNormalizationDisabled()
    {
        ExternalObjectSpec spec = CreateProjectTool.resolveExternalObject(
            "ExternalReport.\u0412\u0441\u0451", false); //$NON-NLS-1$
        assertNull(spec.error);
        assertEquals("\u0412\u0441\u0451", spec.objectName); //$NON-NLS-1$
        assertEquals("ExternalReport.\u0412\u0441\u0451", spec.canonicalFqn); //$NON-NLS-1$
        assertEquals("normalizeYo=false must preserve the Name handed to EDT exactly", //$NON-NLS-1$
            "\u0412\u0441\u0451", createRootFromSpec(spec).getName()); //$NON-NLS-1$
    }

    @Test
    public void testSlowSeededRootAnswerRequiresVerificationWhenUnconfirmed()
    {
        ExternalObjectSpec spec =
            CreateProjectTool.resolveExternalObject("ExternalDataProcessor.UnconfirmedRoot"); //$NON-NLS-1$
        // A deliberately absent project makes the package-visible response seam's best-effort
        // metadata-scope read return false. Production reaches this builder only after exists().
        String result = CreateProjectTool.buildExternalObjectsSlowResponse(
            "NoSuchSlowProjectForCreateProjectToolTest", "SlowProject", Version.LATEST, null, spec); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("slow answer must name the requested root", //$NON-NLS-1$
            result.contains("ExternalDataProcessor.UnconfirmedRoot")); //$NON-NLS-1$
        assertTrue("slow answer must report that the root was not confirmed", //$NON-NLS-1$
            result.contains("\"externalObjectConfirmed\":false")); //$NON-NLS-1$
        assertTrue("an unresolved read-back is information, not a second tool error", //$NON-NLS-1$
            result.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue("slow answer must require verification", //$NON-NLS-1$
            result.contains("\"action\":\"verificationRequired\"")); //$NON-NLS-1$
        assertFalse("an unconfirmed root must not use the created action", //$NON-NLS-1$
            result.contains("\"action\":\"created\"")); //$NON-NLS-1$
        assertFalse("an unconfirmed root must not use the created state", //$NON-NLS-1$
            result.contains("\"state\":\"created\"")); //$NON-NLS-1$
        assertTrue("slow answer must name the metadata verification route", //$NON-NLS-1$
            result.contains("get_metadata_objects")); //$NON-NLS-1$
        assertTrue("slow answer must explain that retry hits the duplicate guard", //$NON-NLS-1$
            result.contains("Do not repeat create_project") && result.contains("duplicate-project guard")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testSlowEmptyProjectAnswerKeepsExistingCreatedSemantics()
    {
        String result = CreateProjectTool.buildExternalObjectsSlowResponse(
            "NoSuchEmptyProjectForCreateProjectToolTest", "EmptyProject", Version.LATEST, null, null); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("empty-project slow path must retain the created action", //$NON-NLS-1$
            result.contains("\"action\":\"created\"")); //$NON-NLS-1$
        assertTrue("empty-project slow path must retain the created state", //$NON-NLS-1$
            result.contains("\"state\":\"created\"")); //$NON-NLS-1$
        assertFalse("empty-project slow path must not gain seeded-root status fields", //$NON-NLS-1$
            result.contains("externalObjectConfirmed") || result.contains("verificationRequired")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("empty-project slow path must retain its existing message", //$NON-NLS-1$
            result.contains("External objects project 'NoSuchEmptyProjectForCreateProjectToolTest' created " //$NON-NLS-1$
                + "(creation completed past the 120s wait window; project now exists).")); //$NON-NLS-1$
    }

    @Test
    public void testExternalObjectUnknownTypeNamesBothValidKinds()
    {
        ExternalObjectSpec spec = CreateProjectTool.resolveExternalObject("UnknownRoot.MyObject"); //$NON-NLS-1$
        assertNotNull(spec.error);
        assertTrue("error must name the bad externalObject", spec.error.contains("UnknownRoot.MyObject")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must name both supported root kinds", //$NON-NLS-1$
            spec.error.contains("ExternalDataProcessor") && spec.error.contains("ExternalReport")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testExternalObjectBareNameShowsExpectedShape()
    {
        ExternalObjectSpec spec = CreateProjectTool.resolveExternalObject("MyProc"); //$NON-NLS-1$
        assertNotNull(spec.error);
        assertTrue("error must name the bare value", spec.error.contains("MyProc")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must show the ExternalDataProcessor shape", //$NON-NLS-1$
            spec.error.contains("ExternalDataProcessor.<Name>")); //$NON-NLS-1$
        assertTrue("error must show the ExternalReport shape", //$NON-NLS-1$
            spec.error.contains("ExternalReport.<Name>")); //$NON-NLS-1$
    }

    @Test
    public void testExternalObjectRejectedForOtherProjectKinds()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "configuration"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "MyConfig"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("externalObject", "ExternalReport.MyReport"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertTrue("externalObject on configuration must be an error", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must name the bad value", result.contains("ExternalReport.MyReport")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must explain the valid project kind", result.contains("projectKind=externalObjects")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMissingProjectKindErrors()
    {
        Map<String, String> params = new HashMap<>();
        String result = new CreateProjectTool().execute(params);
        assertTrue("missing 'projectKind' must name the param", result.contains("projectKind")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMissingNameErrors()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "extension"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertTrue("missing 'name' must name the param", result.contains("name")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInvalidProjectKindErrors()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "bogusKind"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertTrue("invalid projectKind must be an error", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("invalid projectKind error must name the bad value", result.contains("bogusKind")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testVersionRejectedForExtension()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "extension"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "MyExt"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("version", "8.3.27"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertTrue("version for extension kind must be an error", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must mention 'version'", result.contains("version")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testBaseProjectNameRejectedForConfiguration()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "configuration"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "MyConfig"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("baseProjectName", "SomeBase"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertTrue("baseProjectName for configuration kind must be an error", //$NON-NLS-1$
            result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("error must mention 'baseProjectName'", result.contains("baseProjectName")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testScriptVariantRejectedForExtension()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "extension"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "MyExt"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("scriptVariant", "Russian"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertTrue("scriptVariant for extension kind must be an error", //$NON-NLS-1$
            result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("error must mention 'scriptVariant'", result.contains("scriptVariant")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testBlankNameErrors()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "extension"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "   "); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertTrue("blank name must be an error", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("blank name error must mention 'name'", result.contains("name")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInvalidIdentifierNameErrors()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "configuration"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "123Bad"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertTrue("invalid identifier must be an error", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("invalid identifier error must name the bad value", result.contains("123Bad")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMissingBaseProjectNameForExtensionErrors()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "extension"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "MyExt"); //$NON-NLS-1$ //$NON-NLS-2$
        // No baseProjectName supplied
        String result = new CreateProjectTool().execute(params);
        assertTrue("missing baseProjectName for extension must be an error", //$NON-NLS-1$
            result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("error must mention 'baseProjectName'", result.contains("baseProjectName")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * FIX 1: invalid scriptVariant value must be rejected immediately (before any EDT
     * service lookup) with an error naming the bad value and the two allowed values.
     * <p>
     * This validation fires in {@code execute()} for non-extension kinds, before dispatch
     * to the kind-specific handler, so it is headless-safe.
     */
    @Test
    public void testInvalidScriptVariantConfigurationErrors()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "configuration"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "MyConfig"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("scriptVariant", "Frenglish"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertNotNull(result);
        assertTrue("invalid scriptVariant must be an error", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must name the bad value", result.contains("Frenglish")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must name the allowed values", //$NON-NLS-1$
            result.contains("Russian") && result.contains("English")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInvalidScriptVariantExternalObjectsErrors()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "externalObjects"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "MyExternal"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("scriptVariant", "Frenglish"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertNotNull(result);
        assertTrue("invalid scriptVariant must be an error", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must name the bad value", result.contains("Frenglish")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must name the allowed values", //$NON-NLS-1$
            result.contains("Russian") && result.contains("English")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testHeadlessExecutionConfigurationReturnsError()
    {
        // Verifies that execute() with a valid name returns a JSON error in a headless
        // (no EDT workspace) context rather than throwing an exception.
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "configuration"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "MyConfig"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertNotNull(result);
        assertTrue("result must contain 'success'", result.contains("success")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testHeadlessExecutionExternalObjectsReturnsError()
    {
        // Verifies that execute() with a valid name returns a JSON error in a headless
        // (no EDT workspace) context rather than throwing an exception.
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "externalObjects"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "MyExternal"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertNotNull(result);
        assertTrue("result must contain 'success'", result.contains("success")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testHeadlessExecutionExtensionReturnsError()
    {
        // Verifies that execute() with nominally valid params returns a JSON error in a
        // headless context rather than throwing an exception.
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "extension"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "MyExt"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("baseProjectName", "SomeBase"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertNotNull(result);
        assertTrue("result must contain 'success'", result.contains("success")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void assertExternalObjectResolution(String value, EClass expectedEClass,
        String expectedName, String expectedFqn)
    {
        ExternalObjectSpec spec = CreateProjectTool.resolveExternalObject(value);
        assertNull("valid externalObject must not carry an error", spec.error); //$NON-NLS-1$
        assertEquals(expectedEClass, spec.eClass);
        assertEquals(expectedName, spec.objectName);
        assertEquals(expectedFqn, spec.canonicalFqn);
    }

    private static MdObject createRootFromSpec(ExternalObjectSpec spec)
    {
        IModelObjectFactory factory = mock(IModelObjectFactory.class);
        MdObject root = MdClassFactory.eINSTANCE.createExternalReport();
        doReturn(root).when(factory).create(spec.eClass, Version.LATEST);
        return CreateProjectTool.createExternalObjectRoot(factory, spec, Version.LATEST);
    }
}
