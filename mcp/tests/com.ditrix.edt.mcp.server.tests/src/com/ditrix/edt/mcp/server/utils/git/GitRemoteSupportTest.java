/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.git;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.git.GitRemoteSupport.RemoteOutcome;

/**
 * Tests {@link GitRemoteSupport}'s pure, thread-free surface: the {@link RemoteOutcome} value object
 * (built directly via the package-private test factory {@link RemoteOutcome#forTest}, bypassing
 * {@link GitRemoteSupport#run} so NO {@link org.eclipse.core.runtime.jobs.Job} and NO live network op ever
 * runs here) and the non-interactive {@link CredentialsProvider} factory (the unattended-safety guarantee:
 * no provider it returns can ever open a modal dialog).
 */
public class GitRemoteSupportTest
{
    @Test
    public void testRanToCompletionOutcomeExposesOptionalRefreshWarning()
    {
        RemoteOutcome outcome = RemoteOutcome.forTest("workspace refresh failed", false, false, null); //$NON-NLS-1$

        assertTrue("a normal outcome ran to completion", outcome.ranToCompletion()); //$NON-NLS-1$
        assertEquals("workspace refresh failed", outcome.refreshWarning()); //$NON-NLS-1$
        assertFalse(outcome.timedOut());
        assertFalse(outcome.interrupted());
        assertNull(outcome.jobError());
    }

    @Test
    public void testRanToCompletionOutcomeWithNoRefreshWarningIsNull()
    {
        RemoteOutcome outcome = RemoteOutcome.forTest(null, false, false, null);

        assertTrue(outcome.ranToCompletion());
        assertNull("no refresh warning means no refresh failure occurred", outcome.refreshWarning()); //$NON-NLS-1$
    }

    @Test
    public void testTimedOutOutcomeDidNotRunToCompletion()
    {
        RemoteOutcome outcome = RemoteOutcome.forTest(null, true, false, null);

        assertFalse("a timed-out Job never ran to completion", outcome.ranToCompletion()); //$NON-NLS-1$
        assertTrue(outcome.timedOut());
        assertFalse(outcome.interrupted());
        assertNull(outcome.jobError());
    }

    @Test
    public void testInterruptedOutcomeDidNotRunToCompletion()
    {
        RemoteOutcome outcome = RemoteOutcome.forTest(null, false, true, null);

        assertFalse("an interrupted wait never ran to completion", outcome.ranToCompletion()); //$NON-NLS-1$
        assertFalse(outcome.timedOut());
        assertTrue(outcome.interrupted());
        assertNull(outcome.jobError());
    }

    @Test
    public void testJobErrorOutcomeDidNotRunToCompletionAndCarriesTheException()
    {
        Exception boom = new IllegalStateException("push blew up"); //$NON-NLS-1$
        RemoteOutcome outcome = RemoteOutcome.forTest(null, false, false, boom);

        assertFalse("a Job that threw never ran to completion", outcome.ranToCompletion()); //$NON-NLS-1$
        assertFalse(outcome.timedOut());
        assertFalse(outcome.interrupted());
        assertSame("the thrown exception must round-trip unchanged", boom, outcome.jobError()); //$NON-NLS-1$
    }

    @Test
    public void testNoCredentialsYieldsNonInteractiveFailFastProvider()
    {
        CredentialsProvider provider = GitRemoteSupport.credentialsProvider(null, null);

        assertNotNull(provider);
        assertFalse("the fallback provider must never prompt", provider.isInteractive()); //$NON-NLS-1$
        assertFalse("it must refuse to supply any credential item (fail fast, no dialog)", //$NON-NLS-1$
            provider.get(null));
        assertFalse("it must report it supports no credential items", provider.supports()); //$NON-NLS-1$
    }

    @Test
    public void testBlankCredentialsAlsoYieldNonInteractiveProvider()
    {
        CredentialsProvider provider = GitRemoteSupport.credentialsProvider("   ", "\t"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse("blank username/token are treated as absent", provider.isInteractive()); //$NON-NLS-1$
        assertFalse(provider.get(null));
    }

    @Test
    public void testSuppliedCredentialsYieldNonInteractiveUsernamePasswordProvider()
    {
        CredentialsProvider provider = GitRemoteSupport.credentialsProvider("alice", "ghp_token"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("supplied credentials build a UsernamePasswordCredentialsProvider", //$NON-NLS-1$
            provider instanceof UsernamePasswordCredentialsProvider);
        assertFalse("a stored-value provider still never prompts", provider.isInteractive()); //$NON-NLS-1$
    }

    @Test
    public void testTokenOnlyStillBuildsAUsernamePasswordProvider()
    {
        CredentialsProvider provider = GitRemoteSupport.credentialsProvider(null, "ghp_token"); //$NON-NLS-1$

        assertTrue("a token alone is enough to build a credentials provider", //$NON-NLS-1$
            provider instanceof UsernamePasswordCredentialsProvider);
        assertFalse(provider.isInteractive());
    }
}
