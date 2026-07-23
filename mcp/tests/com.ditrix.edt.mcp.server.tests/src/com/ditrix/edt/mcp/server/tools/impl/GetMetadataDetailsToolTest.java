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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.Indexing;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegisterDimension;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.ReturnValuesReuse;
import com._1c.g5.v8.dt.metadata.mdclass.ScheduledJob;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link GetMetadataDetailsTool}.
 * <p>
 * Covers tool metadata, the input/output schema, the pure {@code getResultFileName}
 * override, the pure failure-formatting helpers, and the projectName/objectFqns
 * required-argument validation (presence, precedence, discovery hint, structured
 * error envelope, empty-array branch) that returns before the first
 * {@code PlatformUI.getWorkbench()} call — the EDT boundary. Resolving the objects
 * needs a live configuration and is covered by the E2E suite.
 */
public class GetMetadataDetailsToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_metadata_details", new GetMetadataDetailsTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetMetadataDetailsTool.NAME, new GetMetadataDetailsTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new GetMetadataDetailsTool().getResponseType());
    }

    @Test
    public void testConnectsToInfobaseIsFalse()
    {
        // #270: get_metadata_details reads EDT/workspace metadata only — it must NOT arm
        // the auth-dialog suppressor's activity window.
        assertFalse(new GetMetadataDetailsTool().connectsToInfobase());
    }

    /**
     * A MARKDOWN tool returns content, not structured data, so it leaves the
     * {@code outputSchema} at the interface default ({@code null}); pinning this
     * guards against a future edit declaring an output schema the tool never fills.
     */
    @Test
    public void testOutputSchemaIsNullForMarkdownTool()
    {
        assertNull(new GetMetadataDetailsTool().getOutputSchema());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetMetadataDetailsTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetMetadataDetailsTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"objectFqns\"")); //$NON-NLS-1$
    }

    /**
     * The optional flags ({@code full}, {@code assignable}, {@code language}) are
     * part of the always-loaded contract; pin them so a rename can't silently drop
     * a parameter the description/guide still advertises.
     */
    @Test
    public void testSchemaDeclaresOptionalParameters()
    {
        String schema = new GetMetadataDetailsTool().getInputSchema();
        assertTrue(schema.contains("\"full\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"assignable\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"language\"")); //$NON-NLS-1$
    }

    /**
     * Only the two genuinely-required parameters are in the required array; the
     * optional flags must stay out of it (an over-strict schema would make a
     * conformant client reject a valid basic-mode call).
     */
    @Test
    public void testRequiredArrayHoldsOnlyProjectNameAndObjectFqns()
    {
        String schema = new GetMetadataDetailsTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        int open = schema.indexOf('[', requiredIdx);
        int close = schema.indexOf(']', open);
        assertTrue("required array must be well-formed", open >= 0 && close > open); //$NON-NLS-1$
        String requiredBlock = schema.substring(open, close + 1);
        assertTrue("projectName must be required", requiredBlock.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("objectFqns must be required", requiredBlock.contains("\"objectFqns\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("full must NOT be required", requiredBlock.contains("\"full\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("assignable must NOT be required", requiredBlock.contains("\"assignable\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("language must NOT be required", requiredBlock.contains("\"language\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== Result file name (pure override, no live workbench needed) ====================

    /**
     * With a project name the EmbeddedResource file name carries the project,
     * lower-cased, between the fixed prefix and the {@code .md} extension.
     */
    @Test
    public void testResultFileNameIncludesLowerCasedProject()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String fileName = new GetMetadataDetailsTool().getResultFileName(params);
        assertEquals("metadata-details-myproject.md", fileName); //$NON-NLS-1$
    }

    /**
     * With no project name the tool falls back to the generic file name rather than
     * the interface default ({@code get_metadata_details.md}).
     */
    @Test
    public void testResultFileNameFallsBackWithoutProject()
    {
        String fileName = new GetMetadataDetailsTool().getResultFileName(new HashMap<>());
        assertEquals("metadata-details.md", fileName); //$NON-NLS-1$
    }

    /**
     * A blank project name is treated like an absent one (the guard checks both
     * null and empty), so it also takes the generic-file-name fallback.
     */
    @Test
    public void testResultFileNameBlankProjectFallsBack()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String fileName = new GetMetadataDetailsTool().getResultFileName(params);
        assertEquals("metadata-details.md", fileName); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionPointsToGuide()
    {
        String desc = new GetMetadataDetailsTool().getDescription();
        assertTrue(desc.contains("get_tool_guide('get_metadata_details')")); //$NON-NLS-1$
    }

    /**
     * The exhaustive detail moved out of the always-loaded
     * description/schema and into the on-demand guide channel: the guide must be
     * non-empty and still carry the migrated specifics (full mode, the
     * {@code [truncated]} cap, the bilingual type token).
     */
    @Test
    public void testGuideNonEmptyAndCarriesMigratedDetail()
    {
        String guide = new GetMetadataDetailsTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
        assertTrue(guide.contains("[truncated]")); //$NON-NLS-1$
        assertTrue(guide.contains("## Parameter details")); //$NON-NLS-1$
        assertTrue(guide.contains("full")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        String result = new GetMetadataDetailsTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingObjectFqns()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetMetadataDetailsTool().execute(params);
        assertTrue(result.contains("objectFqns is required")); //$NON-NLS-1$
    }

    /**
     * The missing-projectName error carries the shared discovery hint so the caller
     * is pointed at the tool that lists valid project names.
     */
    @Test
    public void testMissingProjectNameCarriesDiscoveryHint()
    {
        String result = new GetMetadataDetailsTool().execute(new HashMap<>());
        assertTrue("missing projectName must steer to list_projects", //$NON-NLS-1$
            result.contains("list_projects")); //$NON-NLS-1$
    }

    /**
     * A validation error returns the structured {@code {"success":false,...}}
     * envelope (recognised by the protocol's error diversion), never a partial
     * Markdown body.
     */
    @Test
    public void testValidationErrorIsStructuredFailureEnvelope()
    {
        String result = new GetMetadataDetailsTool().execute(new HashMap<>());
        assertTrue("error must be a structured failure envelope", //$NON-NLS-1$
            result.contains("\"success\"") && result.contains("false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a validation error must not emit the Markdown details header", //$NON-NLS-1$
            result.contains("# Metadata Details")); //$NON-NLS-1$
    }

    /**
     * projectName is validated before objectFqns: when BOTH are missing the
     * projectName error wins, so a caller fixes the most fundamental gap first.
     */
    @Test
    public void testProjectNameValidatedBeforeObjectFqns()
    {
        // objectFqns present, projectName absent -> projectName error.
        Map<String, String> onlyFqns = new HashMap<>();
        onlyFqns.put("objectFqns", "[\"Catalog.Products\"]"); //$NON-NLS-1$ //$NON-NLS-2$
        String r1 = new GetMetadataDetailsTool().execute(onlyFqns);
        assertTrue("projectName must be reported when it is the missing one", //$NON-NLS-1$
            r1.contains("projectName is required")); //$NON-NLS-1$
        assertFalse("objectFqns is present, so its error must not appear", //$NON-NLS-1$
            r1.contains("objectFqns is required")); //$NON-NLS-1$

        // Both missing -> still the projectName error (checked first).
        String r2 = new GetMetadataDetailsTool().execute(new HashMap<>());
        assertTrue("with both missing, projectName is reported first", //$NON-NLS-1$
            r2.contains("projectName is required")); //$NON-NLS-1$
        assertFalse("the objectFqns error must not pre-empt projectName", //$NON-NLS-1$
            r2.contains("objectFqns is required")); //$NON-NLS-1$
    }

    /**
     * An empty JSON array (and a comma-only string) parse to no FQNs, which is the
     * same "objectFqns is required" branch as an absent key — the empty-array case
     * the missing-key test does not exercise.
     */
    @Test
    public void testEmptyObjectFqnsArrayIsRequiredError()
    {
        Map<String, String> emptyArray = new HashMap<>();
        emptyArray.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        emptyArray.put("objectFqns", "[]"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("an empty JSON array must hit the required-objectFqns branch", //$NON-NLS-1$
            new GetMetadataDetailsTool().execute(emptyArray).contains("objectFqns is required")); //$NON-NLS-1$

        Map<String, String> commaOnly = new HashMap<>();
        commaOnly.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        commaOnly.put("objectFqns", " , , "); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a comma-only string yields no FQNs and hits the same branch", //$NON-NLS-1$
            new GetMetadataDetailsTool().execute(commaOnly).contains("objectFqns is required")); //$NON-NLS-1$
    }

    // ==================== Per-object failure channel (no live workbench needed) ====================
    //
    // Object resolution needs a live configuration, but the dual-channel contract
    // (a per-object failure must be machine-distinguishable from data, never prose
    // mixed into the success body) is enforced by the pure formatting helpers below.

    @Test
    public void testResolutionFailureReasonForMalformedFqn()
    {
        GetMetadataDetailsTool tool = new GetMetadataDetailsTool();
        String reason = tool.describeResolutionFailure("NotAnFqn"); //$NON-NLS-1$
        assertTrue(reason.contains("Invalid FQN")); //$NON-NLS-1$
    }

    @Test
    public void testResolutionFailureReasonForMissingObject()
    {
        GetMetadataDetailsTool tool = new GetMetadataDetailsTool();
        String reason = tool.describeResolutionFailure("Catalog.NoSuchObject"); //$NON-NLS-1$
        // The reason is actionable: it states the object was not found AND points at the
        // discovery tool (get_metadata_objects) to obtain a valid FQN.
        assertTrue(reason.contains("Object not found")); //$NON-NLS-1$
        assertTrue(reason.contains("get_metadata_objects")); //$NON-NLS-1$
    }

    /**
     * A batch with one valid FQN (its formatted data already in the body) and one
     * broken FQN: the broken outcome must be machine-differentiable (a dedicated
     * {@code ## Errors} section with an ERROR status row carrying the FQN) and the
     * success body must contain no {@code **Error:**} prose.
     */
    @Test
    public void testBrokenObjectIsMachineDistinguishableNotProse()
    {
        GetMetadataDetailsTool tool = new GetMetadataDetailsTool();

        // The valid FQN's data, as the formatter would emit it into the body.
        StringBuilder body = new StringBuilder();
        body.append("# Metadata Details: MyProject\n\n"); //$NON-NLS-1$
        body.append("## Catalog.Products\n\nSome data.\n"); //$NON-NLS-1$
        body.append("\n---\n\n"); //$NON-NLS-1$

        // The broken FQN goes into the dedicated machine-readable failures section.
        List<String[]> failures = new ArrayList<>();
        failures.add(new String[] { "Catalog.NoSuchObject", "Object not found" }); //$NON-NLS-1$ //$NON-NLS-2$
        body.append(tool.formatFailures(failures));

        String result = body.toString();

        // Machine-differentiable: a delimited section and a structured ERROR row.
        assertTrue(result.contains("## Errors")); //$NON-NLS-1$
        assertTrue(result.contains("| ERROR |")); //$NON-NLS-1$
        assertTrue(result.contains("Catalog.NoSuchObject")); //$NON-NLS-1$
        // The valid object's data is still present.
        assertTrue(result.contains("Catalog.Products")); //$NON-NLS-1$
        // No prose error line buried in the success body.
        assertFalse(result.contains("**Error:**")); //$NON-NLS-1$
    }

    /**
     * A pipe in an FQN or reason must not break the failures table — the shared
     * table builder escapes every cell.
     */
    @Test
    public void testFailuresTableEscapesPipes()
    {
        GetMetadataDetailsTool tool = new GetMetadataDetailsTool();
        List<String[]> failures = new ArrayList<>();
        failures.add(new String[] { "Catalog.A|B", "bad | reason" }); //$NON-NLS-1$ //$NON-NLS-2$
        String section = tool.formatFailures(failures);
        assertTrue(section.contains("Catalog.A\\|B")); //$NON-NLS-1$
        assertTrue(section.contains("bad \\| reason")); //$NON-NLS-1$
        assertFalse(section.contains("**Error:**")); //$NON-NLS-1$
    }

    // ==================== Form-member assignable view: the general reflective extInfo path (#235) ====================
    //
    // A form MEMBER (a group / field / table / decoration inside a form's editable content model) is
    // not an mdclass node, so its assignable schema is rendered from the ELEMENT's own features UNION
    // its <extInfo>'s layout properties. formatAssignable is widened to any EObject for exactly this;
    // resolving the live form needs a workbench + BM model (covered by the E2E suite), but the union
    // RENDERING is verified here headlessly on a synthetic UsualGroup whose UsualGroupExtInfo carries
    // the `group` (layout enum) and `united` (boolean) props that live inside <extInfo>.

    /**
     * {@code formatAssignable} on a form GROUP element lists BOTH the element's own features and the
     * layout properties nested in its {@code extInfo} (issue #235): a UsualGroup's {@code group} (an
     * enum, so its allowed literals are surfaced for modify_metadata to validate against) and
     * {@code united} appear in the assignable table.
     */
    @Test
    public void testFormGroupAssignableListsExtInfoLayoutProps()
    {
        EObject group = syntheticUsualGroupWithExtInfo();
        String md = GetMetadataDetailsTool.formatAssignable(
            "Catalog.Catalog.Form.ItemForm.Group.G", group); //$NON-NLS-1$
        assertTrue("must render the assignable schema heading", //$NON-NLS-1$
            md.contains("Assignable properties")); //$NON-NLS-1$
        assertTrue("the extInfo layout enum 'group' must be listed as assignable", //$NON-NLS-1$
            md.contains("| group |")); //$NON-NLS-1$
        assertTrue("the extInfo 'united' layout flag must be listed as assignable", //$NON-NLS-1$
            md.contains("| united |")); //$NON-NLS-1$
        // `group` is an enum, so its allowed literals are surfaced so modify_metadata can validate.
        assertTrue("the group layout enum must surface its allowed literals", //$NON-NLS-1$
            md.contains("Horizontal")); //$NON-NLS-1$
    }

    /**
     * When the form GROUP element carries a LIVE extInfo with values SET, {@code formatAssignable}
     * renders those values in the Current column (issue #235): it feeds the live extInfo instance
     * through the instance-aware introspection, so a designer-authored {@code group=Horizontal} /
     * {@code united=true} shows up rather than a blank Current cell. Guards against regressing to the
     * EClass-only listing (which renders no current value for the extInfo props while the direct
     * features show theirs - the inconsistency the reviewer flagged).
     */
    @Test
    public void testFormGroupAssignableRendersExtInfoCurrentValue()
    {
        EObject group = syntheticUsualGroupWithExtInfo();
        // Set group=Horizontal + united=true on the live extInfo, mimicking a designer-authored layout.
        EObject extInfo = (EObject)group.eGet(group.eClass().getEStructuralFeature("extInfo")); //$NON-NLS-1$
        EStructuralFeature groupFeature = extInfo.eClass().getEStructuralFeature("group"); //$NON-NLS-1$
        EEnum grouping = (EEnum)((EAttribute)groupFeature).getEAttributeType();
        extInfo.eSet(groupFeature, grouping.getEEnumLiteralByLiteral("Horizontal")); //$NON-NLS-1$
        extInfo.eSet(extInfo.eClass().getEStructuralFeature("united"), Boolean.TRUE); //$NON-NLS-1$

        String md = GetMetadataDetailsTool.formatAssignable(
            "Catalog.Catalog.Form.ItemForm.Group.G", group); //$NON-NLS-1$
        // The extInfo props must carry their CURRENT value, not a blank Current cell.
        assertTrue("the extInfo 'group' current value must render as the set literal", //$NON-NLS-1$
            md.contains("| group | ENUM | Horizontal |")); //$NON-NLS-1$
        assertTrue("the extInfo 'united' current value must render as true", //$NON-NLS-1$
            md.contains("| united | BOOLEAN | true |")); //$NON-NLS-1$
    }

    /**
     * Builds a synthetic form GROUP element whose {@code extInfo} is a {@code UsualGroupExtInfo}
     * carrying the {@code group} (a layout EEnum with Vertical / Horizontal literals) and
     * {@code united} (a boolean) layout properties - the reflective shape
     * {@code get_metadata_details(assignable)} renders from, so the union of element + extInfo features
     * is exercised WITHOUT a live workbench. The concrete {@code extInfo} instance is set so the
     * extInfo EClass resolves regardless of the resolver's abstract-type strategy.
     */
    private static EObject syntheticUsualGroupWithExtInfo()
    {
        EcoreFactory f = EcoreFactory.eINSTANCE;
        EPackage pkg = f.createEPackage();
        pkg.setName("formlike"); //$NON-NLS-1$
        pkg.setNsPrefix("formlike"); //$NON-NLS-1$
        pkg.setNsURI("http://example.com/edt-mcp/formlike/235"); //$NON-NLS-1$

        EEnum groupEnum = f.createEEnum();
        groupEnum.setName("FormChildrenGroup"); //$NON-NLS-1$
        addLiteral(f, groupEnum, "Vertical", 0); //$NON-NLS-1$
        addLiteral(f, groupEnum, "Horizontal", 1); //$NON-NLS-1$
        pkg.getEClassifiers().add(groupEnum);

        EClass extInfo = f.createEClass();
        extInfo.setName("UsualGroupExtInfo"); //$NON-NLS-1$
        EAttribute groupAttr = f.createEAttribute();
        groupAttr.setName("group"); //$NON-NLS-1$
        groupAttr.setEType(groupEnum);
        extInfo.getEStructuralFeatures().add(groupAttr);
        EAttribute unitedAttr = f.createEAttribute();
        unitedAttr.setName("united"); //$NON-NLS-1$
        unitedAttr.setEType(EcorePackage.Literals.EBOOLEAN);
        extInfo.getEStructuralFeatures().add(unitedAttr);
        pkg.getEClassifiers().add(extInfo);

        EClass groupClass = f.createEClass();
        groupClass.setName("UsualGroup"); //$NON-NLS-1$
        EAttribute nameAttr = f.createEAttribute();
        nameAttr.setName("name"); //$NON-NLS-1$
        nameAttr.setEType(EcorePackage.Literals.ESTRING);
        groupClass.getEStructuralFeatures().add(nameAttr);
        EReference extInfoRef = f.createEReference();
        extInfoRef.setName("extInfo"); //$NON-NLS-1$
        extInfoRef.setEType(extInfo);
        extInfoRef.setContainment(true);
        groupClass.getEStructuralFeatures().add(extInfoRef);
        pkg.getEClassifiers().add(groupClass);

        EObject groupObj = pkg.getEFactoryInstance().create(groupClass);
        EObject extInfoObj = pkg.getEFactoryInstance().create(extInfo);
        groupObj.eSet(extInfoRef, extInfoObj);
        return groupObj;
    }

    private static void addLiteral(EcoreFactory f, EEnum eEnum, String name, int value)
    {
        EEnumLiteral literal = f.createEEnumLiteral();
        literal.setName(name);
        literal.setValue(value);
        literal.setLiteral(name);
        eEnum.getELiterals().add(literal);
    }

    // ==================== Type-specific properties: ScheduledJob / CommonModule / InformationRegister
    // dimension Indexing (issue #288) ====================
    //
    // get_metadata_details WRITES these via modify_metadata but used to render only Name/Synonym for
    // ScheduledJob/CommonModule in the default (non-full) view, and never showed a register dimension's
    // Indexing at all - reading them back was impossible. formatTypeSpecificProperties is the pure
    // rendering hook (package-private, called from processFqn right after the universal formatter and
    // before the ORIGIN footer); it needs only in-memory MdClassFactory objects, no live workbench.

    @Test
    public void testScheduledJobPropertiesRenderedInBasicMode()
    {
        ScheduledJob job = MdClassFactory.eINSTANCE.createScheduledJob();
        job.setMethodName("CommonModule.MyJob.Execute"); //$NON-NLS-1$
        job.setUse(true);
        job.setPredefined(false);
        job.setRestartCountOnFailure(3);
        job.setRestartIntervalOnFailure(60);
        job.setKey("JobKey"); //$NON-NLS-1$

        String md = GetMetadataDetailsTool.formatTypeSpecificProperties(job, false);

        assertTrue("must render the Properties section", md.contains("### Properties")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("methodName must be present", //$NON-NLS-1$
            md.contains("Method Name") && md.contains("CommonModule.MyJob.Execute")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("use=true must render as Yes", md.contains("| Use | Yes |")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("predefined=false must render as No, not be omitted", //$NON-NLS-1$
            md.contains("| Predefined | No |")); //$NON-NLS-1$
        assertTrue("restartCountOnFailure must render its int value", //$NON-NLS-1$
            md.contains("| Restart Count On Failure | 3 |")); //$NON-NLS-1$
        assertTrue("restartIntervalOnFailure must render its int value", //$NON-NLS-1$
            md.contains("| Restart Interval On Failure | 60 |")); //$NON-NLS-1$
        assertTrue("key must be present", md.contains("| Key | JobKey |")); //$NON-NLS-1$ //$NON-NLS-2$
        // No Schedule was set on this job, so its presence row must show the dash placeholder,
        // never a raw toString() dump of an absent/unresolvable cross-model object.
        assertTrue("an unset Schedule must render as the dash placeholder", //$NON-NLS-1$
            md.contains("| Schedule | - |")); //$NON-NLS-1$
    }

    /**
     * A blank {@code methodName}/{@code key} must render as the dash placeholder, not an empty cell
     * (which would look identical to a table-formatting bug rather than "not set").
     */
    @Test
    public void testScheduledJobBlankStringsRenderAsDash()
    {
        ScheduledJob job = MdClassFactory.eINSTANCE.createScheduledJob();
        // methodName/key left unset (EMF default for an unset String feature is null/empty).

        String md = GetMetadataDetailsTool.formatTypeSpecificProperties(job, false);

        assertTrue("an unset Method Name must render as the dash placeholder", //$NON-NLS-1$
            md.contains("| Method Name | - |")); //$NON-NLS-1$
        assertTrue("an unset Key must render as the dash placeholder", //$NON-NLS-1$
            md.contains("| Key | - |")); //$NON-NLS-1$
    }

    /**
     * The type-specific Properties table renders in FULL mode too. The generic "All Properties" dump
     * repeats the plain scalar attributes, but it omits the transient Schedule reference, so full mode
     * must keep this table or it would carry LESS than basic mode (codex #288).
     */
    @Test
    public void testScheduledJobPropertiesRenderedInFullMode()
    {
        // Full mode must ALSO render the type-specific table: the generic "All Properties" dump
        // does not include the transient Schedule reference, so skipping here would make full mode
        // lose information present in basic mode (codex #288).
        ScheduledJob job = MdClassFactory.eINSTANCE.createScheduledJob();
        job.setMethodName("CommonModule.MyJob.Execute"); //$NON-NLS-1$

        String md = GetMetadataDetailsTool.formatTypeSpecificProperties(job, true);

        assertTrue("full mode must still render the ScheduledJob Properties table", //$NON-NLS-1$
            md.contains("### Properties") && md.contains("Method Name")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testCommonModulePropertiesRenderedInBasicMode()
    {
        CommonModule module = MdClassFactory.eINSTANCE.createCommonModule();
        module.setServer(true);
        module.setServerCall(false);
        module.setClientManagedApplication(true);
        module.setClientOrdinaryApplication(false);
        module.setExternalConnection(false);
        module.setGlobal(true);
        module.setPrivileged(false);
        module.setReturnValuesReuse(ReturnValuesReuse.DURING_SESSION);

        String md = GetMetadataDetailsTool.formatTypeSpecificProperties(module, false);

        assertTrue("must render the Properties section", md.contains("### Properties")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("server=true must render as Yes", md.contains("| Server | Yes |")); //$NON-NLS-1$ //$NON-NLS-2$
        // false is a real, meaningful value for these flags - it must render as No, never be omitted.
        assertTrue("serverCall=false must render as No, not be omitted", //$NON-NLS-1$
            md.contains("| Server Call | No |")); //$NON-NLS-1$
        assertTrue("clientManagedApplication=true must render as Yes", //$NON-NLS-1$
            md.contains("| Client (Managed Application) | Yes |")); //$NON-NLS-1$
        assertTrue("clientOrdinaryApplication=false must render as No", //$NON-NLS-1$
            md.contains("| Client (Ordinary Application) | No |")); //$NON-NLS-1$
        assertTrue("externalConnection=false must render as No", md.contains("| External Connection | No |")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("global=true must render as Yes", md.contains("| Global | Yes |")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("privileged=false must render as No", md.contains("| Privileged | No |")); //$NON-NLS-1$ //$NON-NLS-2$
        // The enum must render its LITERAL name (whatever ReturnValuesReuse.DURING_SESSION.toString()
        // is), never a raw Java object dump (e.g. containing '@' + a hashcode).
        assertTrue("returnValuesReuse must render its literal name", //$NON-NLS-1$
            md.contains(ReturnValuesReuse.DURING_SESSION.toString()));
        assertFalse("the enum cell must not be a raw object dump", md.contains("@")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Mirrors {@link #testScheduledJobPropertiesRenderedInFullMode} for CommonModule: the
     * type-specific table renders in full mode too, so full output never carries less than basic.
     */
    @Test
    public void testCommonModulePropertiesRenderedInFullMode()
    {
        CommonModule module = MdClassFactory.eINSTANCE.createCommonModule();
        module.setServer(true);

        String md = GetMetadataDetailsTool.formatTypeSpecificProperties(module, true);

        assertTrue("full mode must still render the CommonModule Properties table", //$NON-NLS-1$
            md.contains("### Properties") && md.contains("| Server | Yes |")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * An InformationRegister's dimension Indexing must be visible in BOTH the default and full views:
     * the shared attributes-table Indexing column only recognizes DbObjectAttribute, and a register
     * dimension is a separate RegisterDimension sub-interface, so it never gets a value there in either
     * mode - this dedicated table is the only place it is visible.
     */
    @Test
    public void testInformationRegisterDimensionIndexingRenderedInBothModes()
    {
        InformationRegister register = MdClassFactory.eINSTANCE.createInformationRegister();
        InformationRegisterDimension indexed = MdClassFactory.eINSTANCE.createInformationRegisterDimension();
        indexed.setName("Indexed"); //$NON-NLS-1$
        indexed.setIndexing(Indexing.INDEX);
        InformationRegisterDimension notIndexed = MdClassFactory.eINSTANCE.createInformationRegisterDimension();
        notIndexed.setName("NotIndexed"); //$NON-NLS-1$
        notIndexed.setIndexing(Indexing.DONT_INDEX);
        register.getDimensions().add(indexed);
        register.getDimensions().add(notIndexed);

        String basic = GetMetadataDetailsTool.formatTypeSpecificProperties(register, false);
        String full = GetMetadataDetailsTool.formatTypeSpecificProperties(register, true);

        for (String md : new String[] { basic, full })
        {
            assertTrue("must render the Dimension Indexing section", md.contains("### Dimension Indexing")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("the indexed dimension's row must carry its Indexing literal", //$NON-NLS-1$
                md.contains("Indexed") && md.contains(Indexing.INDEX.toString())); //$NON-NLS-1$
            assertTrue("the non-indexed dimension's row must carry its Indexing literal", //$NON-NLS-1$
                md.contains("NotIndexed") && md.contains(Indexing.DONT_INDEX.toString())); //$NON-NLS-1$
        }
    }

    /**
     * A register with no dimensions renders no Dimension Indexing section (and, since an
     * InformationRegister is neither a ScheduledJob nor a CommonModule, no Properties table either) -
     * the whole type-specific section is empty.
     */
    @Test
    public void testInformationRegisterWithNoDimensionsRendersNothing()
    {
        InformationRegister register = MdClassFactory.eINSTANCE.createInformationRegister();

        String md = GetMetadataDetailsTool.formatTypeSpecificProperties(register, false);

        assertEquals("a register with no dimensions must add no section", "", md); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A type this method does not special-case (e.g. Catalog) must add nothing, in either mode - the
     * hook must be a true no-op for everything else, matching the ratchet other section-formatter tests
     * apply.
     */
    @Test
    public void testUnrelatedTypeRendersNothing()
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Products"); //$NON-NLS-1$

        assertEquals("", GetMetadataDetailsTool.formatTypeSpecificProperties(catalog, false)); //$NON-NLS-1$
        assertEquals("", GetMetadataDetailsTool.formatTypeSpecificProperties(catalog, true)); //$NON-NLS-1$
    }
}
