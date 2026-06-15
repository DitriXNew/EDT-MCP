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

import org.junit.Test;

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
}
