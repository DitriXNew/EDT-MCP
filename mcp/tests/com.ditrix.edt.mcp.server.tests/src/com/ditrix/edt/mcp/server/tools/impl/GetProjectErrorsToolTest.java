/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.validation.marker.IExtraInfoMap;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.impl.GetProjectErrorsTool.ErrorInfo;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;

/**
 * Unit tests for the marker filtering / building helpers of {@link GetProjectErrorsTool}.
 *
 * <p>Focuses on the review point 1 (PR #120) discrepancy: a marker whose location cannot
 * be resolved must be counted as {@code unresolvedShown} when it is still reported with a
 * placeholder, and as {@code unresolvedFilteredOut} when an explicit {@code objects} filter
 * excludes it from the result. These two cases must never overlap.</p>
 *
 * <p>{@link Marker} / {@link IProject} / {@link ICheckRepository} are mocked with Mockito.
 * The symbolic-check-id resolution success path goes through the platform
 * {@code ICheckRepository.getUidForShortUid} + {@code CheckUid} and is exercised by e2e; the
 * pure substring matching it feeds into is covered directly via {@link #checkIdMatches}.</p>
 */
public class GetProjectErrorsToolTest
{
    // ========== checkIdMatches (pure) ==========

    @Test
    public void testCheckIdMatchesByShortUid()
    {
        assertTrue(GetProjectErrorsTool.checkIdMatches("SU23", null, "su2")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testCheckIdMatchesBySymbolicId()
    {
        assertTrue(GetProjectErrorsTool.checkIdMatches("SU23", "ql-temp-table-index", "temp")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testCheckIdMatchesCaseInsensitive()
    {
        assertTrue(GetProjectErrorsTool.checkIdMatches("Su23", null, "SU23")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(GetProjectErrorsTool.checkIdMatches(null, "QL-Temp-Table", "ql-temp")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testCheckIdMatchesNoMatch()
    {
        assertFalse(GetProjectErrorsTool.checkIdMatches("SU23", "ql-temp-table-index", "zzz")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testCheckIdMatchesBothNull()
    {
        assertFalse(GetProjectErrorsTool.checkIdMatches(null, null, "anything")); //$NON-NLS-1$
    }

    // ========== unresolvedPlaceholder ==========

    @Test
    public void testUnresolvedPlaceholderWithProject()
    {
        IProject project = project("MyProject"); //$NON-NLS-1$
        Marker marker = mock(Marker.class);
        when(marker.getProject()).thenReturn(project);
        assertEquals("<unresolved: MyProject>", GetProjectErrorsTool.unresolvedPlaceholder(marker)); //$NON-NLS-1$
    }

    @Test
    public void testUnresolvedPlaceholderNullProject()
    {
        Marker marker = mock(Marker.class);
        when(marker.getProject()).thenReturn(null);
        assertEquals("<unresolved: ?>", GetProjectErrorsTool.unresolvedPlaceholder(marker)); //$NON-NLS-1$
    }

    // ========== resolveSymbolicCheckId null-guards ==========

    @Test
    public void testResolveSymbolicCheckIdNullRepository()
    {
        Marker marker = mock(Marker.class);
        assertNull(GetProjectErrorsTool.resolveSymbolicCheckId(marker, "SU23", null)); //$NON-NLS-1$
    }

    @Test
    public void testResolveSymbolicCheckIdEmptyShortUid()
    {
        Marker marker = mock(Marker.class);
        ICheckRepository repo = mock(ICheckRepository.class);
        assertNull(GetProjectErrorsTool.resolveSymbolicCheckId(marker, "", repo)); //$NON-NLS-1$
    }

    @Test
    public void testResolveSymbolicCheckIdNullProject()
    {
        Marker marker = mock(Marker.class);
        when(marker.getProject()).thenReturn(null);
        ICheckRepository repo = mock(ICheckRepository.class);
        assertNull(GetProjectErrorsTool.resolveSymbolicCheckId(marker, "SU23", repo)); //$NON-NLS-1$
    }

    @Test
    public void testResolveSymbolicCheckIdSuccess()
    {
        IProject project = project("Proj"); //$NON-NLS-1$
        Marker marker = mock(Marker.class);
        when(marker.getProject()).thenReturn(project);
        CheckUid uid = checkUid("ql-temp-table-index"); //$NON-NLS-1$
        ICheckRepository repo = mock(ICheckRepository.class);
        when(repo.getUidForShortUid(eq("SU23"), any(IProject.class))).thenReturn(uid); //$NON-NLS-1$

        assertEquals("ql-temp-table-index", //$NON-NLS-1$
            GetProjectErrorsTool.resolveSymbolicCheckId(marker, "SU23", repo)); //$NON-NLS-1$
    }

    // ========== buildIfMatches: review point 1 counters ==========

    @Test
    public void testObjectsFilterUnresolvedCountedAsFilteredOut()
    {
        // Active objects filter + presentation cannot be resolved -> excluded, counted as
        // filteredOut only (NOT shown). This is the exact review point 1 discrepancy.
        Marker marker = markerThatThrowsOnPresentation(MarkerSeverity.MINOR, "SU23", "msg", "Proj"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        int[] shown = {0};
        int[] filteredOut = {0};

        ErrorInfo error = GetProjectErrorsTool.buildIfMatches(marker, null, null,
            singleton("catalog.foo"), null, shown, filteredOut); //$NON-NLS-1$

        assertNull(error);
        assertEquals(0, shown[0]);
        assertEquals(1, filteredOut[0]);
    }

    @Test
    public void testNoObjectsFilterUnresolvedCountedAsShown()
    {
        // No objects filter + presentation cannot be resolved -> reported with placeholder,
        // counted as shown only (NOT filteredOut).
        Marker marker = markerThatThrowsOnPresentation(MarkerSeverity.MINOR, "SU23", "msg", "Proj"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        int[] shown = {0};
        int[] filteredOut = {0};

        ErrorInfo error = GetProjectErrorsTool.buildIfMatches(marker, null, null,
            Collections.emptySet(), null, shown, filteredOut);

        assertNotNull(error);
        assertEquals("<unresolved: Proj>", error.objectPresentation); //$NON-NLS-1$
        assertEquals("SU23", error.checkCode); //$NON-NLS-1$
        assertNull(error.checkId);
        assertFalse(error.hasDocumentation);
        assertEquals(1, shown[0]);
        assertEquals(0, filteredOut[0]);
    }

    @Test
    public void testResolvedMarkerNoCountersIncremented()
    {
        Marker marker = marker(MarkerSeverity.MINOR, "SU23", "msg", "Proj"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        when(marker.getObjectPresentation()).thenReturn("Catalog.Foo"); //$NON-NLS-1$
        int[] shown = {0};
        int[] filteredOut = {0};

        ErrorInfo error = GetProjectErrorsTool.buildIfMatches(marker, null, null,
            Collections.emptySet(), null, shown, filteredOut);

        assertNotNull(error);
        assertEquals("Catalog.Foo", error.objectPresentation); //$NON-NLS-1$
        assertEquals("msg", error.message); //$NON-NLS-1$
        assertEquals(0, shown[0]);
        assertEquals(0, filteredOut[0]);
    }

    @Test
    public void testObjectsFilterResolvedButEmptyPresentationExcludedWithoutCounter()
    {
        Marker marker = marker(MarkerSeverity.MINOR, "SU23", "msg", "Proj"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        when(marker.getObjectPresentation()).thenReturn(""); //$NON-NLS-1$
        int[] shown = {0};
        int[] filteredOut = {0};

        ErrorInfo error = GetProjectErrorsTool.buildIfMatches(marker, null, null,
            singleton("catalog.foo"), null, shown, filteredOut); //$NON-NLS-1$

        assertNull(error);
        assertEquals(0, shown[0]);
        assertEquals(0, filteredOut[0]);
    }

    // ========== buildIfMatches: filters ==========

    @Test
    public void testSeverityFilterExcludes()
    {
        // Mismatching severity returns null before the presentation is ever read.
        Marker marker = markerThatThrowsOnPresentation(MarkerSeverity.MINOR, "SU23", "msg", "Proj"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        int[] shown = {0};
        int[] filteredOut = {0};

        ErrorInfo error = GetProjectErrorsTool.buildIfMatches(marker, MarkerSeverity.MAJOR, null,
            Collections.emptySet(), null, shown, filteredOut);

        assertNull(error);
        assertEquals(0, shown[0]);
        assertEquals(0, filteredOut[0]);
    }

    @Test
    public void testObjectsFilterMatchesSubstring()
    {
        Marker marker = marker(MarkerSeverity.MINOR, "SU23", "msg", "Proj"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        when(marker.getObjectPresentation()).thenReturn("Catalog.Foo"); //$NON-NLS-1$

        ErrorInfo error = GetProjectErrorsTool.buildIfMatches(marker, null, null,
            singleton("catalog.foo"), null, new int[]{0}, new int[]{0}); //$NON-NLS-1$

        assertNotNull(error);
    }

    @Test
    public void testObjectsFilterNoMatch()
    {
        Marker marker = marker(MarkerSeverity.MINOR, "SU23", "msg", "Proj"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        when(marker.getObjectPresentation()).thenReturn("Catalog.Foo"); //$NON-NLS-1$

        ErrorInfo error = GetProjectErrorsTool.buildIfMatches(marker, null, null,
            singleton("catalog.bar"), null, new int[]{0}, new int[]{0}); //$NON-NLS-1$

        assertNull(error);
    }

    @Test
    public void testCheckIdFilterMatchesShortUid()
    {
        Marker marker = marker(MarkerSeverity.MINOR, "SU23", "msg", "Proj"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        when(marker.getObjectPresentation()).thenReturn("Catalog.Foo"); //$NON-NLS-1$

        ErrorInfo error = GetProjectErrorsTool.buildIfMatches(marker, null, "su2",
            Collections.emptySet(), null, new int[]{0}, new int[]{0}); //$NON-NLS-1$

        assertNotNull(error);
    }

    @Test
    public void testCheckIdFilterMatchesSymbolicId()
    {
        // checkId matches only the resolved symbolic id, not the short UID. Exercises the
        // resolveSymbolicCheckId -> checkIdMatches integration inside buildIfMatches.
        IProject project = project("Proj"); //$NON-NLS-1$
        Marker marker = mock(Marker.class);
        when(marker.getSeverity()).thenReturn(MarkerSeverity.MINOR);
        when(marker.getCheckId()).thenReturn("SU23"); //$NON-NLS-1$
        when(marker.getMessage()).thenReturn("msg"); //$NON-NLS-1$
        when(marker.getProject()).thenReturn(project);
        when(marker.getObjectPresentation()).thenReturn("Catalog.Foo"); //$NON-NLS-1$
        CheckUid uid = checkUid("ql-temp-table-index"); //$NON-NLS-1$
        ICheckRepository repo = mock(ICheckRepository.class);
        when(repo.getUidForShortUid(eq("SU23"), any(IProject.class))).thenReturn(uid); //$NON-NLS-1$

        ErrorInfo error = GetProjectErrorsTool.buildIfMatches(marker, null, "temp",
            Collections.emptySet(), repo, new int[]{0}, new int[]{0}); //$NON-NLS-1$

        assertNotNull(error);
        assertEquals("SU23", error.checkCode); //$NON-NLS-1$
        assertEquals("ql-temp-table-index", error.checkId); //$NON-NLS-1$
    }

    @Test
    public void testCheckIdFilterExcludes()
    {
        Marker marker = markerThatThrowsOnPresentation(MarkerSeverity.MINOR, "SU23", "msg", "Proj"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        int[] shown = {0};
        int[] filteredOut = {0};

        // checkId does not match -> null before the presentation is read; no counter touched.
        ErrorInfo error = GetProjectErrorsTool.buildIfMatches(marker, null, "zzz",
            Collections.emptySet(), null, shown, filteredOut); //$NON-NLS-1$

        assertNull(error);
        assertEquals(0, shown[0]);
        assertEquals(0, filteredOut[0]);
    }

    // ========== resolveBslModulePath (pure URI parsing) ==========

    @Test
    public void testResolveBslModulePathFromPlatformUri()
    {
        // platform:/resource/<Project>/src/<modulePath>.bsl -> <modulePath>.bsl
        assertEquals("CommonModules/MyModule/Module.bsl", //$NON-NLS-1$
            GetProjectErrorsTool.resolveBslModulePath(
                "platform:/resource/MyProject/src/CommonModules/MyModule/Module.bsl")); //$NON-NLS-1$
    }

    @Test
    public void testResolveBslModulePathStripsFragment()
    {
        // The EMF problem URI carries an object fragment after '#'; it must be trimmed.
        assertEquals("Documents/SalesOrder/ObjectModule.bsl", //$NON-NLS-1$
            GetProjectErrorsTool.resolveBslModulePath(
                "platform:/resource/Proj/src/Documents/SalesOrder/ObjectModule.bsl#/0/@methods.1")); //$NON-NLS-1$
    }

    @Test
    public void testResolveBslModulePathNullWhenNotBsl()
    {
        // A non-.bsl resource (e.g. a metadata MDO file) is not a module location.
        assertNull(GetProjectErrorsTool.resolveBslModulePath(
            "platform:/resource/Proj/src/Catalogs/Products/Products.mdo")); //$NON-NLS-1$
    }

    @Test
    public void testResolveBslModulePathNullWhenNoSrcSegment()
    {
        // A .bsl path that is not under the source folder yields no usable modulePath.
        assertNull(GetProjectErrorsTool.resolveBslModulePath(
            "platform:/resource/Proj/build/Module.bsl")); //$NON-NLS-1$
    }

    @Test
    public void testResolveBslModulePathNullWhenNotPlatformResource()
    {
        // A non platform:/resource URI cannot be turned into a src-relative module path.
        assertNull(GetProjectErrorsTool.resolveBslModulePath(
            "file:/C:/tmp/src/Module.bsl")); //$NON-NLS-1$
    }

    @Test
    public void testResolveBslModulePathNullForNullOrEmpty()
    {
        assertNull(GetProjectErrorsTool.resolveBslModulePath(null));
        assertNull(GetProjectErrorsTool.resolveBslModulePath("")); //$NON-NLS-1$
    }

    @Test
    public void testResolveBslModulePathNullForGarbage()
    {
        // An unparseable / unrelated string must never be guessed into a path.
        assertNull(GetProjectErrorsTool.resolveBslModulePath("not a uri at all")); //$NON-NLS-1$
    }

    // ========== populateModuleLocation (extraInfo -> ErrorInfo) ==========

    @Test
    public void testPopulateModuleLocationSetsPathAndLine()
    {
        Marker marker = mock(Marker.class);
        when(marker.getExtraInfo()).thenReturn(extraInfo(
            "platform:/resource/Proj/src/CommonModules/MyModule/Module.bsl#/0", "42")); //$NON-NLS-1$ //$NON-NLS-2$

        ErrorInfo error = new ErrorInfo();
        GetProjectErrorsTool.populateModuleLocation(marker, error);

        assertEquals("CommonModules/MyModule/Module.bsl", error.modulePath); //$NON-NLS-1$
        assertEquals(Integer.valueOf(42), error.line);
    }

    @Test
    public void testPopulateModuleLocationNullExtraInfo()
    {
        Marker marker = mock(Marker.class);
        when(marker.getExtraInfo()).thenReturn(null);

        ErrorInfo error = new ErrorInfo();
        GetProjectErrorsTool.populateModuleLocation(marker, error);

        assertNull(error.modulePath);
        assertNull(error.line);
    }

    @Test
    public void testPopulateModuleLocationNonBslUriLeavesBothNull()
    {
        // A metadata (non-BSL) marker resolves to no module location even if a line exists.
        Marker marker = mock(Marker.class);
        when(marker.getExtraInfo()).thenReturn(extraInfo(
            "platform:/resource/Proj/src/Catalogs/Products/Products.mdo", "7")); //$NON-NLS-1$ //$NON-NLS-2$

        ErrorInfo error = new ErrorInfo();
        GetProjectErrorsTool.populateModuleLocation(marker, error);

        assertNull(error.modulePath);
        assertNull(error.line);
    }

    @Test
    public void testPopulateModuleLocationPathWithoutLine()
    {
        // A BSL marker may carry a uriToProblem but no line; path is set, line stays null.
        Marker marker = mock(Marker.class);
        when(marker.getExtraInfo()).thenReturn(extraInfo(
            "platform:/resource/Proj/src/CommonModules/MyModule/Module.bsl", null)); //$NON-NLS-1$

        ErrorInfo error = new ErrorInfo();
        GetProjectErrorsTool.populateModuleLocation(marker, error);

        assertEquals("CommonModules/MyModule/Module.bsl", error.modulePath); //$NON-NLS-1$
        assertNull(error.line);
    }

    @Test
    public void testPopulateModuleLocationDropsNonPositiveLine()
    {
        // A 0 / negative line is not a usable 1-based locator; keep the path, drop the line.
        Marker marker = mock(Marker.class);
        when(marker.getExtraInfo()).thenReturn(extraInfo(
            "platform:/resource/Proj/src/CommonModules/MyModule/Module.bsl", "0")); //$NON-NLS-1$ //$NON-NLS-2$

        ErrorInfo error = new ErrorInfo();
        GetProjectErrorsTool.populateModuleLocation(marker, error);

        assertEquals("CommonModules/MyModule/Module.bsl", error.modulePath); //$NON-NLS-1$
        assertNull(error.line);
    }

    // ========== buildIfMatches: structural locator end-to-end ==========

    @Test
    public void testBuildIfMatchesPopulatesLocatorForBslMarker()
    {
        Marker marker = marker(MarkerSeverity.MINOR, "SU23", "msg", "Proj"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        when(marker.getObjectPresentation()).thenReturn("CommonModule.MyModule"); //$NON-NLS-1$
        when(marker.getExtraInfo()).thenReturn(extraInfo(
            "platform:/resource/Proj/src/CommonModules/MyModule/Module.bsl#/0", "13")); //$NON-NLS-1$ //$NON-NLS-2$

        ErrorInfo error = GetProjectErrorsTool.buildIfMatches(marker, null, null,
            Collections.emptySet(), null, new int[]{0}, new int[]{0});

        assertNotNull(error);
        assertEquals("CommonModules/MyModule/Module.bsl", error.modulePath); //$NON-NLS-1$
        assertEquals(Integer.valueOf(13), error.line);
    }

    @Test
    public void testBuildIfMatchesLeavesLocatorNullForMetadataMarker()
    {
        // A marker without BSL extraInfo (e.g. a metadata-object marker) gets no locator.
        Marker marker = marker(MarkerSeverity.MINOR, "SU23", "msg", "Proj"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        when(marker.getObjectPresentation()).thenReturn("Catalog.Products"); //$NON-NLS-1$
        // getExtraInfo() is left unstubbed -> returns null -> no locator.

        ErrorInfo error = GetProjectErrorsTool.buildIfMatches(marker, null, null,
            Collections.emptySet(), null, new int[]{0}, new int[]{0});

        assertNotNull(error);
        assertNull(error.modulePath);
        assertNull(error.line);
    }

    // ========== severity enum (schema + validation) ==========

    @Test
    public void testSeverityEnumMatchesMarkerSeverityValues()
    {
        // The schema enum AND the validation set must EXACTLY match what
        // MarkerSeverity.valueOf accepts (all 7 constants incl. NONE) so no
        // previously-accepted value is rejected by the new out-of-set guard.
        String schema = new GetProjectErrorsTool().getInputSchema();
        assertTrue(schema.contains("\"enum\"")); //$NON-NLS-1$
        for (MarkerSeverity s : MarkerSeverity.values())
        {
            assertTrue("schema enum is missing " + s.name(), //$NON-NLS-1$
                schema.contains("\"" + s.name() + "\"")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("SEVERITY_VALUES is missing " + s.name(), //$NON-NLS-1$
                GetProjectErrorsTool.SEVERITY_VALUES.contains(s.name()));
        }
    }

    @Test
    public void testSchemaDeclaresResponseFormatEnum()
    {
        // responseFormat is read in execute(), so it MUST be declared in the schema (the
        // schema<->execute parity ratchet). It is an optional concise/detailed enum.
        String schema = new GetProjectErrorsTool().getInputSchema();
        assertTrue("schema must declare responseFormat", //$NON-NLS-1$
            schema.contains("responseFormat")); //$NON-NLS-1$
        assertTrue("responseFormat enum must list concise", //$NON-NLS-1$
            schema.contains("\"concise\"")); //$NON-NLS-1$
        assertTrue("responseFormat enum must list detailed", //$NON-NLS-1$
            schema.contains("\"detailed\"")); //$NON-NLS-1$
    }

    @Test
    public void testTextsDeclareBothObjectFiltersAndWhichOneReportsMisses()
    {
        // The same claim lives in the description, the two schema entries and the guide. Only the
        // EXACT filter reports misses; saying so for `objects` would promise a report the loose
        // substring filter cannot honestly produce (issue #312 review).
        GetProjectErrorsTool tool = new GetProjectErrorsTool();
        String description = tool.getDescription();
        String schema = tool.getInputSchema();
        String guide = tool.getGuide();

        for (String text : new String[] {description, schema, guide})
        {
            assertTrue("every text must name the exact filter: " + text, //$NON-NLS-1$
                text.contains(GetProjectErrorsTool.PARAM_OBJECT_FQNS));
            assertTrue("every text must name the objectsNotFound report: " + text, //$NON-NLS-1$
                text.contains(GetProjectErrorsTool.KEY_OBJECTS_NOT_FOUND));
            assertTrue("every text must name the objectsUnsupported report: " + text, //$NON-NLS-1$
                text.contains(GetProjectErrorsTool.KEY_OBJECTS_UNSUPPORTED));
            assertTrue("every text must say the two filters are mutually exclusive: " + text, //$NON-NLS-1$
                text.toLowerCase().contains("mutually exclusive")); //$NON-NLS-1$
        }
        // The loose entry must describe itself as a substring test, not as a resolver.
        assertTrue("the objects schema entry must still say SUBSTRING", //$NON-NLS-1$
            schema.contains("SUBSTRING")); //$NON-NLS-1$
    }

    @Test
    public void testObjectsAndObjectFqnsAreMutuallyExclusive()
    {
        // Both filters at once has no single meaning (a fragment vs an asserted address), so the
        // call is refused rather than silently reinterpreted. Validation runs before any
        // project/BM access, so this is headless-safe.
        Map<String, String> params = new HashMap<>();
        params.put(GetProjectErrorsTool.PARAM_OBJECTS, "[\"Catalog.Prod\"]"); //$NON-NLS-1$
        params.put(GetProjectErrorsTool.PARAM_OBJECT_FQNS, "[\"Catalog.Products\"]"); //$NON-NLS-1$
        String result = new GetProjectErrorsTool().execute(params);

        assertTrue("the refusal must be a ToolResult error", //$NON-NLS-1$
            result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the refusal must name both parameters", //$NON-NLS-1$
            result.contains(GetProjectErrorsTool.PARAM_OBJECTS)
                && result.contains(GetProjectErrorsTool.PARAM_OBJECT_FQNS));
        assertTrue("the refusal must echo the received values", //$NON-NLS-1$
            result.contains("Catalog.Prod") && result.contains("Catalog.Products")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOnlyTheExactFilterSwitchesTheResponseToJson()
    {
        // structuredContent is emitted for the exact filter alone; every other call keeps the
        // historical Markdown response, so no existing consumer changes shape.
        GetProjectErrorsTool tool = new GetProjectErrorsTool();

        Map<String, String> none = new HashMap<>();
        assertEquals(IMcpTool.ResponseType.MARKDOWN, tool.getResponseType(none));

        Map<String, String> loose = new HashMap<>();
        loose.put(GetProjectErrorsTool.PARAM_OBJECTS, "[\"Catalog.Products\"]"); //$NON-NLS-1$
        assertEquals(IMcpTool.ResponseType.MARKDOWN, tool.getResponseType(loose));

        Map<String, String> exact = new HashMap<>();
        exact.put(GetProjectErrorsTool.PARAM_OBJECT_FQNS, "[\"Catalog.Products\"]"); //$NON-NLS-1$
        assertEquals(IMcpTool.ResponseType.JSON, tool.getResponseType(exact));

        // A blank/empty array is not a filter: it must not flip the response format either.
        Map<String, String> blank = new HashMap<>();
        blank.put(GetProjectErrorsTool.PARAM_OBJECT_FQNS, "[\"  \"]"); //$NON-NLS-1$
        assertEquals(IMcpTool.ResponseType.MARKDOWN, tool.getResponseType(blank));
    }

    @Test
    public void testGuideExplainsResponseFormat()
    {
        // The guide documents concise (default) vs detailed and what concise omits.
        String guide = new GetProjectErrorsTool().getGuide();
        assertTrue("guide should document responseFormat", //$NON-NLS-1$
            guide.contains("responseFormat")); //$NON-NLS-1$
        assertTrue("guide should name both format values", //$NON-NLS-1$
            guide.contains("concise") && guide.contains("detailed")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInvalidSeverityRejected()
    {
        // Validation runs before any project/BM access, so this is headless-safe.
        Map<String, String> params = new HashMap<>();
        params.put("severity", "NOTASEVERITY"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetProjectErrorsTool().execute(params);
        // The message now ECHOES the rejected value alongside the valid set.
        assertTrue(result.contains("Invalid severity")); //$NON-NLS-1$
        assertTrue("rejected value must be echoed", result.contains("NOTASEVERITY")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ========== objectsNotFound / objectsUnsupported (issue #312) ==========

    @Test
    public void testObjectsNotFoundWarningNamesEveryMissingFqnAndTheFix()
    {
        StringBuilder md = new StringBuilder("# No Errors Found\n"); //$NON-NLS-1$
        GetProjectErrorsTool.appendObjectsNotFoundWarning(md,
            Arrays.asList("Catalog.Nope", "Document.AlsoNope")); //$NON-NLS-1$ //$NON-NLS-2$
        String out = md.toString();

        assertTrue("must carry the objectsNotFound marker", //$NON-NLS-1$
            out.contains("objectsNotFound:")); //$NON-NLS-1$
        assertTrue("must name every missing address", //$NON-NLS-1$
            out.contains("Catalog.Nope") && out.contains("Document.AlsoNope")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must say the filter matched nothing", //$NON-NLS-1$
            out.contains("filtered nothing")); //$NON-NLS-1$
        assertTrue("must point at the discovery tool", //$NON-NLS-1$
            out.contains("get_metadata_objects")); //$NON-NLS-1$
        assertTrue("must be rendered as a blockquote warning", //$NON-NLS-1$
            out.contains("\n> ")); //$NON-NLS-1$
    }

    @Test
    public void testObjectsNotFoundWarningAbsentWhenNothingIsMissing()
    {
        // Every address resolved: the report keeps its previous shape.
        StringBuilder empty = new StringBuilder("# No Errors Found"); //$NON-NLS-1$
        GetProjectErrorsTool.appendObjectsNotFoundWarning(empty, Collections.emptyList());
        assertEquals("# No Errors Found", empty.toString()); //$NON-NLS-1$

        StringBuilder nullCase = new StringBuilder("# No Errors Found"); //$NON-NLS-1$
        GetProjectErrorsTool.appendObjectsNotFoundWarning(nullCase, null);
        assertEquals("# No Errors Found", nullCase.toString()); //$NON-NLS-1$
    }

    @Test
    public void testObjectsUnsupportedWarningIsSeparateFromNotFoundAndCarriesTheReason()
    {
        StringBuilder md = new StringBuilder("# No Errors Found\n"); //$NON-NLS-1$
        GetProjectErrorsTool.appendObjectsUnsupportedWarning(md,
            Collections.singletonList(unsupportedEntry("XDTOPackage.P.ObjectType.T", "because"))); //$NON-NLS-1$ //$NON-NLS-2$
        String out = md.toString();

        assertTrue("must carry its OWN marker, not the objectsNotFound one", //$NON-NLS-1$
            out.contains("objectsUnsupported:")); //$NON-NLS-1$
        assertFalse("an unsupported address must not be reported as missing", //$NON-NLS-1$
            out.contains("objectsNotFound")); //$NON-NLS-1$
        assertTrue("must name the address", out.contains("XDTOPackage.P.ObjectType.T")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must carry the reason", out.contains("because")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testObjectsUnsupportedWarningAbsentWhenThereIsNone()
    {
        StringBuilder empty = new StringBuilder("# No Errors Found"); //$NON-NLS-1$
        GetProjectErrorsTool.appendObjectsUnsupportedWarning(empty, Collections.emptyList());
        assertEquals("# No Errors Found", empty.toString()); //$NON-NLS-1$
        GetProjectErrorsTool.appendObjectsUnsupportedWarning(empty, null);
        assertEquals("# No Errors Found", empty.toString()); //$NON-NLS-1$
    }

    @Test
    public void testNoErrorsBannerNamesTheFilterThatWasActuallyUsed()
    {
        // The two filters produce different reports; the banner must not let a caller mistake
        // one for the other.
        StringBuilder loose = new StringBuilder();
        GetProjectErrorsTool.appendNoErrorsSection(loose, "P", null, //$NON-NLS-1$
            Collections.singletonList("Catalog.Prod"), GetProjectErrorsTool.PARAM_OBJECTS); //$NON-NLS-1$
        assertTrue("the loose banner keeps its historical wording: " + loose, //$NON-NLS-1$
            loose.toString().contains("Objects filter: Catalog.Prod")); //$NON-NLS-1$

        StringBuilder exact = new StringBuilder();
        GetProjectErrorsTool.appendNoErrorsSection(exact, "P", null, //$NON-NLS-1$
            Collections.singletonList("Catalog.Products"), GetProjectErrorsTool.PARAM_OBJECT_FQNS); //$NON-NLS-1$
        assertTrue("the exact banner names objectFqns: " + exact, //$NON-NLS-1$
            exact.toString().contains("objectFqns filter: Catalog.Products")); //$NON-NLS-1$
    }

    // ========== objectFqns: address classification ==========

    @Test
    public void testXdtoMemberShapesAreUnsupportedNotMissing()
    {
        // The filter can only compare against the marker's object presentation, and EDT reports an
        // XDTO problem on 'XDTOPackage.<P>.Package'. A member address can therefore never match,
        // which is NOT the same statement as "this member does not exist".
        for (String member : new String[] {
            "XDTOPackage.P.ObjectType.T", //$NON-NLS-1$
            "XDTOPackage.P.Property.N", //$NON-NLS-1$
            "XDTOPackage.P.ObjectType.T.Property.N"}) //$NON-NLS-1$
        {
            String reason = GetProjectErrorsTool.unsupportedAddressReason(member);
            assertNotNull("an XDTO member address must be classified unsupported: " + member, //$NON-NLS-1$
                reason);
            assertTrue("the reason must point at the package-level address instead: " + reason, //$NON-NLS-1$
                reason.contains("XDTOPackage.<Package>")); //$NON-NLS-1$
        }
    }

    @Test
    public void testSupportedAddressFamiliesAreNotClassifiedUnsupported()
    {
        // The package itself IS addressable (its presentation starts with 'XDTOPackage.<P>.'), and
        // so is every non-XDTO family - none of them may be diverted into objectsUnsupported.
        for (String supported : new String[] {
            "XDTOPackage.P", //$NON-NLS-1$
            "Catalog.Products", //$NON-NLS-1$
            "Catalog.Products.Attribute.Weight", //$NON-NLS-1$
            "Catalog.Products.Form.ItemForm", //$NON-NLS-1$
            "CommonForm.Main.Attribute.Object", //$NON-NLS-1$
            "Subsystem.Sales.Subsystem.Orders", //$NON-NLS-1$
            "Catalog.Products.Predefined.Sample"}) //$NON-NLS-1$
        {
            assertNull("must stay a supported address: " + supported, //$NON-NLS-1$
                GetProjectErrorsTool.unsupportedAddressReason(supported));
        }
        // Russian type token, same verdict: the classification must not be language-sensitive.
        // XDTOPackage has no Russian alias, so the bilingual probe uses Catalog (Spravochnik).
        assertNull(GetProjectErrorsTool.unsupportedAddressReason(
            "\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.Products")); //$NON-NLS-1$
    }

    @Test
    public void testUnsupportedAddressesAreClassifiedWithoutTouchingTheModel()
    {
        // An unsupported address needs no model at all, so an empty project scope must still
        // produce the verdict - and must NOT trigger the "nothing could be inspected" refusal,
        // which exists only for addresses that genuinely need resolution.
        GetProjectErrorsTool.AddressResolution resolution = GetProjectErrorsTool.resolveAddresses(
            Collections.singletonList("XDTOPackage.P.ObjectType.T"), //$NON-NLS-1$
            Collections.<IProject> emptyList(), null);

        assertNull("a shape-only verdict must not fail the call", resolution.error); //$NON-NLS-1$
        assertEquals(1, resolution.unsupported.size());
        assertEquals("XDTOPackage.P.ObjectType.T", resolution.unsupported.get(0).get("fqn")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the entry must carry a reason", //$NON-NLS-1$
            resolution.unsupported.get(0).get("reason").isEmpty()); //$NON-NLS-1$
        assertTrue("an unsupported address is NOT missing", resolution.notFound.isEmpty()); //$NON-NLS-1$
        assertTrue("an unsupported address does NOT scope the scan", resolution.resolved.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testNoInspectableProjectRefusesInsteadOfDeclaringEverythingMissing()
    {
        // Without a readable model every address would be "not found", which is exactly the false
        // verdict this input exists to prevent - so the call is refused with an actionable error.
        GetProjectErrorsTool.AddressResolution resolution = GetProjectErrorsTool.resolveAddresses(
            Arrays.asList("Catalog.Products", "Catalog.Nope"), //$NON-NLS-1$ //$NON-NLS-2$
            Collections.<IProject> emptyList(), null);

        assertNotNull("an undecidable scope must be an error, not a verdict", resolution.error); //$NON-NLS-1$
        assertTrue("the error must be a ToolResult error payload", //$NON-NLS-1$
            resolution.error.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the error must name the parameter it could not resolve", //$NON-NLS-1$
            resolution.error.contains(GetProjectErrorsTool.PARAM_OBJECT_FQNS));
        assertTrue("the error must be actionable", //$NON-NLS-1$
            resolution.error.contains("projectName") && resolution.error.contains("list_projects")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("nothing may be declared missing", resolution.notFound.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testAFailedResolvePassDoesNotCountAsAnInspection()
    {
        // The project HAS a readable model and configuration, but the resolve pass throws. Nothing
        // was decided, so the project must NOT count as inspected: otherwise its undecided
        // addresses are declared missing on the strength of an inspection that never happened.
        IBmModel model = mock(IBmModel.class);
        when(model.executeReadonlyTask(any())).thenThrow(new IllegalStateException("model busy")); //$NON-NLS-1$
        Map<String, Set<String>> found = new LinkedHashMap<>();

        boolean inspected = GetProjectErrorsTool.resolveInProject(project("P"), model, //$NON-NLS-1$
            MdClassFactory.eINSTANCE.createConfiguration(),
            Collections.singletonList("Catalog.Products"), found); //$NON-NLS-1$

        assertFalse("a pass that threw decided nothing and is not an inspection", inspected); //$NON-NLS-1$
        assertTrue("and it must not decide any address either", found.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testACompletedResolvePassCountsAsAnInspection()
    {
        // The counterpart: a pass that ran to the end IS an inspection, even when it resolved
        // nothing - only then may an address be reported as missing.
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Map<String, Set<String>> found = new LinkedHashMap<>();

        boolean inspected = GetProjectErrorsTool.resolveInProject(project("P"), readModel(), //$NON-NLS-1$
            config, Collections.singletonList("Catalog.Nope"), found); //$NON-NLS-1$

        assertTrue("a completed pass is an inspection", inspected); //$NON-NLS-1$
        assertTrue("nothing resolved in an empty configuration", found.isEmpty()); //$NON-NLS-1$
    }

    // ========== objectFqns: yo (U+0451) addressing ==========

    @Test
    public void testYoSpellingResolvesToTheStoredNameAndScopesTheScanWithIt()
    {
        // create_metadata normalizes yo (U+0451) to ye (U+0435) in names by default, so an object
        // the user knows as "M[yo]d" is STORED as "Med". The exact filter must resolve the yo
        // spelling (the write/delete paths already do) AND remember the stored spelling: the
        // markers carry the stored name, so scoping the scan by the caller's spelling would
        // silently match nothing. All Cyrillic here is built from code points (pure-ASCII source).
        String stored = fromCp(0x041c, 0x0435, 0x0434); // Med
        String requested = "Catalog." + fromCp(0x041c, 0x0451, 0x0434); // Catalog.M[yo]d //$NON-NLS-1$
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName(stored);
        config.getCatalogs().add(catalog);

        Map<String, Set<String>> found = new LinkedHashMap<>();
        assertTrue(GetProjectErrorsTool.resolveInProject(project("P"), readModel(), config, //$NON-NLS-1$
            Collections.singletonList(requested), found));

        assertEquals("the yo spelling must resolve against the stored ye name", //$NON-NLS-1$
            singleton("Catalog." + stored), found.get(requested)); //$NON-NLS-1$
        // A Russian TYPE token takes the same route (Spravochnik.M[yo]d).
        String ruRequested = fromCp(0x0421, 0x043f, 0x0440, 0x0430, 0x0432, 0x043e, 0x0447, 0x043d,
            0x0438, 0x043a) + "." + fromCp(0x041c, 0x0451, 0x0434); //$NON-NLS-1$
        Map<String, Set<String>> ruFound = new LinkedHashMap<>();
        assertTrue(GetProjectErrorsTool.resolveInProject(project("P"), readModel(), config, //$NON-NLS-1$
            Collections.singletonList(ruRequested), ruFound));
        assertEquals(singleton("Catalog." + stored), ruFound.get(ruRequested)); //$NON-NLS-1$
    }

    @Test
    public void testAYolessAddressResolvesToItselfAndAGenuineMissStaysMissing()
    {
        // The fallback must not blur the verdicts: an address that resolves as written keeps its own
        // spelling, and a name that exists in NEITHER spelling is still undecided (-> not found).
        String stored = fromCp(0x041c, 0x0435, 0x0434); // Med
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName(stored);
        config.getCatalogs().add(catalog);

        Map<String, Set<String>> found = new LinkedHashMap<>();
        assertTrue(GetProjectErrorsTool.resolveInProject(project("P"), readModel(), config, //$NON-NLS-1$
            Arrays.asList("Catalog." + stored, "Catalog." + fromCp(0x041b, 0x0451, 0x0434)), found)); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(singleton("Catalog." + stored), found.get("Catalog." + stored)); //$NON-NLS-1$
        assertNull("a name that exists in neither spelling must stay undecided", //$NON-NLS-1$
            found.get("Catalog." + fromCp(0x041b, 0x0451, 0x0434))); //$NON-NLS-1$
    }

    // ========== objectFqns: the structuredContent payload ==========

    @Test
    public void testAddressPayloadCarriesEveryVerdictListAndTheReport()
    {
        GetProjectErrorsTool.AddressResolution resolution =
            new GetProjectErrorsTool.AddressResolution();
        resolution.resolved.add("Catalog.Products"); //$NON-NLS-1$
        resolution.notFound.add("Catalog.Nope"); //$NON-NLS-1$
        resolution.unsupported.add(unsupportedEntry("XDTOPackage.P.Property.N", "why")); //$NON-NLS-1$ //$NON-NLS-2$

        String json = GetProjectErrorsTool.addressPayload("# Configuration Problems", 2, resolution); //$NON-NLS-1$

        assertTrue("must be a success envelope", json.contains("\"success\":true")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must carry the human report", //$NON-NLS-1$
            json.contains("\"report\":") && json.contains("Configuration Problems")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must carry the row count", json.contains("\"problemsFound\":2")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must carry the resolved addresses", //$NON-NLS-1$
            json.contains("\"objectsResolved\":[\"Catalog.Products\"]")); //$NON-NLS-1$
        assertTrue("must carry the missing addresses", //$NON-NLS-1$
            json.contains("\"objectsNotFound\":[\"Catalog.Nope\"]")); //$NON-NLS-1$
        assertTrue("must carry the unsupported addresses with their reason", //$NON-NLS-1$
            json.contains("\"objectsUnsupported\"") && json.contains("XDTOPackage.P.Property.N") //$NON-NLS-1$ //$NON-NLS-2$
                && json.contains("why")); //$NON-NLS-1$
    }

    @Test
    public void testAddressPayloadEmitsEveryVerdictListEvenWhenEmpty()
    {
        // Consistent emission across branches: a consumer must never have to tell "absent" from
        // "none" (the response-contract rule the project pins for every output field).
        String json = GetProjectErrorsTool.addressPayload("# No Errors Found", 0, //$NON-NLS-1$
            new GetProjectErrorsTool.AddressResolution());

        assertTrue(json.contains("\"objectsResolved\":[]")); //$NON-NLS-1$
        assertTrue(json.contains("\"objectsNotFound\":[]")); //$NON-NLS-1$
        assertTrue(json.contains("\"objectsUnsupported\":[]")); //$NON-NLS-1$
        assertTrue(json.contains("\"problemsFound\":0")); //$NON-NLS-1$
    }

    /** A single {@code objectsUnsupported} entry in the wire shape the tool emits. */
    private static Map<String, String> unsupportedEntry(String fqn, String reason)
    {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("fqn", fqn); //$NON-NLS-1$
        entry.put("reason", reason); //$NON-NLS-1$
        return entry;
    }

    // ========== on-demand guide (detail moved out of description/schema) ==========

    @Test
    public void testGuideIsNonEmptyAndHoldsMigratedDetail()
    {
        // The exhaustive prose now lives in getGuide() (served on demand), not in the
        // always-loaded description/schema. Assert it migrated rather than vanished by
        // checking keywords that were removed from the slim description/schema.
        String guide = new GetProjectErrorsTool().getGuide();
        assertNotNull(guide);
        assertFalse("guide must be non-empty", guide.isEmpty()); //$NON-NLS-1$
        // The guide body no longer repeats the tool-name H1 (GuideRenderer emits the
        // "# get_project_errors" title itself), so assert the migrated DETAIL instead.
        // Detail moved out of the description: the structural locator columns.
        assertTrue("guide should document the Module path locator", //$NON-NLS-1$
            guide.contains("Module path")); //$NON-NLS-1$
        // Detail moved out of the schema: the checkId short-UID vs symbolic-id nuance.
        assertTrue("guide should explain the short UID / symbolic check id", //$NON-NLS-1$
            guide.contains("ql-temp-table-index") && guide.contains("SU23")); //$NON-NLS-1$ //$NON-NLS-2$
        // Detail moved out of the description: the unresolved-marker behaviour.
        assertTrue("guide should explain unresolved markers", //$NON-NLS-1$
            guide.contains("unresolved")); //$NON-NLS-1$
    }

    // ========== objectFqns: form-member addressing ==========

    @Test
    public void testAnItemLevelHandlerAddressMustNameTheOwnersKind()
    {
        // The owner of an item-level handler is looked up by NAME alone, exactly like a leaf member
        // is, so the OWNER's kind token has to be checked too. Otherwise `...Button.Price.Handler.X`
        // (where Price is a FIELD) is called resolved and then scopes the marker scan by a kind
        // segment no location ever carries - a clean report for an address that does not exist.
        FormModel form = newFormModel();

        assertFalse("the owner's OWN kind must resolve", //$NON-NLS-1$
            scopeSpellings(form, HANDLER_ON_FIELD).isEmpty());
        assertTrue("a FOREIGN owner kind must not resolve", //$NON-NLS-1$
            scopeSpellings(form, FORM_FQN + ".Button.Price.Handler.OnChange").isEmpty()); //$NON-NLS-1$
        assertTrue("a MISSPELT owner kind must not resolve", //$NON-NLS-1$
            scopeSpellings(form, FORM_FQN + ".Fielld.Price.Handler.OnChange").isEmpty()); //$NON-NLS-1$
        // A form COMMAND is a legal handler owner and is routed BY kind, so it keeps resolving.
        assertFalse("Command is a legal handler owner", //$NON-NLS-1$
            scopeSpellings(form, FORM_FQN + ".Command.Save.Handler.Action").isEmpty()); //$NON-NLS-1$
        // ...and a command addressed as an item is not an item, so it stays a miss.
        assertTrue("a command addressed with an item kind must not resolve", //$NON-NLS-1$
            scopeSpellings(form, FORM_FQN + ".Button.Save.Handler.Action").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testAResolvedHandlerIsScopedByTheEventSpellingsTheModelCarries()
    {
        // findFormHandler matches the English `name` AND the Russian `nameRu` of the event, while a
        // marker location renders exactly ONE of them. Scoping by the spelling the CALLER typed
        // would therefore filter out every problem on the handler just proven to exist.
        FormModel form = newFormModel();
        String ruEvent = fromCp(0x041f, 0x0440, 0x0438, 0x0418, 0x0437, 0x043c, 0x0435, 0x043d,
            0x0435, 0x043d, 0x0438, 0x0438); // PriIzmenenii
        String ruAddress = FORM_FQN + ".Field.Price.Handler." + ruEvent; //$NON-NLS-1$

        List<String> fromRu = scopeSpellings(form, ruAddress);
        assertTrue("the address as written must still scope the scan", //$NON-NLS-1$
            fromRu.contains(ruAddress));
        assertTrue("the event's OTHER spelling must scope it too", //$NON-NLS-1$
            fromRu.contains(HANDLER_ON_FIELD));

        // Symmetrical: an English address must scope by the Russian spelling as well, so the same
        // request works against a Russian-language project.
        List<String> fromEn = scopeSpellings(form, HANDLER_ON_FIELD);
        assertTrue(fromEn.contains(HANDLER_ON_FIELD));
        assertTrue("an English address must scope by the Russian spelling too", //$NON-NLS-1$
            fromEn.contains(ruAddress));
    }

    @Test
    public void testAFormMemberWhoseContentModelCannotBeReadStaysUndecided()
    {
        // The form EXISTS in the configuration, but its CONTENT model cannot be read (here: no BM
        // services outside a workbench; live: EDT still indexing, or the transaction threw). That is
        // a failure to DECIDE, not an absence: the address must stay undecided and the project must
        // NOT count as inspected, or the single failed attempt is reported as objectsNotFound.
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("C"); //$NON-NLS-1$
        CatalogForm form = MdClassFactory.eINSTANCE.createCatalogForm();
        form.setName("ItemForm"); //$NON-NLS-1$
        catalog.getForms().add(form);
        config.getCatalogs().add(catalog);

        Map<String, Set<String>> found = new LinkedHashMap<>();
        boolean inspected = GetProjectErrorsTool.resolveInProject(project("P"), readModel(), config, //$NON-NLS-1$
            Collections.singletonList("Catalog.C.Form.ItemForm.Field.Code"), found); //$NON-NLS-1$

        assertFalse("a form whose content model could not be read decided nothing", inspected); //$NON-NLS-1$
        assertTrue("and it must not decide the address either", found.isEmpty()); //$NON-NLS-1$

        // The counterpart: a form that is simply ABSENT is a decided miss, so the pass still counts
        // as an inspection - the undecided verdict must not swallow the ordinary not-found one.
        Map<String, Set<String>> absent = new LinkedHashMap<>();
        assertTrue("an absent form is a decided miss", //$NON-NLS-1$
            GetProjectErrorsTool.resolveInProject(project("P"), readModel(), config, //$NON-NLS-1$
                Collections.singletonList("Catalog.C.Form.NoSuchForm.Field.Code"), absent)); //$NON-NLS-1$
        assertTrue(absent.isEmpty());
    }

    @Test
    public void testTheSameAddressKeepsEveryProjectsOwnStoredSpelling()
    {
        // With no projectName the SAME address is offered to every project, and two projects may
        // legitimately store it differently: create_metadata's yo->ye normalization is a DEFAULT,
        // not a rule, so one project holds "M[ye]d" while another holds the verbatim "M[yo]d".
        // Keeping only the first spelling would scope BOTH projects by one variant, losing every
        // problem under the other object.
        String ye = fromCp(0x041c, 0x0435, 0x0434); // Med
        String yo = fromCp(0x041c, 0x0451, 0x0434); // M[yo]d
        String requested = "Catalog." + yo; //$NON-NLS-1$

        Map<String, Set<String>> found = new LinkedHashMap<>();
        assertTrue(GetProjectErrorsTool.resolveInProject(project("A"), readModel(), //$NON-NLS-1$
            configWithCatalog(ye), Collections.singletonList(requested), found));
        assertTrue(GetProjectErrorsTool.resolveInProject(project("B"), readModel(), //$NON-NLS-1$
            configWithCatalog(yo), Collections.singletonList(requested), found));

        assertEquals("every project's own stored spelling must scope the scan", //$NON-NLS-1$
            new HashSet<>(Arrays.asList("Catalog." + ye, "Catalog." + yo)), found.get(requested)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ========== helpers ==========

    /** A configuration holding one catalog under the given stored Name. */
    private static Configuration configWithCatalog(String storedName)
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName(storedName);
        config.getCatalogs().add(catalog);
        return config;
    }

    /** The owning form of the synthetic form model, as an FQN prefix. */
    private static final String FORM_FQN = "Catalog.C.Form.ItemForm"; //$NON-NLS-1$

    /** The English address of the handler bound on the synthetic model's FIELD. */
    private static final String HANDLER_ON_FIELD = FORM_FQN + ".Field.Price.Handler.OnChange"; //$NON-NLS-1$

    /**
     * Decides one form-member address against the synthetic form model, exactly as the deferred
     * member pass does (the probe spelling IS the requested one here - the yo fallback is covered
     * separately).
     */
    private static List<String> scopeSpellings(FormModel form, String fqn)
    {
        FormElementWriter.FormMemberRef ref = FormElementWriter.parse(fqn);
        assertNotNull("the address must parse as a form member: " + fqn, ref); //$NON-NLS-1$
        return GetProjectErrorsTool.memberScopeSpellings(form.root,
            new GetProjectErrorsTool.DeferredMember(fqn, fqn, ref));
    }

    /** The synthetic form content model: its root plus the elements addressed by the tests. */
    private static final class FormModel
    {
        EObject root;
    }

    /**
     * A self-contained dynamic EMF model shaped like the form CONTENT metamodel - enough for the
     * member / handler resolution under test: a form root with an {@code items} tree of
     * {@code FormField} / {@code Button} items and a {@code formCommands} list, where every item
     * carries {@code handlers} typed to an {@code EventHandler} whose {@code event} exposes the
     * bilingual {@code name} / {@code nameRu}. The real form package lives in an EDT runtime bundle
     * this plugin must not bind to at compile time (which is why the production code is reflective),
     * so the test supplies its own shape.
     *
     * <p>Contents: a {@code FormField} named {@code Price} with a handler bound to the
     * {@code OnChange} / {@code [PriIzmenenii]} event, and a {@code FormCommand} named {@code Save}
     * with an action.</p>
     */
    private static FormModel newFormModel()
    {
        EcoreFactory f = EcoreFactory.eINSTANCE;
        EPackage pkg = f.createEPackage();
        pkg.setName("form"); //$NON-NLS-1$
        pkg.setNsURI("http://g5.1c.ru/v8/dt/form/geterrorstest"); //$NON-NLS-1$
        pkg.setNsPrefix("form"); //$NON-NLS-1$

        EClass event = f.createEClass();
        event.setName("Event"); //$NON-NLS-1$
        event.getEStructuralFeatures().add(stringAttribute("name")); //$NON-NLS-1$
        event.getEStructuralFeatures().add(stringAttribute("nameRu")); //$NON-NLS-1$
        pkg.getEClassifiers().add(event);

        EClass eventHandler = f.createEClass();
        eventHandler.setName("EventHandler"); //$NON-NLS-1$
        eventHandler.getEStructuralFeatures().add(stringAttribute("name")); //$NON-NLS-1$
        eventHandler.getEStructuralFeatures().add(containment("event", event, false)); //$NON-NLS-1$
        pkg.getEClassifiers().add(eventHandler);

        EClass formItem = f.createEClass();
        formItem.setName("FormItem"); //$NON-NLS-1$
        formItem.setAbstract(true);
        formItem.getEStructuralFeatures().add(stringAttribute("name")); //$NON-NLS-1$
        formItem.getEStructuralFeatures().add(containment("handlers", eventHandler, true)); //$NON-NLS-1$
        pkg.getEClassifiers().add(formItem);

        EClass formField = subclass("FormField", formItem); //$NON-NLS-1$
        pkg.getEClassifiers().add(formField);
        pkg.getEClassifiers().add(subclass("Button", formItem)); //$NON-NLS-1$

        EClass action = f.createEClass();
        action.setName("FormCommandHandlerContainer"); //$NON-NLS-1$
        pkg.getEClassifiers().add(action);

        EClass formCommand = f.createEClass();
        formCommand.setName("FormCommand"); //$NON-NLS-1$
        formCommand.getEStructuralFeatures().add(stringAttribute("name")); //$NON-NLS-1$
        formCommand.getEStructuralFeatures().add(containment("action", action, false)); //$NON-NLS-1$
        pkg.getEClassifiers().add(formCommand);

        EClass form = f.createEClass();
        form.setName("Form"); //$NON-NLS-1$
        form.getEStructuralFeatures().add(containment("items", formItem, true)); //$NON-NLS-1$
        form.getEStructuralFeatures().add(containment("formCommands", formCommand, true)); //$NON-NLS-1$
        pkg.getEClassifiers().add(form);

        FormModel model = new FormModel();
        model.root = pkg.getEFactoryInstance().create(form);

        EObject field = pkg.getEFactoryInstance().create(formField);
        setString(field, "name", "Price"); //$NON-NLS-1$ //$NON-NLS-2$
        list(model.root, "items").add(field); //$NON-NLS-1$

        EObject boundEvent = pkg.getEFactoryInstance().create(event);
        setString(boundEvent, "name", "OnChange"); //$NON-NLS-1$ //$NON-NLS-2$
        setString(boundEvent, "nameRu", fromCp(0x041f, 0x0440, 0x0438, 0x0418, 0x0437, 0x043c, //$NON-NLS-1$
            0x0435, 0x043d, 0x0435, 0x043d, 0x0438, 0x0438)); // PriIzmenenii
        EObject handler = pkg.getEFactoryInstance().create(eventHandler);
        setString(handler, "name", "PriceOnChange"); //$NON-NLS-1$ //$NON-NLS-2$
        handler.eSet(handler.eClass().getEStructuralFeature("event"), boundEvent); //$NON-NLS-1$
        list(field, "handlers").add(handler); //$NON-NLS-1$

        EObject command = pkg.getEFactoryInstance().create(formCommand);
        setString(command, "name", "Save"); //$NON-NLS-1$ //$NON-NLS-2$
        command.eSet(command.eClass().getEStructuralFeature("action"), //$NON-NLS-1$
            pkg.getEFactoryInstance().create(action));
        list(model.root, "formCommands").add(command); //$NON-NLS-1$

        return model;
    }

    private static EAttribute stringAttribute(String name)
    {
        EAttribute attribute = EcoreFactory.eINSTANCE.createEAttribute();
        attribute.setName(name);
        attribute.setEType(EcorePackage.Literals.ESTRING);
        return attribute;
    }

    private static EReference containment(String name, EClass type, boolean many)
    {
        EReference reference = EcoreFactory.eINSTANCE.createEReference();
        reference.setName(name);
        reference.setEType(type);
        reference.setContainment(true);
        reference.setUpperBound(many ? -1 : 1);
        return reference;
    }

    private static EClass subclass(String name, EClass superType)
    {
        EClass eClass = EcoreFactory.eINSTANCE.createEClass();
        eClass.setName(name);
        eClass.getESuperTypes().add(superType);
        return eClass;
    }

    private static void setString(EObject object, String featureName, String value)
    {
        object.eSet(object.eClass().getEStructuralFeature(featureName), value);
    }

    @SuppressWarnings("unchecked")
    private static EList<EObject> list(EObject object, String featureName)
    {
        return (EList<EObject>)object.eGet(object.eClass().getEStructuralFeature(featureName));
    }

    private static Marker marker(MarkerSeverity severity, String checkId, String message, String projectName)
    {
        // Build the project mock first; stubbing one mock inside another's thenReturn() trips
        // Mockito's UnfinishedStubbingException.
        IProject project = project(projectName);
        Marker marker = mock(Marker.class);
        when(marker.getSeverity()).thenReturn(severity);
        when(marker.getCheckId()).thenReturn(checkId);
        when(marker.getMessage()).thenReturn(message);
        when(marker.getProject()).thenReturn(project);
        return marker;
    }

    private static Marker markerThatThrowsOnPresentation(MarkerSeverity severity, String checkId,
        String message, String projectName)
    {
        Marker marker = marker(severity, checkId, message, projectName);
        when(marker.getObjectPresentation()).thenThrow(new RuntimeException("cannot resolve")); //$NON-NLS-1$
        return marker;
    }

    private static IProject project(String name)
    {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn(name);
        return project;
    }

    /**
     * An {@link IBmModel} whose read task really RUNS (with a stand-in transaction), so the address
     * resolution under test executes instead of being stubbed away.
     */
    private static IBmModel readModel()
    {
        IBmModel model = mock(IBmModel.class);
        when(model.executeReadonlyTask(any())).thenAnswer(inv -> {
            IBmTask<?> task = inv.getArgument(0);
            return task.execute(mock(IBmTransaction.class), null);
        });
        return model;
    }

    /** Builds a string from BMP code points (keeps this test source pure ASCII). */
    private static String fromCp(int... cps)
    {
        return new String(cps, 0, cps.length);
    }

    private static CheckUid checkUid(String symbolicCheckId)
    {
        CheckUid uid = mock(CheckUid.class);
        when(uid.getCheckId()).thenReturn(symbolicCheckId);
        return uid;
    }

    private static Set<String> singleton(String value)
    {
        Set<String> set = new HashSet<>();
        set.add(value);
        return set;
    }

    /**
     * Builds an {@link IExtraInfoMap} carrying the raw marker keys the structural locator
     * reads: {@code uriToProblem} and {@code line}. Mirrors how EDT stores them as strings
     * on the marker, so {@code StandardExtraInfo.TEXT_*.get(...)} parses them the same way at
     * runtime. A null value leaves that key unset.
     *
     * @param uriToProblem the EMF problem URI string, or null to omit it
     * @param line the 1-based line as a string, or null to omit it
     */
    private static IExtraInfoMap extraInfo(String uriToProblem, String line)
    {
        ExtraInfoMap map = new ExtraInfoMap();
        if (uriToProblem != null)
        {
            map.put("uriToProblem", uriToProblem); //$NON-NLS-1$
        }
        if (line != null)
        {
            map.put("line", line); //$NON-NLS-1$
        }
        return map;
    }

    /**
     * Minimal {@link IExtraInfoMap} backed by a {@link HashMap}. {@code IExtraInfoMap} is an
     * interface of default methods over {@code Map<String, String>}, so delegating the map
     * behaviour to {@link HashMap} is enough for the locator helpers under test.
     */
    private static final class ExtraInfoMap extends HashMap<String, String> implements IExtraInfoMap
    {
        private static final long serialVersionUID = 1L;
    }
}
