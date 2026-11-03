/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.reference;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.reference.MetadataReferenceService.ReferenceInfo;
import com.ditrix.edt.mcp.server.tools.reference.MetadataReferenceService.ScanGap;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectState;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.SearchDependenciesResult;

/** The diagnostic count stays parseable and every captured adopted-target gap is explicit. */
public class MetadataReferenceServiceScanGapTest
{
    private static final String FQN = "CommonModule.Calc";
    static final String NOT_READY = "**Scan incomplete:** search-scope project(s) still not ready: "
        + "ExternalObjects (building). Adopted copies of CommonModule.Calc in TestConfiguration.tests "
        + "were not searched, so the count above may be low. Wait for list_projects to report them 'ready' and retry.";

    private static IProject project(String name)
    {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn(name);
        return project;
    }

    /** Build the immutable value through its package-private factory, without a live workspace. */
    static SearchDependenciesResult snapshot(Map<String, ProjectState> readiness,
        String... extensions) throws Exception
    {
        List<IProject> projects = new ArrayList<>();
        for (String name : readiness.keySet())
        {
            projects.add(project(name));
        }
        List<IProject> extensionProjects = new ArrayList<>();
        for (String name : extensions)
        {
            extensionProjects.add(projects.stream().filter(p -> name.equals(p.getName()))
                .findFirst().orElseThrow());
        }
        Method factory = SearchDependenciesResult.class.getDeclaredMethod("determined",
            List.class, List.class, Map.class);
        factory.setAccessible(true);
        return (SearchDependenciesResult)factory.invoke(null, projects, extensionProjects, readiness);
    }

    static Map<String, ProjectState> readiness()
    {
        Map<String, ProjectState> readiness = new LinkedHashMap<>();
        readiness.put("TestConfiguration", ProjectState.READY);
        readiness.put("TestConfiguration.tests", ProjectState.READY);
        readiness.put("ExternalObjects", ProjectState.BUILDING);
        return readiness;
    }

    private static ScanGap gap() throws Exception
    {
        return ScanGap.of("TestConfiguration", snapshot(readiness(), "TestConfiguration.tests"));
    }

    @Test
    public void namesOnlyTheUnreadyProjectsAndTheUnsearchedExtensions() throws Exception
    {
        ScanGap gap = gap();
        assertEquals(ScanGap.Kind.NOT_READY, gap.kind);
        assertEquals(List.of("ExternalObjects"), new ArrayList<>(gap.notReady.keySet()));
        assertEquals(NOT_READY, MetadataReferenceService.incompleteScanNote(FQN, gap));
    }

    @Test
    public void multipleProjectsKeepTheirSnapshotOrderAndState() throws Exception
    {
        Map<String, ProjectState> readiness = readiness();
        readiness.put("SecondExternal", ProjectState.NOT_AVAILABLE);
        readiness.put("Second.tests", ProjectState.READY);
        ScanGap gap = ScanGap.of("TestConfiguration",
            snapshot(readiness, "TestConfiguration.tests", "Second.tests"));
        assertEquals("**Scan incomplete:** search-scope project(s) still not ready: "
            + "ExternalObjects (building), SecondExternal (not_available). Adopted copies of "
            + "CommonModule.Calc in TestConfiguration.tests, Second.tests were not searched, so the count above "
            + "may be low. Wait for list_projects to report them 'ready' and retry.",
            MetadataReferenceService.incompleteScanNote(FQN, gap));
    }

    @Test
    public void anUndeterminedScopeExplainsTheChangingContexts() throws Exception
    {
        Method factory = SearchDependenciesResult.class.getDeclaredMethod("undetermined");
        factory.setAccessible(true);
        ScanGap gap = ScanGap.of("TestConfiguration", (SearchDependenciesResult)factory.invoke(null));
        assertEquals(ScanGap.Kind.UNDETERMINED, gap.kind);
        assertEquals("**Scan incomplete:** the search scope of project 'TestConfiguration' could not be "
            + "determined (EDT project contexts are changing), so adopted copies of CommonModule.Calc in its "
            + "extensions were not searched and the count above may be low. Wait for list_projects to "
            + "report every project 'ready' and retry.", MetadataReferenceService.incompleteScanNote(FQN, gap));
    }

    @Test
    public void theNoteIsItsOwnParagraphImmediatelyAfterTheCount() throws Exception
    {
        List<ReferenceInfo> refs = List.of(new ReferenceInfo("BSL modules", "CommonModules/A/Module.bsl", 1),
            new ReferenceInfo("BSL modules", "CommonModules/B/Module.bsl", 2));
        String report = MetadataReferenceService.renderReport(FQN, refs,
            MetadataReferenceService.incompleteScanNote(FQN, gap()));
        assertTrue(report.startsWith("# References to CommonModule.Calc\n\n"
            + "**Total references found:** 2\n\n" + NOT_READY + "\n\n"));
        assertTrue(report.contains("CommonModules/A/Module.bsl [Line 1]"));
        assertTrue(report.contains("CommonModules/B/Module.bsl [Line 2]"));
    }

    @Test
    public void zeroReferencesStillCarryTheNoteBeforeTheEarlyReturn() throws Exception
    {
        assertEquals("# References to CommonModule.Calc\n\n**Total references found:** 0\n\n"
            + NOT_READY + "\n\nNo references found.\n", MetadataReferenceService.renderReport(
                FQN, List.of(), MetadataReferenceService.incompleteScanNote(FQN, gap())));
    }

    @Test
    public void noGapPreservesTheReportWithoutACaveat()
    {
        assertNull(MetadataReferenceService.incompleteScanNote(FQN, null));
        assertEquals("# References to CommonModule.Calc\n\n**Total references found:** 0\n\nNo references found.\n",
            MetadataReferenceService.renderReport(FQN, List.of(), null));
        assertFalse(MetadataReferenceService.renderReport(FQN,
            List.of(new ReferenceInfo("BSL modules", "A.bsl", 1)), null).contains("**Scan incomplete:**"));
    }

    @Test
    public void noExtensionsMeanNoAdoptedTargetGapEvenIfExternalObjectsAreBuilding() throws Exception
    {
        assertNull(ScanGap.of("TestConfiguration", snapshot(readiness())));
        assertNotNull(gap());
    }

    @Test
    public void aReadyScopeHasNoGap() throws Exception
    {
        Map<String, ProjectState> readiness = readiness();
        readiness.put("ExternalObjects", ProjectState.READY);
        assertNull(ScanGap.of("TestConfiguration", snapshot(readiness, "TestConfiguration.tests")));
    }

    @Test
    public void theObjectIdentifierIsPreservedInEitherDialect() throws Exception
    {
        String fqn = "CommonModule.\u0420\u0430\u0441\u0447\u0435\u0442";
        String note = MetadataReferenceService.incompleteScanNote(fqn, gap());
        assertTrue(note.contains("Adopted copies of " + fqn + " in TestConfiguration.tests"));
        assertTrue(MetadataReferenceService.renderReport(fqn, List.of(), note)
            .startsWith("# References to " + fqn + "\n\n"));
    }
}
