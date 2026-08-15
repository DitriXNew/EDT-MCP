/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.model.InfobaseAccess;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;

/**
 * Tests for {@link SetInfobaseCredentialsTool}.
 * <p>
 * Covers tool metadata, schema parity, and the argument-validation guards that execute BEFORE any
 * workspace or platform-services access. The real store path (resolve application -&gt;
 * {@code IInfobaseApplication.getInfobase()} -&gt; {@code IInfobaseAccessManager.updateSettings})
 * needs a live EDT and is covered by the e2e suite.
 * <p>
 * Issue #359 added a second consumer: the launched 1C CLIENT, which reads its user from the launch
 * configuration's own attributes rather than from the infobase access settings the designer agent
 * uses. Its decision seam {@link SetInfobaseCredentialsTool#configureClient} is driven here for
 * both target shapes, and {@link #theClientIsConfiguredOnlyAfterTheAgentCredentialsCommit} pins
 * that the tool really calls it - the helper being covered while the call site quietly stopped
 * using it is the exact way this class of fix goes green while staying broken.
 */
public class SetInfobaseCredentialsToolTest
{
    @Test
    public void testName()
    {
        assertEquals("set_infobase_credentials", new SetInfobaseCredentialsTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(SetInfobaseCredentialsTool.NAME, new SetInfobaseCredentialsTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new SetInfobaseCredentialsTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new SetInfobaseCredentialsTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
        assertTrue("description must mention the credentials purpose", //$NON-NLS-1$
            desc.toLowerCase().contains("credential")); //$NON-NLS-1$
        assertTrue("description must steer to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('set_infobase_credentials')")); //$NON-NLS-1$
    }

    @Test
    public void testInvalidAccessIsError()
    {
        // An out-of-enum access value is rejected before any service lookup (headless-safe),
        // naming the bad value and the allowed kinds — the schema enum is advisory for clients.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("applicationId", "someApp"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("access", "OOPS"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SetInfobaseCredentialsTool().execute(params);
        assertNotNull(result);
        assertTrue("invalid access must be an error", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must name the bad value", result.contains("OOPS")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must list allowed kinds", //$NON-NLS-1$
            result.contains("INFOBASE") && result.contains("OS")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInputSchemaDeclaresAllParameters()
    {
        String schema = new SetInfobaseCredentialsTool().getInputSchema();
        assertNotNull(schema);
        assertTrue("schema must declare launchConfigurationName", //$NON-NLS-1$
            schema.contains("\"launchConfigurationName\"")); //$NON-NLS-1$
        assertTrue("schema must declare projectName", schema.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare applicationId", schema.contains("\"applicationId\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare user", schema.contains("\"user\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare password", schema.contains("\"password\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare access", schema.contains("\"access\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAccessIsClosedEnum()
    {
        String schema = new SetInfobaseCredentialsTool().getInputSchema();
        assertTrue("access must be a closed enum", schema.contains("\"enum\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("access must advertise INFOBASE", schema.contains("\"INFOBASE\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("access must advertise OS", schema.contains("\"OS\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNoParameterIsRequired()
    {
        // Targeting is launchConfigurationName OR projectName+applicationId, so no single parameter
        // is statically required; user/password are optional too (empty = no-user credentials).
        String schema = new SetInfobaseCredentialsTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        if (requiredIdx >= 0)
        {
            int open = schema.indexOf('[', requiredIdx);
            int close = schema.indexOf(']', open);
            if (open >= 0 && close > open)
            {
                String requiredBlock = schema.substring(open, close + 1);
                assertTrue("user must NOT be statically required", //$NON-NLS-1$
                    !requiredBlock.contains("\"user\"")); //$NON-NLS-1$
                assertTrue("projectName must NOT be statically required", //$NON-NLS-1$
                    !requiredBlock.contains("\"projectName\"")); //$NON-NLS-1$
            }
        }
    }

    @Test
    public void testOutputSchemaDeclaresExpectedFields()
    {
        String schema = new SetInfobaseCredentialsTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue("outputSchema must declare success", schema.contains("\"success\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare applicationId", schema.contains("\"applicationId\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare user", schema.contains("\"user\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare access", schema.contains("\"access\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare passwordSet", schema.contains("\"passwordSet\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare clientConfigured (issue #359): the caller has no " //$NON-NLS-1$
            + "other way to tell whether the launched client was covered", //$NON-NLS-1$
            schema.contains("\"clientConfigured\"")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testMissingTargetIsError()
    {
        // No launchConfigurationName and no projectName -> projectName is named first.
        Map<String, String> params = new HashMap<>();
        params.put("user", "Admin"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SetInfobaseCredentialsTool().execute(params);
        assertNotNull(result);
        assertTrue("missing target must name projectName", //$NON-NLS-1$
            result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingApplicationIdIsError()
    {
        // projectName given but no applicationId and no launchConfigurationName.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("user", "Admin"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SetInfobaseCredentialsTool().execute(params);
        assertNotNull(result);
        assertTrue("missing applicationId must produce an error", //$NON-NLS-1$
            result.contains("applicationId is required")); //$NON-NLS-1$
    }

    // ==================== Pure storeOutcome seam (no live EDT, no jobs framework) ====================

    /** A representative SUCCESS JSON the bounded Job records the instant updateSettings commits. */
    private static final String SUCCESS_JSON =
        "{\"success\":true,\"project\":\"TestProject\",\"applicationId\":\"app1\"," //$NON-NLS-1$
            + "\"applicationName\":\"My Infobase\",\"user\":\"Admin\",\"access\":\"INFOBASE\"," //$NON-NLS-1$
            + "\"passwordSet\":true,\"message\":\"Stored ...\"}"; //$NON-NLS-1$

    @Test
    public void testStoreOutcomeFinishedReturnsRecordedSuccess()
    {
        // A clean finish returns whatever the Job recorded, verbatim.
        String result = SetInfobaseCredentialsTool.storeOutcome(true, SUCCESS_JSON, "TestProject", "app1"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(SUCCESS_JSON, result);
    }

    @Test
    public void testStoreOutcomeTimeoutWithRecordedSuccessReturnsSuccess()
    {
        // Persist-first guarantee: a timeout AFTER updateSettings committed still reports success.
        String result = SetInfobaseCredentialsTool.storeOutcome(false, SUCCESS_JSON, "TestProject", "app1"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a persisted success must survive a post-commit timeout", SUCCESS_JSON, result); //$NON-NLS-1$
    }

    @Test
    public void testStoreOutcomeTimeoutWithNoResultIsGracefulError()
    {
        // A timeout BEFORE the credentials committed yields a graceful, actionable error.
        String result = SetInfobaseCredentialsTool.storeOutcome(false, null, "TestProject", "app1"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(result);
        assertTrue("timeout-with-no-result must be an error", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must say it timed out", result.contains("timed out")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must name the application", result.contains("app1")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must name the project", result.contains("TestProject")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testStoreOutcomeFinishedWithNoResultIsGracefulError()
    {
        // A clean finish that recorded nothing must not hang or NPE — graceful "no result" error.
        String result = SetInfobaseCredentialsTool.storeOutcome(true, null, "TestProject", "app1"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(result);
        assertTrue("no-result finish must be an error", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must explain no result was produced", result.contains("no result")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testStoreOutcomeKeepsLowerCamelCaseOutputKeys()
    {
        // The success branch (recorded JSON returned verbatim) preserves the lowerCamelCase wire keys.
        String result = SetInfobaseCredentialsTool.storeOutcome(true, SUCCESS_JSON, "TestProject", "app1"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must keep applicationId", result.contains("\"applicationId\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must keep applicationName", result.contains("\"applicationName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must keep passwordSet", result.contains("\"passwordSet\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== The launched CLIENT (issue #359) ====================

    /** The launch configuration name a client-targeting call carries. */
    private static final String CONFIG_NAME = "TestConfiguration - thin client"; //$NON-NLS-1$

    /**
     * Ceiling for every latch below. It is a deadlock guard, not a timing assumption: each latch is
     * released by the test itself, so a wait that actually runs this long is a hang, not a slow box.
     */
    private static final int LATCH_TIMEOUT_SECONDS = 30;

    /** The caller is still waiting: the state every ordinary client write happens in. */
    private static AtomicBoolean stillWaiting()
    {
        return new AtomicBoolean(false);
    }

    /**
     * A LOCAL launch configuration (workspace metadata) handing out {@code copy}.
     * <p>
     * Said out loud because {@code LaunchConfigUtils} refuses to write a password into a SHARED
     * configuration, and an unstubbed mock reports {@code isLocal() == false}, i.e. shared.
     *
     * @param copy the working copy the configuration hands out
     * @return the mocked configuration
     */
    private static ILaunchConfiguration localConfig(ILaunchConfigurationWorkingCopy copy) throws CoreException
    {
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        when(config.isLocal()).thenReturn(true);
        when(config.getWorkingCopy()).thenReturn(copy);
        return config;
    }

    @Test
    public void configureClientWritesTheLaunchConfigurationWhenOneWasNamed() throws CoreException
    {
        // launchConfigurationName target: the client's own attributes are written, so the launched
        // 1C client authenticates instead of popping the platform's "Infobase access" dialog.
        ILaunchConfigurationWorkingCopy copy = mock(ILaunchConfigurationWorkingCopy.class);
        ILaunchConfiguration config = localConfig(copy);

        assertNull("a clean client write reports no error", //$NON-NLS-1$
            SetInfobaseCredentialsTool.configureClient(stillWaiting(), CONFIG_NAME, config, "Admin", //$NON-NLS-1$
                "pwd", false)); //$NON-NLS-1$

        verify(copy).setAttribute(LaunchConfigUtils.ATTR_LAUNCH_USER_NAME, "Admin"); //$NON-NLS-1$
        verify(copy).setAttribute(LaunchConfigUtils.ATTR_LAUNCH_USER_PASSWORD, "pwd"); //$NON-NLS-1$
        verify(copy).setAttribute(LaunchConfigUtils.ATTR_LAUNCH_USER_USE_INFOBASE_ACCESS, false);
        verify(copy).doSave();
    }

    @Test
    public void configureClientTouchesNothingWhenTheTargetWasProjectAndApplicationId()
            throws CoreException
    {
        // projectName + applicationId target: there is no launch configuration in play, so nothing
        // may be written - not even to a configuration that happens to be at hand. This is the
        // NEGATIVE half of the decision, and it is what makes the "client NOT configured" warning
        // in the success message true rather than decorative.
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);

        assertNull("no launch configuration named is not a failure", //$NON-NLS-1$
            SetInfobaseCredentialsTool.configureClient(stillWaiting(), null, config, "Admin", "pwd", //$NON-NLS-1$ //$NON-NLS-2$
                false));
        assertNull("an empty name is the same as none", //$NON-NLS-1$
            SetInfobaseCredentialsTool.configureClient(stillWaiting(), "", config, "Admin", "pwd", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                false));

        verify(config, never()).getWorkingCopy();
    }

    @Test
    public void configureClientReportsAFailedWriteInsteadOfThrowing() throws CoreException
    {
        // A configuration that refuses the save must become a reported failure: the agent-side
        // credentials still commit, and the message tells the caller the client is NOT covered.
        ILaunchConfigurationWorkingCopy copy = mock(ILaunchConfigurationWorkingCopy.class);
        ILaunchConfiguration config = localConfig(copy);
        doThrow(new CoreException(new Status(IStatus.ERROR, "test", "launch config is read-only"))) //$NON-NLS-1$ //$NON-NLS-2$
            .when(copy).doSave();

        String error = SetInfobaseCredentialsTool.configureClient(stillWaiting(), CONFIG_NAME, config,
            "Admin", "pwd", false); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull("a failed client write must be reported", error); //$NON-NLS-1$
        assertTrue("the reason must reach the caller: " + error, error.contains("read-only")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void configureClientPassesOsAuthenticationThrough() throws CoreException
    {
        ILaunchConfigurationWorkingCopy copy = mock(ILaunchConfigurationWorkingCopy.class);
        ILaunchConfiguration config = localConfig(copy);

        SetInfobaseCredentialsTool.configureClient(stillWaiting(), CONFIG_NAME, config, "Admin", "pwd", //$NON-NLS-1$ //$NON-NLS-2$
            true);

        verify(copy).setAttribute(LaunchConfigUtils.ATTR_LAUNCH_OS_INFOBASE_ACCESS, true);
    }

    // ======== The call is over: no launch-configuration write behind the caller's back ========

    @Test
    public void configureClientWritesNothingOnceTheCallerHasBeenAnswered() throws CoreException
    {
        // The bounded join gives up after 30s, cancels the Job and answers the caller - but
        // cancellation is COOPERATIVE and this Job polls no monitor, so it runs on and reaches the
        // client half anyway. When the deadline elapsed BEFORE the agent credentials committed the
        // caller was told the call failed; writing a user and a password into the launch
        // configuration after that is a side effect of a failed call, which is exactly what must
        // not happen.
        ILaunchConfigurationWorkingCopy copy = mock(ILaunchConfigurationWorkingCopy.class);
        ILaunchConfiguration config = localConfig(copy);

        String error = SetInfobaseCredentialsTool.configureClient(new AtomicBoolean(true), CONFIG_NAME,
            config, "Admin", "pwd", false); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull("an abandoned client write must be reported, not silently skipped", error); //$NON-NLS-1$
        assertTrue("the reason must say the call was already over: " + error, //$NON-NLS-1$
            error.contains("already returned")); //$NON-NLS-1$
        verify(config, never()).getWorkingCopy();
        verify(copy, never()).doSave();
    }

    @Test
    public void awaitStoreJobAnswersTheCallerAndSaysSoBeforeItReturns()
    {
        // The flag the check above reads is raised HERE, and it has to be raised on every way out -
        // a path that returns without raising it leaves the Job free to write.
        AtomicBoolean callerAnswered = new AtomicBoolean();
        AtomicReference<String> jobResult = new AtomicReference<>(SUCCESS_JSON);
        // Never scheduled, so join() returns immediately and the test does not wait out the 30s
        // budget; what is under test is the bookkeeping around the join, not the join itself.
        Job job = new Job("test: never scheduled") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                return Status.OK_STATUS;
            }
        };

        String result = SetInfobaseCredentialsTool.awaitStoreJob(job, jobResult, callerAnswered,
            "TestProject", "app1"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(SUCCESS_JSON, result);
        assertTrue("awaitStoreJob must raise callerAnswered before it returns: without it a job " //$NON-NLS-1$
            + "that outran the deadline goes on to write the launch configuration for a call that " //$NON-NLS-1$
            + "already reported a failure", callerAnswered.get()); //$NON-NLS-1$
    }

    /**
     * The defect itself, end to end: a store Job that outruns the deadline must not write the launch
     * configuration once the caller has been told the call failed.
     * <p>
     * Everything here is ordered by latches rather than by timing: the Job signals that it is RUNNING
     * (so the cancel on the timeout path cannot simply dequeue it before it starts), the caller's
     * wait is given a 1 ms deadline it cannot meet, and only THEN is the Job let through to its
     * client half. So the write it attempts is unambiguously a write after the answer - the exact
     * sequence that used to put a user and a password into a launch configuration behind the back of
     * a call that returned {@code success:false}.
     *
     * @throws Exception when the latches or the job join are interrupted
     */
    @Test
    public void aJobThatOutranTheDeadlineWritesNoLaunchConfigurationAfterwards() throws Exception
    {
        ILaunchConfigurationWorkingCopy copy = mock(ILaunchConfigurationWorkingCopy.class);
        ILaunchConfiguration config = localConfig(copy);
        AtomicBoolean callerAnswered = new AtomicBoolean();
        AtomicReference<String> jobResult = new AtomicReference<>();
        AtomicReference<String> clientOutcome = new AtomicReference<>();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch answered = new CountDownLatch(1);

        Job job = new Job("test: slower than the deadline") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                running.countDown();
                try
                {
                    // Stand in for the agent half still grinding away when the caller gives up.
                    answered.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
                clientOutcome.set(SetInfobaseCredentialsTool.configureClient(callerAnswered,
                    CONFIG_NAME, config, "Admin", "pwd", false)); //$NON-NLS-1$ //$NON-NLS-2$
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.schedule();
        try
        {
            assertTrue("the job must be RUNNING before the deadline elapses, or cancel() would " //$NON-NLS-1$
                + "simply dequeue it and the write under test would never be attempted", //$NON-NLS-1$
                running.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS));

            String result = SetInfobaseCredentialsTool.awaitStoreJob(job, jobResult, callerAnswered,
                "TestProject", "app1", 1L); //$NON-NLS-1$ //$NON-NLS-2$

            assertTrue("the caller must be told the call timed out: " + result, //$NON-NLS-1$
                result.contains("timed out")); //$NON-NLS-1$
            answered.countDown();
            job.join();

            assertNotNull("the job's client half must report that it stood down", //$NON-NLS-1$
                clientOutcome.get());
            verify(config, never()).getWorkingCopy();
            verify(copy, never()).doSave();
        }
        finally
        {
            answered.countDown();
            job.join();
        }
    }

    // ==================== What the answer SAYS about the client ====================

    @Test
    public void successWarnsThatTheClientIsNotConfiguredForAnExplicitTarget()
    {
        // The real-world failure this fixes: a caller read "credentials stored" as "a launch will
        // now work", ran the tests, and got the platform's login dialog. With no launch
        // configuration named, the answer has to say the client is NOT covered and how to cover it.
        String json = SetInfobaseCredentialsTool.buildSuccess("TestProject", "app1", "My Infobase", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Admin", true, InfobaseAccess.INFOBASE, null, null); //$NON-NLS-1$

        assertTrue("clientConfigured must be false with no launch configuration named", //$NON-NLS-1$
            json.contains("\"clientConfigured\":false")); //$NON-NLS-1$
        assertTrue("the message must warn the client is NOT covered: " + json, //$NON-NLS-1$
            json.contains("NOT covered")); //$NON-NLS-1$
        assertTrue("the message must name the way to cover it: " + json, //$NON-NLS-1$
            json.contains("launchConfigurationName")); //$NON-NLS-1$
    }

    @Test
    public void successReportsTheClientAsConfiguredWhenTheLaunchConfigWasUpdated()
    {
        String json = SetInfobaseCredentialsTool.buildSuccess("TestProject", "app1", "My Infobase", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Admin", true, InfobaseAccess.INFOBASE, CONFIG_NAME, null); //$NON-NLS-1$

        assertTrue("clientConfigured must be true once the launch config was updated", //$NON-NLS-1$
            json.contains("\"clientConfigured\":true")); //$NON-NLS-1$
        assertTrue("the message must name the configuration: " + json, json.contains(CONFIG_NAME)); //$NON-NLS-1$
    }

    @Test
    public void successReportsAFailedClientWriteAsNotConfigured()
    {
        // The agent-side credentials committed, so this is still a success - but claiming the
        // client is configured when its write failed is exactly the lie this field exists to stop.
        String json = SetInfobaseCredentialsTool.buildSuccess("TestProject", "app1", "My Infobase", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Admin", true, InfobaseAccess.INFOBASE, CONFIG_NAME, "launch config is read-only"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("a failed client write is NOT a configured client", //$NON-NLS-1$
            json.contains("\"clientConfigured\":false")); //$NON-NLS-1$
        assertTrue("the message must carry the reason: " + json, json.contains("read-only")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the message must point at the manual fix: " + json, //$NON-NLS-1$
            json.contains("Client application user")); //$NON-NLS-1$
    }

    @Test
    public void clientNoteClaimsWhatThisCallDidAndNotWhatTheLaunchWillDo()
    {
        // A configuration this call did NOT touch may already carry a user somebody set by hand,
        // and one it DID write can still fail at connect on a wrong password. Neither sentence may
        // promise an outcome the tool cannot know.
        String none = SetInfobaseCredentialsTool.clientNote(null, null);
        String configured = SetInfobaseCredentialsTool.clientNote(CONFIG_NAME, null);

        assertTrue("the untouched case must not assert the client WILL ask: " + none, //$NON-NLS-1$
            none.contains("unless")); //$NON-NLS-1$
        assertTrue("the configured case must not promise authentication succeeds: " + configured, //$NON-NLS-1$
            configured.contains("as long as what was stored is valid")); //$NON-NLS-1$
        // access=OS stores no user at all, and clientNote does not know which mode was used - so it
        // must not talk about "this user" in the branch that both modes share.
        assertTrue("the configured sentence must hold for access=OS too: " + configured, //$NON-NLS-1$
            !configured.contains("this user")); //$NON-NLS-1$
    }

    @Test
    public void clientNoteDistinguishesTheThreeOutcomes()
    {
        String none = SetInfobaseCredentialsTool.clientNote(null, null);
        String configured = SetInfobaseCredentialsTool.clientNote(CONFIG_NAME, null);
        String failed = SetInfobaseCredentialsTool.clientNote(CONFIG_NAME, "denied"); //$NON-NLS-1$

        assertTrue("no configuration named must warn", none.contains("NOT covered")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a clean write must not warn: " + configured, //$NON-NLS-1$
            !configured.contains("NOT covered") && !configured.contains("could NOT be updated")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a failed write must say so: " + failed, failed.contains("could NOT be updated")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void descriptionSaysWhichTargetCoversTheClient()
    {
        // The boundary lives in five places (description, inputSchema, outputSchema, message,
        // guide). The description is the one an agent reads before choosing the target shape.
        String desc = new SetInfobaseCredentialsTool().getDescription();
        assertTrue("the description must say launchConfigurationName covers the client: " + desc, //$NON-NLS-1$
            new SetInfobaseCredentialsTool().getGuide().contains("launchConfigurationName the launched 1C CLIENT is configured too")); //$NON-NLS-1$
        assertTrue("the description must say the other shape does not: " + desc, //$NON-NLS-1$
            new SetInfobaseCredentialsTool().getGuide().contains("only the agent is")); //$NON-NLS-1$
        assertTrue("the description must point at the field that reports which happened: " + desc, //$NON-NLS-1$
            new SetInfobaseCredentialsTool().getGuide().contains("clientConfigured")); //$NON-NLS-1$
    }

    // ============ Wiring ratchet: the tool really configures the client, and does it last ============

    /**
     * Pins that the tool actually calls {@link SetInfobaseCredentialsTool#configureClient} - and
     * calls it AFTER the agent's credentials have committed.
     * <p>
     * Why a ratchet: the call path needs a live launch manager, a live workspace and the EDT
     * application manager, so no unit test can drive it. Every behavioural case above therefore
     * drives {@code configureClient} directly, which leaves the WIRING unpinned - delete the one
     * line and the whole suite stays green while the launched client asks for a password again.
     * That is precisely how this class of fix ships broken.
     * <p>
     * The ORDER half is a contract of its own. The two writes cannot be atomic (EDT's secure
     * storage and a launch configuration are separate stores), so one of them is always second.
     * Putting the CLIENT second means a failure of the agent half leaves the launch configuration
     * untouched, while the reverse residue is reported through {@code clientConfigured} plus the
     * message. Swap them and a call answering {@code success:false} has silently rewritten a launch
     * configuration - password included.
     * <p>
     * It reads the COMPILED methods, not the source, so a call that was commented out or left
     * behind in a javadoc cannot satisfy it. The class files are read as resources, the way
     * {@code BareErrorStringRatchetTest} reads constant pools; JaCoCo instruments classes as they
     * are LOADED and never rewrites the file, so what is parsed here is javac's own output. The
     * tool's anonymous inner classes are scanned too - the bounded store Job is one of them.
     * <p>
     * Both calls must sit in the SAME method body: bytecode offsets restart at zero per method, so
     * comparing across bodies would compare meaningless numbers, and a future overload could
     * otherwise supply one of the two calls while the real path is broken.
     * <p>
     * What it deliberately does NOT prove, because bytecode order is not control flow: that the
     * returned {@code clientError} reaches {@code buildSuccess} (the {@code configureClient*} and
     * {@code success*} tests pin the two ends), that any check is a conditional rather than a bare
     * call, or that the method carrying the pair is REACHABLE - a dead helper holding both calls in
     * the right order would satisfy it. The single-call-site assertion below narrows that last gap:
     * the pair cannot be added somewhere new while the real one is quietly removed.
     *
     * @throws IOException when a compiled class cannot be read
     */
    @Test
    public void theClientIsConfiguredOnlyAfterTheAgentCredentialsCommit() throws IOException
    {
        List<MethodCalls> methods = ToolBytecode.staticCallsPerMethod();

        // Positive control: a parse that found nothing would make this ratchet's failure mode
        // identical to its pass. This call is unconditionally the first thing execute() does.
        assertNotNull("no static calls were read from SetInfobaseCredentialsTool: the class-file " //$NON-NLS-1$
            + "parse is broken, and a wiring ratchet that reads nothing proves nothing", //$NON-NLS-1$
            bodyCalling(methods, "JsonUtils", "extractStringArgument")); //$NON-NLS-1$ //$NON-NLS-2$

        MethodCalls body = bodyCalling(methods, "SetInfobaseCredentialsTool", "configureClient"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("nothing in SetInfobaseCredentialsTool calls configureClient any more: only " //$NON-NLS-1$
            + "the designer agent would get credentials, so the launched 1C client asks for a " //$NON-NLS-1$
            + "password at every launch again (issue #359) - while every configureClient test " //$NON-NLS-1$
            + "above stays green", body); //$NON-NLS-1$

        int clientAt = offsetOf(body, "SetInfobaseCredentialsTool", "configureClient"); //$NON-NLS-1$ //$NON-NLS-2$
        int commitAt = offsetOf(body, "InfobaseAccessSupport", "storeCredentials"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the client write in " + body.name + " no longer sits next to the agent commit: " //$NON-NLS-1$ //$NON-NLS-2$
            + "InfobaseAccessSupport.storeCredentials is not called in the same method, so a " //$NON-NLS-1$
            + "failure of the agent half can leave the launch configuration rewritten by a call " //$NON-NLS-1$
            + "that answered success:false", commitAt >= 0); //$NON-NLS-1$
        assertTrue("the launch configuration is written at bytecode offset " + clientAt //$NON-NLS-1$
            + " BEFORE the agent commit at " + commitAt + " in " + body.name + ": a call that goes " //$NON-NLS-1$ //$NON-NLS-2$
            + "on to fail must leave the launch configuration - and its password - untouched", //$NON-NLS-1$
            commitAt < clientAt);

        // ONE call site: the javadoc on configureClient claims a single place decides whether the
        // client is configured, and a second call site would put a launch-configuration write
        // somewhere this ratchet never checked the ordering of.
        List<String> callSites = new ArrayList<>();
        for (MethodCalls candidate : methods)
        {
            if (offsetOf(candidate, "SetInfobaseCredentialsTool", "configureClient") >= 0) //$NON-NLS-1$ //$NON-NLS-2$
            {
                callSites.add(candidate.name);
            }
        }
        assertEquals("configureClient must have exactly ONE call site, so that the ordering pinned " //$NON-NLS-1$
            + "above is the ordering of every launch-configuration write - found " + callSites, //$NON-NLS-1$
            1, callSites.size());
    }

    /**
     * The first parsed method body that calls {@code owner.method}.
     *
     * @param methods every parsed method body, in class-file order
     * @param owner the callee's simple class name
     * @param method the callee's name
     * @return the body, or {@code null} when no body calls it
     */
    private static MethodCalls bodyCalling(List<MethodCalls> methods, String owner, String method)
    {
        for (MethodCalls candidate : methods)
        {
            if (offsetOf(candidate, owner, method) >= 0)
            {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The offset of the first {@code invokestatic} to {@code owner.method} WITHIN one body.
     *
     * @param body one parsed method body
     * @param owner the callee's simple class name
     * @param method the callee's name
     * @return the lowest matching bytecode offset, or {@code -1} when there is none
     */
    private static int offsetOf(MethodCalls body, String owner, String method)
    {
        for (StaticCall call : body.calls)
        {
            if (owner.equals(call.owner) && method.equals(call.method))
            {
                return call.offset;
            }
        }
        return -1;
    }

    /** One compiled method: where it came from, and the {@code invokestatic}s it makes. */
    private static final class MethodCalls
    {
        /** {@code SimpleClassName.methodName} - only ever used in failure messages. */
        private final String name;

        private final List<StaticCall> calls;

        private MethodCalls(String name, List<StaticCall> calls)
        {
            this.name = name;
            this.calls = calls;
        }
    }

    /** One {@code invokestatic}: where it sits, and what it calls. */
    private static final class StaticCall
    {
        private final int offset;

        private final String owner;

        private final String method;

        private StaticCall(int offset, String owner, String method)
        {
            this.offset = offset;
            this.owner = owner;
            this.method = method;
        }
    }

    /**
     * The pieces of a compiled class this ratchet needs: the constant pool (to name a call's
     * target) and the bytecode of every method.
     */
    private static final class ToolBytecode
    {
        /**
         * How many anonymous inner classes to look for. The bounded store Job is one; the loop
         * simply stops at the first missing resource, so the ceiling only bounds the search.
         */
        private static final int MAX_INNER_CLASSES = 20;

        private static final String CODE_ATTRIBUTE = "Code"; //$NON-NLS-1$

        /** Text of every CONSTANT_Utf8 entry, by pool index. */
        private final String[] utf8;

        /** For a CONSTANT_Class: the pool index of its name. */
        private final int[] classNames;

        /** For a CONSTANT_Methodref: the pool index of its owning CONSTANT_Class. */
        private final int[] refOwners;

        /** For a CONSTANT_Methodref: the pool index of its CONSTANT_NameAndType. */
        private final int[] refNameAndTypes;

        /** For a CONSTANT_NameAndType: the pool index of its NAME. */
        private final int[] nameAndTypeNames;

        /** The simple name of the class being parsed, for the failure messages. */
        private final String className;

        /** Every method's name paired with its bytecode, in class-file order. */
        private final List<MethodCalls> methods = new ArrayList<>();

        private ToolBytecode(int poolSize, String className)
        {
            utf8 = new String[poolSize];
            classNames = new int[poolSize];
            refOwners = new int[poolSize];
            refNameAndTypes = new int[poolSize];
            nameAndTypeNames = new int[poolSize];
            this.className = className;
        }

        /**
         * Every {@code invokestatic} of {@code SetInfobaseCredentialsTool} and its anonymous inner
         * classes, grouped by the method that makes it and in bytecode order within each method.
         *
         * @return one entry per compiled method that carries code
         * @throws IOException when a compiled class cannot be read
         */
        static List<MethodCalls> staticCallsPerMethod() throws IOException
        {
            Class<?> clazz = SetInfobaseCredentialsTool.class;
            String simpleName = clazz.getSimpleName();
            List<MethodCalls> all = new ArrayList<>(readOne(clazz, simpleName + ".class", simpleName)); //$NON-NLS-1$
            if (all.isEmpty())
            {
                fail("class resource not found for " + clazz.getName() + " - a wiring ratchet must " //$NON-NLS-1$ //$NON-NLS-2$
                    + "never pass because it read nothing"); //$NON-NLS-1$
            }
            // The bounded store Job is an anonymous class, so its body lives in its own class file.
            for (int i = 1; i <= MAX_INNER_CLASSES; i++)
            {
                String inner = simpleName + "$" + i; //$NON-NLS-1$
                List<MethodCalls> innerMethods = readOne(clazz, inner + ".class", inner); //$NON-NLS-1$
                if (innerMethods.isEmpty())
                {
                    break; // numbering is contiguous; the first gap is the end
                }
                all.addAll(innerMethods);
            }
            return all;
        }

        /**
         * Parses one class file that sits next to {@code anchor}.
         *
         * @param anchor any class in the same package (used to resolve the resource)
         * @param resource the class file's name
         * @param owner the simple class name, used to label the methods
         * @return its methods, or an empty list when the resource does not exist
         * @throws IOException when the resource exists but cannot be parsed
         */
        private static List<MethodCalls> readOne(Class<?> anchor, String resource, String owner)
                throws IOException
        {
            try (InputStream raw = anchor.getResourceAsStream(resource))
            {
                if (raw == null)
                {
                    return List.of();
                }
                try (DataInputStream in = new DataInputStream(raw))
                {
                    return parse(in, owner).methods;
                }
            }
        }

        /**
         * Walks one method body instruction by instruction (including the variable-length
         * {@code wide} / {@code tableswitch} / {@code lookupswitch} forms), so a constant-pool index
         * that happens to look like an opcode inside another instruction's operands cannot be
         * mistaken for a call.
         *
         * @param code the method's bytecode
         * @return its {@code invokestatic} calls, in the order they execute
         */
        private List<StaticCall> staticCallsIn(byte[] code)
        {
            List<StaticCall> calls = new ArrayList<>();
            int pc = 0;
            while (pc < code.length)
            {
                if ((code[pc] & 0xFF) == 0xB8) // invokestatic
                {
                    int ref = readUnsignedShort(code, pc + 1);
                    calls.add(new StaticCall(pc, simpleName(text(classNames[refOwners[ref]])),
                        text(nameAndTypeNames[refNameAndTypes[ref]])));
                }
                pc += instructionLength(code, pc);
            }
            return calls;
        }

        private String text(int poolIndex)
        {
            if (poolIndex <= 0 || poolIndex >= utf8.length || utf8[poolIndex] == null)
            {
                return ""; //$NON-NLS-1$
            }
            return utf8[poolIndex];
        }

        private static ToolBytecode parse(DataInputStream in, String owner) throws IOException
        {
            if (in.readInt() != 0xCAFEBABE)
            {
                throw new IOException("not a class file (bad magic)"); //$NON-NLS-1$
            }
            in.readUnsignedShort(); // minor version
            in.readUnsignedShort(); // major version

            ToolBytecode parsed = new ToolBytecode(in.readUnsignedShort(), owner);
            parsed.readConstantPool(in);
            in.readUnsignedShort(); // access flags
            in.readUnsignedShort(); // this class
            in.readUnsignedShort(); // super class
            skipFully(in, in.readUnsignedShort() * 2); // interfaces
            parsed.skipMembers(in); // fields
            parsed.readMethods(in);
            return parsed;
        }

        private void readConstantPool(DataInputStream in) throws IOException
        {
            for (int i = 1; i < utf8.length; i++)
            {
                int tag = in.readUnsignedByte();
                switch (tag)
                {
                    case 1: // CONSTANT_Utf8
                        utf8[i] = in.readUTF();
                        break;
                    case 7: // CONSTANT_Class
                        classNames[i] = in.readUnsignedShort();
                        break;
                    case 8: // CONSTANT_String
                    case 16: // CONSTANT_MethodType
                    case 19: // CONSTANT_Module
                    case 20: // CONSTANT_Package
                        in.readUnsignedShort();
                        break;
                    case 15: // CONSTANT_MethodHandle
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                        break;
                    case 9: // CONSTANT_Fieldref
                    case 10: // CONSTANT_Methodref
                    case 11: // CONSTANT_InterfaceMethodref
                        refOwners[i] = in.readUnsignedShort();
                        refNameAndTypes[i] = in.readUnsignedShort();
                        break;
                    case 12: // CONSTANT_NameAndType
                        nameAndTypeNames[i] = in.readUnsignedShort();
                        in.readUnsignedShort(); // descriptor
                        break;
                    case 3: // CONSTANT_Integer
                    case 4: // CONSTANT_Float
                    case 17: // CONSTANT_Dynamic
                    case 18: // CONSTANT_InvokeDynamic
                        in.readInt();
                        break;
                    case 5: // CONSTANT_Long
                    case 6: // CONSTANT_Double
                        in.readLong();
                        i++; // 8-byte constants take two pool slots
                        break;
                    default:
                        throw new IOException("unknown constant pool tag: " + tag); //$NON-NLS-1$
                }
            }
        }

        /** Skips a whole fields (or methods) table. */
        private void skipMembers(DataInputStream in) throws IOException
        {
            int count = in.readUnsignedShort();
            for (int i = 0; i < count; i++)
            {
                in.readUnsignedShort(); // access flags
                in.readUnsignedShort(); // name
                in.readUnsignedShort(); // descriptor
                int attributes = in.readUnsignedShort();
                for (int a = 0; a < attributes; a++)
                {
                    in.readUnsignedShort(); // attribute name
                    skipFully(in, in.readInt());
                }
            }
        }

        /**
         * Reads the methods table, keeping each method's own calls SEPARATELY - offsets restart at
         * zero per method, so bodies must never be merged.
         */
        private void readMethods(DataInputStream in) throws IOException
        {
            int count = in.readUnsignedShort();
            for (int i = 0; i < count; i++)
            {
                in.readUnsignedShort(); // access flags
                String name = text(in.readUnsignedShort());
                String descriptor = text(in.readUnsignedShort());
                int attributes = in.readUnsignedShort();
                for (int a = 0; a < attributes; a++)
                {
                    String attribute = text(in.readUnsignedShort());
                    int length = in.readInt();
                    if (!CODE_ATTRIBUTE.equals(attribute))
                    {
                        skipFully(in, length);
                        continue;
                    }
                    in.readUnsignedShort(); // max stack
                    in.readUnsignedShort(); // max locals
                    int codeLength = in.readInt();
                    byte[] code = new byte[codeLength];
                    in.readFully(code);
                    // The descriptor is part of the label so an overload cannot be confused with
                    // the method the failure message names.
                    methods.add(new MethodCalls(className + "." + name + descriptor, //$NON-NLS-1$
                        staticCallsIn(code)));
                    // The exception table and the Code attribute's own attributes follow.
                    skipFully(in, length - 8 - codeLength);
                }
            }
        }
    }

    /** The class name without its package, from the internal {@code a/b/C} form. */
    private static String simpleName(String internalName)
    {
        int lastSlash = internalName.lastIndexOf('/');
        return lastSlash < 0 ? internalName : internalName.substring(lastSlash + 1);
    }

    private static int readUnsignedShort(byte[] code, int at)
    {
        return ((code[at] & 0xFF) << 8) | (code[at + 1] & 0xFF);
    }

    private static int readInt(byte[] code, int at)
    {
        return ((code[at] & 0xFF) << 24) | ((code[at + 1] & 0xFF) << 16) | ((code[at + 2] & 0xFF) << 8)
            | (code[at + 3] & 0xFF);
    }

    /**
     * The full length of the instruction at {@code pc}, including its operands.
     *
     * @param code the method's bytecode
     * @param pc the instruction's offset
     * @return the number of bytes it occupies
     */
    private static int instructionLength(byte[] code, int pc)
    {
        int opcode = code[pc] & 0xFF;
        if (opcode == 0xC4) // wide
        {
            return (code[pc + 1] & 0xFF) == 0x84 ? 6 : 4; // wide iinc, else wide load/store/ret
        }
        if (opcode == 0xAA) // tableswitch: padding, default, low, high, then one offset per case
        {
            int operands = padded(pc);
            int low = readInt(code, operands + 4);
            int high = readInt(code, operands + 8);
            return operands + 12 + (high - low + 1) * 4 - pc;
        }
        if (opcode == 0xAB) // lookupswitch: padding, default, npairs, then match/offset pairs
        {
            int operands = padded(pc);
            return operands + 8 + readInt(code, operands + 4) * 8 - pc;
        }
        int length = LENGTHS[opcode];
        if (length <= 0)
        {
            throw new IllegalStateException("unknown opcode 0x" + Integer.toHexString(opcode) //$NON-NLS-1$
                + " at " + pc); //$NON-NLS-1$
        }
        return length;
    }

    /** The offset of a switch instruction's operands: the next 4-byte boundary after the opcode. */
    private static int padded(int pc)
    {
        return (pc + 4) / 4 * 4;
    }

    /** Instruction lengths by opcode; the three variable-length forms are handled separately. */
    private static final int[] LENGTHS = buildLengths();

    private static int[] buildLengths()
    {
        int[] lengths = new int[256];
        Arrays.fill(lengths, 1); // most instructions are a bare opcode
        // One operand byte: the small pushes, the single-index loads/stores, ret, newarray.
        for (int opcode : new int[] { 0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38,
            0x39, 0x3A, 0xA9, 0xBC })
        {
            lengths[opcode] = 2;
        }
        // Two operand bytes: sipush, the wide ldc forms, iinc, the field/method refs, the type ops.
        for (int opcode : new int[] { 0x11, 0x13, 0x14, 0x84, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7,
            0xB8, 0xBB, 0xBD, 0xC0, 0xC1, 0xC6, 0xC7 })
        {
            lengths[opcode] = 3;
        }
        for (int opcode = 0x99; opcode <= 0xA8; opcode++) // ifeq..jsr: 16-bit branch offsets
        {
            lengths[opcode] = 3;
        }
        lengths[0xC5] = 4; // multianewarray
        lengths[0xB9] = 5; // invokeinterface
        lengths[0xBA] = 5; // invokedynamic
        lengths[0xC8] = 5; // goto_w
        lengths[0xC9] = 5; // jsr_w
        lengths[0xAA] = -1; // tableswitch
        lengths[0xAB] = -1; // lookupswitch
        lengths[0xC4] = -1; // wide
        for (int opcode = 0xCB; opcode < 0x100; opcode++) // reserved / not emitted by javac
        {
            lengths[opcode] = -1;
        }
        return lengths;
    }

    private static void skipFully(DataInputStream in, int bytes) throws IOException
    {
        int remaining = bytes;
        while (remaining > 0)
        {
            int skipped = in.skipBytes(remaining);
            if (skipped <= 0)
            {
                throw new IOException("truncated class file: " + remaining + " bytes missing"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            remaining -= skipped;
        }
    }
}
