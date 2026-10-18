/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.git;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;
import com.ditrix.edt.mcp.server.utils.TemporaryDirectories;

/**
 * Tests for {@link AssociationStoreFile}: the file is located exactly as EDT's
 * {@code ProjectSettingsStoreLocationProvider} + {@code InfobaseAssociationContextPathConverter} locate it, and
 * every entry EDT's read hides is reported.
 */
public class AssociationStoreFileTest
{
    private static final UUID TARGET = UUID.fromString("11111111-1111-1111-1111-111111111111"); //$NON-NLS-1$

    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222"); //$NON-NLS-1$

    private Path store;

    @Before
    public void createStore() throws IOException
    {
        store = Files.createTempDirectory("store-file"); //$NON-NLS-1$
    }

    @After
    public void deleteStore() throws IOException, InterruptedException
    {
        TemporaryDirectories.deleteRecursively(store);
    }

    private IProject projectWithStore()
    {
        IProject project = mock(IProject.class);
        when(project.getWorkingLocation("com._1c.g5.v8.dt.platform.services.core")) //$NON-NLS-1$
            .thenReturn(org.eclipse.core.runtime.Path.fromOSString(store.toString()));
        return project;
    }

    private Path write(String... lines) throws IOException
    {
        Path file = store.resolve("AssociationData.properties"); //$NON-NLS-1$
        Files.write(file, Arrays.asList(lines), StandardCharsets.ISO_8859_1);
        return file;
    }

    @Test
    public void aBranchContextIsItsRefAsANestedPath()
    {
        Path file = AssociationStoreFile.locate(projectWithStore(), InfobaseAssociationContext.of("refs/heads/feature/x")); //$NON-NLS-1$

        assertEquals(store.resolve("refs").resolve("heads").resolve("feature").resolve("x") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .resolve("AssociationData.properties"), file); //$NON-NLS-1$
    }

    @Test
    public void theUnnamedContextIsTheDefaultDirectory()
    {
        Path file = AssociationStoreFile.locate(projectWithStore(), InfobaseAssociationContext.empty());

        assertEquals(store.resolve(".default").resolve("AssociationData.properties"), file); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aProjectWithoutAWorkingLocationHasNoFile()
    {
        assertNull(AssociationStoreFile.locate(mock(IProject.class), InfobaseAssociationContext.of("refs/heads/x"))); //$NON-NLS-1$
    }

    @Test
    public void aMissingFileHoldsNothing() throws IOException
    {
        assertTrue(AssociationStoreFile.entriesBesides(store.resolve("absent.properties"), Set.of()).isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void theTargetInBothKeysAndCommentsAreNothingElse() throws IOException
    {
        Path file = write("#Infobase association data of the project", "Infobases=" + TARGET, //$NON-NLS-1$ //$NON-NLS-2$
            "DefaultInfobase=" + TARGET); //$NON-NLS-1$

        assertTrue(AssociationStoreFile.entriesBesides(file, Set.of(TARGET)).isEmpty());
    }

    @Test
    public void anEmptyInfobasesValueHoldsNothing() throws IOException
    {
        assertTrue(AssociationStoreFile.entriesBesides(write("Infobases="), Set.of()).isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void everyOtherEntryIsReported() throws IOException
    {
        Path file = write("Infobases=" + TARGET + ";" + OTHER + ";not-a-uuid", "DefaultInfobase=" + OTHER, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Unknown=1"); //$NON-NLS-1$

        List<String> others = AssociationStoreFile.entriesBesides(file, Set.of(TARGET));

        // Keys in sorted order: DefaultInfobase, Infobases (its entries in file order), Unknown.
        assertEquals(List.of("default-infobase entry " + OTHER, "infobase entry " + OTHER, //$NON-NLS-1$ //$NON-NLS-2$
            "unreadable infobase entry 'not-a-uuid'", "entry 'Unknown'"), others); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aKnownSiblingIsNotReportedAgain() throws IOException
    {
        Path file = write("Infobases=" + TARGET + ";" + OTHER); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(AssociationStoreFile.entriesBesides(file, Set.of(TARGET, OTHER)).isEmpty());
    }

    @Test
    public void aLongValueIsCut() throws IOException
    {
        Path file = write("Infobases=" + "x".repeat(200)); //$NON-NLS-1$ //$NON-NLS-2$

        List<String> others = AssociationStoreFile.entriesBesides(file, Set.of());

        assertEquals(List.of("unreadable infobase entry '" + "x".repeat(80) + "...'"), others); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
