/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;

import org.junit.Test;

import com._1c.g5.v8.dt.core.platform.ProjectManifest;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;

/**
 * Tests for {@link ExtensionOriginUtils#originLabel(ObjectBelonging, boolean)} — the
 * pure decision that turns an object's belonging plus its owning-project type into a
 * core / core (adopted) / extension label. The {@code isExtensionProject(IProject)}
 * resolver needs a live workbench (project managers) and is e2e-covered; this
 * unit-tests only the pure label logic.
 */
public class ExtensionOriginUtilsTest
{
    @Test
    public void testBaseProjectIsAlwaysCore()
    {
        // A base configuration never holds adopted objects; every object is core
        // regardless of its (always NATIVE) belonging.
        assertEquals(ExtensionOriginUtils.ORIGIN_CORE,
            ExtensionOriginUtils.originLabel(ObjectBelonging.NATIVE, false));
        // Defensive: even an (impossible) ADOPTED flag in a base project reads as core.
        assertEquals(ExtensionOriginUtils.ORIGIN_CORE,
            ExtensionOriginUtils.originLabel(ObjectBelonging.ADOPTED, false));
    }

    @Test
    public void testExtensionAdoptedObjectIsCoreAdopted()
    {
        // In an extension an ADOPTED object is borrowed from the base configuration.
        assertEquals(ExtensionOriginUtils.ORIGIN_ADOPTED,
            ExtensionOriginUtils.originLabel(ObjectBelonging.ADOPTED, true));
    }

    @Test
    public void testExtensionNativeObjectIsExtensionOwn()
    {
        // In an extension a NATIVE object is the extension's own new object.
        assertEquals(ExtensionOriginUtils.ORIGIN_EXTENSION,
            ExtensionOriginUtils.originLabel(ObjectBelonging.NATIVE, true));
    }

    @Test
    public void testNullBelongingTreatedAsNative()
    {
        // A null belonging (defensive) is treated as NATIVE: extension-own in an
        // extension, core in a base configuration.
        assertEquals(ExtensionOriginUtils.ORIGIN_EXTENSION,
            ExtensionOriginUtils.originLabel(null, true));
        assertEquals(ExtensionOriginUtils.ORIGIN_CORE,
            ExtensionOriginUtils.originLabel(null, false));
    }

    /**
     * An external data processor / report belongs to its external-objects project. The
     * core/extension question does not apply to it, and answering "core" states that it belongs
     * to a base configuration - the exact confusion issue #309 exists to remove.
     */
    @Test
    public void testExternalObjectsProjectIsNeitherCoreNorExtension()
    {
        assertEquals(ExtensionOriginUtils.ORIGIN_EXTERNAL,
            ExtensionOriginUtils.originLabel(ObjectBelonging.NATIVE, false, true));
        // The external answer wins even if something claimed extension too - there is no
        // project that is both, and "external" is the one that names the owner.
        assertEquals(ExtensionOriginUtils.ORIGIN_EXTERNAL,
            ExtensionOriginUtils.originLabel(ObjectBelonging.ADOPTED, true, true));
        // The two-argument form is unchanged for every existing caller.
        assertEquals(ExtensionOriginUtils.originLabel(ObjectBelonging.NATIVE, true),
            ExtensionOriginUtils.originLabel(ObjectBelonging.NATIVE, true, false));
        assertEquals(ExtensionOriginUtils.ORIGIN_CORE,
            ExtensionOriginUtils.originLabel(ObjectBelonging.NATIVE, false, false));
    }

    /**
     * The manifest is the PERMANENT proof of a dependent project's link, because a null runtime
     * parent also means "not wired yet" or "parent not accessible". These cases pin what counts as
     * proof: only a complete manifest without Base-Project may be read as genuinely unlinked.
     */
    @Test
    public void declaredBaseProjectReadsTheManifest() throws Exception
    {
        assertEquals(ExtensionOriginUtils.DeclaredBaseProject.NONE,
            ExtensionOriginUtils.readDeclaredBaseProject(projectWithManifest(
                "Manifest-Version: 1.0\nRuntime-Version: 8.3.27\n"))); //$NON-NLS-1$
        assertEquals(ExtensionOriginUtils.DeclaredBaseProject.DECLARED,
            ExtensionOriginUtils.readDeclaredBaseProject(projectWithManifest(
                "Manifest-Version: 1.0\nRuntime-Version: 8.3.27\nBase-Project: Base\n"))); //$NON-NLS-1$
    }

    /**
     * A manifest rewritten during a save, refresh or git checkout can be read back truncated. A
     * truncation that lands past the mandatory headers but before Base-Project parses CLEANLY into a
     * map that merely looks unlinked - so the mandatory headers must be checked, and a blank
     * Base-Project value must not read as a declaration either.
     */
    @Test
    public void incompleteManifestIsUnreadableRatherThanUnlinked() throws Exception
    {
        assertEquals(ExtensionOriginUtils.DeclaredBaseProject.UNREADABLE,
            ExtensionOriginUtils.readDeclaredBaseProject(projectWithManifest(""))); //$NON-NLS-1$
        assertEquals(ExtensionOriginUtils.DeclaredBaseProject.UNREADABLE,
            ExtensionOriginUtils.readDeclaredBaseProject(
                projectWithManifest("Manifest-Version: 1.0\n"))); //$NON-NLS-1$
        assertEquals(ExtensionOriginUtils.DeclaredBaseProject.UNREADABLE,
            ExtensionOriginUtils.readDeclaredBaseProject(
                projectWithManifest("Runtime-Version: 8.3.27\n"))); //$NON-NLS-1$
    }

    /** A manifest that changes under the read proves nothing about the link. */
    @Test
    public void manifestWrittenDuringTheReadIsUnreadable() throws Exception
    {
        IProject project = projectWithManifest(
            "Manifest-Version: 1.0\nRuntime-Version: 8.3.27\n"); //$NON-NLS-1$
        IFile manifest = project.getFile(ProjectManifest.DT_PROJECT_MANIFEST);
        when(manifest.getModificationStamp()).thenReturn(1L, 2L);

        assertEquals(ExtensionOriginUtils.DeclaredBaseProject.UNREADABLE,
            ExtensionOriginUtils.readDeclaredBaseProject(project));
    }

    /** A missing manifest is a malformed project, not an unlinked one. */
    @Test
    public void missingManifestIsUnreadable()
    {
        IProject project = mock(IProject.class);
        IFile manifest = mock(IFile.class);
        when(manifest.exists()).thenReturn(false);
        when(project.getFile(ProjectManifest.DT_PROJECT_MANIFEST)).thenReturn(manifest);

        assertEquals(ExtensionOriginUtils.DeclaredBaseProject.UNREADABLE,
            ExtensionOriginUtils.readDeclaredBaseProject(project));
        assertEquals(ExtensionOriginUtils.DeclaredBaseProject.UNREADABLE,
            ExtensionOriginUtils.readDeclaredBaseProject(null));
    }

    private static IProject projectWithManifest(String content) throws Exception
    {
        IProject project = mock(IProject.class);
        IFile manifest = mock(IFile.class);
        when(manifest.exists()).thenReturn(true);
        when(manifest.getModificationStamp()).thenReturn(42L);
        when(manifest.getContents(true)).thenAnswer(invocation -> new ByteArrayInputStream(
            content.getBytes(StandardCharsets.UTF_8)));
        when(project.getName()).thenReturn("Probe"); //$NON-NLS-1$
        when(project.getFile(ProjectManifest.DT_PROJECT_MANIFEST)).thenReturn(manifest);
        return project;
    }

    /**
     * The workspace modification stamp does not move for a rewrite performed outside Eclipse, so a
     * single read can land mid-write with the stamp unchanged. Two reads that disagree prove the
     * manifest is in flight.
     */
    @Test
    public void manifestThatDiffersBetweenReadsIsUnreadable() throws Exception
    {
        IProject project = mock(IProject.class);
        IFile manifest = mock(IFile.class);
        when(manifest.exists()).thenReturn(true);
        when(manifest.getModificationStamp()).thenReturn(42L);
        when(project.getName()).thenReturn("Probe"); //$NON-NLS-1$
        when(project.getFile(ProjectManifest.DT_PROJECT_MANIFEST)).thenReturn(manifest);
        when(manifest.getContents(true)).thenReturn(
            new ByteArrayInputStream(
                "Manifest-Version: 1.0\nRuntime-Version: 8.3.27\n".getBytes(StandardCharsets.UTF_8)), //$NON-NLS-1$
            new ByteArrayInputStream(
                ("Manifest-Version: 1.0\nRuntime-Version: 8.3.27\n" //$NON-NLS-1$
                    + "Base-Project: Base\n").getBytes(StandardCharsets.UTF_8))); //$NON-NLS-1$

        assertEquals(ExtensionOriginUtils.DeclaredBaseProject.UNREADABLE,
            ExtensionOriginUtils.readDeclaredBaseProject(project));
    }
}
