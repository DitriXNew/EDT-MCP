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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
 * English); the Russian form is exactly what #698's reporter found in the EDT log. Only one
 * complete prompt at the start is skipped: a fragment or a repetition of it is list content.
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

    /**
     * The user-name prompt alone: ibcmd asks for the user name first when it does not know it, and
     * the password prompt follows on the same line.
     */
    private static final String RU_USER_PROMPT = "\u0418\u043C\u044F \u043F\u043E\u043B\u044C\u0437\u043E\u0432\u0430\u0442\u0435\u043B\u044F: "; //$NON-NLS-1$

    /** The password prompt alone. */
    private static final String RU_PASSWORD_PROMPT = "\u041F\u0430\u0440\u043E\u043B\u044C: "; //$NON-NLS-1$

    /** The same prompts, alone, in the platform's English resources. */
    private static final String EN_USER_PROMPT = "User: "; //$NON-NLS-1$
    private static final String EN_PASSWORD_PROMPT = "Password: "; //$NON-NLS-1$

    /** The English password prompt that names the user; it does not quote the name. */
    private static final String EN_PASSWORD_FOR_ADMINISTRATOR = "Password for Administrator: "; //$NON-NLS-1$

    /** A line that is no part of ibcmd's prompt: the start of some other message. */
    private static final String UNKNOWN_LINE = "The connection to the server is unstable"; //$NON-NLS-1$

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
        String output = UNKNOWN_LINE + "\r\n" //$NON-NLS-1$
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
            new NullProgressMonitor(), new InfobaseSessionSupport.CallerState(), () -> process);

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
            new NullProgressMonitor(), new InfobaseSessionSupport.CallerState(), () -> process);

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

    /** Each complete prompt ends right after its last line; blank lines and whitespace may surround its parts. */
    @Test
    public void eachCompletePromptEndsRightAfterItsLastLine()
    {
        assertEquals("greeting, user and password on one line", 2, //$NON-NLS-1$
            promptEnd(RU_GREETING, RU_PROMPTS, "session : " + FIRST_ID)); //$NON-NLS-1$
        assertEquals("the same in English", 2, promptEnd(EN_GREETING, EN_PROMPTS)); //$NON-NLS-1$
        assertEquals("user and password on two lines", 3, //$NON-NLS-1$
            promptEnd(RU_GREETING, RU_USER_PROMPT, RU_PASSWORD_PROMPT));
        assertEquals("the password prompt that names the user", 2, //$NON-NLS-1$
            promptEnd(RU_GREETING, RU_PASSWORD_FOR_ADMINISTRATOR));
        assertEquals("the same in English", 2, //$NON-NLS-1$
            promptEnd(EN_GREETING, EN_PASSWORD_FOR_ADMINISTRATOR));
        assertEquals("blank lines and whitespace around the parts", 5, //$NON-NLS-1$
            promptEnd("", "  " + EN_GREETING + "\t", "", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "\t" + EN_USER_PROMPT, EN_PASSWORD_PROMPT, "")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Without one complete prompt at its start, the output is read from its first line. */
    @Test
    public void nothingButOneCompletePromptIsThePrompt()
    {
        assertEquals("the greeting alone", 0, promptEnd(RU_GREETING)); //$NON-NLS-1$
        assertEquals("the greeting and the user-name prompt", 0, //$NON-NLS-1$
            promptEnd(RU_GREETING, RU_USER_PROMPT));
        assertEquals("the prompts before the greeting", 0, promptEnd(RU_PROMPTS, RU_GREETING)); //$NON-NLS-1$
        assertEquals("the texts are matched case-sensitively", 0, //$NON-NLS-1$
            promptEnd(EN_GREETING, "user: password: ")); //$NON-NLS-1$
        assertEquals("an echoed answer", 0, promptEnd(RU_GREETING, RU_PROMPTS + ADMINISTRATOR)); //$NON-NLS-1$
        assertEquals("the refusal", 0, promptEnd(RU_GREETING, RU_AUTHENTICATION_FAILED)); //$NON-NLS-1$
        assertEquals("a line before the greeting", 0, //$NON-NLS-1$
            promptEnd(UNKNOWN_LINE, EN_GREETING, EN_PROMPTS));
        assertEquals("the password prompt twice", 0, //$NON-NLS-1$
            promptEnd(RU_GREETING, RU_PROMPTS + RU_PASSWORD_PROMPT));
        assertEquals("the bare password prompt after the one that names the user", 0, //$NON-NLS-1$
            promptEnd(EN_GREETING, EN_PASSWORD_FOR_ADMINISTRATOR + EN_PASSWORD_PROMPT));
        assertEquals("the password prompt that names the user, twice", 0, //$NON-NLS-1$
            promptEnd(RU_GREETING, RU_PASSWORD_FOR_ADMINISTRATOR + RU_PASSWORD_FOR_ADMINISTRATOR));
        assertEquals("a session line", 0, //$NON-NLS-1$
            promptEnd("session                          : " + FIRST_ID)); //$NON-NLS-1$
        assertEquals("no output", 0, promptEnd("")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Split over two lines, the prompt is the user-name prompt and then the bare password prompt:
     * any other line in between or in its place, or text after it, makes no prompt.
     */
    @Test
    public void theSplitPromptIsTheUserPromptAndThenExactlyThePasswordPrompt()
    {
        assertEquals("another line after the user-name prompt", 0, //$NON-NLS-1$
            promptEnd(EN_GREETING, EN_USER_PROMPT, UNKNOWN_LINE));
        assertEquals("another line before the password prompt", 0, //$NON-NLS-1$
            promptEnd(EN_GREETING, EN_USER_PROMPT, UNKNOWN_LINE, EN_PASSWORD_PROMPT));
        assertEquals("text after the password prompt", 0, //$NON-NLS-1$
            promptEnd(EN_GREETING, EN_USER_PROMPT, EN_PASSWORD_PROMPT + "s3cret")); //$NON-NLS-1$
        assertEquals("the one-line prompt after the user-name prompt", 0, //$NON-NLS-1$
            promptEnd(EN_GREETING, EN_USER_PROMPT, EN_PROMPTS));
        assertEquals("the password prompt that names the user after the user-name prompt", 0, //$NON-NLS-1$
            promptEnd(EN_GREETING, EN_USER_PROMPT, EN_PASSWORD_FOR_ADMINISTRATOR));
    }

    /**
     * The user-name prompt on its own is a fragment of ibcmd's prompt, not the prompt: a run that
     * printed only it has not shown that the list is empty.
     */
    @Test
    public void theUserPromptAloneIsNotAnEmptyList()
    {
        assertOneUnreadableBlock(listedWithExitZero(EN_USER_PROMPT + "\r\n")); //$NON-NLS-1$
    }

    @Test
    public void thePasswordPromptAloneIsNotAnEmptyList()
    {
        assertOneUnreadableBlock(listedWithExitZero(EN_PASSWORD_PROMPT + "\r\n")); //$NON-NLS-1$
    }

    /** ibcmd follows its greeting with a prompt or fails, so the greeting alone proves nothing. */
    @Test
    public void theGreetingAloneIsNotAnEmptyList()
    {
        assertOneUnreadableBlock(listedWithExitZero(RU_GREETING + "\r\n")); //$NON-NLS-1$
    }

    /** After the user-name prompt ibcmd always asks for the password: without it the prompt is cut short. */
    @Test
    public void theGreetingWithOnlyTheUserPromptIsNotAnEmptyList()
    {
        assertOneUnreadableBlock(listedWithExitZero(
            RU_GREETING + "\r\n" + RU_USER_PROMPT + "\r\n")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Right after its greeting ibcmd asks for the user name, or names the user whose password it
     * asks for; a bare password prompt there is not its prompt.
     */
    @Test
    public void theGreetingWithOnlyTheBarePasswordPromptIsNotAnEmptyList()
    {
        assertOneUnreadableBlock(listedWithExitZero(
            RU_GREETING + "\r\n" + RU_PASSWORD_PROMPT + "\r\n")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Prompt texts repeated on a line, as truncated or garbled output could show them, are not one prompt. */
    @Test
    public void repeatedPromptsAreNotAnEmptyList()
    {
        assertOneUnreadableBlock(listedWithExitZero(EN_PROMPTS.repeat(3) + "\r\n")); //$NON-NLS-1$
    }

    @Test
    public void theUserPromptTwiceIsNotThePrompt()
    {
        assertOneUnreadableBlock(listedWithExitZero(RU_GREETING + "\r\n" //$NON-NLS-1$
            + RU_USER_PROMPT + RU_PROMPTS + "\r\n")); //$NON-NLS-1$
    }

    @Test
    public void twoGreetingsAreNotOnePrompt()
    {
        assertOneUnreadableBlock(listedWithExitZero(RU_GREETING + "\r\n" + RU_GREETING + "\r\n" //$NON-NLS-1$ //$NON-NLS-2$
            + RU_PROMPTS + "\r\n")); //$NON-NLS-1$
    }

    /** The prompt is recognised once: a second one after it is list content. */
    @Test
    public void theWholePromptTwiceIsNotOnePrompt()
    {
        assertOneUnreadableBlock(listedWithExitZero(promptOnly() + promptOnly()));
    }

    /** ibcmd ends its greeting with a line break, so the greeting and the prompts never share a line. */
    @Test
    public void theGreetingAndThePromptsOnOneLineAreNotThePrompt()
    {
        assertOneUnreadableBlock(listedWithExitZero(
            RU_GREETING + " " + RU_PROMPTS + "\r\n")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** ibcmd prints all the texts of its prompt in one language. */
    @Test
    public void aPromptMixingTwoLanguagesIsNotThePrompt()
    {
        assertOneUnreadableBlock(listedWithExitZero(
            RU_GREETING + "\r\n" + EN_PROMPTS + "\r\n")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A second password prompt after the one that names the user is a repetition, not part of one prompt. */
    @Test
    public void aPasswordPromptAfterThePasswordForTheUserIsNotThePrompt()
    {
        assertOneUnreadableBlock(listedWithExitZero(RU_GREETING + "\r\n" //$NON-NLS-1$
            + RU_PASSWORD_FOR_ADMINISTRATOR + RU_PASSWORD_PROMPT + "\r\n")); //$NON-NLS-1$
    }

    /**
     * After the user-name prompt ibcmd asks for the password, so a refusal right after it leaves the
     * prompt cut short: the run has not shown that the list is empty.
     */
    @Test
    public void aRefusalRightAfterTheUserPromptIsNotAnEmptyList()
    {
        assertOneUnreadableBlock(listedWithExitZero(RU_GREETING + "\r\n" + RU_USER_PROMPT + "\r\n" //$NON-NLS-1$ //$NON-NLS-2$
            + RU_AUTHENTICATION_FAILED + "\r\n")); //$NON-NLS-1$
    }

    /** The password prompt repeated on the prompt line is a repetition, not part of one prompt. */
    @Test
    public void thePasswordPromptTwiceIsNotOnePrompt()
    {
        assertOneUnreadableBlock(listedWithExitZero(RU_GREETING + "\r\n" //$NON-NLS-1$
            + RU_PROMPTS + RU_PASSWORD_PROMPT + "\r\n")); //$NON-NLS-1$
    }

    /** Only a prompt at the start is skipped: after an unknown line, even a complete prompt is list content. */
    @Test
    public void anUnknownLineBeforeACompletePromptIsNotAnEmptyList()
    {
        assertOneUnreadableBlock(listedWithExitZero(UNKNOWN_LINE + "\r\n" + EN_GREETING + "\r\n" //$NON-NLS-1$ //$NON-NLS-2$
            + EN_PROMPTS + "\r\n")); //$NON-NLS-1$
    }

    /**
     * Thousands of prompt texts on one line after the greeting are not a prompt either, and the
     * prompt patterns judge that line at once: a {@link StackOverflowError} would escape the list's
     * {@code catch (RuntimeException)}.
     */
    @Test(timeout = 10_000)
    public void thousandsOfRepeatedPromptsAreUnreadableAndJudgedQuickly()
    {
        assertOneUnreadableBlock(listedWithExitZero(EN_GREETING + "\r\n" //$NON-NLS-1$
            + EN_PROMPTS.repeat(50_000) + "\r\n")); //$NON-NLS-1$
    }

    /** The same in Russian and before a list: the list behind such a line is not read either. */
    @Test(timeout = 10_000)
    public void aHugeRepeatedPromptBeforeTheListMakesItUnreadable()
    {
        assertOneUnreadableBlock(listedWithExitZero(RU_GREETING + "\r\n" //$NON-NLS-1$
            + RU_PROMPTS.repeat(50_000) + "\r\n" //$NON-NLS-1$
            + sessionBlock(FIRST_ID, 249, ADMINISTRATOR, "1CV8C"))); //$NON-NLS-1$
    }

    /** The password prompt that names a user ibcmd already knows is the other complete prompt. */
    @Test
    public void thePasswordPromptThatNamesTheUserAloneIsAReadableEmptyList()
    {
        ReadResult result = listedWithExitZero(
            RU_GREETING + "\r\n" + RU_PASSWORD_FOR_ADMINISTRATOR + "\r\n"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("one complete prompt proves an empty list: " + result.unreachableReason(), //$NON-NLS-1$
            result.isReadable());
        assertTrue(result.sessions().isEmpty());
    }

    @Test
    public void theEnglishPasswordPromptThatNamesTheUserAloneIsAReadableEmptyList()
    {
        ReadResult result = listedWithExitZero(
            EN_GREETING + "\n" + EN_PASSWORD_FOR_ADMINISTRATOR + "\n"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("one complete prompt proves an empty list: " + result.unreachableReason(), //$NON-NLS-1$
            result.isReadable());
        assertTrue(result.sessions().isEmpty());
    }

    @Test
    public void theListAfterThePasswordPromptThatNamesTheUserIsRead()
    {
        ReadResult result = listedWithExitZero(RU_GREETING + "\r\n" //$NON-NLS-1$
            + RU_PASSWORD_FOR_ADMINISTRATOR + "\r\n" //$NON-NLS-1$
            + sessionBlock(FIRST_ID, 249, ADMINISTRATOR, "1CV8C")); //$NON-NLS-1$

        assertTrue("a list behind the prompt must be readable: " + result.unreachableReason(), //$NON-NLS-1$
            result.isReadable());
        assertEquals(1, result.sessions().size());
        assertEquals(FIRST_ID, result.sessions().get(0).sessionId());
    }

    /** The user-name and password prompts are the same prompt when a line break separates them. */
    @Test
    public void thePromptsSplitOverTwoLinesAreThePrompt()
    {
        InfobaseSessionSupport.ParsedSessions parsed = InfobaseSessionSupport.parseSessions(RU_GREETING
            + "\r\n" + RU_USER_PROMPT + "\r\n" + RU_PASSWORD_PROMPT + "\r\n" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + sessionBlock(FIRST_ID, 249, ADMINISTRATOR, "1CV8C")); //$NON-NLS-1$

        assertEquals("the split prompt must not be counted as an unreadable session block", 0, //$NON-NLS-1$
            parsed.unreadableBlocks());
        assertEquals(1, parsed.sessions().size());
        assertEquals(FIRST_ID, parsed.sessions().get(0).sessionId());
    }

    @Test
    public void thePromptsSplitOverTwoLinesAloneAreAReadableEmptyList()
    {
        ReadResult result = listedWithExitZero(EN_GREETING + "\n" + EN_USER_PROMPT + "\n" //$NON-NLS-1$ //$NON-NLS-2$
            + EN_PASSWORD_PROMPT + "\n"); //$NON-NLS-1$

        assertTrue("one complete prompt proves an empty list: " + result.unreachableReason(), //$NON-NLS-1$
            result.isReadable());
        assertTrue(result.sessions().isEmpty());
    }

    /** Blank lines may stand before the prompt, between its parts and after it. */
    @Test
    public void blankLinesAroundThePromptPartsAreAllowed()
    {
        ReadResult result = listedWithExitZero("\r\n" + RU_GREETING + "\r\n\r\n" //$NON-NLS-1$ //$NON-NLS-2$
            + RU_USER_PROMPT + "\r\n\r\n" + RU_PASSWORD_PROMPT + "\r\n\r\n" //$NON-NLS-1$ //$NON-NLS-2$
            + sessionBlock(FIRST_ID, 249, ADMINISTRATOR, "1CV8C")); //$NON-NLS-1$

        assertTrue("blank lines do not end the prompt: " + result.unreachableReason(), //$NON-NLS-1$
            result.isReadable());
        assertEquals(1, result.sessions().size());
        assertEquals(FIRST_ID, result.sessions().get(0).sessionId());
    }

    /**
     * The rule rather than a list of shapes: every one-line edit of a complete prompt that is not
     * itself a complete prompt is not a proven empty list. A one-line edit here is exactly one of
     * these: a line deleted or duplicated; a prompt part (the greeting, the user-name prompt, the
     * password prompt, the user-name and password prompts on one line, the password prompt that
     * names the user, in either language) or an unknown line inserted anywhere, put in place of a
     * line, or glued before or after one; other text before or after a line; and "x" glued to the
     * last visible character of a line, since the prompts end in a space that the other suffixes
     * keep. Other edits of one line, such as a changed character inside a prompt part, are not
     * generated. None of the edits adds a blank line or bare whitespace, so the only edits skipped
     * are the ones that give a complete prompt back: the prompt itself or another of the six.
     *
     * <p>No edit holds a session block, so a readable verdict could only be a proven empty list:
     * each edit must be unreadable, as any output whose first block is not a session always was.
     */
    @Test
    public void everyOneLineEditOfACompletePromptIsNotAnEmptyList()
    {
        List<List<String>> complete = completePrompts();
        int checked = 0;
        for (List<String> prompt : complete)
        {
            ReadResult alone = listedWithExitZero(asOutput(prompt));
            assertTrue("a complete prompt alone is a readable empty list: " + prompt, //$NON-NLS-1$
                alone.isReadable() && alone.sessions().isEmpty());
            for (List<String> edit : oneLineEdits(prompt))
            {
                if (complete.contains(edit))
                {
                    continue;
                }
                checked++;
                assertFalse("not one complete prompt, yet a proven empty list: " + edit, //$NON-NLS-1$
                    listedWithExitZero(asOutput(edit)).isReadable());
            }
        }
        assertTrue("too few edits checked: " + checked, checked > 600); //$NON-NLS-1$
    }

    /** The password prompt that names the user names someone: with an empty name it is not the prompt. */
    @Test
    public void thePasswordPromptThatNamesNoUserIsNotThePrompt()
    {
        assertEquals(0, promptEnd(RU_GREETING,
            RU_PASSWORD_FOR_ADMINISTRATOR.replace(ADMINISTRATOR, ""))); //$NON-NLS-1$
        assertEquals(0, promptEnd(EN_GREETING,
            EN_PASSWORD_FOR_ADMINISTRATOR.replace("Administrator", ""))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The name in the password prompt that names the user holds something besides whitespace: a
     * name of whitespace alone names no one, so such a line is not the prompt and the run has not
     * shown that the list is empty. Whitespace is what {@code String.isBlank} counts, Unicode
     * whitespace that {@code trim} keeps (U+3000) included.
     */
    @Test
    public void thePasswordPromptForAWhitespaceOnlyNameIsNotThePrompt()
    {
        for (String name : List.of(" ", "   ", "\t", " \t ", "\u3000", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            " \u3000 ")) //$NON-NLS-1$
        {
            assertEquals("Russian, name [" + name + "]", 0, //$NON-NLS-1$ //$NON-NLS-2$
                promptEnd(RU_GREETING, ruPasswordFor(name)));
            assertEquals("English, name [" + name + "]", 0, //$NON-NLS-1$ //$NON-NLS-2$
                promptEnd(EN_GREETING, enPasswordFor(name)));
            assertOneUnreadableBlock(listedWithExitZero(
                RU_GREETING + "\r\n" + ruPasswordFor(name) + "\r\n")); //$NON-NLS-1$ //$NON-NLS-2$
            assertOneUnreadableBlock(listedWithExitZero(
                EN_GREETING + "\n" + enPasswordFor(name) + "\n")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * ibcmd prints the name as it is, and a user name may hold ':': in English the name ends at the
     * line's last ':', so such a prompt is the prompt.
     */
    @Test
    public void anEnglishUserNameMayHoldColons()
    {
        List<String> names = List.of("Ops:Admin", "a:b:c", //$NON-NLS-1$ //$NON-NLS-2$
            ":Ops", "Ops:"); //$NON-NLS-1$ //$NON-NLS-2$
        for (String name : names)
        {
            assertEquals("name [" + name + "]", 2, //$NON-NLS-1$ //$NON-NLS-2$
                promptEnd(EN_GREETING, enPasswordFor(name)));
        }
        ReadResult alone = listedWithExitZero(
            EN_GREETING + "\n" + enPasswordFor("Ops:Admin") + "\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue("one complete prompt proves an empty list: " + alone.unreachableReason(), //$NON-NLS-1$
            alone.isReadable());
        assertTrue(alone.sessions().isEmpty());

        ReadResult listed = listedWithExitZero(EN_GREETING + "\r\n" //$NON-NLS-1$
            + enPasswordFor("Ops:Admin") + "\r\n" //$NON-NLS-1$ //$NON-NLS-2$
            + sessionBlock(FIRST_ID, 249, "Ops:Admin", "1CV8C")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the list after the prompt is read: " + listed.unreachableReason(), //$NON-NLS-1$
            listed.isReadable());
        assertEquals(1, listed.sessions().size());
        assertEquals(FIRST_ID, listed.sessions().get(0).sessionId());
    }

    /**
     * The same in Russian, where ibcmd quotes the name and does not escape a quote in it: the name
     * ends at the line's last quote and ':', so it may hold quotes as well as ':'.
     */
    @Test
    public void aRussianUserNameMayHoldQuotesAndColons()
    {
        List<String> names = List.of("O'Brien", "'Ops'", "Ops':Admin", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Ops:Admin", "Ops: Admin"); //$NON-NLS-1$ //$NON-NLS-2$
        for (String name : names)
        {
            assertEquals("name [" + name + "]", 2, //$NON-NLS-1$ //$NON-NLS-2$
                promptEnd(RU_GREETING, ruPasswordFor(name)));
        }
        ReadResult listed = listedWithExitZero(RU_GREETING + "\r\n" //$NON-NLS-1$
            + ruPasswordFor("O'Brien") + "\r\n" //$NON-NLS-1$ //$NON-NLS-2$
            + sessionBlock(FIRST_ID, 249, "O'Brien", "1CV8C")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the list after the prompt is read: " + listed.unreachableReason(), //$NON-NLS-1$
            listed.isReadable());
        assertEquals(1, listed.sessions().size());
        assertEquals(FIRST_ID, listed.sessions().get(0).sessionId());
    }

    /**
     * ibcmd follows every prompt with a space, so inside the name the prompt's own delimiter followed
     * by whitespace is where a second prompt on the line would begin: such a line is list content,
     * whatever follows.
     */
    @Test
    public void theDelimiterBeforeWhitespaceInsideTheNameIsNotOnePrompt()
    {
        assertEquals(0, promptEnd(EN_GREETING, enPasswordFor("Ops: Admin"))); //$NON-NLS-1$
        assertEquals(0, promptEnd(EN_GREETING, enPasswordFor("Ops:\tAdmin"))); //$NON-NLS-1$
        assertEquals(0, promptEnd(RU_GREETING, ruPasswordFor("Ops': Admin"))); //$NON-NLS-1$
        assertEquals(0, promptEnd(EN_GREETING,
            enPasswordFor("Administrator: Password for Administrator"))); //$NON-NLS-1$
        assertOneUnreadableBlock(listedWithExitZero(EN_GREETING + "\n" //$NON-NLS-1$
            + enPasswordFor("Ops: Admin") + "\n")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The password prompt that names the user, repeated thousands of times on one line or ending in
     * thousands of delimiters, is judged at once: the name has no repeated group to recurse on.
     */
    @Test(timeout = 10_000)
    public void aHugePasswordPromptForAUserIsJudgedQuickly()
    {
        assertOneUnreadableBlock(listedWithExitZero(EN_GREETING + "\r\n" //$NON-NLS-1$
            + EN_PASSWORD_FOR_ADMINISTRATOR.repeat(50_000) + "\r\n")); //$NON-NLS-1$
        assertOneUnreadableBlock(listedWithExitZero(RU_GREETING + "\r\n" //$NON-NLS-1$
            + RU_PASSWORD_FOR_ADMINISTRATOR.repeat(50_000) + "\r\n")); //$NON-NLS-1$
        assertOneUnreadableBlock(listedWithExitZero(EN_GREETING + "\r\n" //$NON-NLS-1$
            + enPasswordFor("a" + ": ".repeat(200_000) + "b") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "x\r\n")); //$NON-NLS-1$
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

    /** The Russian password prompt that names this user, as ibcmd prints it. */
    private static String ruPasswordFor(String name)
    {
        return RU_PASSWORD_FOR_ADMINISTRATOR.replace(ADMINISTRATOR, name);
    }

    /** The English password prompt that names this user, as ibcmd prints it. */
    private static String enPasswordFor(String name)
    {
        return EN_PASSWORD_FOR_ADMINISTRATOR.replace("Administrator", name); //$NON-NLS-1$
    }

    /** Where the authentication prompt ends in output made of these lines. */
    private static int promptEnd(String... lines)
    {
        return InfobaseSessionSupport.authenticationPromptEnd(lines);
    }

    /** A completed run that exited with 0 and printed this, judged as the list judges every run. */
    private static ReadResult listedWithExitZero(String stdout)
    {
        return InfobaseSessionSupport.listOutcome(
            new InfobaseSessionSupport.CommandExecution(0, stdout, "", false)); //$NON-NLS-1$
    }

    /**
     * ibcmd's six complete prompts: in each language the greeting and then the user-name and
     * password prompts on one line, the same on two lines, or the password prompt that names the user.
     */
    private static List<List<String>> completePrompts()
    {
        return List.of(List.of(RU_GREETING, RU_PROMPTS),
            List.of(RU_GREETING, RU_USER_PROMPT, RU_PASSWORD_PROMPT),
            List.of(RU_GREETING, RU_PASSWORD_FOR_ADMINISTRATOR),
            List.of(EN_GREETING, EN_PROMPTS),
            List.of(EN_GREETING, EN_USER_PROMPT, EN_PASSWORD_PROMPT),
            List.of(EN_GREETING, EN_PASSWORD_FOR_ADMINISTRATOR));
    }

    /** Every part of ibcmd's prompts in both languages, and a line that is no part of them. */
    private static List<String> linesAnEditMayAdd()
    {
        return List.of(RU_GREETING, RU_USER_PROMPT, RU_PASSWORD_PROMPT, RU_PROMPTS,
            RU_PASSWORD_FOR_ADMINISTRATOR, EN_GREETING, EN_USER_PROMPT, EN_PASSWORD_PROMPT, EN_PROMPTS,
            EN_PASSWORD_FOR_ADMINISTRATOR, UNKNOWN_LINE);
    }

    /** Every output that differs from these lines by one edited line of the kinds the test that uses it lists. */
    private static Set<List<String>> oneLineEdits(List<String> lines)
    {
        Set<List<String>> edits = new LinkedHashSet<>();
        for (int index = 0; index < lines.size(); index++)
        {
            String line = lines.get(index);
            edits.add(removed(lines, index));
            edits.add(inserted(lines, index, line));
            for (String text : List.of("x", "x ", " x")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {
                edits.add(replaced(lines, index, text + line));
                edits.add(replaced(lines, index, line + text));
            }
            edits.add(replaced(lines, index, line.strip() + "x")); //$NON-NLS-1$
        }
        for (String added : linesAnEditMayAdd())
        {
            for (int index = 0; index <= lines.size(); index++)
            {
                edits.add(inserted(lines, index, added));
            }
            for (int index = 0; index < lines.size(); index++)
            {
                String line = lines.get(index);
                edits.add(replaced(lines, index, added));
                edits.add(replaced(lines, index, added + line));
                edits.add(replaced(lines, index, line + added));
            }
        }
        return edits;
    }

    private static List<String> removed(List<String> lines, int index)
    {
        List<String> edit = new ArrayList<>(lines);
        edit.remove(index);
        return edit;
    }

    private static List<String> inserted(List<String> lines, int index, String line)
    {
        List<String> edit = new ArrayList<>(lines);
        edit.add(index, line);
        return edit;
    }

    private static List<String> replaced(List<String> lines, int index, String line)
    {
        List<String> edit = new ArrayList<>(lines);
        edit.set(index, line);
        return edit;
    }

    /** These lines as ibcmd output: each ends with a line break. */
    private static String asOutput(List<String> lines)
    {
        return String.join("\r\n", lines) + "\r\n"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The verdict ibcmd output got before #698 whenever its first block was not a session: one
     * unreadable block, so not a proven empty list.
     */
    private static void assertOneUnreadableBlock(ReadResult result)
    {
        assertFalse("must not be a proven empty list: " + result.sessions(), result.isReadable()); //$NON-NLS-1$
        assertNull(result.sessions());
        assertEquals(ONE_UNREADABLE_BLOCK, result.unreachableReason());
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
