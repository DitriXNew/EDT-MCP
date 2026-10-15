/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.InfobaseSessionSupport.ReadResult;
import com.ditrix.edt.mcp.server.utils.InfobaseSessionSupport.SessionInfo;

/**
 * How {@code ibcmd session list} output is read (#698): the interactive authentication prompt
 * ibcmd prints before the list when it is fed the stored credentials, and the charset of its
 * output.
 *
 * <p>The prompt texts are the platform's own (the ibcli resources of 8.3.27 and 8.5, Russian and
 * English); the Russian form is exactly what #698's reporter found in the EDT log.
 */
public class InfobaseSessionOutputTest
{
    /** ibcmd's greeting before it asks for credentials, as #698's reporter captured it. */
    private static final String RU_GREETING = "\u0414\u043B\u044F \u0432\u044B\u043F\u043E\u043B\u043D\u0435\u043D\u0438\u044F \u043E\u043F\u0435\u0440\u0430\u0446\u0438\u0438 \u0442\u0440\u0435\u0431\u0443\u0435\u0442\u0441\u044F \u0430\u0443\u0442\u0435\u043D\u0442\u0438\u0444\u0438\u043A\u0430\u0446\u0438\u044F \u0432 \u0438\u043D\u0444\u043E\u0440\u043C\u0430\u0446\u0438\u043E\u043D\u043D\u043E\u0439 \u0431\u0430\u0437\u0435"; //$NON-NLS-1$

    /**
     * The user-name and password prompts. They share one line because ibcmd does not echo the
     * answers piped to its standard input; the line ends with the password prompt's space.
     */
    private static final String RU_PROMPTS = "\u0418\u043C\u044F \u043F\u043E\u043B\u044C\u0437\u043E\u0432\u0430\u0442\u0435\u043B\u044F: \u041F\u0430\u0440\u043E\u043B\u044C: "; //$NON-NLS-1$

    /** The same greeting in the platform's English resources. */
    private static final String EN_GREETING =
        "Authentication in the infobase is required to perform the operation"; //$NON-NLS-1$

    /** The same prompts in the platform's English resources. */
    private static final String EN_PROMPTS = "User: Password: "; //$NON-NLS-1$

    /** What ibcmd says when the credentials are refused; it is not part of the prompt. */
    private static final String RU_AUTHENTICATION_FAILED = "\u0418\u0434\u0435\u043D\u0442\u0438\u0444\u0438\u043A\u0430\u0446\u0438\u044F \u043F\u043E\u043B\u044C\u0437\u043E\u0432\u0430\u0442\u0435\u043B\u044F \u043D\u0435 \u0432\u044B\u043F\u043E\u043B\u043D\u0435\u043D\u0430"; //$NON-NLS-1$

    /** An infobase user named in Cyrillic, as in #698. */
    private static final String ADMINISTRATOR = "\u0410\u0434\u043C\u0438\u043D\u0438\u0441\u0442\u0440\u0430\u0442\u043E\u0440"; //$NON-NLS-1$

    /** The platform's password prompt that names the user. */
    private static final String RU_PASSWORD_FOR_ADMINISTRATOR = "\u041F\u0430\u0440\u043E\u043B\u044C \u0434\u043B\u044F '\u0410\u0434\u043C\u0438\u043D\u0438\u0441\u0442\u0440\u0430\u0442\u043E\u0440': "; //$NON-NLS-1$

    /** The OEM code page ibcmd output was always read with on Windows. */
    private static final Charset CP866 = Charset.forName("CP866"); //$NON-NLS-1$

    private static final String FIRST_ID = "fe63b824-630f-4c3e-b5e9-08a9e5edae74"; //$NON-NLS-1$
    private static final String SECOND_ID = "0d2c3b4a-5e6f-4a1b-8c9d-0e1f2a3b4c5d"; //$NON-NLS-1$

    private static final String ONE_UNREADABLE_BLOCK = "ibcmd session list returned 1 unreadable " //$NON-NLS-1$
        + "session block. The raw output was written to the EDT log."; //$NON-NLS-1$

    @Test
    public void theRussianPromptBeforeTheFirstSessionIsNotASessionBlock()
    {
        String output = RU_GREETING + "\r\n" + RU_PROMPTS + "\r\n" //$NON-NLS-1$ //$NON-NLS-2$
            + sessionBlock(FIRST_ID, 249, ADMINISTRATOR, "1CV8C"); //$NON-NLS-1$

        InfobaseSessionSupport.ParsedSessions parsed = InfobaseSessionSupport.parseSessions(output);

        assertEquals("the prompt must not be counted as an unreadable session block", 0, //$NON-NLS-1$
            parsed.unreadableBlocks());
        assertEquals(1, parsed.sessions().size());
        SessionInfo session = parsed.sessions().get(0);
        assertEquals(FIRST_ID, session.sessionId());
        assertEquals(Long.valueOf(249), session.sessionNumber());
        assertEquals(ADMINISTRATOR, session.userName());
        assertEquals("1CV8C", session.applicationKind()); //$NON-NLS-1$
    }

    @Test
    public void everySessionAfterThePromptIsRead()
    {
        String output = RU_GREETING + "\r\n" + RU_PROMPTS + "\r\n" //$NON-NLS-1$ //$NON-NLS-2$
            + sessionBlock(FIRST_ID, 249, ADMINISTRATOR, "1CV8C") + "\r\n" //$NON-NLS-1$ //$NON-NLS-2$
            + sessionBlock(SECOND_ID, 250, "Operator", "Designer"); //$NON-NLS-1$ //$NON-NLS-2$

        ReadResult result = InfobaseSessionSupport.readSessionsOutput(output);

        assertTrue("a list behind the prompt must be readable: " + result.unreachableReason(), //$NON-NLS-1$
            result.isReadable());
        assertEquals(2, result.sessions().size());
        assertEquals(FIRST_ID, result.sessions().get(0).sessionId());
        assertEquals(SECOND_ID, result.sessions().get(1).sessionId());
        assertTrue(result.sessions().get(1).applicationKindIsDesigner());
    }

    @Test
    public void thePromptAloneIsAReadableEmptyList()
    {
        String output = RU_GREETING + "\r\n" + RU_PROMPTS + "\r\n"; //$NON-NLS-1$ //$NON-NLS-2$

        ReadResult result = InfobaseSessionSupport.readSessionsOutput(output);

        assertTrue("an infobase without sessions prints only the prompt: " //$NON-NLS-1$
            + result.unreachableReason(), result.isReadable());
        assertTrue(result.sessions().isEmpty());
        assertNull(result.unreachableReason());
    }

    @Test
    public void theEnglishPromptBeforeTheFirstSessionIsNotASessionBlock()
    {
        String output = EN_GREETING + "\n" + EN_PROMPTS + "\n" //$NON-NLS-1$ //$NON-NLS-2$
            + sessionBlock(FIRST_ID, 7, "Operator", "1CV8C").replace("\r\n", "\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        ReadResult result = InfobaseSessionSupport.readSessionsOutput(output);

        assertTrue("the English prompt is the same preamble: " + result.unreachableReason(), //$NON-NLS-1$
            result.isReadable());
        assertEquals(1, result.sessions().size());
        assertEquals(FIRST_ID, result.sessions().get(0).sessionId());
    }

    @Test
    public void theEnglishPromptAloneIsAReadableEmptyList()
    {
        ReadResult result = InfobaseSessionSupport.readSessionsOutput(
            EN_GREETING + "\n" + EN_PROMPTS + "\n"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the English prompt alone proves an empty list: " + result.unreachableReason(), //$NON-NLS-1$
            result.isReadable());
        assertTrue(result.sessions().isEmpty());
    }

    /** Only the platform's prompt is skipped: any other leading line without ':' still poisons its block. */
    @Test
    public void anUnrecognisedLeadingLineStillMakesTheListUnreadable()
    {
        String output = "The connection to the server is unstable\r\n" //$NON-NLS-1$
            + sessionBlock(FIRST_ID, 5, "Operator", "1CV8C"); //$NON-NLS-1$ //$NON-NLS-2$

        ReadResult result = InfobaseSessionSupport.readSessionsOutput(output);

        assertFalse(result.isReadable());
        assertEquals(ONE_UNREADABLE_BLOCK, result.unreachableReason());
    }

    /** The prompt is matched as a whole line, not found inside a longer one. */
    @Test
    public void aLineThatMerelyContainsTheGreetingIsNotThePrompt()
    {
        String output = RU_GREETING + ".\r\n" + RU_PROMPTS + "\r\n" //$NON-NLS-1$ //$NON-NLS-2$
            + sessionBlock(FIRST_ID, 5, "Operator", "1CV8C"); //$NON-NLS-1$ //$NON-NLS-2$

        ReadResult result = InfobaseSessionSupport.readSessionsOutput(output);

        assertFalse(result.isReadable());
        assertEquals(ONE_UNREADABLE_BLOCK, result.unreachableReason());
    }

    /**
     * The refusal ibcmd prints for wrong credentials is not part of the prompt, so a prompt
     * followed by it can never read as a proven empty list.
     */
    @Test
    public void theAuthenticationFailureAfterThePromptIsNotAnEmptyList()
    {
        String output = RU_GREETING + "\r\n" + RU_PROMPTS + "\r\n" //$NON-NLS-1$ //$NON-NLS-2$
            + RU_AUTHENTICATION_FAILED + "\r\n"; //$NON-NLS-1$

        ReadResult result = InfobaseSessionSupport.readSessionsOutput(output);

        assertFalse(result.isReadable());
        assertNull(result.sessions());
    }

    /** The prompt is a preamble only before the list: the same text after a session is not. */
    @Test
    public void thePromptAfterASessionIsAnUnreadableBlock()
    {
        String output = sessionBlock(FIRST_ID, 5, "Operator", "1CV8C") + "\r\n" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + RU_GREETING + "\r\n" + RU_PROMPTS + "\r\n"; //$NON-NLS-1$ //$NON-NLS-2$

        ReadResult result = InfobaseSessionSupport.readSessionsOutput(output);

        assertFalse(result.isReadable());
        assertEquals(ONE_UNREADABLE_BLOCK, result.unreachableReason());
    }

    /** Skipping the prompt does not weaken the guard on malformed blocks after the first session. */
    @Test
    public void aMalformedBlockAfterThePromptedSessionIsStillUnreadable()
    {
        String output = RU_GREETING + "\r\n" + RU_PROMPTS + "\r\n" //$NON-NLS-1$ //$NON-NLS-2$
            + sessionBlock(FIRST_ID, 249, ADMINISTRATOR, "1CV8C") + "\r\n" //$NON-NLS-1$ //$NON-NLS-2$
            + "foreign client record\r\n"; //$NON-NLS-1$

        ReadResult result = InfobaseSessionSupport.readSessionsOutput(output);

        assertFalse(result.isReadable());
        assertNull(result.sessions());
        assertEquals("only the foreign record is unreadable; the prompted session is not", //$NON-NLS-1$
            ONE_UNREADABLE_BLOCK, result.unreachableReason());
    }

    /**
     * ibcmd 8.3.27 writes UTF-8 to the pipe: a Cyrillic user name must come back exactly, not as
     * the CP866 reading #698 found in the EDT log. The stored credentials still go in as UTF-8.
     */
    @Test
    public void utf8OutputIsReadExactlyAndCredentialsStayUtf8() throws Exception
    {
        String text = RU_GREETING + "\r\n" + RU_PROMPTS + "\r\n" //$NON-NLS-1$ //$NON-NLS-2$
            + sessionBlock(FIRST_ID, 249, ADMINISTRATOR, "1CV8C"); //$NON-NLS-1$
        FinishedProcess process = new FinishedProcess(text.getBytes(StandardCharsets.UTF_8),
            new byte[0], 0);

        InfobaseSessionSupport.CommandExecution result = InfobaseSessionSupport.runCommand(
            new InfobaseAccessSupport.Credentials(ADMINISTRATOR, "s3cret"), //$NON-NLS-1$
            new NullProgressMonitor(), new AtomicBoolean(), () -> process);

        assertEquals(0, result.exitCode);
        assertEquals("the output must decode as the UTF-8 ibcmd wrote", text, result.stdout); //$NON-NLS-1$
        List<SessionInfo> sessions = InfobaseSessionSupport.readSessionsOutput(result.stdout)
            .sessions();
        assertEquals(ADMINISTRATOR, sessions.get(0).userName());
        assertArrayEquals("the credentials are written as UTF-8, as EDT itself writes them", //$NON-NLS-1$
            (ADMINISTRATOR + "\ns3cret\n").getBytes(StandardCharsets.UTF_8), //$NON-NLS-1$
            process.stdin.toByteArray());
    }

    /** A failed run is judged before its output, so the prompt it printed is not an empty list. */
    @Test
    public void aNonZeroExitWithOnlyThePromptIsStillAnError()
    {
        ReadResult result = InfobaseSessionSupport.listOutcome(
            new InfobaseSessionSupport.CommandExecution(1, promptOnly(), "", false)); //$NON-NLS-1$

        assertFalse(result.isReadable());
        assertEquals("ibcmd session list failed with exit code 1. " //$NON-NLS-1$
            + "The raw output was written to the EDT log.", result.unreachableReason()); //$NON-NLS-1$
    }

    @Test
    public void aTimedOutRunWithOnlyThePromptIsStillAnError()
    {
        ReadResult result = InfobaseSessionSupport.listOutcome(
            new InfobaseSessionSupport.CommandExecution(-1, promptOnly(), "", true)); //$NON-NLS-1$

        assertFalse(result.isReadable());
        assertEquals("ibcmd session list timed out after 10 seconds and was terminated.", //$NON-NLS-1$
            result.unreachableReason());
    }

    @Test
    public void exitZeroWithOnlyThePromptIsAReadableEmptyList()
    {
        ReadResult result = InfobaseSessionSupport.listOutcome(
            new InfobaseSessionSupport.CommandExecution(0, promptOnly(), "", false)); //$NON-NLS-1$

        assertTrue(result.isReadable());
        assertTrue(result.sessions().isEmpty());
    }

    /**
     * A successful run's list is read from standard output; standard error is not consulted on
     * exit 0. Read from the wrong stream, every list would come back empty, since standard error
     * is normally blank.
     */
    @Test
    public void aSuccessfulRunIsReadFromStandardOutputWhateverStandardErrorHolds()
    {
        ReadResult result = InfobaseSessionSupport.listOutcome(
            new InfobaseSessionSupport.CommandExecution(0, reportedOutput(),
                "a diagnostic on standard error\r\n", false)); //$NON-NLS-1$

        assertTrue("the list is read from standard output: " + result.unreachableReason(), //$NON-NLS-1$
            result.isReadable());
        assertEquals(1, result.sessions().size());
        assertEquals(FIRST_ID, result.sessions().get(0).sessionId());
        assertEquals(ADMINISTRATOR, result.sessions().get(0).userName());
    }

    @Test
    public void utf8IsTriedBeforeTheLegacyCharset()
    {
        assertEquals(ADMINISTRATOR, InfobaseSessionSupport.decodeOutput(
            ADMINISTRATOR.getBytes(StandardCharsets.UTF_8), CP866));
    }

    @Test
    public void outputInTheOemCodePageStillDecodesThroughTheFallback()
    {
        String text = reportedOutput();

        assertEquals(text, InfobaseSessionSupport.decodeOutput(text.getBytes(CP866), CP866));
    }

    @Test
    public void elsewhereTheFallbackIsTheOldLenientUtf8()
    {
        byte[] oem = ADMINISTRATOR.getBytes(CP866);

        assertEquals("bytes that are not UTF-8 decode as before, never throw", //$NON-NLS-1$
            new String(oem, StandardCharsets.UTF_8),
            InfobaseSessionSupport.decodeOutput(oem, StandardCharsets.UTF_8));
    }

    @Test
    public void theLegacyCharsetIsCp866OnWindowsAndUtf8Elsewhere()
    {
        assertEquals(CP866, InfobaseSessionSupport.legacyOutputCharset(true));
        assertEquals(StandardCharsets.UTF_8, InfobaseSessionSupport.legacyOutputCharset(false));
    }

    /** Through the reader: output that is not UTF-8 reads as it did before #698 on this OS. */
    @Test
    public void theReaderFallsBackToThisPlatformsLegacyCharset() throws Exception
    {
        String text = reportedOutput();
        byte[] oem = text.getBytes(CP866);
        FinishedProcess process = new FinishedProcess(oem, oem, 0);

        InfobaseSessionSupport.CommandExecution result = InfobaseSessionSupport.runCommand(null,
            new NullProgressMonitor(), new AtomicBoolean(), () -> process);

        boolean windows = System.getProperty("os.name", "") //$NON-NLS-1$ //$NON-NLS-2$
            .toLowerCase(Locale.ROOT).contains("win"); //$NON-NLS-1$
        String legacyReading = new String(oem, InfobaseSessionSupport.legacyOutputCharset(windows));
        assertEquals(legacyReading, result.stdout);
        assertEquals("standard error goes through the same reader", legacyReading, result.stderr); //$NON-NLS-1$
        if (windows)
        {
            assertEquals("on Windows the OEM code page reads back exactly", text, result.stdout); //$NON-NLS-1$
        }
    }

    @Test
    public void thePlatformsPromptTextsMakePromptLines()
    {
        assertTrue(InfobaseSessionSupport.isAuthenticationPromptLine(RU_GREETING));
        assertTrue(InfobaseSessionSupport.isAuthenticationPromptLine(RU_PROMPTS));
        assertTrue(InfobaseSessionSupport.isAuthenticationPromptLine(EN_GREETING));
        assertTrue(InfobaseSessionSupport.isAuthenticationPromptLine(EN_PROMPTS));
        assertTrue(InfobaseSessionSupport.isAuthenticationPromptLine(RU_PASSWORD_FOR_ADMINISTRATOR));
        assertTrue(InfobaseSessionSupport.isAuthenticationPromptLine("Password for Administrator: ")); //$NON-NLS-1$
    }

    @Test
    public void nothingElseMakesAPromptLine()
    {
        assertFalse("a session line", InfobaseSessionSupport.isAuthenticationPromptLine( //$NON-NLS-1$
            "session                          : " + FIRST_ID)); //$NON-NLS-1$
        assertFalse("the prompt is matched exactly, and ibcmd's keys are lower-case", //$NON-NLS-1$
            InfobaseSessionSupport.isAuthenticationPromptLine("user: password:")); //$NON-NLS-1$
        assertFalse("an echoed answer is not the prompt", //$NON-NLS-1$
            InfobaseSessionSupport.isAuthenticationPromptLine(RU_PROMPTS + ADMINISTRATOR));
        assertFalse("the refusal is not the prompt", //$NON-NLS-1$
            InfobaseSessionSupport.isAuthenticationPromptLine(RU_AUTHENTICATION_FAILED));
    }

    /**
     * Each repetition of the prompt grammar is matched without growing the stack, so even a line
     * far longer than any prompt ibcmd prints gets a verdict: a {@link StackOverflowError} would
     * escape the list's {@code catch (RuntimeException)}.
     */
    @Test
    public void aVeryLongPromptLineIsJudgedWithoutExhaustingTheStack()
    {
        String repeated = EN_PROMPTS.repeat(50_000);

        assertTrue(InfobaseSessionSupport.isAuthenticationPromptLine(repeated));
        assertFalse("other text after the repeated prompts still ends the prompt", //$NON-NLS-1$
            InfobaseSessionSupport.isAuthenticationPromptLine(repeated + "x")); //$NON-NLS-1$
    }

    /** The same through the list: a very long prompt before it is skipped, and the list is read. */
    @Test
    public void aVeryLongPromptBeforeTheListIsSkippedWithoutExhaustingTheStack()
    {
        String output = RU_GREETING + "\r\n" + RU_PROMPTS.repeat(50_000) + "\r\n" //$NON-NLS-1$ //$NON-NLS-2$
            + sessionBlock(FIRST_ID, 249, ADMINISTRATOR, "1CV8C"); //$NON-NLS-1$

        ReadResult result = InfobaseSessionSupport.listOutcome(
            new InfobaseSessionSupport.CommandExecution(0, output, "", false)); //$NON-NLS-1$

        assertTrue("a long prompt is still a prompt: " + result.unreachableReason(), //$NON-NLS-1$
            result.isReadable());
        assertEquals(1, result.sessions().size());
        assertEquals(FIRST_ID, result.sessions().get(0).sessionId());
    }

    /** The prompt alone, as a list without sessions prints it. */
    private static String promptOnly()
    {
        return RU_GREETING + "\r\n" + RU_PROMPTS + "\r\n"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The prompt and one session, the shape of the output #698's reporter found. */
    private static String reportedOutput()
    {
        return promptOnly() + sessionBlock(FIRST_ID, 249, ADMINISTRATOR, "1CV8C"); //$NON-NLS-1$
    }

    /** One ibcmd session block in the column-padded layout a real server prints. */
    private static String sessionBlock(String id, int number, String userName, String appId)
    {
        return "session                          : " + id + "\r\n" //$NON-NLS-1$ //$NON-NLS-2$
            + "session-id                       : " + number + "\r\n" //$NON-NLS-1$ //$NON-NLS-2$
            + "infobase                         : 8ef869af-9785-4d3c-9094-e1c434068afe\r\n" //$NON-NLS-1$
            + "connection                       : \r\n" //$NON-NLS-1$
            + "process                          : \r\n" //$NON-NLS-1$
            + "user-name                        : " + userName + "\r\n" //$NON-NLS-1$ //$NON-NLS-2$
            + "host                             : \r\n" //$NON-NLS-1$
            + "app-id                           : " + appId + "\r\n" //$NON-NLS-1$ //$NON-NLS-2$
            + "started-at                       : 2026-09-29T15:00:00\r\n"; //$NON-NLS-1$
    }

    /** A process that has already exited: it replays fixed output and records its stdin. */
    private static final class FinishedProcess extends Process
    {
        private final byte[] stdout;
        private final byte[] stderr;
        private final int exitCode;
        final ByteArrayOutputStream stdin = new ByteArrayOutputStream();

        FinishedProcess(byte[] stdout, byte[] stderr, int exitCode)
        {
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitCode = exitCode;
        }

        @Override
        public OutputStream getOutputStream()
        {
            return stdin;
        }

        @Override
        public InputStream getInputStream()
        {
            return new ByteArrayInputStream(stdout);
        }

        @Override
        public InputStream getErrorStream()
        {
            return new ByteArrayInputStream(stderr);
        }

        @Override
        public int waitFor()
        {
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit)
        {
            return true;
        }

        @Override
        public int exitValue()
        {
            return exitCode;
        }

        @Override
        public void destroy()
        {
            // already exited
        }
    }
}
