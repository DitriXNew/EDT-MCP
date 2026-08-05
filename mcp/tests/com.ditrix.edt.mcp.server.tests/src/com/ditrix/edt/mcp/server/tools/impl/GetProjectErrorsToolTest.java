/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.Collection;
import java.util.TreeSet;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com._1c.g5.v8.dt.validation.marker.IExtraInfoMap;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.impl.GetProjectErrorsTool.ErrorInfo;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.ditrix.edt.mcp.server.utils.MetadataNodeResolver;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.PredefinedWriter;

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

    // ========== checkIdMatchesExact (pure) ==========
    // Used by apply_quick_fix, a mutation locator: unlike checkIdMatches, a substring must NOT
    // match, since a loose needle could silently pick the wrong check to auto-fix.

    @Test
    public void testCheckIdMatchesExactBySymbolicId()
    {
        assertTrue(GetProjectErrorsTool.checkIdMatchesExact("SU23", "ql-temp-table-index", "ql-temp-table-index")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testCheckIdMatchesExactByShortUid()
    {
        assertTrue(GetProjectErrorsTool.checkIdMatchesExact("SU23", "ql-temp-table-index", "SU23")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testCheckIdMatchesExactCaseInsensitive()
    {
        assertTrue(GetProjectErrorsTool.checkIdMatchesExact("Su23", null, "su23")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(GetProjectErrorsTool.checkIdMatchesExact(null, "QL-Temp-Table", "ql-temp-table")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testCheckIdMatchesExactRejectsSubstring()
    {
        // The exact bug this guards: "doc" must NOT match "doc-comment-parameter-section" here,
        // even though the loose checkIdMatches used by get_project_errors would allow it.
        assertFalse(GetProjectErrorsTool.checkIdMatchesExact("SU1", "doc-comment-parameter-section", "doc")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(GetProjectErrorsTool.checkIdMatchesExact("SU23", null, "su2")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testCheckIdMatchesExactNoMatch()
    {
        assertFalse(GetProjectErrorsTool.checkIdMatchesExact("SU23", "ql-temp-table-index", "zzz")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testCheckIdMatchesExactBothNull()
    {
        assertFalse(GetProjectErrorsTool.checkIdMatchesExact(null, null, "anything")); //$NON-NLS-1$
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
            singleton("catalog.foo"), null, null, shown, filteredOut); //$NON-NLS-1$

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
            Collections.emptySet(), null, null, shown, filteredOut);

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
            Collections.emptySet(), null, null, shown, filteredOut);

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
            singleton("catalog.foo"), null, null, shown, filteredOut); //$NON-NLS-1$

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
            Collections.emptySet(), null, null, shown, filteredOut);

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
            singleton("catalog.foo"), null, null, new int[]{0}, new int[]{0}); //$NON-NLS-1$

        assertNotNull(error);
    }

    @Test
    public void testObjectsFilterNoMatch()
    {
        Marker marker = marker(MarkerSeverity.MINOR, "SU23", "msg", "Proj"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        when(marker.getObjectPresentation()).thenReturn("Catalog.Foo"); //$NON-NLS-1$

        ErrorInfo error = GetProjectErrorsTool.buildIfMatches(marker, null, null,
            singleton("catalog.bar"), null, null, new int[]{0}, new int[]{0}); //$NON-NLS-1$

        assertNull(error);
    }

    @Test
    public void testCheckIdFilterMatchesShortUid()
    {
        Marker marker = marker(MarkerSeverity.MINOR, "SU23", "msg", "Proj"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        when(marker.getObjectPresentation()).thenReturn("Catalog.Foo"); //$NON-NLS-1$

        ErrorInfo error = GetProjectErrorsTool.buildIfMatches(marker, null, "su2",
            Collections.emptySet(), null, null, new int[]{0}, new int[]{0}); //$NON-NLS-1$

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
            Collections.emptySet(), repo, null, new int[]{0}, new int[]{0}); //$NON-NLS-1$

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
            Collections.emptySet(), null, null, shown, filteredOut); //$NON-NLS-1$

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
            Collections.emptySet(), null, null, new int[]{0}, new int[]{0});

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
            Collections.emptySet(), null, null, new int[]{0}, new int[]{0});

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

        GetProjectErrorsTool.ProjectResolution decided = GetProjectErrorsTool.resolveInProject(
            project("P"), model, MdClassFactory.eINSTANCE.createConfiguration(), //$NON-NLS-1$
            Collections.singletonList("Catalog.Products")); //$NON-NLS-1$

        assertFalse("a pass that threw decided nothing and is not an inspection", //$NON-NLS-1$
            decided.passCompleted);
        assertTrue("and it must not decide any address either", decided.resolved.isEmpty()); //$NON-NLS-1$
        assertEquals("every address it was asked about stays UNDECIDED, never 'not found'", //$NON-NLS-1$
            singleton("Catalog.Products"), decided.undecided); //$NON-NLS-1$
    }

    @Test
    public void testACompletedResolvePassCountsAsAnInspection()
    {
        // The counterpart: a pass that ran to the end IS an inspection, even when it resolved
        // nothing - only then may an address be reported as missing.
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();

        GetProjectErrorsTool.ProjectResolution decided = GetProjectErrorsTool.resolveInProject(
            project("P"), readModel(), config, Collections.singletonList("Catalog.Nope")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("a completed pass is an inspection", decided.passCompleted); //$NON-NLS-1$
        assertTrue("nothing resolved in an empty configuration", decided.resolved.isEmpty()); //$NON-NLS-1$
        assertTrue("a decided absence is NOT undecided", decided.undecided.isEmpty()); //$NON-NLS-1$
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

        Map<String, Set<String>> found = resolvedIn(config, requested);
        assertEquals("the yo spelling must resolve against the stored ye name", //$NON-NLS-1$
            singleton("Catalog." + stored), found.get(requested)); //$NON-NLS-1$
        // A Russian TYPE token takes the same route (Spravochnik.M[yo]d).
        String ruRequested = fromCp(0x0421, 0x043f, 0x0440, 0x0430, 0x0432, 0x043e, 0x0447, 0x043d,
            0x0438, 0x043a) + "." + fromCp(0x041c, 0x0451, 0x0434); //$NON-NLS-1$
        assertEquals(singleton("Catalog." + stored), //$NON-NLS-1$
            resolvedIn(config, ruRequested).get(ruRequested));
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

        Map<String, Set<String>> found = resolvedIn(config, "Catalog." + stored, //$NON-NLS-1$
            "Catalog." + fromCp(0x041b, 0x0451, 0x0434)); //$NON-NLS-1$

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
    public void testAResolvedCommandActionIsScopedByBothActionSpellings()
    {
        // A form COMMAND carries no platform event: its single handler slot IS the `action`
        // containment, so there is no `event` reference to read the other spelling from. Scoping by
        // the caller's own leaf alone therefore filtered out every problem of a handler that had
        // just been PROVEN to exist - a Russian address never matched the `Handler.Action` an
        // English-language project renders, and the address was still echoed in objectsResolved.
        FormModel form = newFormModel();
        String ruAction = fromCp(0x0414, 0x0435, 0x0439, 0x0441, 0x0442, 0x0432, 0x0438, 0x0435); // Dejstvie
        String enAddress = FORM_FQN + ".Command.Save.Handler.Action"; //$NON-NLS-1$
        String ruAddress = FORM_FQN + ".Command.Save.Handler." + ruAction; //$NON-NLS-1$

        List<String> fromRu = scopeSpellings(form, ruAddress);
        assertTrue("the address as written must still scope the scan", //$NON-NLS-1$
            fromRu.contains(ruAddress));
        assertTrue("the canonical Action spelling must scope the scan too", //$NON-NLS-1$
            fromRu.contains(enAddress));

        // Symmetrical: an English address must scope by the localized spelling as well.
        List<String> fromEn = scopeSpellings(form, enAddress);
        assertTrue(fromEn.contains(enAddress));
        assertTrue("an English command address must scope by the Russian Action spelling too", //$NON-NLS-1$
            fromEn.contains(ruAddress));
    }

    @Test
    public void testAResolvedFormMemberIsScopedByTheFormItsProblemsAreReportedOn()
    {
        // EDT indexes a form's markers on the form CONTENT object, whose presentation is
        // "<formPath>.Form" - never on the item. Verified live on EDT 2026.1: a form FIELD bound to a
        // missing handler procedure produced form-legacy-check-event-handler at
        // "Catalog.Catalog.Form.ItemForm.Form", with no trace of the field. So a member address
        // scoped by the member alone matches NOTHING: objectsResolved next to "# No Errors Found",
        // on an element that demonstrably has a problem. The owning form must scope the scan too.
        FormModel form = newFormModel();

        for (String address : new String[] {
            FORM_FQN + ".Field.Price", //$NON-NLS-1$
            HANDLER_ON_FIELD,
            FORM_FQN + ".Command.Save.Handler.Action"}) //$NON-NLS-1$
        {
            List<String> spellings = scopeSpellings(form, address);
            assertTrue("the address must resolve: " + address, !spellings.isEmpty()); //$NON-NLS-1$
            assertTrue("the address itself stays in the scope: " + address, //$NON-NLS-1$
                spellings.contains(address));
            assertTrue("the OWNING FORM must scope the scan too, or the member selects nothing: " //$NON-NLS-1$
                + address, spellings.contains(FORM_FQN));
        }

        // A member that does NOT resolve must stay an empty verdict - the widening may never turn a
        // miss into a hit on the containing form.
        assertTrue("a ghost member must not be absolved by its form", //$NON-NLS-1$
            scopeSpellings(form, FORM_FQN + ".Field.NoSuchItem").isEmpty()); //$NON-NLS-1$
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

        GetProjectErrorsTool.ProjectResolution decided = GetProjectErrorsTool.resolveInProject(
            project("P"), readModel(), config, //$NON-NLS-1$
            Collections.singletonList("Catalog.C.Form.ItemForm.Field.Code")); //$NON-NLS-1$

        assertTrue("and it must not decide the address", decided.resolved.isEmpty()); //$NON-NLS-1$
        assertEquals("the ADDRESS is what stays undecided, not the whole project", //$NON-NLS-1$
            singleton("Catalog.C.Form.ItemForm.Field.Code"), decided.undecided); //$NON-NLS-1$

        // The counterpart: a form that is simply ABSENT is a decided miss - the undecided verdict
        // must not swallow the ordinary not-found one.
        GetProjectErrorsTool.ProjectResolution absent = GetProjectErrorsTool.resolveInProject(
            project("P"), readModel(), config, //$NON-NLS-1$
            Collections.singletonList("Catalog.C.Form.NoSuchForm.Field.Code")); //$NON-NLS-1$
        assertTrue(absent.resolved.isEmpty());
        assertTrue("an absent form is a decided miss, never undecided", //$NON-NLS-1$
            absent.undecided.isEmpty());
    }

    @Test
    public void testAnAddressNoProjectCouldDecideRefusesInsteadOfBeingCalledMissing()
    {
        // The whole point of the per-ADDRESS undecided state. Project A cannot read the form's
        // content model, so it decides nothing about the member; project B completes normally but
        // simply does not hold that form. A request-wide "was anything inspected" flag would be
        // true here (B inspected fine) and the member would be declared objectsNotFound on the
        // strength of an inspection that never looked at it.
        Configuration withForm = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("C"); //$NON-NLS-1$
        CatalogForm form = MdClassFactory.eINSTANCE.createCatalogForm();
        form.setName("ItemForm"); //$NON-NLS-1$
        catalog.getForms().add(form);
        withForm.getCatalogs().add(catalog);

        String member = "Catalog.C.Form.ItemForm.Field.Code"; //$NON-NLS-1$
        GetProjectErrorsTool.ProjectResolution undecided = GetProjectErrorsTool.resolveInProject(
            project("A"), readModel(), withForm, Collections.singletonList(member)); //$NON-NLS-1$
        GetProjectErrorsTool.ProjectResolution complete = GetProjectErrorsTool.resolveInProject(
            project("B"), readModel(), MdClassFactory.eINSTANCE.createConfiguration(), //$NON-NLS-1$
            Collections.singletonList(member));

        // The premise: A left it undecided while B ran to the end and resolved nothing.
        assertEquals(singleton(member), undecided.undecided);
        assertTrue(complete.passCompleted);
        assertTrue(complete.undecided.isEmpty());

        // So the request-level verdict must be a REFUSAL naming the address, never "not found".
        GetProjectErrorsTool.AddressResolution resolution =
            new GetProjectErrorsTool.AddressResolution();
        GetProjectErrorsTool.foldProjectDecisions(resolution, Collections.singletonList(member), Collections.singletonList(member),
            Arrays.asList(undecided, complete));

        assertNotNull("an address nobody could decide must refuse the call", resolution.error); //$NON-NLS-1$
        assertTrue("the refusal must name the address it could not decide", //$NON-NLS-1$
            resolution.error.contains("Form.ItemForm.Field.Code")); //$NON-NLS-1$
        assertTrue("nothing may be declared missing", resolution.notFound.isEmpty()); //$NON-NLS-1$

        // And the counterpart: once ANY project resolves it, the undecided project is irrelevant.
        GetProjectErrorsTool.ProjectResolution resolvedSomewhere =
            new GetProjectErrorsTool.ProjectResolution("B"); //$NON-NLS-1$
        resolvedSomewhere.passCompleted = true;
        resolvedSomewhere.resolved.put(member, singleton(member));
        GetProjectErrorsTool.AddressResolution ok = new GetProjectErrorsTool.AddressResolution();
        GetProjectErrorsTool.foldProjectDecisions(ok, Collections.singletonList(member), Collections.singletonList(member),
            Arrays.asList(undecided, resolvedSomewhere));
        assertNull("a resolution elsewhere settles the address", ok.error); //$NON-NLS-1$
        assertEquals(Collections.singletonList(member), ok.resolved);
    }

    @Test
    public void testAnUnreadableEdtProjectIsUndecidedWhileANonEdtOneIsSkipped()
    {
        // The two reasons a project in scope has no readable model are NOT the same fact, and the
        // answer must differ:
        //   * an ordinary Eclipse/Java/Maven project has no BM model by definition. It cannot hold
        //     1C metadata, so it is skipped - otherwise ONE such project would mute the missing
        //     address report for the whole workspace (exactly what the review of e4cf002b caught).
        //   * a 1C:EDT project that is still INDEXING could perfectly well hold the address.
        //     Skipping it lets another project's completed pass stand in as the inspection, and the
        //     address is reported missing on the strength of a project nobody looked at.
        List<String> candidates = Collections.singletonList("Catalog.Nope"); //$NON-NLS-1$

        // Entered through the SAME seam the scope loop uses, with the null model/configuration a
        // project in either state really presents - so this covers the branch, not just the helper.
        assertNull("a non-EDT project is legitimately skipped", //$NON-NLS-1$
            GetProjectErrorsTool.projectDecision(project("plain-java", false), null, null, //$NON-NLS-1$
                candidates));

        GetProjectErrorsTool.ProjectResolution loading = GetProjectErrorsTool.projectDecision(
            project("indexing", true), null, null, candidates); //$NON-NLS-1$
        assertNotNull("an unreadable EDT project must decide NOTHING, not be skipped", loading); //$NON-NLS-1$
        assertFalse("it never ran a pass", loading.passCompleted); //$NON-NLS-1$
        assertEquals("and every candidate stays undecided", //$NON-NLS-1$
            singleton("Catalog.Nope"), loading.undecided); //$NON-NLS-1$
        assertTrue(loading.resolved.isEmpty());

        // End to end, both halves of the author's requirement in one fold:
        // a readable EDT project + a non-EDT one must still report the address missing...
        GetProjectErrorsTool.ProjectResolution readable = GetProjectErrorsTool.resolveInProject(
            project("edt-ok"), readModel(), MdClassFactory.eINSTANCE.createConfiguration(), //$NON-NLS-1$
            candidates);
        GetProjectErrorsTool.AddressResolution withNonEdt =
            new GetProjectErrorsTool.AddressResolution();
        GetProjectErrorsTool.foldProjectDecisions(withNonEdt, candidates, candidates,
            Collections.singletonList(readable));
        assertNull("a non-EDT project must not mute the missing-address report", //$NON-NLS-1$
            withNonEdt.error);
        assertEquals(candidates, withNonEdt.notFound);

        // ...while the same query with an INDEXING EDT project alongside must not claim absence.
        GetProjectErrorsTool.AddressResolution withLoading =
            new GetProjectErrorsTool.AddressResolution();
        GetProjectErrorsTool.foldProjectDecisions(withLoading, candidates, candidates,
            Arrays.asList(readable, loading));
        assertNotNull("an unreadable EDT project must stop the absence claim", withLoading.error); //$NON-NLS-1$
        assertTrue("the refusal must name the address", //$NON-NLS-1$
            withLoading.error.contains("Catalog.Nope")); //$NON-NLS-1$
        assertTrue("and nothing may be declared missing", withLoading.notFound.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testAClosedProjectIsUndecidedRatherThanSilentlyDroppedFromTheVerdict()
    {
        // A closed project used to leave the universe entirely: never asked, so never disagreeing.
        // An address living only in it came back as objectsNotFound while its persisted markers were
        // dropped from the scan - the answer looked complete and was not. "Cannot be consulted" is
        // UNKNOWN, and UNKNOWN must reach the verdict.
        List<String> candidates = Collections.singletonList("Catalog.Nope"); //$NON-NLS-1$

        // 1) It must stay in the UNIVERSE. Filtering it out here is what made it invisible: never
        //    asked, so never disagreeing.
        IProject open = project("open", true); //$NON-NLS-1$
        IProject shut = closedProject("archived"); //$NON-NLS-1$
        assertEquals("a closed project belongs to the universe like any other", //$NON-NLS-1$
            Arrays.asList(open, shut),
            GetProjectErrorsTool.exactScopeProjects(new IProject[] {open, shut}));

        // 2) And it must be classified UNKNOWN, not skipped.
        GetProjectErrorsTool.ProjectResolution closed = GetProjectErrorsTool.projectDecision(
            shut, null, null, candidates);
        assertNotNull("a closed project must not be dropped from the universe", closed); //$NON-NLS-1$
        assertFalse(closed.passCompleted);
        assertEquals(singleton("Catalog.Nope"), closed.undecided); //$NON-NLS-1$

        // With a readable project alongside, the address must NOT be called missing.
        GetProjectErrorsTool.ProjectResolution readable = GetProjectErrorsTool.resolveInProject(
            project("open"), readModel(), MdClassFactory.eINSTANCE.createConfiguration(), //$NON-NLS-1$
            candidates);
        GetProjectErrorsTool.AddressResolution r = new GetProjectErrorsTool.AddressResolution();
        GetProjectErrorsTool.foldProjectDecisions(r, candidates, candidates, Arrays.asList(readable, closed));

        assertNotNull("a closed project makes the absence claim unprovable", r.error); //$NON-NLS-1$
        assertTrue(r.error.contains("Catalog.Nope")); //$NON-NLS-1$
        assertTrue("nothing may be declared missing", r.notFound.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testAnAddressFoundInOneProjectStillReportsThatAnotherCouldNotBeConsulted()
    {
        // The corner that survived the per-address fix: the address IS found in A, so the undecided
        // state of B was simply dropped. B never gets a scan scope, its markers are skipped, and the
        // report reads as complete while a whole project's problems on that address are missing.
        // Found-somewhere settles EXISTENCE; it does not make the ANSWER complete.
        String fqn = "Catalog.C"; //$NON-NLS-1$
        List<String> candidates = Collections.singletonList(fqn);

        GetProjectErrorsTool.ProjectResolution owner = GetProjectErrorsTool.resolveInProject(
            project("A"), readModel(), configWithCatalog("C"), candidates); //$NON-NLS-1$ //$NON-NLS-2$
        GetProjectErrorsTool.ProjectResolution unreadable = GetProjectErrorsTool.projectDecision(
            project("B", true), null, null, candidates); //$NON-NLS-1$

        GetProjectErrorsTool.AddressResolution r = new GetProjectErrorsTool.AddressResolution();
        GetProjectErrorsTool.foldProjectDecisions(r, candidates, candidates, Arrays.asList(owner, unreadable));

        // Existence is settled - no refusal, no "missing".
        assertNull("an owner settles existence", r.error); //$NON-NLS-1$
        assertEquals(Collections.singletonList(fqn), r.resolved);
        assertTrue(r.notFound.isEmpty());
        // ...but the incompleteness must be REPORTED, naming the project that could not answer.
        assertEquals("the partial answer must be reported, not swallowed", //$NON-NLS-1$
            singleton("B"), r.incompleteFor.get(fqn)); //$NON-NLS-1$
        // Only the owner scopes a scan; B contributes no markers, which is exactly why it is partial.
        assertTrue(r.scopeByProject.containsKey("A")); //$NON-NLS-1$
        assertFalse(r.scopeByProject.containsKey("B")); //$NON-NLS-1$

        StringBuilder md = new StringBuilder();
        GetProjectErrorsTool.appendIncompleteScopeWarning(md, r.incompleteFor);
        assertTrue("the human report must carry the same caveat", //$NON-NLS-1$
            md.toString().contains(fqn) && md.toString().contains("B")); //$NON-NLS-1$

        // The counterpart: with every project consulted there is nothing to warn about.
        GetProjectErrorsTool.ProjectResolution absentHere = GetProjectErrorsTool.resolveInProject(
            project("B"), readModel(), MdClassFactory.eINSTANCE.createConfiguration(), candidates); //$NON-NLS-1$
        GetProjectErrorsTool.AddressResolution complete =
            new GetProjectErrorsTool.AddressResolution();
        GetProjectErrorsTool.foldProjectDecisions(complete, candidates, candidates,
            Arrays.asList(owner, absentHere));
        assertTrue("a fully consulted universe is not partial", complete.incompleteFor.isEmpty()); //$NON-NLS-1$
        assertEquals(Collections.singletonList(fqn), complete.resolved);
    }

    @Test
    public void testEachProjectScopesTheScanByItsOwnSpellingOnly()
    {
        // With no projectName the SAME address can resolve to a DIFFERENT stored spelling in each
        // project. One merged scope would let project A's spelling select project B's markers - and
        // would silently drop every problem B stores under its own. The scope is therefore kept
        // per project, and a project that resolved NOTHING contributes no marker at all.
        String ye = fromCp(0x041c, 0x0435, 0x0434); // Med
        String yo = fromCp(0x041c, 0x0451, 0x0434); // M[yo]d
        String requested = "Catalog." + yo; //$NON-NLS-1$

        GetProjectErrorsTool.ProjectResolution a = GetProjectErrorsTool.resolveInProject(
            project("A"), readModel(), configWithCatalog(ye), //$NON-NLS-1$
            Collections.singletonList(requested));
        GetProjectErrorsTool.ProjectResolution b = GetProjectErrorsTool.resolveInProject(
            project("B"), readModel(), configWithCatalog(yo), //$NON-NLS-1$
            Collections.singletonList(requested));
        GetProjectErrorsTool.ProjectResolution c = GetProjectErrorsTool.resolveInProject(
            project("C"), readModel(), MdClassFactory.eINSTANCE.createConfiguration(), //$NON-NLS-1$
            Collections.singletonList(requested));

        GetProjectErrorsTool.AddressResolution resolution =
            new GetProjectErrorsTool.AddressResolution();
        GetProjectErrorsTool.foldProjectDecisions(resolution,
            Collections.singletonList(requested),
            Collections.singletonList(requested), Arrays.asList(a, b, c));

        assertEquals("each project must scope by the spelling IT stores", //$NON-NLS-1$
            singleton("Catalog." + ye), resolution.scopeByProject.get("A")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(singleton("Catalog." + yo), resolution.scopeByProject.get("B")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a project that resolved nothing must not be scoped at all", //$NON-NLS-1$
            resolution.scopeByProject.containsKey("C")); //$NON-NLS-1$

        // And the filter each project's marker scan really receives keeps that separation
        // (asserted through the consumption point, not the map: merging the scopes anywhere
        // between the two would put B's spelling in front of A's markers).
        Map<String, Set<String>> variants =
            GetProjectErrorsTool.filterVariantsByProject(resolution.scopeByProject);
        Set<String> forA = scanFilterFor(variants, "A"); //$NON-NLS-1$
        Set<String> forB = scanFilterFor(variants, "B"); //$NON-NLS-1$

        assertTrue(forA.contains("catalog." + ye.toLowerCase())); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("project A must NOT be scoped by project B's spelling", //$NON-NLS-1$
            forA.contains("catalog." + yo.toLowerCase())); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(forB.contains("catalog." + yo.toLowerCase())); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("project B must NOT be scoped by project A's spelling", //$NON-NLS-1$
            forB.contains("catalog." + ye.toLowerCase())); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("and a project that resolved nothing is scanned for nothing", //$NON-NLS-1$
            scanFilterFor(variants, "C")); //$NON-NLS-1$
    }

    @Test
    public void testAProjectOutsideTheResolvedScopeContributesNoMarker()
    {
        // The per-project scope decides membership, not just the spelling: a project whose name is
        // absent from the map resolved nothing, so its markers - including a CLOSED project's, which
        // are in the marker index but whose model was never resolved against - must be skipped
        // entirely rather than matched by another project's spelling.
        Map<String, Set<String>> byProject = new LinkedHashMap<>();
        byProject.put("A", singleton("catalog.products")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("the resolving project keeps its own filter", //$NON-NLS-1$
            singleton("catalog.products"), objectsFor(byProject, "A")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("a project outside the resolved scope must be skipped, not matched", //$NON-NLS-1$
            objectsFor(byProject, "B")); //$NON-NLS-1$
        // A loose (non-exact) call is unaffected: one filter still applies to every project.
        assertEquals(singleton("catalog.products"), //$NON-NLS-1$
            objectsFor(null, "B")); //$NON-NLS-1$
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

        GetProjectErrorsTool.ProjectResolution a = GetProjectErrorsTool.resolveInProject(
            project("A"), readModel(), configWithCatalog(ye), //$NON-NLS-1$
            Collections.singletonList(requested));
        GetProjectErrorsTool.ProjectResolution b = GetProjectErrorsTool.resolveInProject(
            project("B"), readModel(), configWithCatalog(yo), //$NON-NLS-1$
            Collections.singletonList(requested));

        assertEquals("project A must report the spelling IT stores", //$NON-NLS-1$
            singleton("Catalog." + ye), a.resolved.get(requested)); //$NON-NLS-1$
        assertEquals("project B must report the spelling IT stores", //$NON-NLS-1$
            singleton("Catalog." + yo), b.resolved.get(requested)); //$NON-NLS-1$
    }

    // ========== objectFqns: what the scan is really scoped to ==========

    @Test
    public void testAMemberAddressIsScopedByTheNodeEdtReportsItsProblemsOn()
    {
        // Marker.getObjectPresentation() - the only thing this filter can compare against - names
        // the object EDT indexed the problem under, never a member inside it. Verified live on EDT
        // 2026.1: an attribute with no type yields md-legacy-emf-check markers located on the OWNING
        // "Catalog.Catalog", and a form item's dangling handler yields form-legacy-check-event-handler
        // on "Catalog.Catalog.Form.ItemForm.Form". Scoping a member address by the member alone
        // therefore matches NOTHING and answers objectsResolved next to a clean report.
        assertEquals("an mdclass member is scoped by its owning object", //$NON-NLS-1$
            "Catalog.Products", GetProjectErrorsTool.markerOwnerFqn("Catalog.Products.Attribute.Weight")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a nested member is scoped by the same owning object", "Catalog.Products", //$NON-NLS-1$ //$NON-NLS-2$
            GetProjectErrorsTool.markerOwnerFqn(
                "Catalog.Products.TabularSection.Goods.Attribute.Price")); //$NON-NLS-1$
        assertEquals("a predefined item is scoped by its owner too", "Catalog.Products", //$NON-NLS-1$ //$NON-NLS-2$
            GetProjectErrorsTool.markerOwnerFqn("Catalog.Products.Predefined.Sample")); //$NON-NLS-1$

        // Addresses that ALREADY name the node EDT reports on must not be widened.
        assertNull("a top object is the node itself", //$NON-NLS-1$
            GetProjectErrorsTool.markerOwnerFqn("Catalog.Products")); //$NON-NLS-1$
        assertNull("a FORM's own presentation already starts with its address", //$NON-NLS-1$
            GetProjectErrorsTool.markerOwnerFqn("Catalog.Products.Form.ItemForm")); //$NON-NLS-1$
        assertNull(GetProjectErrorsTool.markerOwnerFqn("CommonForm.Settings")); //$NON-NLS-1$
        assertNull("a nested Subsystem is a top object of its own", //$NON-NLS-1$
            GetProjectErrorsTool.markerOwnerFqn("Subsystem.Sales.Subsystem.Orders")); //$NON-NLS-1$
    }

    @Test
    public void testAResolvedMemberCarriesTheOwnerInItsScanScope()
    {
        // The end-to-end shape of the rule above: a resolved mdclass member scopes the scan by BOTH
        // its own address and the owner EDT actually reports its problems on.
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("C"); //$NON-NLS-1$
        catalog.getAttributes().add(attribute("A")); //$NON-NLS-1$
        config.getCatalogs().add(catalog);

        Set<String> scope = resolvedIn(config, "Catalog.C.Attribute.A").get("Catalog.C.Attribute.A"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("the member must resolve", scope); //$NON-NLS-1$
        assertTrue("the address itself stays in the scope", //$NON-NLS-1$
            scope.contains("Catalog.C.Attribute.A")); //$NON-NLS-1$
        assertTrue("and so does the node EDT reports its problems on", //$NON-NLS-1$
            scope.contains("Catalog.C")); //$NON-NLS-1$
    }

    @Test
    public void testAPredefinedItemIsScopedByItsStoredNameNotTheRequestedYoSpelling()
    {
        // Yo (U+0451) tolerance is the CALLER's: the requested "...Predefined.M[yo]d" is enumerated
        // into its spellings and each is probed EXACTLY, so the probe that hits is the one the model
        // really stores. Recording the REQUESTED spelling as the scan scope would filter out every
        // problem on the very item that was just proven to exist - objectsResolved next to a clean
        // report.
        String ye = fromCp(0x041c, 0x0435, 0x0434); // Med
        String yo = fromCp(0x041c, 0x0451, 0x0434); // M[yo]d
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("C"); //$NON-NLS-1$
        assertFalse("the fixture item must be created", //$NON-NLS-1$
            PredefinedWriter.create(catalog, ye, new PredefinedWriter.ItemProps(), false).isError());
        config.getCatalogs().add(catalog);

        String requested = "Catalog.C.Predefined." + yo; //$NON-NLS-1$
        Set<String> scope = resolvedIn(config, requested).get(requested);

        assertNotNull("the yo spelling must resolve through the caller's enumeration", scope); //$NON-NLS-1$
        assertTrue("the scan must be scoped by the STORED name, not the requested one", //$NON-NLS-1$
            scope.contains("Catalog.C.Predefined." + ye)); //$NON-NLS-1$
        assertFalse("the requested yo spelling must not scope the scan", //$NON-NLS-1$
            scope.contains(requested));
        // The owner is in the scope as well - that is where EDT reports a predefined item's problem.
        assertTrue(scope.contains("Catalog.C")); //$NON-NLS-1$
    }

    @Test
    public void testEveryValidFirstStepIsAContainmentAndStaysPossible()
    {
        // Walks THE grammar the gate resolves against - MetadataNodeResolver's own token -> feature
        // map - not a catalogue that mirrors it. Pinning the mirror was vacuous: a token added to
        // the resolver alone is live in address resolution while the mirror, and so the check,
        // knows nothing about it.
        Map<String, Integer> ownersPerFeature = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : MetadataNodeResolver.childFeatureByToken().entrySet())
        {
            String token = entry.getKey();
            String feature = entry.getValue();
            assertNotNull("resolver token is not published by the kind catalogue - the two have " //$NON-NLS-1$
                + "drifted apart: " + token, MetadataTypeUtils.resolveNestedKind(token)); //$NON-NLS-1$

            ownersPerFeature.putIfAbsent(feature, Integer.valueOf(0));
            for (EClassifier classifier : MdClassPackage.eINSTANCE.getEClassifiers())
            {
                if (!(classifier instanceof EClass)
                    || MetadataTypeUtils.toEnglishSingular(classifier.getName()) == null)
                {
                    continue;
                }
                EStructuralFeature f = ((EClass)classifier).getEStructuralFeature(feature);
                if (f == null)
                {
                    continue;
                }
                String type = classifier.getName();
                assertTrue("a valid first step must be a CONTAINMENT reference: " //$NON-NLS-1$
                    + type + "." + feature, //$NON-NLS-1$
                    f instanceof EReference && ((EReference)f).isContainment());
                assertTrue("the owner question must accept it: " + type + "." + feature, //$NON-NLS-1$ //$NON-NLS-2$
                    MetadataTypeUtils.typeCanContain(type, feature));
                String address = type + ".X." + token + ".Y"; //$NON-NLS-1$ //$NON-NLS-2$
                assertTrue("a valid first step must stay POSSIBLE: " + address, //$NON-NLS-1$
                    GetProjectErrorsTool.possibleAddressShape(address));
                ownersPerFeature.put(feature, Integer.valueOf(ownersPerFeature.get(feature) + 1));
            }
        }

        // PER KIND, not in aggregate. A total floor lets a whole family fall to zero - a renamed
        // feature, or an owner that lost its containment - while the other families hold the count
        // up and nothing is reported.
        for (Map.Entry<String, Integer> entry : ownersPerFeature.entrySet())
        {
            if (DEEPER_ONLY_FEATURES.contains(entry.getKey()))
            {
                assertEquals("legal only deeper, so it must have NO first-step owner: " //$NON-NLS-1$
                    + entry.getKey(), Integer.valueOf(0), entry.getValue());
                continue;
            }
            assertTrue("no owner type carries this first-step feature any more: " + entry.getKey(), //$NON-NLS-1$
                entry.getValue().intValue() > 0);
        }
    }

    /**
     * Features whose kinds are legal only DEEPER in an address: {@code methods} hangs off an
     * HTTPService URLTemplate and {@code parameters} off a WebService Operation, so neither may have
     * a first-step owner. Named explicitly so a new kind that resolves to nothing is a failure
     * rather than another silent zero.
     */
    private static final Set<String> DEEPER_ONLY_FEATURES =
        new HashSet<>(Arrays.asList("methods", "parameters")); //$NON-NLS-1$ //$NON-NLS-2$


    @Test
    public void testKindsThatAreLegalOnlyDeeperAreNotValidFirstSteps()
    {
        // Method lives on an HTTPService URLTemplate and Parameter on a WebService Operation, so
        // neither is a first step. They must NOT be swept into the derived table above just because
        // the catalogue publishes their tokens.
        for (String address : new String[] {
            "HTTPService.Service.Method.Get",        // Method belongs to a URLTemplate //$NON-NLS-1$
            "WebService.Service.Parameter.Value"})   // Parameter belongs to an Operation //$NON-NLS-1$
        {
            assertFalse("legal only deeper, so not a valid first step: " + address, //$NON-NLS-1$
                GetProjectErrorsTool.possibleAddressShape(address));
        }
        // ...and the legitimate deeper shapes they belong to must still be possible.
        assertTrue(GetProjectErrorsTool.possibleAddressShape(
            "HTTPService.Service.URLTemplate.Root.Method.Get")); //$NON-NLS-1$
        assertTrue(GetProjectErrorsTool.possibleAddressShape(
            "WebService.Service.Operation.Calc.Parameter.Value")); //$NON-NLS-1$
    }


    @Test
    public void testTheOwnerQuestionIsAboutCONTAINMENT()
    {
        // The contract says CONTAINMENT feature. A scalar such as Catalog.uuid is a real
        // EStructuralFeature, so a plain getEStructuralFeature answered yes for it - a contract
        // written wider than the truth, and an address step that can never exist.
        assertFalse("a scalar feature must NOT satisfy the owner question", //$NON-NLS-1$
            MetadataTypeUtils.typeCanContain("Catalog", "uuid")); //$NON-NLS-1$ //$NON-NLS-2$

        // The mdclass first steps are covered exhaustively by the derived walk above. These two are
        // NOT in that table - forms and predefined are reached by their own grammars, never through
        // the resolver's child-feature map - so they are pinned here.
        assertTrue("Catalog must be able to own forms", //$NON-NLS-1$
            MetadataTypeUtils.typeCanContain("Catalog", "forms")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("Catalog must be able to own predefined items", //$NON-NLS-1$
            MetadataTypeUtils.typeCanContain("Catalog", "predefined")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a Document holds no predefined items", //$NON-NLS-1$
            MetadataTypeUtils.typeCanContain("Document", "predefined")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTheOwnerQuestionSeesAnInheritedCONTAINMENT()
    {
        // The whole owner gate rests on one unstated assumption: that asking an EClass for a feature
        // reaches containments declared on its ANCESTORS, not only its own. The MdClass metamodel
        // does have such pairs on gate-addressable types (measured 2026-08-03: Catalog.
        // standardAttributes, Catalog.characteristics, ChartOfAccounts.tabularParts and others), so
        // this control is built on a real one - no synthetic hierarchy and, deliberately, no scalar
        // stand-in: a scalar would prove the opposite of the contract.
        EClass catalog = (EClass)MdClassPackage.eINSTANCE.getEClassifier("Catalog"); //$NON-NLS-1$
        assertNotNull("the metamodel must model Catalog", catalog); //$NON-NLS-1$
        EStructuralFeature inherited = catalog.getEStructuralFeature("standardAttributes"); //$NON-NLS-1$
        assertNotNull("Catalog must reach 'standardAttributes' at all", inherited); //$NON-NLS-1$
        // Self-checks: the control means nothing unless the feature really is BOTH inherited and a
        // containment. If the metamodel ever moves or re-kinds it, these say so instead of passing.
        assertNotSame("'standardAttributes' must be INHERITED for this control to test anything", //$NON-NLS-1$
            catalog, inherited.getEContainingClass());
        assertTrue("'standardAttributes' must be a CONTAINMENT reference", //$NON-NLS-1$
            inherited instanceof EReference && ((EReference)inherited).isContainment());

        assertTrue("an inherited containment must satisfy the owner question", //$NON-NLS-1$
            MetadataTypeUtils.typeCanContain("Catalog", "standardAttributes")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEveryOwnerAPredefinedAddressCanMeanIsScoped()
    {
        // Both spellings of the owner AND of the item exist, in the crossed arrangement: M[yo]d
        // stores V[ye]s while M[ye]d stores V[yo]s. The address means BOTH items, so both must scope
        // the scan. A lookup that carries its own yo fallback answers the as-typed probe with the
        // FIRST owner's differently spelled item, the caller stops enumerating on that hit, and every
        // problem on the second owner's item is reported as if the item were clean.
        String yoOwner = fromCp(0x041c, 0x0451, 0x0434);  // M[yo]d
        String yeOwner = fromCp(0x041c, 0x0435, 0x0434);  // M[ye]d
        String yoItem = fromCp(0x0412, 0x0451, 0x0441);   // V[yo]s
        String yeItem = fromCp(0x0412, 0x0435, 0x0441);   // V[ye]s

        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog first = MdClassFactory.eINSTANCE.createCatalog();
        first.setName(yoOwner);
        assertFalse(PredefinedWriter.create(first, yeItem, new PredefinedWriter.ItemProps(), false).isError());
        Catalog second = MdClassFactory.eINSTANCE.createCatalog();
        second.setName(yeOwner);
        assertFalse(PredefinedWriter.create(second, yoItem, new PredefinedWriter.ItemProps(), false).isError());
        config.getCatalogs().add(first);
        config.getCatalogs().add(second);

        String requested = "Catalog." + yoOwner + ".Predefined." + yoItem; //$NON-NLS-1$ //$NON-NLS-2$
        Set<String> scope = resolvedIn(config, requested).get(requested);

        assertNotNull("the address names real items and must resolve", scope); //$NON-NLS-1$
        assertTrue("the first owner's item must scope the scan", //$NON-NLS-1$
            scope.contains("Catalog." + yoOwner + ".Predefined." + yeItem)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the SECOND owner's item must scope the scan too", //$NON-NLS-1$
            scope.contains("Catalog." + yeOwner + ".Predefined." + yoItem)); //$NON-NLS-1$ //$NON-NLS-2$
    }


    @Test
    public void testTheYoRetryIsPerSegmentSoAMixedAddressStillResolves()
    {
        // create_metadata's yo->ye normalization is a per-NAME default, not a rule for the whole
        // configuration, so the spellings genuinely mix: a catalog created with normalizeYo=false
        // keeps its yo while an attribute created afterwards is stored normalized. Retrying the
        // WHOLE address rewrote both segments at once, so neither the address as typed nor its fully
        // normalized twin resolved - and an attribute that plainly exists came back missing.
        String yoCatalog = fromCp(0x041c, 0x0451, 0x0434);          // M[yo]d - stored WITH yo
        String yeAttr = fromCp(0x0412, 0x0435, 0x0441);             // V[ye]s - stored normalized
        String yoAttr = fromCp(0x0412, 0x0451, 0x0441);             // V[yo]s - as the caller types it

        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName(yoCatalog);
        catalog.getAttributes().add(attribute(yeAttr));
        config.getCatalogs().add(catalog);

        String requested = "Catalog." + yoCatalog + ".Attribute." + yoAttr; //$NON-NLS-1$
        Set<String> scope = resolvedIn(config, requested).get(requested);

        assertNotNull("a mixed-spelling address must still resolve", scope); //$NON-NLS-1$
        assertTrue("the scan must be scoped by the STORED spellings", //$NON-NLS-1$
            scope.contains("Catalog." + yoCatalog + ".Attribute." + yeAttr)); //$NON-NLS-1$ //$NON-NLS-2$

        // The probe list itself: as typed FIRST, and never rewriting a structural token.
        List<String> probes = GetProjectErrorsTool.addressProbes(requested);
        assertEquals("the address as typed must be probed first", requested, probes.get(0)); //$NON-NLS-1$
        assertTrue("the per-segment combination must be among the probes", //$NON-NLS-1$
            probes.contains("Catalog." + yoCatalog + ".Attribute." + yeAttr)); //$NON-NLS-1$ //$NON-NLS-2$
        for (String probe : probes)
        {
            assertTrue("a structural token must never be rewritten: " + probe, //$NON-NLS-1$
                probe.startsWith("Catalog.") && probe.contains(".Attribute.")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // A yo-less address still costs exactly one probe.
        assertEquals(Collections.singletonList("Catalog.Products"), //$NON-NLS-1$
            GetProjectErrorsTool.addressProbes("Catalog.Products")); //$NON-NLS-1$
    }


    // ========== objectFqns: a malformed address addresses NOTHING ==========

    @Test
    public void testAMalformedAddressResolvesToNothingInsteadOfANeighbouringNode()
    {
        // 'Catalog.C.' looks like a typo and is one - but MetadataNodeResolver drops the trailing
        // empty segment when it splits, so it resolved the NEIGHBOURING object 'Catalog.C' while the
        // scan stayed scoped by 'catalog.c.', which matches neither 'Catalog.C' nor its content
        // segments. objectsResolved next to "# No Errors Found": the false all-clear this input
        // exists to prevent, produced by a stray keystroke.
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("C"); //$NON-NLS-1$
        config.getCatalogs().add(catalog);

        // The control: the well-formed address DOES resolve, so a miss below is about the shape.
        assertNotNull("the well-formed address must resolve", //$NON-NLS-1$
            resolvedIn(config, "Catalog.C").get("Catalog.C")); //$NON-NLS-1$ //$NON-NLS-2$

        // EVERY shape of empty segment is refused - one example of each, not just the trailing dot.
        for (String malformed : new String[] {
            "Catalog.C.",      // trailing dot //$NON-NLS-1$
            ".Catalog.C",      // leading dot //$NON-NLS-1$
            "Catalog..C",      // doubled dot //$NON-NLS-1$
            "Catalog.C..",     // doubled trailing //$NON-NLS-1$
            ".",               // only a dot //$NON-NLS-1$
            "..",              // only dots //$NON-NLS-1$
            "Catalog. ",       // a blank segment //$NON-NLS-1$
            " .C"})            // a blank leading segment //$NON-NLS-1$
        {
            assertNull("a malformed address must not be canonical: " + malformed, //$NON-NLS-1$
                GetProjectErrorsTool.canonicalAddress(malformed));
            assertTrue("a malformed address must yield no probe at all: " + malformed, //$NON-NLS-1$
                GetProjectErrorsTool.addressProbes(malformed).isEmpty());
            assertNull("and must resolve to NOTHING, never to the neighbouring node: " + malformed, //$NON-NLS-1$
                resolvedIn(config, malformed).get(malformed));
        }
    }

    @Test
    public void testWhitespaceAroundASegmentIsNormalizedRatherThanRefused()
    {
        // The other half of the same rule, and the reason it is not "refuse anything unusual":
        // whitespace has exactly ONE reading, so normalizing it guesses nothing - and the whole
        // entry is already trimmed by cleanedEntries, so trimming the outside but not the inside
        // would be arbitrary. It also matters for correctness: SubsystemUtils / PredefinedWriter
        // trim internally, so an untrimmed address could RESOLVE while the scan stayed scoped by the
        // spaced spelling, which matches no marker.
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("C"); //$NON-NLS-1$
        config.getCatalogs().add(catalog);

        assertEquals("Catalog.C", GetProjectErrorsTool.canonicalAddress("Catalog. C")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Catalog.C", GetProjectErrorsTool.canonicalAddress(" Catalog . C ")); //$NON-NLS-1$ //$NON-NLS-2$

        Set<String> scope = resolvedIn(config, "Catalog. C").get("Catalog. C"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("a spaced address must still resolve", scope); //$NON-NLS-1$
        assertTrue("and the scan must be scoped by the TRIMMED spelling, or it matches no marker", //$NON-NLS-1$
            scope.contains("Catalog.C")); //$NON-NLS-1$
        for (String spelling : scope)
        {
            assertEquals("no scoping spelling may carry the stray whitespace: " + spelling, //$NON-NLS-1$
                spelling.trim(), spelling);
            assertFalse(spelling.contains(". ")); //$NON-NLS-1$
        }
    }

    @Test
    public void testAnExternalObjectsProjectIsAbsentNotUndecided()
    {
        // A project of external objects (reports / data processors) carries a V8 nature but has NO
        // Configuration BY DESIGN - not "not yet". Classifying it as an unreadable EDT project (its
        // nature IS a V8 one) turned every ordinary miss into a refusal on a workspace-wide scan,
        // and made a projectName pointing at one unable to resolve anything ever. Its lack of a
        // configuration is KNOWLEDGE: it cannot own an mdclass address, so it answers ABSENT.
        List<String> candidates = Collections.singletonList("Catalog.Nope"); //$NON-NLS-1$

        GetProjectErrorsTool.ProjectResolution external = GetProjectErrorsTool.projectDecision(
            natureProject("ext", "com._1c.g5.v8.dt.core.V8ExternalObjectsNature"), //$NON-NLS-1$ //$NON-NLS-2$
            null, null, candidates);
        assertNotNull("an external-objects project stays in the universe", external); //$NON-NLS-1$
        assertTrue("its pass is COMPLETE - the absence is decided, not a failure to look", //$NON-NLS-1$
            external.passCompleted);
        assertTrue("nothing may be undecided there", external.undecided.isEmpty()); //$NON-NLS-1$
        assertTrue(external.resolved.isEmpty());

        // So a workspace-wide scan next to a readable project reports an ordinary MISS...
        GetProjectErrorsTool.ProjectResolution readable = GetProjectErrorsTool.resolveInProject(
            project("cfg"), readModel(), MdClassFactory.eINSTANCE.createConfiguration(), //$NON-NLS-1$
            candidates);
        GetProjectErrorsTool.AddressResolution r = new GetProjectErrorsTool.AddressResolution();
        GetProjectErrorsTool.foldProjectDecisions(r, candidates, candidates, Arrays.asList(readable, external));
        assertNull("an external-objects project must not refuse the call", r.error); //$NON-NLS-1$
        assertEquals(candidates, r.notFound);
        assertTrue("nor make the answer partial", r.incompleteFor.isEmpty()); //$NON-NLS-1$

        // ...and it is ALONE enough to answer: no readable configuration project is required.
        GetProjectErrorsTool.AddressResolution alone = new GetProjectErrorsTool.AddressResolution();
        GetProjectErrorsTool.foldProjectDecisions(alone, candidates, candidates,
            Collections.singletonList(external));
        assertNull("an external-objects project is an inspection in its own right", alone.error); //$NON-NLS-1$
        assertEquals(candidates, alone.notFound);

        // The contrast: a CONFIGURATION project with no readable configuration is still UNDECIDED.
        GetProjectErrorsTool.ProjectResolution loading = GetProjectErrorsTool.projectDecision(
            natureProject("cfg-loading", "com._1c.g5.v8.dt.core.V8ConfigurationNature"), //$NON-NLS-1$ //$NON-NLS-2$
            null, null, candidates);
        assertEquals(singleton("Catalog.Nope"), loading.undecided); //$NON-NLS-1$
        assertFalse(loading.passCompleted);

        // An EXTENSION project is a configuration holder too, so it behaves like the one above.
        GetProjectErrorsTool.ProjectResolution extension = GetProjectErrorsTool.projectDecision(
            natureProject("ext-loading", "com._1c.g5.v8.dt.core.V8ExtensionNature"), //$NON-NLS-1$ //$NON-NLS-2$
            null, null, candidates);
        assertEquals(singleton("Catalog.Nope"), extension.undecided); //$NON-NLS-1$

        // A plain Eclipse project leaves the universe entirely; unknowable natures stay UNDECIDED.
        assertNull("a non-EDT project must contribute nothing", //$NON-NLS-1$
            GetProjectErrorsTool.projectDecision(natureProject("plain"), null, null, candidates)); //$NON-NLS-1$
        GetProjectErrorsTool.ProjectResolution unknowable = GetProjectErrorsTool.projectDecision(
            closedProject("gone"), null, null, candidates); //$NON-NLS-1$
        assertEquals("unknowable natures are never proof that a project holds nothing", //$NON-NLS-1$
            singleton("Catalog.Nope"), unknowable.undecided); //$NON-NLS-1$
    }


    @Test
    public void testADeepSubsystemChainResolvesWithYoMixedPerLevel()
    {
        // The depth cap that used to guard the per-segment probes silently restored the WHOLE-address
        // retry for deep chains - exactly the bug those probes were introduced to fix, just switched
        // on by depth. A five-level chain whose names were created with different normalizeYo
        // settings matches neither "as typed" nor "fully normalized".
        //
        // A subsystem chain is the only family whose depth is unbounded, so it is resolved LEVEL BY
        // LEVEL instead: linear in depth, and no combination is ever built.
        String[] stored = {"A" + fromCp(0x0435), "B" + fromCp(0x0451), "C" + fromCp(0x0435), "D" + fromCp(0x0451), "E" + fromCp(0x0435)};
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Subsystem parent = null;
        for (String name : stored)
        {
            Subsystem subsystem = MdClassFactory.eINSTANCE.createSubsystem();
            subsystem.setName(name);
            if (parent == null)
            {
                config.getSubsystems().add(subsystem);
            }
            else
            {
                parent.getSubsystems().add(subsystem);
            }
            parent = subsystem;
        }

        // The caller types yo at EVERY level - matching the stored spelling at only two of them.
        StringBuilder requested = new StringBuilder();
        for (String name : stored)
        {
            requested.append(requested.length() == 0 ? "Subsystem." : ".Subsystem.");
            requested.append(name.replace(fromCp(0x0435), fromCp(0x0451)));
        }
        String address = requested.toString();

        Set<String> scope = resolvedIn(config, address).get(address);
        assertNotNull("a deep chain with per-level yo spellings must resolve", scope);
        StringBuilder expected = new StringBuilder();
        for (String name : stored)
        {
            expected.append(expected.length() == 0 ? "Subsystem." : ".Subsystem.");
            expected.append(name);
        }
        assertTrue("the scan must be scoped by the STORED chain, level by level",
            scope.contains(expected.toString()));

        // And the probe list stays a SINGLE entry: no 2^depth enumeration for this family.
        assertEquals("a subsystem chain must not enumerate spellings",
            1, GetProjectErrorsTool.addressProbes(address).size());
    }


    @Test
    public void testAnExistingAttributeColumnIsNotReportedUnresolved()
    {
        // Registering Kind.COLUMN in the shared token table (the #342 merge) made 'Column' a token
        // this EXACT filter recognizes, while its kind map still answered "no addressable kind" for a
        // FormAttributeColumn - so a column that plainly exists was declared unresolved and landed in
        // objectsNotFound (issue #295 review). The class must answer to the token that addresses it.
        FormModel form = newFormModel();
        EObject column = FormElementWriter.resolveFormMember(form.root,
            FormElementWriter.parse(columnAddress(FORM_ATTRIBUTE_COLUMN)));

        // FIXTURE FIDELITY, asserted so this test cannot quietly stop guarding what it claims to:
        // the real FormAttributeColumn inherits AbstractFormAttribute, and issue #343's hierarchical
        // classifier maps that base to Kind.ATTRIBUTE. A column that did not inherit it would make the
        // assertion below pass for a shape that cannot occur - green by accident.
        assertNotNull("the fixture must expose the column", column); //$NON-NLS-1$
        assertTrue("the synthetic column must inherit AbstractFormAttribute, like the real one - " //$NON-NLS-1$
            + "otherwise this test stops guarding the #343 ordering", //$NON-NLS-1$
            column.eClass().getEAllSuperTypes().stream()
                .anyMatch(s -> "AbstractFormAttribute".equals(s.getName()))); //$NON-NLS-1$

        // THE ordering guard. It holds today (the flat map answers COLUMN) and it is exactly what
        // breaks if #343's hierarchical classifier gains the Column arm BELOW its AbstractFormAttribute
        // arm: the column would classify as ATTRIBUTE and this fails with expected COLUMN.
        assertEquals("a FormAttributeColumn must answer to the Column kind", //$NON-NLS-1$
            FormElementWriter.Kind.COLUMN, FormElementWriter.addressableKind(column));
        assertFalse("an existing attribute COLUMN must resolve", //$NON-NLS-1$
            scopeSpellings(form, columnAddress(FORM_ATTRIBUTE_COLUMN)).isEmpty());

        // ...and a column that does NOT exist still resolves to nothing.
        assertTrue("a missing column must stay unresolved", //$NON-NLS-1$
            scopeSpellings(form, columnAddress("NoSuchColumn_zz")).isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testAnAttributeColumnIsScopedByItsOwningFormNotByItsAttribute()
    {
        // EDT publishes a form member's markers on the content FORM, so the scan scope must include
        // the form path. How many segments to cut came from isItemLevel(), which is FALSE for a
        // column - yet a column's tail is 4 (Attribute.Rows.Column.Price), so 2 were cut and the
        // scope became the ATTRIBUTE. Nothing is ever reported there, so the caller got
        // objectsResolved next to "No Errors Found" - the false all-clear issue #312 exists to
        // prevent (issue #295 review).
        FormModel form = newFormModel();
        List<String> spellings = scopeSpellings(form, columnAddress(FORM_ATTRIBUTE_COLUMN));

        assertTrue("the scan must be scoped by the OWNING FORM: " + spellings, //$NON-NLS-1$
            spellings.contains(FORM_FQN));
        assertFalse("...and must not stop at the owning attribute: " + spellings, //$NON-NLS-1$
            spellings.contains(FORM_FQN + ".Attribute." + FORM_ATTRIBUTE)); //$NON-NLS-1$

        // The shapes that were already right must stay right - the cut length now comes from the
        // parsed shape itself, so every one of them is asserted here.
        assertTrue("a form-level member stays scoped by its form", //$NON-NLS-1$
            scopeSpellings(form, FORM_FQN + ".Field.Price").contains(FORM_FQN)); //$NON-NLS-1$
        assertTrue("an item-level handler stays scoped by its form", //$NON-NLS-1$
            scopeSpellings(form, HANDLER_ON_FIELD).contains(FORM_FQN));
    }

    /** The synthetic model's column address, with {@code name} as the leaf. */
    private static String columnAddress(String name)
    {
        return FORM_FQN + ".Attribute." + FORM_ATTRIBUTE + ".Column." + name; //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAnItemWithNoAddressableKindDoesNotAnswerToAForeignKind()
    {
        // findFormItem finds an element by NAME, and matchesKindToken accepts ANY requested kind for
        // a class that carries no addressable kind token (AutoCommandBar, ContextMenu,
        // ExtendedTooltip) so that such elements stay reachable for the write tools. For the EXACT
        // filter that is a hole: '...Button.<AutoCommandBar name>' was reported as a resolved
        // address, and the scan then filtered by a kind segment no location carries - a clean report
        // for an address that does not exist. This is the remaining actual == null success path.
        FormModel form = newFormModel();

        assertTrue("the fixture must expose a tokenless item by name", //$NON-NLS-1$
            FormElementWriter.findFormItem(form.root, TOKENLESS_ITEM) != null);
        // That it carries no addressable kind is what the loop below proves from the outside: no
        // kind token reaches it. (A separate assertion on the classifier used to state the same
        // thing directly; it went with FormElementWriter.addressableKind, which the single strict
        // predicate made redundant.)

        for (String kind : new String[] {"Button", "Field", "Group", "Decoration", "Table"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        {
            assertTrue("a tokenless item must not answer to " + kind, //$NON-NLS-1$
                scopeSpellings(form, FORM_FQN + "." + kind + "." + TOKENLESS_ITEM).isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // The leniency that HAS to stay: Attribute and Command are not item kinds at all - they are
        // routed into their own containment, whose classes carry no item kind either, so demanding
        // one would make every attribute and command address unresolvable.
        assertFalse("a form ATTRIBUTE address must still resolve", //$NON-NLS-1$
            scopeSpellings(form, FORM_FQN + ".Attribute." + FORM_ATTRIBUTE).isEmpty()); //$NON-NLS-1$
        assertFalse("a form COMMAND address must still resolve", //$NON-NLS-1$
            scopeSpellings(form, FORM_FQN + ".Command.Save").isEmpty()); //$NON-NLS-1$

        // And a real item still resolves through its OWN kind, and only through it.
        assertFalse(scopeSpellings(form, FORM_FQN + ".Field.Price").isEmpty()); //$NON-NLS-1$
        assertTrue(scopeSpellings(form, FORM_FQN + ".Button.Price").isEmpty()); //$NON-NLS-1$
    }


    @Test
    public void testTheYoEnumerationIsGatedByShapeBeforeAnyProbeIsBuilt()
    {
        // The enumeration used to materialize one probe per SUBSET before the family parse could
        // reject the shape, so external garbage with ~25-30 yo-bearing segments meant millions of
        // strings and 31+ overflowed `1 << n`. The shape is judged FIRST now.
        StringBuilder garbage = new StringBuilder("Catalog");
        for (int i = 0; i < 40; i++)
        {
            // Alternating unknown kind / yo-bearing name: 40 yo segments, and not a real grammar.
            garbage.append(".N").append(fromCp(0x0451)).append(i).append(".NoSuchKind");
        }
        List<String> probes = GetProjectErrorsTool.addressProbes(garbage.toString());
        assertEquals("garbage must never enumerate: it is not a supported shape",
            1, probes.size());
        assertEquals(garbage.toString(), probes.get(0));

        // Even a WELL-FORMED-looking chain that is deeper than any real 1C address stays at one
        // probe - and, unlike the depth cap this replaces, the single probe is the address AS
        // TYPED, never a whole-address retry that would resolve a DIFFERENT node.
        StringBuilder deep = new StringBuilder("Catalog.C" + fromCp(0x0451));
        for (int i = 0; i < 8; i++)
        {
            deep.append(".Attribute.A").append(fromCp(0x0451)).append(i);
        }
        List<String> deepProbes = GetProjectErrorsTool.addressProbes(deep.toString());
        assertEquals(1, deepProbes.size());
        assertEquals("the single probe must be the address as typed",
            deep.toString(), deepProbes.get(0));

        // The supported grammars still enumerate, and stay within the structural bound of 2^4.
        String yo = fromCp(0x0451);
        for (String supported : new String[] {
            "Catalog.M" + yo + "d",
            "Catalog.M" + yo + "d.Attribute.V" + yo + "s",
            "Catalog.M" + yo + "d.TabularSection.G" + yo + ".Attribute.P" + yo,
            "Catalog.M" + yo + "d.Form.F" + yo + ".Field.C" + yo + ".Handler.E" + yo,
            "Catalog.M" + yo + "d.Predefined.It" + yo + "m"})
        {
            List<String> p = GetProjectErrorsTool.addressProbes(supported);
            assertTrue("a supported shape must enumerate: " + supported, p.size() > 1);
            assertTrue("and stay bounded (got " + p.size() + "): " + supported, p.size() <= 16);
            assertEquals("as typed must always be first", supported, p.get(0));
        }
    }


    @Test
    public void testAnExactScopeCallDoesNotGetTheFragmentParity()
    {
        // validate_xdto_package reaches the same collector with exactScope=true, and the fragment
        // reading was hardcoded there. 'XDTOPackage.Package' - a package literally named 'Package' -
        // then also produced the odd-parity variant 'xdtopackage.<Paket>', which matches the markers
        // of a DIFFERENT package named '<Paket>': exact validation reporting a sibling's problems.
        //
        // The distinction is in the SIGNATURE now, so this is checked at the boundary the caller
        // actually crosses rather than trusted to a convention.
        String ruPackage = fromCp(0x041F, 0x0430, 0x043A, 0x0435, 0x0442); // Paket
        String address = "XDTOPackage.Package"; //$NON-NLS-1$

        // Entered through the SEAM the caller really crosses, so this pins the WIRING and not just
        // the catalogue: reading the variants straight out of MetadataTypeUtils would stay green
        // with exactScope ignored at the call site, which is exactly where the bug lived.
        Set<String> exact = GetProjectErrorsTool.scanFilterVariants(
            Collections.singletonList(address), true);
        Set<String> loose = GetProjectErrorsTool.scanFilterVariants(
            Collections.singletonList(address), false);

        assertTrue("the address itself must scope the scan", //$NON-NLS-1$
            exact.contains("xdtopackage.package")); //$NON-NLS-1$
        // NB: the Russian TYPE token of XDTOPackage legitimately contains that word, so the claim
        // is about the NAME segment specifically - the odd-parity variant must not exist.
        assertFalse("an EXACT scope must not translate the NAME segment", //$NON-NLS-1$
            exact.contains("xdtopackage." + ruPackage.toLowerCase())); //$NON-NLS-1$
        assertTrue("...while the LOOSE reading is where that second parity belongs", //$NON-NLS-1$
            loose.contains("xdtopackage." + ruPackage.toLowerCase())); //$NON-NLS-1$

        // And the scan really is scoped that narrowly: the sibling package's marker is excluded.
        int[] filteredOut = {0};
        assertTrue("a sibling package's marker must NOT pass an exact scope", //$NON-NLS-1$
            GetProjectErrorsTool.excludedByObjectsFilter(exact, true,
                "XDTOPackage." + ruPackage + ".Package", filteredOut, true)); //$NON-NLS-1$
        assertFalse("...while the package's own marker still does", //$NON-NLS-1$
            GetProjectErrorsTool.excludedByObjectsFilter(exact, true,
                "XDTOPackage.Package.Package", filteredOut, true)); //$NON-NLS-1$
    }


    @Test
    public void testRepeatedAddressesCostOneFormReadEachNotOnePerEntry()
    {
        // The array comes off the wire. A form member that resolves to nothing is never recorded in
        // `resolved`, so every copy of the same missing address used to open its own content-model
        // read transaction - unbounded work chosen by the caller. Deduplicating the ENTRIES would
        // have been the wrong fix twice over: it changes a list the caller gets echoed back, and it
        // misses the real case anyway, since two differently spaced spellings of one address are
        // distinct strings. The bound belongs on the PROBE, which is already canonical.
        List<GetProjectErrorsTool.DeferredMember> deferred = new ArrayList<>();
        String probe = "Catalog.C.Form.ItemForm.Field.Missing"; //$NON-NLS-1$
        FormElementWriter.FormMemberRef ref = FormElementWriter.parse(probe);
        assertNotNull(ref);
        for (int i = 0; i < 500; i++)
        {
            // Same canonical PROBE, different raw spellings - what the caller may really send.
            String raw = "Catalog." + repeat(" ", i) + "C.Form.ItemForm.Field.Missing"; //$NON-NLS-1$ //$NON-NLS-2$
            deferred.add(new GetProjectErrorsTool.DeferredMember(raw, probe, ref));
        }

        int[] reads = {0};
        GetProjectErrorsTool.ProjectResolution decided =
            new GetProjectErrorsTool.ProjectResolution("P"); //$NON-NLS-1$
        GetProjectErrorsTool.resolveDeferredMembers(deferred, decided, member -> {
            reads[0]++;
            return null; // the content model could not be read
        });

        assertEquals("500 entries naming ONE address must cost ONE content-model read", //$NON-NLS-1$
            1, reads[0]);
        assertEquals("...and every entry still gets its own verdict", //$NON-NLS-1$
            500, decided.undecided.size());

        // Two DISTINCT addresses still cost two reads - the memo bounds work, it does not skip it.
        String other = "Catalog.C.Form.ItemForm.Field.Other"; //$NON-NLS-1$
        deferred.add(new GetProjectErrorsTool.DeferredMember(other, other,
            FormElementWriter.parse(other)));
        int[] reads2 = {0};
        GetProjectErrorsTool.resolveDeferredMembers(deferred,
            new GetProjectErrorsTool.ProjectResolution("P"), member -> { //$NON-NLS-1$
                reads2[0]++;
                return null;
            });
        assertEquals(2, reads2[0]);

        // CASING is not a distinction either: the member lookup matches names case-insensitively,
        // so four casings of one attribute are one node. Keying the memo on the raw probe let
        // external input multiply the reads just by varying the case.
        List<GetProjectErrorsTool.DeferredMember> casings = new ArrayList<>();
        for (String cased : new String[] {
            "Catalog.C.Form.ItemForm.Attribute.alpha", //$NON-NLS-1$
            "Catalog.C.Form.ItemForm.Attribute.Alpha", //$NON-NLS-1$
            "Catalog.C.Form.ItemForm.Attribute.aLpHa", //$NON-NLS-1$
            "Catalog.C.Form.ItemForm.Attribute.ALPHA"}) //$NON-NLS-1$
        {
            casings.add(new GetProjectErrorsTool.DeferredMember(cased, cased,
                FormElementWriter.parse(cased)));
        }
        int[] casedReads = {0};
        GetProjectErrorsTool.resolveDeferredMembers(casings,
            new GetProjectErrorsTool.ProjectResolution("P"), member -> { //$NON-NLS-1$
                casedReads[0]++;
                return null;
            });
        assertEquals("four casings of ONE address must cost ONE content-model read", //$NON-NLS-1$
            1, casedReads[0]);
    }

    /** Java 8-friendly String.repeat. */
    private static String repeat(String unit, int times)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++)
        {
            sb.append(unit);
        }
        return sb.toString();
    }


    @Test
    public void testAmbiguousYoAddressScopesEveryNodeItCouldMean()
    {
        // With yo the request can be genuinely ambiguous. Given BOTH 'Catalog.M[yo]d' (holding
        // attribute 'V[ye]s') and 'Catalog.M[ye]d' (holding 'V[yo]s'), the address
        // 'Catalog.M[yo]d.Attribute.V[yo]s' matches both under the fallback - one by normalizing the
        // ancestor, the other the leaf. Returning at whichever probe came first scoped the scan to
        // one of them and reported the other's problems as absent: a false clean decided by probe
        // ORDER. Every probe that resolves must contribute.
        String ye = fromCp(0x0435);
        String yo = fromCp(0x0451);
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();

        Catalog withYo = MdClassFactory.eINSTANCE.createCatalog();
        withYo.setName("M" + yo + "d"); //$NON-NLS-1$ //$NON-NLS-2$
        withYo.getAttributes().add(attribute("V" + ye + "s")); //$NON-NLS-1$ //$NON-NLS-2$
        config.getCatalogs().add(withYo);

        Catalog withYe = MdClassFactory.eINSTANCE.createCatalog();
        withYe.setName("M" + ye + "d"); //$NON-NLS-1$ //$NON-NLS-2$
        withYe.getAttributes().add(attribute("V" + yo + "s")); //$NON-NLS-1$ //$NON-NLS-2$
        config.getCatalogs().add(withYe);

        String requested = "Catalog.M" + yo + "d.Attribute.V" + yo + "s"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Set<String> scope = resolvedIn(config, requested).get(requested);

        assertNotNull("the ambiguous address must resolve", scope); //$NON-NLS-1$
        assertTrue("the node reached by normalizing the ANCESTOR must scope the scan", //$NON-NLS-1$
            scope.contains("Catalog.M" + ye + "d.Attribute.V" + yo + "s")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue("and so must the node reached by normalizing the LEAF", //$NON-NLS-1$
            scope.contains("Catalog.M" + yo + "d.Attribute.V" + ye + "s")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }


    @Test
    public void testAnAddressThatResolvesAsTypedIsNotWidenedToItsYoSibling()
    {
        // The counterpart to the ambiguous case, and the limit of it. When BOTH spellings exist as
        // separate objects and the caller typed one of them EXACTLY, that one is the answer:
        // accumulating every resolving probe scoped the scan onto a sibling the caller never asked
        // about, and an exact address promises ONE model node. This is the same exact-first rule the
        // write/delete resolver uses (MetadataNodeResolver.resolveExistingWithYoFallback).
        String ye = fromCp(0x0435);
        String yo = fromCp(0x0451);
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        for (String name : new String[] {"M" + yo + "d", "M" + ye + "d"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
            catalog.setName(name);
            config.getCatalogs().add(catalog);
        }

        String requested = "Catalog.M" + yo + "d"; //$NON-NLS-1$ //$NON-NLS-2$
        Set<String> scope = resolvedIn(config, requested).get(requested);

        assertEquals("an address that resolves AS TYPED must scope exactly itself", //$NON-NLS-1$
            singleton(requested), scope);
        assertFalse("the yo sibling must not be scanned", //$NON-NLS-1$
            scope.contains("Catalog.M" + ye + "d")); //$NON-NLS-1$ //$NON-NLS-2$

        // ...and the fallback still works when the typed spelling does NOT exist.
        Configuration onlyYe = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog stored = MdClassFactory.eINSTANCE.createCatalog();
        stored.setName("M" + ye + "d"); //$NON-NLS-1$ //$NON-NLS-2$
        onlyYe.getCatalogs().add(stored);
        assertEquals(singleton("Catalog.M" + ye + "d"), //$NON-NLS-1$ //$NON-NLS-2$
            resolvedIn(onlyYe, requested).get(requested));
    }


    @Test
    public void testTheDeferredPathHonoursExactFirstAndNeverMasksAnUndecidedExactProbe()
    {
        // The mdclass ambiguity test never touches DeferredMember, so the same rules are pinned here
        // on the form-member path, through the injected resolver.
        String ye = fromCp(0x0435);
        String yo = fromCp(0x0451);
        String asTypedFqn = "Catalog.M" + yo + "d.Form.F.Field.Code"; //$NON-NLS-1$ //$NON-NLS-2$
        String yoReading = "Catalog.M" + ye + "d.Form.F.Field.Code"; //$NON-NLS-1$ //$NON-NLS-2$

        // 1) As typed it resolves -> that alone scopes the scan; the yo reading is never consulted.
        List<GetProjectErrorsTool.DeferredMember> exactFirst = Arrays.asList(
            new GetProjectErrorsTool.DeferredMember(asTypedFqn, asTypedFqn,
                FormElementWriter.parse(asTypedFqn), true),
            new GetProjectErrorsTool.DeferredMember(asTypedFqn, yoReading,
                FormElementWriter.parse(yoReading), false));
        GetProjectErrorsTool.ProjectResolution exact =
            new GetProjectErrorsTool.ProjectResolution("P"); //$NON-NLS-1$
        GetProjectErrorsTool.resolveDeferredMembers(exactFirst, exact,
            member -> Collections.singletonList(member.probeFqn));
        assertEquals("an as-typed hit must scope exactly itself", //$NON-NLS-1$
            singleton(asTypedFqn), exact.resolved.get(asTypedFqn));

        // 2) As typed it is ABSENT while two yo readings are real -> both scope the scan.
        List<GetProjectErrorsTool.DeferredMember> ambiguous = Arrays.asList(
            new GetProjectErrorsTool.DeferredMember(asTypedFqn, asTypedFqn,
                FormElementWriter.parse(asTypedFqn), true),
            new GetProjectErrorsTool.DeferredMember(asTypedFqn, yoReading,
                FormElementWriter.parse(yoReading), false));
        GetProjectErrorsTool.ProjectResolution ambig =
            new GetProjectErrorsTool.ProjectResolution("P"); //$NON-NLS-1$
        GetProjectErrorsTool.resolveDeferredMembers(ambiguous, ambig,
            member -> member.asTyped ? Collections.<String> emptyList()
                : Collections.singletonList(member.probeFqn));
        assertEquals("the yo reading must scope the scan when as typed is absent", //$NON-NLS-1$
            singleton(yoReading), ambig.resolved.get(asTypedFqn));

        // 3) As typed it is UNDECIDED (content unreadable) while a yo reading resolves -> the
        //    address stays UNDECIDED. Answering about the sibling would report on a node the caller
        //    did not name while the one they did name was never looked at.
        GetProjectErrorsTool.ProjectResolution masked =
            new GetProjectErrorsTool.ProjectResolution("P"); //$NON-NLS-1$
        GetProjectErrorsTool.resolveDeferredMembers(ambiguous, masked,
            member -> member.asTyped ? null : Collections.singletonList(member.probeFqn));
        assertTrue("an undecided EXACT probe must not be masked by a yo reading", //$NON-NLS-1$
            masked.resolved.isEmpty());
        assertEquals(singleton(asTypedFqn), masked.undecided);
    }


    @Test
    public void testADeadEndTypedParentDoesNotBlockTheYoTwinOfThatParent()
    {
        // The per-level walk committed to the first child that matched, so a chain whose typed
        // parent EXISTS but is a dead end never got to try that parent's yo twin. Here
        // 'Subsystem.M[yo]d' exists and is childless, while 'Subsystem.M[ye]d' holds 'V[ye]s': the
        // address 'Subsystem.M[yo]d.Subsystem.V[yo]s' must resolve through the fallback, and the
        // greedy descent stopped at the parent and answered objectsNotFound.
        String ye = fromCp(0x0435);
        String yo = fromCp(0x0451);
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();

        Subsystem deadEnd = MdClassFactory.eINSTANCE.createSubsystem();
        deadEnd.setName("M" + yo + "d"); //$NON-NLS-1$ //$NON-NLS-2$
        config.getSubsystems().add(deadEnd);

        Subsystem alive = MdClassFactory.eINSTANCE.createSubsystem();
        alive.setName("M" + ye + "d"); //$NON-NLS-1$ //$NON-NLS-2$
        Subsystem child = MdClassFactory.eINSTANCE.createSubsystem();
        child.setName("V" + ye + "s"); //$NON-NLS-1$ //$NON-NLS-2$
        alive.getSubsystems().add(child);
        config.getSubsystems().add(alive);

        String requested = "Subsystem.M" + yo + "d.Subsystem.V" + yo + "s"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Set<String> scope = resolvedIn(config, requested).get(requested);

        assertNotNull("a dead-end typed parent must not end the search", scope); //$NON-NLS-1$
        assertTrue("the scan must be scoped by the STORED chain reached by backtracking", //$NON-NLS-1$
            scope.contains("Subsystem.M" + ye + "d.Subsystem.V" + ye + "s")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        // EXACT-FIRST still wins as a WHOLE chain: when the typed chain resolves end to end, the
        // yo twin is never consulted, even though it exists and also carries a matching child.
        Subsystem typedChild = MdClassFactory.eINSTANCE.createSubsystem();
        typedChild.setName("V" + yo + "s"); //$NON-NLS-1$ //$NON-NLS-2$
        deadEnd.getSubsystems().add(typedChild);
        assertEquals("a fully typed chain must scope exactly itself", //$NON-NLS-1$
            singleton(requested), resolvedIn(config, requested).get(requested));

        // And a chain that exists nowhere is still an honest miss.
        assertNull(resolvedIn(config, "Subsystem.M" + yo + "d.Subsystem.NoSuch") //$NON-NLS-1$ //$NON-NLS-2$
            .get("Subsystem.M" + yo + "d.Subsystem.NoSuch")); //$NON-NLS-1$ //$NON-NLS-2$
    }


    @Test
    public void testTheAssembledReportReallyCarriesEveryCaveat()
    {
        // A revert sweep caught this: dropping the partial-answer warning from the report reddened
        // NOTHING, because the only test called the renderer by hand. The report is assembled here,
        // so this pins that each caveat is actually EMITTED - the human channel is the only place a
        // partial answer is currently visible at all.
        GetProjectErrorsTool.AddressResolution r = new GetProjectErrorsTool.AddressResolution();
        r.resolved.add("Catalog.Products"); //$NON-NLS-1$
        r.notFound.add("Catalog.Typo"); //$NON-NLS-1$
        r.unsupported.add(unsupportedEntry("XDTOPackage.P.Property.N", "why")); //$NON-NLS-1$ //$NON-NLS-2$
        r.incompleteFor.put("Catalog.Products", singleton("Archived")); //$NON-NLS-1$ //$NON-NLS-2$

        String report = GetProjectErrorsTool.assembleAddressReport(
            Collections.<ErrorInfo> emptyList(), "P", null, //$NON-NLS-1$
            Arrays.asList("Catalog.Products", "Catalog.Typo"), 100, false, r, //$NON-NLS-1$ //$NON-NLS-2$
            new int[] {0}, new int[] {0});

        assertTrue("the missing address must be named", report.contains("objectsNotFound")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the unsupported address must be named", //$NON-NLS-1$
            report.contains("objectsUnsupported")); //$NON-NLS-1$
        assertTrue("the PARTIAL answer must be named, with the project that could not answer", //$NON-NLS-1$
            report.contains("Partial answer for") && report.contains("Archived")); //$NON-NLS-1$ //$NON-NLS-2$

        // ...and a complete answer carries no such caveat.
        GetProjectErrorsTool.AddressResolution complete =
            new GetProjectErrorsTool.AddressResolution();
        complete.resolved.add("Catalog.Products"); //$NON-NLS-1$
        String clean = GetProjectErrorsTool.assembleAddressReport(
            Collections.<ErrorInfo> emptyList(), "P", null, //$NON-NLS-1$
            Collections.singletonList("Catalog.Products"), 100, false, complete, //$NON-NLS-1$
            new int[] {0}, new int[] {0});
        assertFalse(clean.contains("Partial answer for")); //$NON-NLS-1$
    }

    @Test
    public void testEveryResolvingYoReadingOfADeferredMemberAccumulates()
    {
        // The guard that lets the deferred path ACCUMULATE was not covered: with only one yo reading
        // in play, keying on "already resolved" and keying on "resolved AS TYPED" behave alike. Two
        // real yo readings tell them apart - and dropping either one is the false clean this branch
        // exists to remove.
        String base = "Catalog.C.Form.ItemForm.Field."; //$NON-NLS-1$
        String requested = base + "A"; //$NON-NLS-1$
        List<GetProjectErrorsTool.DeferredMember> deferred = Arrays.asList(
            new GetProjectErrorsTool.DeferredMember(requested, requested,
                FormElementWriter.parse(requested), true),
            new GetProjectErrorsTool.DeferredMember(requested, base + "B", //$NON-NLS-1$
                FormElementWriter.parse(base + "B"), false), //$NON-NLS-1$
            new GetProjectErrorsTool.DeferredMember(requested, base + "C", //$NON-NLS-1$
                FormElementWriter.parse(base + "C"), false)); //$NON-NLS-1$

        GetProjectErrorsTool.ProjectResolution decided =
            new GetProjectErrorsTool.ProjectResolution("P"); //$NON-NLS-1$
        GetProjectErrorsTool.resolveDeferredMembers(deferred, decided,
            member -> member.asTyped ? Collections.<String> emptyList()
                : Collections.singletonList(member.probeFqn));

        assertEquals("both resolving yo readings must scope the scan", //$NON-NLS-1$
            new HashSet<>(Arrays.asList(base + "B", base + "C")), //$NON-NLS-1$ //$NON-NLS-2$
            decided.resolved.get(requested));
    }


    @Test
    public void testEveryRealSubsystemChainTheAddressCanMeanScopesTheScan()
    {
        // Exact-first was carried into the tree walk; ACCUMULATION was not. With both
        // 'M[yo]d -> V[ye]s' and 'M[ye]d -> V[yo]s' present, the address
        // 'Subsystem.M[yo]d.Subsystem.V[yo]s' matches BOTH chains through the fallback, and
        // returning whichever the walk met first scoped the scan to one and hid the markers under
        // the other.
        String ye = fromCp(0x0435);
        String yo = fromCp(0x0451);
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        config.getSubsystems().add(chain("M" + yo + "d", "V" + ye + "s")); //$NON-NLS-1$ //$NON-NLS-2$
        config.getSubsystems().add(chain("M" + ye + "d", "V" + yo + "s")); //$NON-NLS-1$ //$NON-NLS-2$

        String requested = "Subsystem.M" + yo + "d.Subsystem.V" + yo + "s"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Set<String> scope = resolvedIn(config, requested).get(requested);

        assertNotNull("the ambiguous chain must resolve", scope); //$NON-NLS-1$
        assertTrue("the chain reached through the LEAF fallback must scope the scan", //$NON-NLS-1$
            scope.contains("Subsystem.M" + yo + "d.Subsystem.V" + ye + "s")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue("and so must the chain reached through the ANCESTOR fallback", //$NON-NLS-1$
            scope.contains("Subsystem.M" + ye + "d.Subsystem.V" + yo + "s")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        // EXACT-FIRST still wins whole: with the typed chain present, only it scopes the scan.
        config.getSubsystems().add(chain("M" + yo + "d2", "V" + yo + "s")); //$NON-NLS-1$ //$NON-NLS-2$
        String typed = "Subsystem.M" + yo + "d2.Subsystem.V" + yo + "s"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("a fully typed chain scopes exactly itself", //$NON-NLS-1$
            singleton(typed), resolvedIn(config, typed).get(typed));
    }

    @Test
    public void testOwningOneSpellingDoesNotEraseAnUndecidedOther()
    {
        // The completeness model is per (address, project) with THREE states, but at this seam a
        // resolved spelling overwrote an undecided one INSIDE one project: the scan was scoped only
        // by the readable spelling, the markers stored under the unreadable one vanished, and there
        // was neither a partial-answer warning nor a refusal. Owning and being unable to decide must
        // coexist.
        String base = "Catalog.C.Form.ItemForm.Field."; //$NON-NLS-1$
        String requested = base + "A"; //$NON-NLS-1$
        List<GetProjectErrorsTool.DeferredMember> deferred = Arrays.asList(
            new GetProjectErrorsTool.DeferredMember(requested, requested,
                FormElementWriter.parse(requested), true),
            new GetProjectErrorsTool.DeferredMember(requested, base + "B", //$NON-NLS-1$
                FormElementWriter.parse(base + "B"), false), //$NON-NLS-1$
            new GetProjectErrorsTool.DeferredMember(requested, base + "C", //$NON-NLS-1$
                FormElementWriter.parse(base + "C"), false)); //$NON-NLS-1$

        GetProjectErrorsTool.ProjectResolution decided =
            new GetProjectErrorsTool.ProjectResolution("P"); //$NON-NLS-1$
        // as typed: absent | B: content model unreadable | C: resolves
        GetProjectErrorsTool.resolveDeferredMembers(deferred, decided, member -> {
            if (member.asTyped)
            {
                return Collections.<String> emptyList();
            }
            return member.probeFqn.endsWith("B") ? null //$NON-NLS-1$
                : Collections.singletonList(member.probeFqn);
        });

        assertTrue("the readable spelling still scopes the scan", //$NON-NLS-1$
            decided.resolved.containsKey(requested));
        assertTrue("...and the unreadable one must STILL be undecided", //$NON-NLS-1$
            decided.undecided.contains(requested));

        // The fold must then call the answer PARTIAL, not complete. (The read pass itself ran to
        // the end here - only individual spellings were undecidable.)
        decided.passCompleted = true;
        GetProjectErrorsTool.AddressResolution r = new GetProjectErrorsTool.AddressResolution();
        GetProjectErrorsTool.foldProjectDecisions(r, Collections.singletonList(requested), Collections.singletonList(requested),
            Collections.singletonList(decided));
        assertEquals(Collections.singletonList(requested), r.resolved);
        assertEquals("the partial answer must name the project that could not decide", //$NON-NLS-1$
            singleton("P"), r.incompleteFor.get(requested)); //$NON-NLS-1$
    }


    @Test
    public void testEveryModelIndependentVerdictIsReachedWithoutAModel()
    {
        // The full list of decisions this filter can make with NO model at all. Each must be
        // settled by shape alone, because each is impossible in EVERY configuration - not merely
        // absent from the ones we managed to read.
        for (String impossible : new String[] {
            "Catalog.Products.",                    // trailing empty segment //$NON-NLS-1$
            ".Catalog.Products",                    // leading empty segment //$NON-NLS-1$
            "Catalog..Products",                    // doubled separator //$NON-NLS-1$
            ".",                                    // nothing but a separator //$NON-NLS-1$
            "NoSuchType_e2e.X",                     // unknown leading TYPE token //$NON-NLS-1$
            "Catalog.Products.Fom.ItemForm",        // misspelt nested KIND //$NON-NLS-1$
            "Catalog.Products.Field.Code",          // form-only kind on a mdclass object //$NON-NLS-1$
            "Catalog.Products.Column.Number",       // Column is a DocumentJournal containment //$NON-NLS-1$
            "Document.Invoice.Predefined.Sample",   // Documents hold no predefined items //$NON-NLS-1$
            "NoSuchType.X.Form.F",                  // form grammar never checked the leading TYPE //$NON-NLS-1$
            "NoSuchType.X.Predefined.Item",         // ...nor did the predefined grammar //$NON-NLS-1$
            "Catalog.Products.Form.ItemForm.Fielld.Code", // misspelt form-element KIND //$NON-NLS-1$
            "Catalog.Products.Module"})             // odd arity: no grammar has it //$NON-NLS-1$
        {
            assertFalse("must be impossible by shape alone: " + impossible, //$NON-NLS-1$
                GetProjectErrorsTool.possibleAddressShape(impossible));
        }
        // ...and every documented grammar must stay POSSIBLE, or a real address would be called
        // missing without anyone looking for it.
        for (String possible : new String[] {
            "Catalog.Products", //$NON-NLS-1$
            "Catalog.Products.Attribute.Weight", //$NON-NLS-1$
            "Catalog.Products.TabularSection.Goods.Attribute.Price", //$NON-NLS-1$
            "Catalog.Products.Form.ItemForm", //$NON-NLS-1$
            "CommonForm.Settings", //$NON-NLS-1$
            "CommonForm.Settings.Field.Code", //$NON-NLS-1$
            "Catalog.Products.Form.ItemForm.Field.Code.Handler.OnChange", //$NON-NLS-1$
            "Catalog.Products.Form.ItemForm.Handler.OnOpen", // form-LEVEL handler //$NON-NLS-1$
            "DocumentJournal.Sales.Column.Number", // a real mdclass Column //$NON-NLS-1$
            "Catalog.Products.TabularSection.Goods", // the owner question must not swallow this //$NON-NLS-1$
            "Catalog.Products.Predefined.Sample", // Catalogs DO hold predefined items //$NON-NLS-1$
            "ChartOfAccounts.Main.Predefined.Cash", // ...and so do charts of accounts //$NON-NLS-1$
            "Subsystem.Sales.Subsystem.Orders", //$NON-NLS-1$
            // The owner question must survive a RUSSIAN owner token and a Russian nested kind: it
            // resolves the type through the bilingual catalogue before asking the metamodel, and a
            // regression there would reject a legitimate address written the other way round.
            fromCp(0x0421, 0x043f, 0x0440, 0x0430, 0x0432, 0x043e, 0x0447, 0x043d, 0x0438, 0x043a)
                + ".Products." //$NON-NLS-1$
                + fromCp(0x0422, 0x0430, 0x0431, 0x043b, 0x0438, 0x0447, 0x043d, 0x0430, 0x044f,
                    0x0427, 0x0430, 0x0441, 0x0442, 0x044c)
                + ".Goods", //$NON-NLS-1$
            "XDTOPackage.Exchange"}) //$NON-NLS-1$
        {
            assertTrue("a documented grammar must stay possible: " + possible, //$NON-NLS-1$
                GetProjectErrorsTool.possibleAddressShape(possible));
        }
    }

    @Test
    public void testAnImpossibleAddressStaysMissingEvenBesideAnUnreadableProject()
    {
        // THE pair. In a workspace with one closed or still-indexing EDT project, every candidate
        // used to be marked undecided - including addresses whose impossibility needs no model at
        // all. The whole call then became "could not decide" instead of naming the typo, so
        // model-dependent uncertainty overrode model-independent knowledge.
        String impossible = "Catalog.Products.Fom.ItemForm"; //$NON-NLS-1$
        String possible = "Catalog.NoSuchObject_e2e"; //$NON-NLS-1$
        List<String> asked = Arrays.asList(impossible, possible);

        // A readable project that holds neither, plus one that cannot be consulted at all.
        GetProjectErrorsTool.ProjectResolution readable = GetProjectErrorsTool.resolveInProject(
            project("open"), readModel(), MdClassFactory.eINSTANCE.createConfiguration(), //$NON-NLS-1$
            Collections.singletonList(possible));
        GetProjectErrorsTool.ProjectResolution unreadable = GetProjectErrorsTool.projectDecision(
            closedProject("archived"), null, null, Collections.singletonList(possible)); //$NON-NLS-1$

        GetProjectErrorsTool.AddressResolution r = new GetProjectErrorsTool.AddressResolution();
        GetProjectErrorsTool.foldProjectDecisions(r, asked, asked, Arrays.asList(readable, unreadable));

        // The POSSIBLE address is still undecided - nobody could rule it out.
        assertNotNull("a possible address must stay undecided beside an unreadable project", //$NON-NLS-1$
            r.error);
        assertTrue("and the refusal must name it", r.error.contains(possible)); //$NON-NLS-1$
        assertFalse("but NOT the impossible one - that one was never in doubt", //$NON-NLS-1$
            r.error.contains(impossible));

        // With only the impossible address asked, there is nothing to refuse at all: it is a plain
        // miss, exactly as in a workspace where every project is readable.
        GetProjectErrorsTool.AddressResolution onlyImpossible =
            new GetProjectErrorsTool.AddressResolution();
        GetProjectErrorsTool.foldProjectDecisions(onlyImpossible,
            Collections.singletonList(impossible),
            Collections.singletonList(impossible),
            Arrays.asList(readable, GetProjectErrorsTool.projectDecision(
                closedProject("archived"), null, null, Collections.<String> emptyList()))); //$NON-NLS-1$
        assertNull("an impossible address must never cause a refusal", onlyImpossible.error); //$NON-NLS-1$
        assertEquals(Collections.singletonList(impossible), onlyImpossible.notFound);
    }


    @Test
    public void testAnImpossibleAddressIsNeverOfferedToAnyProject()
    {
        // The wiring, not just the predicate: an address settled by shape must keep its place among
        // the candidates (it still needs a verdict) while being withheld from the projects, because
        // that is exactly what stops one unreadable project from calling it "undecided".
        GetProjectErrorsTool.AddressResolution r = new GetProjectErrorsTool.AddressResolution();
        List<String> candidates = new ArrayList<>();
        List<String> resolvable = GetProjectErrorsTool.classifyRequestedAddresses(Arrays.asList(
            "Catalog.Products",                 // possible //$NON-NLS-1$
            "Catalog.Products.Fom.ItemForm",    // impossible: misspelt kind //$NON-NLS-1$
            "XDTOPackage.P.ObjectType.T",       // unsupported family //$NON-NLS-1$
            "Catalog.Products.",                // impossible: empty segment //$NON-NLS-1$
            "NoSuchType_e2e.X"),                // impossible: unknown head //$NON-NLS-1$
            r, candidates);

        assertEquals("every address that still needs a verdict keeps request order", //$NON-NLS-1$
            Arrays.asList("Catalog.Products", "Catalog.Products.Fom.ItemForm", //$NON-NLS-1$ //$NON-NLS-2$
                "Catalog.Products.", "NoSuchType_e2e.X"), candidates); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ONLY the possible address may be offered to a project", //$NON-NLS-1$
            Collections.singletonList("Catalog.Products"), resolvable); //$NON-NLS-1$
        assertEquals("the XDTO member is a family verdict, not a candidate", //$NON-NLS-1$
            1, r.unsupported.size());
    }


    @Test
    public void testImpossibleAddressesAnswerEvenWhenNoProjectCouldBeInspected()
    {
        // The guard for "nothing could be inspected" fired before the verdict was published, so a
        // workspace whose every project is closed / indexing / non-EDT refused the whole call even
        // when the only addresses asked were impossible by SHAPE - decided long before any project
        // mattered. Same inversion as before: knowledge that needs no model, overridden by the state
        // of the inspection.
        List<String> impossible = Arrays.asList("NoSuchType_e2e.X", "Catalog.Products."); //$NON-NLS-1$ //$NON-NLS-2$

        GetProjectErrorsTool.AddressResolution none = new GetProjectErrorsTool.AddressResolution();
        List<String> candidates = new ArrayList<>();
        List<String> resolvable = GetProjectErrorsTool.classifyRequestedAddresses(impossible, none,
            candidates);
        assertTrue("the premise: nothing here needs a model", resolvable.isEmpty()); //$NON-NLS-1$

        // No project completed a pass - there was none to complete.
        GetProjectErrorsTool.foldProjectDecisions(none, candidates, resolvable,
            Collections.<GetProjectErrorsTool.ProjectResolution> emptyList());
        assertNull("an impossible address must not be refused for want of an inspection", //$NON-NLS-1$
            none.error);
        assertEquals(impossible, none.notFound);

        // The pair: add ONE address of possible shape to the same workspace, and the call is
        // refused again - that one genuinely could not be decided.
        GetProjectErrorsTool.AddressResolution mixed = new GetProjectErrorsTool.AddressResolution();
        List<String> mixedCandidates = new ArrayList<>();
        List<String> mixedResolvable = GetProjectErrorsTool.classifyRequestedAddresses(
            Arrays.asList("NoSuchType_e2e.X", "Catalog.Products"), mixed, mixedCandidates); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Collections.singletonList("Catalog.Products"), mixedResolvable); //$NON-NLS-1$
        GetProjectErrorsTool.foldProjectDecisions(mixed, mixedCandidates, mixedResolvable,
            Collections.<GetProjectErrorsTool.ProjectResolution> emptyList());
        assertNotNull("a possible address with no inspection must still refuse", mixed.error); //$NON-NLS-1$
        assertTrue("nothing may be declared missing in that case", mixed.notFound.isEmpty()); //$NON-NLS-1$
    }

    // ========== helpers ==========
    /** A two-level subsystem chain: parent -> child. */
    private static Subsystem chain(String parentName, String childName)
    {
        Subsystem parent = MdClassFactory.eINSTANCE.createSubsystem();
        parent.setName(parentName);
        Subsystem child = MdClassFactory.eINSTANCE.createSubsystem();
        child.setName(childName);
        parent.getSubsystems().add(child);
        return parent;
    }

    /**
     * An OPEN project whose description carries exactly {@code natureIds} - the shape
     * ProjectContext.naturesOf reads for an open project.
     */
    private static IProject natureProject(String name, String... natureIds)
    {
        IProject project = project(name);
        when(project.isOpen()).thenReturn(true);
        IProjectDescription description = mock(IProjectDescription.class);
        when(description.getNatureIds()).thenReturn(natureIds);
        try
        {
            when(project.getDescription()).thenReturn(description);
        }
        catch (CoreException e)
        {
            throw new IllegalStateException(e); // stubbing only; never thrown
        }
        return project;
    }


    /**
     * Resolves the given addresses against {@code config} in a single synthetic project and returns
     * that project's own decisions (requested address -&gt; the spellings that scope the scan).
     */
    private static Map<String, Set<String>> resolvedIn(Configuration config, String... fqns)
    {
        GetProjectErrorsTool.ProjectResolution decided = GetProjectErrorsTool.resolveInProject(
            project("P"), readModel(), config, Arrays.asList(fqns)); //$NON-NLS-1$
        assertTrue("the resolve pass must complete", decided.passCompleted); //$NON-NLS-1$
        return decided.resolved;
    }

    /** The object filter one project gets, given a per-project map (null = the loose single filter). */
    private static Set<String> objectsFor(Map<String, Set<String>> byProject, String projectName)
    {
        return GetProjectErrorsTool.objectsForProject(byProject, singleton("catalog.products"), //$NON-NLS-1$
            projectName);
    }

    /** The filter one project's marker scan really receives on an EXACT-address call. */
    private static Set<String> scanFilterFor(Map<String, Set<String>> variants, String projectName)
    {
        return GetProjectErrorsTool.objectsForProject(variants, Collections.<String> emptySet(),
            projectName);
    }

    /** A catalog attribute under the given Name. */
    private static CatalogAttribute attribute(String name)
    {
        CatalogAttribute attr = MdClassFactory.eINSTANCE.createCatalogAttribute();
        attr.setName(name);
        return attr;
    }

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

    /** The synthetic model's item whose class carries NO addressable kind token. */
    private static final String TOKENLESS_ITEM = "FormCommandBar"; //$NON-NLS-1$

    /** The synthetic model's form ATTRIBUTE (reached by its own containment, not by an item kind). */
    private static final String FORM_ATTRIBUTE = "Object"; //$NON-NLS-1$

    /** The synthetic model's attribute COLUMN, addressed '...Attribute.Object.Column.Amount'. */
    private static final String FORM_ATTRIBUTE_COLUMN = "Amount"; //$NON-NLS-1$

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
        // An item whose class carries NO addressable kind token - the real form metamodel has three
        // (AutoCommandBar, ContextMenu, ExtendedTooltip) and findFormItem returns them by name.
        EClass autoCommandBar = subclass("AutoCommandBar", formItem); //$NON-NLS-1$
        pkg.getEClassifiers().add(autoCommandBar);

        // The real metamodel's SHARED base: FormAttribute and FormAttributeColumn both inherit
        // AbstractFormAttribute (verified in the 2025.2 apidocs - FormAttributeColumn's superinterfaces
        // are AbstractFormAttribute / NamedElement / Titled, and getColumns() lives on FormAttribute
        // alone). The inheritance is mirrored here ON PURPOSE: issue #343's hierarchical classifier
        // maps AbstractFormAttribute -> Kind.ATTRIBUTE, so a column that did NOT inherit it would let
        // the ordering test below pass without ever exercising the case that can actually break.
        EClass abstractFormAttribute = f.createEClass();
        abstractFormAttribute.setName("AbstractFormAttribute"); //$NON-NLS-1$
        abstractFormAttribute.setAbstract(true);
        abstractFormAttribute.getEStructuralFeatures().add(stringAttribute("name")); //$NON-NLS-1$
        pkg.getEClassifiers().add(abstractFormAttribute);

        // A collection attribute's COLUMN: addressed '...Attribute.<Attr>.Column.<Name>' (issue #295).
        EClass formAttributeColumn = subclass("FormAttributeColumn", abstractFormAttribute); //$NON-NLS-1$
        pkg.getEClassifiers().add(formAttributeColumn);

        EClass formAttribute = subclass("FormAttribute", abstractFormAttribute); //$NON-NLS-1$
        formAttribute.getEStructuralFeatures().add(containment("columns", formAttributeColumn, true)); //$NON-NLS-1$
        pkg.getEClassifiers().add(formAttribute);

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
        form.getEStructuralFeatures().add(containment("attributes", formAttribute, true)); //$NON-NLS-1$
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

        EObject commandBar = pkg.getEFactoryInstance().create(autoCommandBar);
        setString(commandBar, "name", TOKENLESS_ITEM); //$NON-NLS-1$
        list(model.root, "items").add(commandBar); //$NON-NLS-1$

        EObject attribute = pkg.getEFactoryInstance().create(formAttribute);
        setString(attribute, "name", FORM_ATTRIBUTE); //$NON-NLS-1$
        EObject attributeColumn = pkg.getEFactoryInstance().create(formAttributeColumn);
        setString(attributeColumn, "name", FORM_ATTRIBUTE_COLUMN); //$NON-NLS-1$
        list(attribute, "columns").add(attributeColumn); //$NON-NLS-1$
        list(model.root, "attributes").add(attribute); //$NON-NLS-1$

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
     * A project mock that answers the nature query the way a real workspace project would: a 1C:EDT
     * project carries exactly one V8 nature, an ordinary Eclipse/Java/Maven project carries none.
     */
    private static IProject project(String name, boolean edt)
    {
        return edt ? natureProject(name, "com._1c.g5.v8.dt.core.V8ConfigurationNature") //$NON-NLS-1$
            : natureProject(name);
    }

    /**
     * A CLOSED project. {@code hasNature} is not answerable for one (it throws), and with no
     * readable location the natures cannot be determined at all - which is the state that must be
     * treated as "could hold metadata", never as proof that it could not.
     */
    private static IProject closedProject(String name)
    {
        IProject project = project(name);
        when(project.isOpen()).thenReturn(false);
        when(project.getLocation()).thenReturn(null);
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
