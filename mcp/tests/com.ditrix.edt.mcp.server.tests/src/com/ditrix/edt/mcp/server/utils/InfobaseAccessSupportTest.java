/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAccessSettings;
import com._1c.g5.v8.dt.platform.services.model.InfobaseAccess;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.ditrix.edt.mcp.server.utils.InfobaseAccessSupport.StoreResult;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;

/**
 * Tests for {@link InfobaseAccessSupport#parseAccess(String)} — the access-kind argument parser
 * (#194) — and for the {@link InfobaseAccessSupport#storeCredentials(IApplication, String, String,
 * InfobaseAccess)} adapter-fallback decision added by issue #275. The full store/read path (Guice
 * injector -&gt; {@code IInfobaseAccessManager} -&gt; {@code updateSettings}) needs a live EDT and
 * is verified on the e2e stand.
 */
public class InfobaseAccessSupportTest
{
    @Test
    public void testOsAnyCaseSelectsOs()
    {
        assertEquals(InfobaseAccess.OS, InfobaseAccessSupport.parseAccess("OS")); //$NON-NLS-1$
        assertEquals(InfobaseAccess.OS, InfobaseAccessSupport.parseAccess("os")); //$NON-NLS-1$
        assertEquals(InfobaseAccess.OS, InfobaseAccessSupport.parseAccess("Os")); //$NON-NLS-1$
    }

    @Test
    public void testInfobaseExplicit()
    {
        assertEquals(InfobaseAccess.INFOBASE, InfobaseAccessSupport.parseAccess("INFOBASE")); //$NON-NLS-1$
        assertEquals(InfobaseAccess.INFOBASE, InfobaseAccessSupport.parseAccess("infobase")); //$NON-NLS-1$
    }

    @Test
    public void testNullEmptyAndUnknownDefaultToInfobase()
    {
        assertEquals(InfobaseAccess.INFOBASE, InfobaseAccessSupport.parseAccess(null));
        assertEquals(InfobaseAccess.INFOBASE, InfobaseAccessSupport.parseAccess("")); //$NON-NLS-1$
        assertEquals(InfobaseAccess.INFOBASE, InfobaseAccessSupport.parseAccess("whatever")); //$NON-NLS-1$
    }

    @Test
    public void testIsOsAccessAgreesWithParseAccess()
    {
        // isOsAccess (issue #359) answers the same question for the launch configuration's
        // client-user section that parseAccess answers for the infobase access settings. Two
        // independent spellings of "is this OS auth" would eventually disagree, and the launch
        // dialog would end up on a different radio than the stored settings - so it delegates,
        // and this pins that the two never diverge.
        for (String access : new String[] { null, "", "INFOBASE", "infobase", "OS", "os", "Os", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "whatever" }) //$NON-NLS-1$
        {
            assertEquals("isOsAccess must agree with parseAccess for '" + access + "'", //$NON-NLS-1$ //$NON-NLS-2$
                InfobaseAccess.OS == InfobaseAccessSupport.parseAccess(access),
                InfobaseAccessSupport.isOsAccess(access));
        }
    }

    @Test
    public void testAccessErrorAcceptsNullEmptyAndEnumValues()
    {
        assertNull(InfobaseAccessSupport.accessError(null));
        assertNull(InfobaseAccessSupport.accessError("")); //$NON-NLS-1$
        assertNull(InfobaseAccessSupport.accessError("INFOBASE")); //$NON-NLS-1$
        assertNull(InfobaseAccessSupport.accessError("infobase")); //$NON-NLS-1$
        assertNull(InfobaseAccessSupport.accessError("OS")); //$NON-NLS-1$
        assertNull(InfobaseAccessSupport.accessError("os")); //$NON-NLS-1$
    }

    @Test
    public void testAccessErrorRejectsOutOfEnumValue()
    {
        String err = InfobaseAccessSupport.accessError("OOPS"); //$NON-NLS-1$
        assertNotNull("a non-empty out-of-enum access must be rejected", err); //$NON-NLS-1$
        assertTrue("error must name the bad value", err.contains("OOPS")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must list the allowed kinds", //$NON-NLS-1$
            err.contains("INFOBASE") && err.contains("OS")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== #275: storeCredentials(IApplication) adapter fallback ====================
    // A non-IInfobaseApplication (e.g. a wst-server standalone-server application) must be adapted
    // to an InfobaseReference — first the application itself, then its module (moduleOfApplication)
    // — before being rejected. In this headless unit JVM no adapter factory is registered (no OSGi
    // platform running), so Adapters.adapt always misses; this exercises the adapter-miss rejection
    // path with its new, more actionable wording. The success path (adapter hits, updateSettings
    // commits) needs a live EDT and is verified on the e2e stand.

    @Test
    public void testStoreCredentialsForNonInfobaseApplicationWithNoAdapterRejectsActionably()
    {
        // A stub application that is NOT an IInfobaseApplication and exposes no getModule() (so the
        // module-fallback probe also misses) — mirrors a wst-server application in an environment
        // where Adapters.adapt cannot resolve it (no StandaloneServerAdapterFactory registered).
        IApplication app = mock(IApplication.class);
        when(app.getId()).thenReturn("wst-app-1"); //$NON-NLS-1$

        StoreResult result =
            InfobaseAccessSupport.storeCredentials(app, "Admin", "", InfobaseAccess.INFOBASE); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull("must reject when neither the app nor its module adapts to an InfobaseReference", //$NON-NLS-1$
            result.error());
        assertTrue("error must name the application id", result.error().contains("wst-app-1")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must mention infobases", result.error().toLowerCase().contains("infobase")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must mention standalone servers", //$NON-NLS-1$
            result.error().toLowerCase().contains("standalone")); //$NON-NLS-1$
    }

    @Test
    public void testModuleOfApplicationReturnsNullWhenNoGetModuleMethod()
    {
        // A plain IApplication (no getModule()) — the reflective probe must degrade to null, not throw.
        IApplication app = mock(IApplication.class);
        assertNull(InfobaseAccessSupport.moduleOfApplication(app));
    }

    @Test
    public void testModuleOfApplicationInvokesGetModuleWhenPresent()
    {
        // A stub exposing getModule() (mirrors a wst-server IServerApplication) — the reflective
        // probe must find and invoke it, returning the module verbatim.
        IApplication app = mock(IApplication.class, withSettings().extraInterfaces(StubModuleAccessor.class));
        Object module = new Object();
        when(((StubModuleAccessor)app).getModule()).thenReturn(module);

        assertSame(module, InfobaseAccessSupport.moduleOfApplication(app));
    }

    /** Stands in for {@code IServerApplication}'s {@code getModule()} accessor (not on {@link IApplication}). */
    public interface StubModuleAccessor
    {
        Object getModule();
    }

    // ==================== issue #281 phase 2: resolveInfobaseReference(IApplication) extraction ====================
    // storeCredentials(IApplication, ...) now delegates entirely to resolveInfobaseReference(IApplication);
    // these tests exercise the extracted resolver directly, covering the same three branches
    // (IInfobaseApplication fast path / adapter-miss / getInfobase() failure) it was extracted from.

    @Test
    public void testResolveInfobaseReferenceReturnsGetInfobaseForInfobaseApplication()
    {
        IInfobaseApplication app = mock(IInfobaseApplication.class);
        InfobaseReference ref = mock(InfobaseReference.class);
        when(app.getInfobase()).thenReturn(ref);

        assertSame("must return the IInfobaseApplication's own getInfobase() reference verbatim", //$NON-NLS-1$
            ref, InfobaseAccessSupport.resolveInfobaseReference(app));
    }

    @Test
    public void testResolveInfobaseReferenceReturnsNullWhenGetInfobaseThrows()
    {
        IInfobaseApplication app = mock(IInfobaseApplication.class);
        when(app.getId()).thenReturn("infobase-app-1"); //$NON-NLS-1$
        when(app.getInfobase()).thenThrow(new RuntimeException("boom")); //$NON-NLS-1$

        assertNull("a getInfobase() failure must degrade to null, never throw or crash the caller", //$NON-NLS-1$
            InfobaseAccessSupport.resolveInfobaseReference(app));
    }

    @Test
    public void testResolveInfobaseReferenceReturnsNullWhenGetInfobaseReturnsNull()
    {
        IInfobaseApplication app = mock(IInfobaseApplication.class);
        when(app.getInfobase()).thenReturn(null);

        assertNull(InfobaseAccessSupport.resolveInfobaseReference(app));
    }

    @Test
    public void testResolveInfobaseReferenceReturnsNullWhenNoAdapterMatches()
    {
        // A non-IInfobaseApplication with no getModule() and (in this headless unit JVM, no OSGi
        // adapter registry) no adapter hit either - mirrors the wst-server "adapter miss" scenario
        // storeCredentials's own test above already covers end-to-end.
        IApplication app = mock(IApplication.class);

        assertNull(InfobaseAccessSupport.resolveInfobaseReference(app));
    }

    @Test
    public void matchingCredentialReadBackIsVerified() throws Exception
    {
        InfobaseReference ref = mock(InfobaseReference.class);
        IInfobaseAccessManager manager = mock(IInfobaseAccessManager.class);
        when(manager.resolveSettings(ref)).thenReturn(new InfobaseAccessSettings(
            InfobaseAccess.INFOBASE, "Admin", "secret-value", "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        StoreResult result = InfobaseAccessSupport.storeCredentials(ref, "Admin", //$NON-NLS-1$
            "secret-value", InfobaseAccess.INFOBASE, manager); //$NON-NLS-1$

        assertNull(result.error());
        assertEquals(StoreResult.Verification.VERIFIED, result.verification());
        assertTrue(result.passwordMatched());
    }

    @Test
    public void committedWriteIsRecordedBeforeCredentialReadBackStarts() throws Exception
    {
        InfobaseReference ref = mock(InfobaseReference.class);
        IInfobaseAccessManager manager = mock(IInfobaseAccessManager.class);
        AtomicBoolean writeCommitted = new AtomicBoolean();
        doAnswer(invocation -> {
            assertTrue("the write boundary must be visible before resolveSettings begins", //$NON-NLS-1$
                writeCommitted.get());
            return new InfobaseAccessSettings(
                InfobaseAccess.INFOBASE, "Admin", "secret-value", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }).when(manager).resolveSettings(ref);

        StoreResult result = InfobaseAccessSupport.storeCredentials(ref, "Admin", //$NON-NLS-1$
            "secret-value", InfobaseAccess.INFOBASE, manager, //$NON-NLS-1$
            () -> writeCommitted.set(true));

        assertTrue(writeCommitted.get());
        assertEquals(StoreResult.Verification.VERIFIED, result.verification());
    }

    @Test
    public void cancelledBoundedStoreSkipsUpdateSettingsAtTheWriteBoundary() throws Exception
    {
        InfobaseReference ref = mock(InfobaseReference.class);
        IInfobaseAccessManager manager = mock(IInfobaseAccessManager.class);
        AtomicBoolean writeCommitted = new AtomicBoolean();
        String password = "must-not-be-written"; //$NON-NLS-1$

        StoreResult result = InfobaseAccessSupport.storeCredentials(ref, "Admin", password, //$NON-NLS-1$
            InfobaseAccess.INFOBASE, manager, () -> writeCommitted.set(true), () -> false);

        verify(manager, never()).updateSettings(any(InfobaseReference.class),
            any(InfobaseAccessSettings.class));
        assertFalse(writeCommitted.get());
        assertNotNull(result.error());
        assertTrue(result.error().contains("cancelled before the settings write")); //$NON-NLS-1$
        assertFalse("the cancellation result must not expose the password", //$NON-NLS-1$
            result.error().contains(password));
    }

    @Test
    public void interruptedBoundedStoreAlsoPreventsALateSettingsWrite() throws Exception
    {
        InfobaseReference ref = mock(InfobaseReference.class);
        IInfobaseAccessManager manager = mock(IInfobaseAccessManager.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<InfobaseAccessSupport.BoundedStoreResult<StoreResult>> answer =
            new AtomicReference<>();
        String password = "interrupted-secret"; //$NON-NLS-1$

        Thread caller = new Thread(() -> answer.set(
            InfobaseAccessSupport.runBoundedCredentialStore("test: interrupted store", //$NON-NLS-1$
                30_000L, (publish, writeCommitted, writeAllowed) -> {
                    started.countDown();
                    try
                    {
                        release.await(30, TimeUnit.SECONDS);
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                    }
                    try
                    {
                        publish.accept(InfobaseAccessSupport.storeCredentials(ref, "Admin", //$NON-NLS-1$
                            password, InfobaseAccess.INFOBASE, manager, writeCommitted,
                            writeAllowed));
                    }
                    finally
                    {
                        finished.countDown();
                    }
                })), "test: interrupt bounded credential caller"); //$NON-NLS-1$

        caller.start();
        try
        {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(5_000L);
            assertFalse(caller.isAlive());
            assertNotNull(answer.get());
            assertEquals(BoundedJob.Outcome.INTERRUPTED,
                answer.get().boundedResult().getOutcome());

            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            verify(manager, never()).updateSettings(any(InfobaseReference.class),
                any(InfobaseAccessSettings.class));
            assertNull("a cancelled worker result must not be published after the caller returns", //$NON-NLS-1$
                answer.get().publishedResult());
        }
        finally
        {
            release.countDown();
            caller.join(5_000L);
            finished.await(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void timedOutWriterRechecksPermissionAfterWaitingForTheInfobaseLock() throws Exception
    {
        InfobaseReference ref = mock(InfobaseReference.class);
        IInfobaseAccessManager manager = mock(IInfobaseAccessManager.class);
        CountDownLatch blockerEnteredWrite = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch lateWorkStarted = new CountDownLatch(1);
        CountDownLatch lateWorkFinished = new CountDownLatch(1);
        List<String> writtenUsers = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<InfobaseAccessSettings> current = new AtomicReference<>();
        doAnswer(invocation -> {
            InfobaseAccessSettings settings = invocation.getArgument(1);
            if ("Blocker".equals(settings.userName())) //$NON-NLS-1$
            {
                blockerEnteredWrite.countDown();
                releaseBlocker.await(30, TimeUnit.SECONDS);
            }
            writtenUsers.add(settings.userName());
            current.set(settings);
            return null;
        }).when(manager).updateSettings(any(InfobaseReference.class),
            any(InfobaseAccessSettings.class));
        when(manager.resolveSettings(ref)).thenAnswer(invocation -> current.get());

        AtomicReference<StoreResult> blockerResult = new AtomicReference<>();
        Thread blocker = new Thread(() -> blockerResult.set(
            InfobaseAccessSupport.storeCredentials(ref, "Blocker", "opaque-one", //$NON-NLS-1$ //$NON-NLS-2$
                InfobaseAccess.INFOBASE, manager)), "test: credential write blocker"); //$NON-NLS-1$
        blocker.start();
        try
        {
            assertTrue(blockerEnteredWrite.await(5, TimeUnit.SECONDS));

            InfobaseAccessSupport.BoundedStoreResult<StoreResult> late =
                InfobaseAccessSupport.runBoundedCredentialStore("late writer", 250L, //$NON-NLS-1$
                    (publish, writeCommitted, writeAllowed) -> {
                        lateWorkStarted.countDown();
                        try
                        {
                            publish.accept(InfobaseAccessSupport.storeCredentials(ref, "Late", //$NON-NLS-1$
                                "opaque-two", InfobaseAccess.INFOBASE, manager, //$NON-NLS-1$
                                writeCommitted, writeAllowed));
                        }
                        finally
                        {
                            lateWorkFinished.countDown();
                        }
                    });

            assertTrue(lateWorkStarted.await(5, TimeUnit.SECONDS));
            assertEquals(BoundedJob.Outcome.TIMED_OUT, late.boundedResult().getOutcome());
            assertNull(late.publishedResult());

            releaseBlocker.countDown();
            assertTrue(lateWorkFinished.await(5, TimeUnit.SECONDS));
            blocker.join(5_000L);
            assertFalse(blocker.isAlive());
            assertNotNull(blockerResult.get());

            StoreResult intended = InfobaseAccessSupport.storeCredentials(ref, "Intended", //$NON-NLS-1$
                "opaque-three", InfobaseAccess.INFOBASE, manager); //$NON-NLS-1$
            assertNull(intended.error());
            assertFalse("the old check-before-lock shape must not let the timed-out worker write", //$NON-NLS-1$
                writtenUsers.contains("Late")); //$NON-NLS-1$
            assertEquals(java.util.Arrays.asList("Blocker", "Intended"), writtenUsers); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            releaseBlocker.countDown();
            blocker.join(5_000L);
            lateWorkFinished.await(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void inProgressLateWriteCannotInterleaveWithASubsequentWrite() throws Exception
    {
        InfobaseReference earlierRef = mock(InfobaseReference.class);
        InfobaseReference laterRef = mock(InfobaseReference.class);
        UUID sharedInfobaseId = UUID.fromString("c36a7165-1bb2-4f64-b112-6b7b7ebd6710"); //$NON-NLS-1$
        when(earlierRef.getUuid()).thenReturn(sharedInfobaseId);
        when(laterRef.getUuid()).thenReturn(sharedInfobaseId);
        IInfobaseAccessManager manager = mock(IInfobaseAccessManager.class);
        CountDownLatch earlierEnteredWrite = new CountDownLatch(1);
        CountDownLatch releaseEarlier = new CountDownLatch(1);
        CountDownLatch laterEnteredWrite = new CountDownLatch(1);
        CountDownLatch earlierWorkFinished = new CountDownLatch(1);
        List<String> writtenUsers = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<InfobaseAccessSettings> current = new AtomicReference<>();
        doAnswer(invocation -> {
            InfobaseAccessSettings settings = invocation.getArgument(1);
            if ("Earlier".equals(settings.userName())) //$NON-NLS-1$
            {
                earlierEnteredWrite.countDown();
                releaseEarlier.await(30, TimeUnit.SECONDS);
            }
            else if ("Later".equals(settings.userName())) //$NON-NLS-1$
            {
                laterEnteredWrite.countDown();
            }
            writtenUsers.add(settings.userName());
            current.set(settings);
            return null;
        }).when(manager).updateSettings(any(InfobaseReference.class),
            any(InfobaseAccessSettings.class));
        when(manager.resolveSettings(any(InfobaseReference.class)))
            .thenAnswer(invocation -> current.get());

        InfobaseAccessSupport.BoundedStoreResult<StoreResult> earlier =
            InfobaseAccessSupport.runBoundedCredentialStore("earlier writer", 250L, //$NON-NLS-1$
                (publish, writeCommitted, writeAllowed) -> {
                    try
                    {
                        publish.accept(InfobaseAccessSupport.storeCredentials(earlierRef, "Earlier", //$NON-NLS-1$
                            "opaque-one", InfobaseAccess.INFOBASE, manager, //$NON-NLS-1$
                            writeCommitted, writeAllowed));
                    }
                    finally
                    {
                        earlierWorkFinished.countDown();
                    }
                });

        assertTrue(earlierEnteredWrite.await(5, TimeUnit.SECONDS));
        assertEquals(BoundedJob.Outcome.TIMED_OUT, earlier.boundedResult().getOutcome());
        AtomicReference<StoreResult> laterResult = new AtomicReference<>();
        Thread later = new Thread(() -> laterResult.set(
            InfobaseAccessSupport.storeCredentials(laterRef, "Later", "opaque-two", //$NON-NLS-1$ //$NON-NLS-2$
                InfobaseAccess.INFOBASE, manager)), "test: subsequent credential writer"); //$NON-NLS-1$
        later.start();
        try
        {
            assertFalse("the later write must not enter while the timed-out write is still active", //$NON-NLS-1$
                laterEnteredWrite.await(250, TimeUnit.MILLISECONDS));

            releaseEarlier.countDown();
            assertTrue(earlierWorkFinished.await(5, TimeUnit.SECONDS));
            later.join(5_000L);
            assertFalse(later.isAlive());
            assertNotNull(laterResult.get());
            assertNull(laterResult.get().error());
            assertEquals(java.util.Arrays.asList("Earlier", "Later"), writtenUsers); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("Later", current.get().userName()); //$NON-NLS-1$
            assertFalse("the earlier timed-out write must not overwrite the later intended value", //$NON-NLS-1$
                "Earlier".equals(current.get().userName())); //$NON-NLS-1$
        }
        finally
        {
            releaseEarlier.countDown();
            later.join(5_000L);
            earlierWorkFinished.await(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void updateFailureReportsItsHeadlineAndDistinctRootCause() throws Exception
    {
        InfobaseReference ref = mock(InfobaseReference.class);
        IInfobaseAccessManager manager = mock(IInfobaseAccessManager.class);
        RuntimeException failure = new RuntimeException("secure preferences write failed", //$NON-NLS-1$
            new IllegalStateException("secure storage is locked by another process")); //$NON-NLS-1$
        doThrow(failure).when(manager).updateSettings(
            org.mockito.ArgumentMatchers.eq(ref), any(InfobaseAccessSettings.class));

        StoreResult result = InfobaseAccessSupport.storeCredentials(ref, "Admin", //$NON-NLS-1$
            "secret-value", InfobaseAccess.INFOBASE, manager); //$NON-NLS-1$

        assertEquals("Failed to store infobase access settings: secure preferences write failed " //$NON-NLS-1$
            + "Caused by: secure storage is locked by another process", result.error()); //$NON-NLS-1$
    }

    @Test
    public void updateFailureScrubsEmbeddedPasswordButKeepsPlatformDiagnosis() throws Exception
    {
        InfobaseReference ref = mock(InfobaseReference.class);
        IInfobaseAccessManager manager = mock(IInfobaseAccessManager.class);
        String password = "credential-log-probe-secret"; //$NON-NLS-1$
        AtomicReference<CoreException> thrownFailure = new AtomicReference<>();
        doAnswer(invocation -> {
            IllegalStateException cause = new IllegalStateException(
                "master password unavailable after reading " + password); //$NON-NLS-1$
            cause.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("SecureStorageBackend", "unlock", //$NON-NLS-1$ //$NON-NLS-2$
                    "SecureStorageBackend.java", 41) //$NON-NLS-1$
            });
            CoreException failure = new CoreException(new Status(IStatus.ERROR, "test", //$NON-NLS-1$
                "secure storage rejected '" + password + "': keyring is locked", //$NON-NLS-1$ //$NON-NLS-2$
                cause));
            failure.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("CredentialSettingsWriter", "store", //$NON-NLS-1$ //$NON-NLS-2$
                    "CredentialSettingsWriter.java", 73) //$NON-NLS-1$
            });
            thrownFailure.set(failure);
            throw failure;
        }).when(manager).updateSettings(
            org.mockito.ArgumentMatchers.eq(ref), any(InfobaseAccessSettings.class));

        StoreResult result = InfobaseAccessSupport.storeCredentials(ref, "Admin", //$NON-NLS-1$
            password, InfobaseAccess.INFOBASE, manager);

        assertNotNull(result.error());
        assertFalse("the complete password must not be returned", result.error().contains(password)); //$NON-NLS-1$
        assertTrue("the keyring diagnosis must remain readable: " + result.error(), //$NON-NLS-1$
            result.error().contains("keyring is locked")); //$NON-NLS-1$
        assertTrue("the root cause must remain readable: " + result.error(), //$NON-NLS-1$
            result.error().contains("master password unavailable")); //$NON-NLS-1$
        assertTrue("the removed password must be visibly marked: " + result.error(), //$NON-NLS-1$
            result.error().contains("[REDACTED]")); //$NON-NLS-1$

        CoreException failure = thrownFailure.get();
        assertNotNull(failure);
        Throwable logged = InfobaseAccessSupport.scrubCredentialFailureForLog(failure, password);
        StringWriter rendered = new StringWriter();
        logged.printStackTrace(new PrintWriter(rendered));
        String logText = rendered.toString();
        assertFalse("the throwable attached to the log must also omit the password: " + logText, //$NON-NLS-1$
            logText.contains(password));
        assertTrue("the log must retain the platform exception type: " + logText, //$NON-NLS-1$
            logText.contains(CoreException.class.getName()));
        assertTrue("the log must retain the cause and its diagnosis: " + logText, //$NON-NLS-1$
            logText.contains(IllegalStateException.class.getName())
                && logText.contains("master password unavailable")); //$NON-NLS-1$
        assertTrue("the log must retain the platform failure's original stack frame: " + logText, //$NON-NLS-1$
            logText.contains("CredentialSettingsWriter.java:73")); //$NON-NLS-1$
        assertTrue("the log must retain the cause's original stack frame: " + logText, //$NON-NLS-1$
            logText.contains("SecureStorageBackend.java:41")); //$NON-NLS-1$
    }

    @Test
    public void updateFailureKeepsOrdinaryPlatformMessageIntact() throws Exception
    {
        InfobaseReference ref = mock(InfobaseReference.class);
        IInfobaseAccessManager manager = mock(IInfobaseAccessManager.class);
        String diagnostic = "secure storage is locked; unlock the keyring and retry"; //$NON-NLS-1$
        doThrow(new RuntimeException(diagnostic)).when(manager).updateSettings(
            org.mockito.ArgumentMatchers.eq(ref), any(InfobaseAccessSettings.class));

        StoreResult result = InfobaseAccessSupport.storeCredentials(ref, "Admin", //$NON-NLS-1$
            "unrelated-secret", InfobaseAccess.INFOBASE, manager); //$NON-NLS-1$

        assertEquals("Failed to store infobase access settings: " + diagnostic, result.error()); //$NON-NLS-1$
        assertTrue("the ordinary platform diagnosis must remain intact: " + result.error(), //$NON-NLS-1$
            result.error().contains(diagnostic));
        assertFalse("the password must remain absent from the ordinary diagnostic", //$NON-NLS-1$
            result.error().contains("unrelated-secret")); //$NON-NLS-1$
        assertFalse("no redaction marker is needed when the password is absent: " + result.error(), //$NON-NLS-1$
            result.error().contains("[REDACTED]")); //$NON-NLS-1$
    }

    @Test
    public void updateFailureWithEmptyPasswordDoesNotCorruptPlatformMessage() throws Exception
    {
        InfobaseReference ref = mock(InfobaseReference.class);
        IInfobaseAccessManager manager = mock(IInfobaseAccessManager.class);
        String diagnostic = "master password is missing; configure secure storage"; //$NON-NLS-1$
        doThrow(new RuntimeException(diagnostic)).when(manager).updateSettings(
            org.mockito.ArgumentMatchers.eq(ref), any(InfobaseAccessSettings.class));

        StoreResult result = InfobaseAccessSupport.storeCredentials(ref, "Admin", "", //$NON-NLS-1$ //$NON-NLS-2$
            InfobaseAccess.INFOBASE, manager);

        assertEquals("Failed to store infobase access settings: " + diagnostic, result.error()); //$NON-NLS-1$
        assertTrue("an empty password must leave the diagnosis readable: " + result.error(), //$NON-NLS-1$
            result.error().contains(diagnostic));
        assertFalse("an empty password must not inject redaction markers throughout the message", //$NON-NLS-1$
            result.error().contains("[REDACTED]")); //$NON-NLS-1$
        assertEquals("ordinary message", //$NON-NLS-1$
            InfobaseAccessSupport.scrubCredentialText("ordinary message", "")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ordinary message", //$NON-NLS-1$
            InfobaseAccessSupport.scrubCredentialText("ordinary message", "a")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void differingAccessIsAMismatch() throws Exception
    {
        InfobaseReference ref = mock(InfobaseReference.class);
        IInfobaseAccessManager manager = mock(IInfobaseAccessManager.class);
        when(manager.resolveSettings(ref)).thenReturn(new InfobaseAccessSettings(
            InfobaseAccess.OS, "Admin", "secret-value", "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        StoreResult result = InfobaseAccessSupport.storeCredentials(ref, "Admin", //$NON-NLS-1$
            "secret-value", InfobaseAccess.INFOBASE, manager); //$NON-NLS-1$

        assertEquals(StoreResult.Verification.MISMATCHED, result.verification());
        assertTrue(result.verificationReason().contains("access=INFOBASE")); //$NON-NLS-1$
        assertTrue(result.verificationReason().contains("access=OS")); //$NON-NLS-1$
        assertFalse(result.verificationReason().contains("secret-value")); //$NON-NLS-1$
    }

    @Test
    public void differingUserIsAMismatch() throws Exception
    {
        InfobaseReference ref = mock(InfobaseReference.class);
        IInfobaseAccessManager manager = mock(IInfobaseAccessManager.class);
        when(manager.resolveSettings(ref)).thenReturn(new InfobaseAccessSettings(
            InfobaseAccess.INFOBASE, "Other", "secret-value", "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        StoreResult result = InfobaseAccessSupport.storeCredentials(ref, "Admin", //$NON-NLS-1$
            "secret-value", InfobaseAccess.INFOBASE, manager); //$NON-NLS-1$

        assertEquals(StoreResult.Verification.MISMATCHED, result.verification());
        assertTrue(result.verificationReason().contains("user='Admin'")); //$NON-NLS-1$
        assertTrue(result.verificationReason().contains("user='Other'")); //$NON-NLS-1$
    }

    @Test
    public void differingPasswordIsReportedOnlyAsABoolean() throws Exception
    {
        InfobaseReference ref = mock(InfobaseReference.class);
        IInfobaseAccessManager manager = mock(IInfobaseAccessManager.class);
        when(manager.resolveSettings(ref)).thenReturn(new InfobaseAccessSettings(
            InfobaseAccess.INFOBASE, "Admin", "different-secret", "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        StoreResult result = InfobaseAccessSupport.storeCredentials(ref, "Admin", //$NON-NLS-1$
            "secret-value", InfobaseAccess.INFOBASE, manager); //$NON-NLS-1$

        assertEquals(StoreResult.Verification.MISMATCHED, result.verification());
        assertFalse(result.passwordMatched());
        assertTrue(result.verificationReason().contains("passwordMatched=false")); //$NON-NLS-1$
        assertFalse(result.verificationReason().contains("secret-value")); //$NON-NLS-1$
        assertFalse(result.verificationReason().contains("different-secret")); //$NON-NLS-1$
    }

    @Test
    public void defaultShapeReadBackIsNotVerifiable() throws Exception
    {
        InfobaseReference ref = mock(InfobaseReference.class);
        IInfobaseAccessManager manager = mock(IInfobaseAccessManager.class);
        when(manager.resolveSettings(ref)).thenReturn(new InfobaseAccessSettings(
            InfobaseAccess.OS, null, null, "")); //$NON-NLS-1$

        StoreResult result = InfobaseAccessSupport.storeCredentials(ref, "", "", //$NON-NLS-1$ //$NON-NLS-2$
            InfobaseAccess.OS, manager);

        assertEquals(StoreResult.Verification.NOT_VERIFIABLE, result.verification());
        assertNull(result.passwordMatched());
        assertTrue(result.verificationReason().contains("default fallback")); //$NON-NLS-1$
        assertTrue(result.verificationReason().contains("cannot prove")); //$NON-NLS-1$
    }
}
