/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.model.FileConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.e1c.g5.dt.applications.IApplication;

/**
 * The branch-binding decision of {@code update_database} (#459): a target is refused only when
 * the current branch binds infobases and the target's infobase is not one of them.
 */
public class BranchInfobaseBindingTest
{
    private static InfobaseReference ref(String name, UUID uuid)
    {
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getName()).thenReturn(name);
        when(ref.getUuid()).thenReturn(uuid);
        return ref;
    }

    private static Optional<IInfobaseAssociation> bound(InfobaseReference... infobases)
    {
        IInfobaseAssociation association = mock(IInfobaseAssociation.class);
        when(association.getInfobases()).thenReturn(Arrays.asList(infobases));
        return Optional.of(association);
    }

    @Test
    public void testAUuidMatchIsFoundBeforeAnyDirectoryProbe()
    {
        UUID targetUuid = UUID.randomUUID();
        InfobaseReference unreachable = fileRef("Dead", UUID.randomUUID(), //$NON-NLS-1$
            Paths.get("X:", "dead-share", "Base").toString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String refusal = BranchInfobaseBinding.decide(bound(unreachable, ref("Target", targetUuid)), //$NON-NLS-1$
            ref("Target", targetUuid), Paths.get("X:", "server-db"), "Proj", "App", "ServerApplication.S", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            (a, b) -> {
                throw new AssertionError("no probe may run when a UUID already matches"); //$NON-NLS-1$
            }, 5_000L);
        assertNull(refusal);
    }

    @Test
    public void testNoBindingIsNotAConflict()
    {
        assertNull(BranchInfobaseBinding.decide(Optional.empty(), ref("A", UUID.randomUUID()), null, //$NON-NLS-1$
            "P", "App", "ServerApplication.S")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testAnAssociationWithoutInfobasesIsNotAConflict()
    {
        IInfobaseAssociation association = mock(IInfobaseAssociation.class);
        when(association.getInfobases()).thenReturn(Collections.emptyList());
        assertNull(BranchInfobaseBinding.decide(Optional.of(association), ref("A", UUID.randomUUID()), null, //$NON-NLS-1$
            "P", "App", "id")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testBoundTargetPassesMatchedByUuidNotByName()
    {
        UUID uuid = UUID.randomUUID();
        // A standalone server reports its own web reference: same UUID, different display name.
        assertNull(BranchInfobaseBinding.decide(bound(ref("Other", UUID.randomUUID()), ref("Registered", uuid)), //$NON-NLS-1$ //$NON-NLS-2$
            ref("Server view", uuid), null, "P", "App", "ServerApplication.S")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void testSameNameDifferentUuidIsRefused()
    {
        assertNotNull(BranchInfobaseBinding.decide(bound(ref("Base", UUID.randomUUID())), //$NON-NLS-1$
            ref("Base", UUID.randomUUID()), null, "P", "App", "id")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void testUnboundTargetIsRefusedWithTheWayOut()
    {
        String refusal = BranchInfobaseBinding.decide(bound(ref("BranchB", UUID.randomUUID())), //$NON-NLS-1$
            ref("BaseA", UUID.randomUUID()), null, "Proj", "Server A", "ServerApplication.Server A"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("'BaseA'")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("bound: BranchB")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("ServerApplication.Server A")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("'Proj'")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Nothing was updated")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("get_applications")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("ignoreBranchBinding=true")); //$NON-NLS-1$
    }

    @Test
    public void testUnresolvableTargetUnderABindingIsRefused()
    {
        String refusal = BranchInfobaseBinding.decide(bound(ref("BranchB", UUID.randomUUID())), null, null, //$NON-NLS-1$
            "P", "App", "id"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("could not be resolved")); //$NON-NLS-1$
    }

    private static InfobaseReference fileRef(String name, UUID uuid, String dir)
    {
        InfobaseReference ref = ref(name, uuid);
        FileConnectionString cs = mock(FileConnectionString.class);
        when(cs.getFile()).thenReturn(dir);
        when(ref.getConnectionString()).thenReturn(cs);
        return ref;
    }

    private static String decideByDirectory(String boundDir, Path targetDir)
    {
        return BranchInfobaseBinding.decide(bound(fileRef("BaseA", UUID.randomUUID(), boundDir)), //$NON-NLS-1$
            ref("Server view", UUID.randomUUID()), targetDir, "P", "App", "ServerApplication.S"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void testServerOverTheSameExistingDirectoryUnderAnotherUuidPasses() throws Exception
    {
        // A standalone server registered over an existing file infobase gets a fresh UUID.
        Path dir = Files.createTempDirectory("ib459"); //$NON-NLS-1$
        try
        {
            assertNull(decideByDirectory(dir.toString(), dir));
        }
        finally
        {
            Files.deleteIfExists(dir);
        }
    }

    @Test
    public void testSameDirectorySpelledDifferentlyPassesByFileSystemIdentity() throws Exception
    {
        Path parent = Files.createTempDirectory("ib459"); //$NON-NLS-1$
        Path dir = Files.createDirectory(parent.resolve("BaseA")); //$NON-NLS-1$
        try
        {
            String trailing = dir.toString() + File.separator;
            Path viaDotDot = dir.resolve("..").resolve("BaseA"); //$NON-NLS-1$ //$NON-NLS-2$
            assertNull(decideByDirectory(trailing, viaDotDot));
        }
        finally
        {
            Files.deleteIfExists(dir);
            Files.deleteIfExists(parent);
        }
    }

    @Test
    public void testTwoDistinctExistingDirectoriesAreRefused() throws Exception
    {
        Path a = Files.createTempDirectory("ib459a"); //$NON-NLS-1$
        Path b = Files.createTempDirectory("ib459b"); //$NON-NLS-1$
        try
        {
            assertNotNull(decideByDirectory(a.toString(), b));
        }
        finally
        {
            Files.deleteIfExists(a);
            Files.deleteIfExists(b);
        }
    }

    @Test
    public void testMissingDirectoryIsNotProvenEvenWhenTheSpellingIsEqual() throws Exception
    {
        Path parent = Files.createTempDirectory("ib459"); //$NON-NLS-1$
        Path missing = parent.resolve("gone"); //$NON-NLS-1$
        try
        {
            assertNotNull(decideByDirectory(missing.toString(), missing));
        }
        finally
        {
            Files.deleteIfExists(parent);
        }
    }

    @Test
    public void testUnknownTargetDirectoryUnderAnotherUuidIsRefused() throws Exception
    {
        // No provable identity: an RDBMS-backed server or an unreadable configuration.
        Path dir = Files.createTempDirectory("ib459"); //$NON-NLS-1$
        try
        {
            assertNotNull(decideByDirectory(dir.toString(), null));
        }
        finally
        {
            Files.deleteIfExists(dir);
        }
    }

    @Test
    public void testNonFileBoundReferenceNeverMatchesByDirectory() throws Exception
    {
        Path dir = Files.createTempDirectory("ib459"); //$NON-NLS-1$
        try
        {
            assertNotNull(BranchInfobaseBinding.decide(bound(ref("Web", UUID.randomUUID())), //$NON-NLS-1$
                ref("Server view", UUID.randomUUID()), dir, "P", "App", "ServerApplication.S")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        finally
        {
            Files.deleteIfExists(dir);
        }
    }

    @Test
    public void testAThrowingBindingReadBecomesTheUnreadableRefusal()
    {
        IInfobaseAssociation association = mock(IInfobaseAssociation.class);
        when(association.getInfobases()).thenThrow(new IllegalStateException("store closed")); //$NON-NLS-1$
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("Proj"); //$NON-NLS-1$
        when(manager.getAssociation(project)).thenReturn(Optional.of(association));
        String refusal = BranchInfobaseBinding.refusalOrNull(manager, project, mock(IApplication.class), "id"); //$NON-NLS-1$
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("could not be read (store closed)")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("ignoreBranchBinding=true")); //$NON-NLS-1$
    }

    @Test
    public void testAThrowingAssociationLookupBecomesTheUnreadableRefusal()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("Proj"); //$NON-NLS-1$
        when(manager.getAssociation(project)).thenThrow(new IllegalStateException("no context")); //$NON-NLS-1$
        String refusal = BranchInfobaseBinding.refusalOrNull(manager, project, mock(IApplication.class), "id"); //$NON-NLS-1$
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("could not be read (no context)")); //$NON-NLS-1$
    }

    @Test
    public void testAProbeThatDoesNotAnswerInTimeRefusesNamingThePaths() throws Exception
    {
        Path target = Paths.get("X:", "unreachable", "server-db"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Path bound = Paths.get("X:", "unreachable", "BaseA"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        CountDownLatch release = new CountDownLatch(1);
        try
        {
            String refusal = BranchInfobaseBinding.decide(bound(fileRef("BaseA", UUID.randomUUID(), bound.toString())), //$NON-NLS-1$
                ref("Server view", UUID.randomUUID()), target, "Proj", "App", "ServerApplication.S", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                (a, b) -> {
                    try
                    {
                        return release.await(30, TimeUnit.SECONDS);
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }, 200L);
            assertNotNull("an unanswered probe is not a match", refusal); //$NON-NLS-1$
            assertTrue(refusal, refusal.contains("did not answer within 200 ms")); //$NON-NLS-1$
            assertTrue(refusal, refusal.contains(target.toString()));
            assertTrue(refusal, refusal.contains(bound.toString()));
            assertTrue(refusal, refusal.contains("Nothing was updated")); //$NON-NLS-1$
            assertTrue(refusal, refusal.contains("ignoreBranchBinding=true")); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
        }
    }

    @Test
    public void testSeveralDeadSharesShareOneDeadline()
    {
        CountDownLatch release = new CountDownLatch(1);
        try
        {
            long started = System.nanoTime();
            String refusal = BranchInfobaseBinding.decide(
                bound(fileRef("DeadA", UUID.randomUUID(), Paths.get("X:", "a").toString()), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    fileRef("DeadB", UUID.randomUUID(), Paths.get("X:", "b").toString()), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    fileRef("DeadC", UUID.randomUUID(), Paths.get("X:", "c").toString())), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                ref("Server view", UUID.randomUUID()), Paths.get("X:", "server-db"), "Proj", "App", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "ServerApplication.S", (a, b) -> { //$NON-NLS-1$
                    try
                    {
                        return release.await(30, TimeUnit.SECONDS);
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }, 400L);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertNotNull(refusal);
            assertTrue("three dead shares must not wait three deadlines: " + elapsedMs + " ms", //$NON-NLS-1$ //$NON-NLS-2$
                elapsedMs < 1_000L);
        }
        finally
        {
            release.countDown();
        }
    }

    @Test
    public void testAProbeThatFailsIsNotAMatch()
    {
        Path dir = Paths.get("X:", "db"); //$NON-NLS-1$ //$NON-NLS-2$
        String refusal = BranchInfobaseBinding.decide(bound(fileRef("BaseA", UUID.randomUUID(), dir.toString())), //$NON-NLS-1$
            ref("Server view", UUID.randomUUID()), dir, "Proj", "App", "ServerApplication.S", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            (a, b) -> {
                throw new java.io.IOException("access denied"); //$NON-NLS-1$
            }, 5_000L);
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("not bound to the current Git branch")); //$NON-NLS-1$
    }
}
