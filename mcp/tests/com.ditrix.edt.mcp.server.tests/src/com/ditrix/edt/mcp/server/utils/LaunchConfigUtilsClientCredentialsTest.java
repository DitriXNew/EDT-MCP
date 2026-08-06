/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Tests for the client-credential half of {@link LaunchConfigUtils} (issue #359): the attribute
 * mapping {@link LaunchConfigUtils#clientCredentialAttributes} and the write
 * {@link LaunchConfigUtils#applyClientCredentials} that puts it on a launch configuration.
 * <p>
 * Why this exists at all: the infobase access settings {@code set_infobase_credentials} used to
 * write are read by the designer AGENT. The 1C CLIENT a launch starts is a different process and
 * reads its user from the launch configuration's own attributes, so the agent-only write left the
 * client popping the platform's "Infobase access" dialog at every launch while the tool reported
 * success.
 * <p>
 * The mapping is a pure function so the part that actually decides WHICH radio of the launch
 * dialog's client-user section ends up selected is pinnable without a live Eclipse; the write is
 * driven against a mocked {@link ILaunchConfiguration} so "the map is right but nothing reaches the
 * configuration" cannot pass.
 */
public class LaunchConfigUtilsClientCredentialsTest
{
    private static final String USER = "Admin";

    private static final String PASSWORD = "s3cret";

    private static final String CONFIG_NAME = "TestConfiguration - thin client";

    /**
     * The password the EDT-log cases carry. Deliberately unlike anything else in the run: the log is
     * shared with every other test, so the marker has to be unmistakably ours.
     */
    private static final String LOG_PROBE_PASSWORD = "s3cret-edt-log-probe-pw";

    /**
     * Marker standing for PLATFORM-PRODUCED text in the failure the log cases drive. It is not a
     * secret itself: it makes "no platform message travels to the log" testable, which the password
     * check alone cannot do - a scrubbed platform message would pass that one and still be the text
     * this fix withholds.
     */
    private static final String LOG_PROBE_PLATFORM_TEXT = "platform-text-edt-log-probe";

    /** Anchor that picks OUR entries out of a log every test in the run writes to. */
    private static final String LOG_ANCHOR = "client credentials";

    // ==================== The pure attribute mapping ====================

    @Test
    public void explicitUserTakesTheClientOffTheInfobaseAccessSettings()
    {
        // The whole point of the fix: an explicit user must switch OFF "use the infobase access
        // settings". Leave that radio on and the client keeps reading the settings only the
        // designer agent consumes - which is exactly the bug.
        Map<String, Object> attributes = LaunchConfigUtils.clientCredentialAttributes(USER, PASSWORD, false);

        assertEquals("an explicit user must not defer to the infobase access settings",
            Boolean.FALSE, attributes.get(LaunchConfigUtils.ATTR_LAUNCH_USER_USE_INFOBASE_ACCESS));
        assertEquals("an explicit user is not OS authentication",
            Boolean.FALSE, attributes.get(LaunchConfigUtils.ATTR_LAUNCH_OS_INFOBASE_ACCESS));
        assertEquals(USER, attributes.get(LaunchConfigUtils.ATTR_LAUNCH_USER_NAME));
        assertEquals(PASSWORD, attributes.get(LaunchConfigUtils.ATTR_LAUNCH_USER_PASSWORD));
    }

    @Test
    public void osAuthSelectsOsAndClearsTheUserAndPassword()
    {
        // The three radios are mutually exclusive: selecting OS authentication must not leave a
        // stale user/password behind that a later edit of the dialog would resurrect.
        Map<String, Object> attributes = LaunchConfigUtils.clientCredentialAttributes(USER, PASSWORD, true);

        assertEquals("OS authentication must not defer to the infobase access settings",
            Boolean.FALSE, attributes.get(LaunchConfigUtils.ATTR_LAUNCH_USER_USE_INFOBASE_ACCESS));
        assertEquals(Boolean.TRUE, attributes.get(LaunchConfigUtils.ATTR_LAUNCH_OS_INFOBASE_ACCESS));
        assertEquals("OS authentication carries no user - the old one must be cleared",
            "", attributes.get(LaunchConfigUtils.ATTR_LAUNCH_USER_NAME));
        assertEquals("OS authentication carries no password - the old one must be cleared",
            "", attributes.get(LaunchConfigUtils.ATTR_LAUNCH_USER_PASSWORD));
    }

    @Test
    public void nullsBecomeEmptyStringsInsteadOfBlowingUp()
    {
        // user/password are optional on the wire, so both arrive null routinely.
        Map<String, Object> attributes = LaunchConfigUtils.clientCredentialAttributes(null, null, false);

        assertEquals("", attributes.get(LaunchConfigUtils.ATTR_LAUNCH_USER_NAME));
        assertEquals("", attributes.get(LaunchConfigUtils.ATTR_LAUNCH_USER_PASSWORD));
    }

    @Test
    public void anEmptyPasswordIsWrittenRatherThanOmitted()
    {
        // A demo base's user legitimately has an EMPTY password, and "empty" has to be a value that
        // overwrites whatever the configuration held - not a key left out, which would keep a stale
        // password and authenticate as somebody the caller did not ask for.
        Map<String, Object> attributes = LaunchConfigUtils.clientCredentialAttributes(USER, "", false);

        assertTrue("an empty password must still be written",
            attributes.containsKey(LaunchConfigUtils.ATTR_LAUNCH_USER_PASSWORD));
        assertEquals("", attributes.get(LaunchConfigUtils.ATTR_LAUNCH_USER_PASSWORD));
        assertEquals("an empty password must not blank the user too", USER,
            attributes.get(LaunchConfigUtils.ATTR_LAUNCH_USER_NAME));
    }

    @Test
    public void everyClientAttributeIsAlwaysWritten()
    {
        // All four attributes on every path: a partially-written section leaves the dialog in a
        // state nobody asked for (e.g. OS off, use-settings off, and no user at all).
        for (boolean osAuth : new boolean[] { false, true })
        {
            Map<String, Object> attributes = LaunchConfigUtils.clientCredentialAttributes(USER, PASSWORD, osAuth);
            assertEquals("osAuth=" + osAuth + ": every client attribute must be written", 4,
                attributes.size());
            assertTrue(attributes.containsKey(LaunchConfigUtils.ATTR_LAUNCH_USER_USE_INFOBASE_ACCESS));
            assertTrue(attributes.containsKey(LaunchConfigUtils.ATTR_LAUNCH_OS_INFOBASE_ACCESS));
            assertTrue(attributes.containsKey(LaunchConfigUtils.ATTR_LAUNCH_USER_NAME));
            assertTrue(attributes.containsKey(LaunchConfigUtils.ATTR_LAUNCH_USER_PASSWORD));
        }
    }

    @Test
    public void theAttributeNamesAreThePlatformsOwn()
    {
        // These four names are the launch dialog's client-user section. A typo here writes an
        // attribute nothing reads, and the tool would report success while the client still asks
        // for a password - the exact failure this fix is about, made silent again.
        assertEquals("com._1c.g5.v8.dt.launching.core.ATTR_LAUNCH_USER_NAME",
            LaunchConfigUtils.ATTR_LAUNCH_USER_NAME);
        assertEquals("com._1c.g5.v8.dt.launching.core.ATTR_LAUNCH_USER_PASSWORD",
            LaunchConfigUtils.ATTR_LAUNCH_USER_PASSWORD);
        assertEquals("com._1c.g5.v8.dt.launching.core.ATTR_LAUNCH_USER_USE_INFOBASE_ACCESS",
            LaunchConfigUtils.ATTR_LAUNCH_USER_USE_INFOBASE_ACCESS);
        assertEquals("com._1c.g5.v8.dt.launching.core.ATTR_LAUNCH_OS_INFOBASE_ACCESS",
            LaunchConfigUtils.ATTR_LAUNCH_OS_INFOBASE_ACCESS);
    }

    // ==================== The write ====================

    /**
     * A launch configuration stored LOCALLY (in the workspace metadata) whose working copy is
     * {@code copy}.
     * <p>
     * "Local" is stated out loud because the local/shared distinction gates the password write: an
     * unstubbed mock leaves {@code isLocal()} at Mockito's {@code false} — i.e. SHARED — and the
     * write is refused. That default is the safe one on purpose: a test that forgets to say where
     * the configuration lives must not be the test that proves a password gets written.
     *
     * @param copy the working copy the configuration hands out, or {@code null} for none
     * @return the mocked configuration
     */
    private static ILaunchConfiguration localConfig(ILaunchConfigurationWorkingCopy copy) throws CoreException
    {
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        when(config.isLocal()).thenReturn(true);
        if (copy != null)
        {
            when(config.getWorkingCopy()).thenReturn(copy);
        }
        return config;
    }

    @Test
    public void applyWritesEveryAttributeOnTheWorkingCopyAndSaves() throws CoreException
    {
        ILaunchConfigurationWorkingCopy copy = mock(ILaunchConfigurationWorkingCopy.class);
        ILaunchConfiguration config = localConfig(copy);

        assertNull("a clean write reports no error",
            LaunchConfigUtils.applyClientCredentials(config, USER, PASSWORD, false));

        verify(copy).setAttribute(LaunchConfigUtils.ATTR_LAUNCH_USER_USE_INFOBASE_ACCESS, false);
        verify(copy).setAttribute(LaunchConfigUtils.ATTR_LAUNCH_OS_INFOBASE_ACCESS, false);
        verify(copy).setAttribute(LaunchConfigUtils.ATTR_LAUNCH_USER_NAME, USER);
        verify(copy).setAttribute(LaunchConfigUtils.ATTR_LAUNCH_USER_PASSWORD, PASSWORD);
        // Without doSave() the working copy is thrown away and nothing persists - the tool would
        // report success over a configuration it never actually changed.
        verify(copy).doSave();
    }

    @Test
    public void applyWritesTheBooleanAttributesAsBooleansNotStrings() throws CoreException
    {
        // The launch dialog reads these two through getAttribute(String, boolean); stored as the
        // STRING "false" they read back as the default and the radio never moves.
        ILaunchConfigurationWorkingCopy copy = mock(ILaunchConfigurationWorkingCopy.class);
        ILaunchConfiguration config = localConfig(copy);

        LaunchConfigUtils.applyClientCredentials(config, USER, PASSWORD, true);

        verify(copy).setAttribute(LaunchConfigUtils.ATTR_LAUNCH_OS_INFOBASE_ACCESS, true);
        verify(copy, never()).setAttribute(LaunchConfigUtils.ATTR_LAUNCH_OS_INFOBASE_ACCESS, "true");
        verify(copy, never()).setAttribute(LaunchConfigUtils.ATTR_LAUNCH_OS_INFOBASE_ACCESS, "false");
    }

    @Test
    public void applyReportsAFailureInsteadOfThrowing() throws CoreException
    {
        // A launch configuration can refuse the save (read-only file, deleted underneath us). The
        // tool must be able to say the client was NOT configured, not blow up mid-call.
        ILaunchConfigurationWorkingCopy copy = mock(ILaunchConfigurationWorkingCopy.class);
        ILaunchConfiguration config = localConfig(copy);
        doThrow(new CoreException(new Status(IStatus.ERROR, "test", "launch config is read-only")))
            .when(copy).doSave();

        String error = LaunchConfigUtils.applyClientCredentials(config, USER, PASSWORD, false);

        assertNotNull("a failed save must be reported, not swallowed", error);
        assertTrue("the reason must reach the caller: " + error, error.contains("read-only"));
    }

    @Test
    public void applyReportsAMissingConfigurationInsteadOfNpe()
    {
        String error = LaunchConfigUtils.applyClientCredentials(null, USER, PASSWORD, false);

        assertNotNull("a null configuration must be reported", error);
        assertTrue("the reason must name the configuration: " + error,
            error.contains("launch configuration"));
    }

    @Test
    public void applyNeverTouchesTheOriginalConfiguration() throws CoreException
    {
        // Eclipse launch configurations are immutable; the edit has to go through a working copy.
        ILaunchConfigurationWorkingCopy copy = mock(ILaunchConfigurationWorkingCopy.class);
        ILaunchConfiguration config = localConfig(copy);
        when(config.getName()).thenReturn(CONFIG_NAME);

        LaunchConfigUtils.applyClientCredentials(config, USER, PASSWORD, false);

        verify(config).getWorkingCopy();
    }

    // ==================== Shared configurations: no password into a committed file ====================

    /**
     * A launch configuration stored as a SHARED resource inside a project — the {@code .launch} file
     * an Eclipse "Shared file" launch lands in, and the one a repository carries.
     *
     * @param copy the working copy the configuration hands out
     * @return the mocked configuration
     */
    private static ILaunchConfiguration sharedConfig(ILaunchConfigurationWorkingCopy copy) throws CoreException
    {
        IFile file = mock(IFile.class);
        when(file.getFullPath()).thenReturn(new Path("/TestConfiguration/launches/thin.launch"));
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        when(config.isLocal()).thenReturn(false);
        when(config.getFile()).thenReturn(file);
        when(config.getWorkingCopy()).thenReturn(copy);
        return config;
    }

    @Test
    public void aPasswordIsNeverWrittenIntoASharedLaunchConfiguration() throws CoreException
    {
        // A shared launch configuration is an ordinary file inside the project, and the platform
        // reads the password back as a plain attribute - so it is serialised there in the clear and
        // gets committed with everything else. Nothing is written at all: not the password, not the
        // radios, not even the user, because a half-written section is a state nobody asked for.
        ILaunchConfigurationWorkingCopy copy = mock(ILaunchConfigurationWorkingCopy.class);
        ILaunchConfiguration config = sharedConfig(copy);

        String error = LaunchConfigUtils.applyClientCredentials(config, USER, PASSWORD, false);

        assertNotNull("writing a password into a shared configuration must be refused", error);
        assertTrue("the refusal must say WHY it is refused: " + error, error.contains("SHARED"));
        assertTrue("the refusal must name the file that would carry it: " + error,
            error.contains("/TestConfiguration/launches/thin.launch"));
        assertTrue("the refusal must not leak the password itself: " + error, !error.contains(PASSWORD));
        verify(config, never()).getWorkingCopy();
        verify(copy, never()).doSave();
    }

    @Test
    public void osAuthenticationIsStillWrittenIntoASharedLaunchConfiguration() throws CoreException
    {
        // OS authentication stores no password at all, so there is no secret to leak and no reason
        // to refuse: scoping the guard to what is actually secret keeps the legitimate case working.
        ILaunchConfigurationWorkingCopy copy = mock(ILaunchConfigurationWorkingCopy.class);
        ILaunchConfiguration config = sharedConfig(copy);

        assertNull("OS authentication writes no secret, so a shared configuration is fine",
            LaunchConfigUtils.applyClientCredentials(config, USER, PASSWORD, true));

        verify(copy).setAttribute(LaunchConfigUtils.ATTR_LAUNCH_OS_INFOBASE_ACCESS, true);
        verify(copy).setAttribute(LaunchConfigUtils.ATTR_LAUNCH_USER_PASSWORD, "");
        verify(copy).doSave();
    }

    @Test
    public void anEmptyPasswordIsStillWrittenIntoASharedLaunchConfiguration() throws CoreException
    {
        // The demo-base case: an empty password is not a secret, and refusing it would break the
        // most common configuration for no gain.
        ILaunchConfigurationWorkingCopy copy = mock(ILaunchConfigurationWorkingCopy.class);
        ILaunchConfiguration config = sharedConfig(copy);

        assertNull("an empty password is not a secret",
            LaunchConfigUtils.applyClientCredentials(config, USER, "", false));

        verify(copy).setAttribute(LaunchConfigUtils.ATTR_LAUNCH_USER_NAME, USER);
        verify(copy).doSave();
    }

    @Test
    public void aLocalConfigurationTakesThePasswordAsBefore() throws CoreException
    {
        // The negative half: the guard must fire on SHARED only. A local configuration lives in the
        // workspace metadata, which the guide already describes, and must keep working.
        ILaunchConfigurationWorkingCopy copy = mock(ILaunchConfigurationWorkingCopy.class);
        ILaunchConfiguration config = localConfig(copy);

        assertNull("a local configuration must still take the password",
            LaunchConfigUtils.applyClientCredentials(config, USER, PASSWORD, false));

        verify(copy).setAttribute(LaunchConfigUtils.ATTR_LAUNCH_USER_PASSWORD, PASSWORD);
        verify(copy).doSave();
    }

    @Test
    public void theRefusalDoesNotSendTheCallerBackToTheSameLeak() throws CoreException
    {
        // The tool appends "set it in the 'Client application user' section by hand" to every failed
        // client write. For a shared configuration that advice writes the very password this guard
        // just refused into the very file it refused to touch, so the reason has to say so.
        ILaunchConfigurationWorkingCopy copy = mock(ILaunchConfigurationWorkingCopy.class);
        ILaunchConfiguration config = sharedConfig(copy);

        String error = LaunchConfigUtils.applyClientCredentials(config, USER, PASSWORD, false);

        assertTrue("the refusal must warn that the manual path leaks the same way: " + error,
            error.contains("by hand"));
    }

    @Test
    public void aFailedSaveNeverEchoesThePasswordBack() throws CoreException
    {
        // The reason travels back to the caller inside the tool message, and that tool promises the
        // password is never returned. Platform messages normally name the resource - "normally" is
        // not a guarantee, so the one string that leaves this method is scrubbed.
        ILaunchConfigurationWorkingCopy copy = mock(ILaunchConfigurationWorkingCopy.class);
        ILaunchConfiguration config = localConfig(copy);
        doThrow(new CoreException(new Status(IStatus.ERROR, "test",
            "could not write attribute value '" + PASSWORD + "' to /P/x.launch"))).when(copy).doSave();

        String error = LaunchConfigUtils.applyClientCredentials(config, USER, PASSWORD, false);

        assertNotNull("a failed save must still be reported", error);
        assertTrue("the password must not travel back in the failure reason: " + error,
            !error.contains(PASSWORD));
        assertTrue("the rest of the platform's message must survive: " + error,
            error.contains("/P/x.launch"));
    }

    @Test
    public void scrubbingAnEmptyPasswordLeavesTheMessageAlone()
    {
        // An empty password is not a secret, and treating "" as one would replace every character
        // boundary in the message - the failure reason would become unreadable.
        assertEquals("boom", LaunchConfigUtils.withoutSecret("boom", ""));
        assertEquals("boom", LaunchConfigUtils.withoutSecret("boom", null));
        assertNull(LaunchConfigUtils.withoutSecret(null, "s3cret"));
    }

    // ==================== The EDT log: the other copy of the same failure ====================

    /**
     * Whether {@code secret} appears anywhere in a status tree — its own message or that of any
     * child. Eclipse writes a multi-status's children as nested log entries, so checking only the
     * top message would call a leak clean.
     *
     * @param status the status to inspect
     * @param secret the value that must not be there
     * @return {@code true} as soon as the secret is found
     */
    private static boolean containsDeep(IStatus status, String secret)
    {
        if (status.getMessage() != null && status.getMessage().contains(secret))
        {
            return true;
        }
        for (IStatus child : status.getChildren())
        {
            if (containsDeep(child, secret))
            {
                return true;
            }
        }
        return false;
    }

    @Test
    public void theEdtLogLineNamesTheExceptionTypesAndNoPlatformText()
    {
        // What the log line RENDERS. The failing save is the save of the attribute that HOLDS the
        // password, so the platform's own text is exactly where that value can surface - only the
        // exception types travel, and a class name can carry no attribute value.
        CoreException thrown = new CoreException(new Status(IStatus.ERROR, "test",
            "attribute value '" + LOG_PROBE_PASSWORD + "' was rejected",
            new IllegalStateException("the writer saw '" + LOG_PROBE_PASSWORD + "'")));

        String line = LaunchConfigUtils.saveFailureLog(thrown);

        assertFalse("no platform-produced text may reach the log line: " + line,
            line.contains(LOG_PROBE_PASSWORD));
        assertTrue("the line must still say what failed: " + line, line.contains(LOG_ANCHOR));
        assertTrue("the exception type is what keeps the line usable: " + line,
            line.contains(CoreException.class.getName()));
        assertTrue("the cause's type must be named too - a CoreException's cause is its status's "
            + "own exception, and that is usually the real failure: " + line,
            line.contains(IllegalStateException.class.getName()));
    }

    @Test
    public void aFailedSaveAttachesNoThrowableToTheEdtLog() throws CoreException
    {
        // The case above pins what saveFailureLog RENDERS; this one pins what the failure branch
        // actually HANDS to the log. They are different claims: 'logError(sanitized, e)' would keep
        // every assertion above green while Eclipse wrote the exception's message, its whole cause
        // chain and - for a CoreException - the statuses behind it into a permanent workspace file.
        // So the plug-in's own log is listened to while the production path runs, and the recorded
        // Status is read back.
        IStatus child = new Status(IStatus.ERROR, "test",
            "attribute value '" + LOG_PROBE_PASSWORD + "' was rejected");
        MultiStatus status = new MultiStatus("test", 0, new IStatus[] { child },
            "could not save /P/x.launch (" + LOG_PROBE_PLATFORM_TEXT + ")",
            new IllegalStateException("the writer saw '" + LOG_PROBE_PASSWORD + "'"));
        CoreException thrown = new CoreException(status);

        // Positive controls: the fixture is only a fixture if the password really is where Eclipse
        // renders it. The password is kept OUT of the top message and put in the cause and the child
        // status instead - scrubbing e.getMessage(), which is all the ANSWER needs, leaves both of
        // those routes wide open, so a case whose secret sat in the top message would prove less.
        assertFalse("the password must sit in the routes the answer's scrubbing cannot reach",
            thrown.getMessage().contains(LOG_PROBE_PASSWORD));
        // ...and the top message carries its own marker, so "no platform text at all" is testable:
        // without it, appending a scrubbed e.getMessage() to the log line would keep this green.
        assertTrue("positive control: the top message must carry its own marker",
            thrown.getMessage().contains(LOG_PROBE_PLATFORM_TEXT));
        StringWriter rendered = new StringWriter();
        thrown.printStackTrace(new PrintWriter(rendered));
        assertTrue("positive control: the cause chain must really carry the password",
            rendered.toString().contains(LOG_PROBE_PASSWORD));
        assertTrue("positive control: the child status must really carry the password",
            containsDeep(status, LOG_PROBE_PASSWORD));

        ILaunchConfigurationWorkingCopy copy = mock(ILaunchConfigurationWorkingCopy.class);
        ILaunchConfiguration config = localConfig(copy);
        doThrow(thrown).when(copy).doSave();

        Bundle bundle = FrameworkUtil.getBundle(LaunchConfigUtils.class);
        assertNotNull("this case can only observe the log from inside OSGi; without the bundle it "
            + "would 'pass' by seeing nothing at all", bundle);
        ILog log = Platform.getLog(bundle);
        List<IStatus> recorded = new ArrayList<>();
        ILogListener listener = (logged, plugin) -> recorded.add(logged);
        String error;
        log.addLogListener(listener);
        try
        {
            error = LaunchConfigUtils.applyClientCredentials(config, USER, LOG_PROBE_PASSWORD, false);
        }
        finally
        {
            log.removeLogListener(listener);
        }

        assertNotNull("a failed save must still be reported to the caller", error);
        List<IStatus> ours = new ArrayList<>();
        for (IStatus entry : recorded)
        {
            // The log is shared with the rest of the run, so only this failure's entries are judged.
            if (entry.getMessage() != null && entry.getMessage().contains(LOG_ANCHOR))
            {
                ours.add(entry);
            }
        }
        // Positive control: a listener that recorded nothing would make this case pass without ever
        // having looked at a log entry.
        assertFalse("the failure branch must really log, or nothing here was observed", ours.isEmpty());
        for (IStatus entry : ours)
        {
            assertNull("no throwable may be attached - Eclipse writes its message, its cause chain "
                + "and the statuses behind a CoreException, and the value that failed to save here "
                + "is the password: " + entry.getMessage(), entry.getException());
            assertFalse("nor may the password reach the logged status itself: " + entry.getMessage(),
                containsDeep(entry, LOG_PROBE_PASSWORD));
            // No platform-produced text at all, not just no password: masking is literal, and the
            // platform can quote a value escaped or re-encoded. This is what catches a log line
            // built as saveFailureLog(e) + e.getMessage(), which the password check alone would not.
            assertFalse("no platform-produced text may travel to the log: " + entry.getMessage(),
                containsDeep(entry, LOG_PROBE_PLATFORM_TEXT));
            assertTrue("the entry must still name what failed: " + entry.getMessage(),
                entry.getMessage().contains(CoreException.class.getName()));
        }
    }

    @Test
    public void theRefusalSurvivesAConfigurationWithNoFileHandle() throws CoreException
    {
        // isLocal() and getFile() are two views of the same fact, but they are separate handle-only
        // methods: the refusal must not depend on the path being available to name.
        ILaunchConfigurationWorkingCopy copy = mock(ILaunchConfigurationWorkingCopy.class);
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        when(config.isLocal()).thenReturn(false);
        when(config.getWorkingCopy()).thenReturn(copy);

        String error = LaunchConfigUtils.applyClientCredentials(config, USER, PASSWORD, false);

        assertNotNull("a shared configuration with no file handle must still be refused", error);
        assertTrue("the refusal must still say why: " + error, error.contains("SHARED"));
        verify(config, never()).getWorkingCopy();
    }
}
