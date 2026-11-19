/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * The regular expressions rewritten for backtracking (Sonar S8786) accept exactly the language
 * they accepted before, with the same groups, and the method terminator tail no longer takes
 * time quadratic in the length of a blank run.
 */
public class SonarRegexRewriteTest
{
    private static final Pattern OLD_METHOD_END_TAIL = Pattern.compile("^\\s*;?\\s*(?://.*)?$"); //$NON-NLS-1$
    private static final Pattern OLD_FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$"); //$NON-NLS-1$
    private static final Pattern OLD_HEADING = Pattern.compile("^ {0,3}(#{1,6})(?:[\\t ]+(.*)|[\\t ]*)$"); //$NON-NLS-1$

    /**
     * A terminator followed by a long blank run and a stray character: the old tail pattern split
     * the run between its two optional blank runs every possible way and took seconds here.
     */
    @Test(timeout = 3000)
    public void testALongBlankTailAfterATerminatorIsRejectedInLinearTime()
    {
        String line = "EndProcedure" + " ".repeat(60_000) + "x"; //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(BslModuleUtils.isMethodTerminatorLine(line, BslModuleUtils.PROCEDURE_END_PATTERN));
    }

    @Test
    public void testTheTerminatorTailStillAcceptsWhatItAcceptedBefore()
    {
        Pattern end = BslModuleUtils.PROCEDURE_END_PATTERN;
        assertTrue(BslModuleUtils.isMethodTerminatorLine("EndProcedure", end)); //$NON-NLS-1$
        assertTrue(BslModuleUtils.isMethodTerminatorLine("  EndProcedure ;  // note", end)); //$NON-NLS-1$
        assertTrue(BslModuleUtils.isMethodTerminatorLine("EndProcedure;// note", end)); //$NON-NLS-1$
        assertTrue(BslModuleUtils.isMethodTerminatorLine("EndProcedure\t", end)); //$NON-NLS-1$
        assertFalse(BslModuleUtils.isMethodTerminatorLine("EndProcedure; Value = 1;", end)); //$NON-NLS-1$
        assertFalse(BslModuleUtils.isMethodTerminatorLine("EndProcedure ; ;", end)); //$NON-NLS-1$
        assertFalse(BslModuleUtils.isMethodTerminatorLine("EndProcedure  x", end)); //$NON-NLS-1$
    }

    @Test
    public void testTheTerminatorTailMatchesTheSameLanguage() throws Exception
    {
        assertSameLanguage(OLD_METHOD_END_TAIL, field(BslModuleUtils.class, "METHOD_END_TAIL_PATTERN"), //$NON-NLS-1$
            " \t;/x\n", 6); //$NON-NLS-1$
    }

    @Test
    public void testTheFencePatternMatchesTheSameLanguageAndGroups() throws Exception
    {
        assertSameLanguage(OLD_FENCE, field(GuideSections.class, "FENCE"), " `~x ", 7); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTheHeadingPatternMatchesTheSameLanguageAndGroups() throws Exception
    {
        assertSameLanguage(OLD_HEADING, field(GuideSections.class, "HEADING"), " \t#x ", 8); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test(timeout = 3000)
    public void testTheGuidePatternsStayLinearOnLongRuns() throws Exception
    {
        String run = " ".repeat(60_000); //$NON-NLS-1$
        assertFalse(field(GuideSections.class, "FENCE").matcher("`".repeat(60_000) + " ").matches()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(field(GuideSections.class, "HEADING").matcher("#" + run + " ").matches()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** Every string over {@code alphabet} up to {@code maxLength}: same verdict, same groups. */
    static void assertSameLanguage(Pattern before, Pattern after, String alphabet, int maxLength)
    {
        assertEquals(before.matcher("").groupCount(), after.matcher("").groupCount()); //$NON-NLS-1$ //$NON-NLS-2$
        for (String input : allStrings(alphabet, maxLength))
        {
            Matcher old = before.matcher(input);
            Matcher now = after.matcher(input);
            boolean matched = old.matches();
            assertEquals(escape(input), matched, now.matches());
            if (matched)
            {
                for (int group = 0; group <= old.groupCount(); group++)
                {
                    assertEquals(escape(input) + " group " + group, old.group(group), now.group(group)); //$NON-NLS-1$
                }
            }
        }
    }

    public static List<String> allStrings(String alphabet, int maxLength)
    {
        List<String> result = new ArrayList<>();
        result.add(""); //$NON-NLS-1$
        int from = 0;
        for (int length = 1; length <= maxLength; length++)
        {
            int to = result.size();
            for (int i = from; i < to; i++)
            {
                for (char c : alphabet.toCharArray())
                {
                    result.add(result.get(i) + c);
                }
            }
            from = to;
        }
        return result;
    }

    public static String escape(String input)
    {
        StringBuilder out = new StringBuilder("'"); //$NON-NLS-1$
        for (char c : input.toCharArray())
        {
            out.append(c >= 0x20 && c < 0x7f ? String.valueOf(c) : String.format("\\u%04x", (int)c)); //$NON-NLS-1$
        }
        return out.append('\'').toString();
    }

    private static Pattern field(Class<?> owner, String name) throws Exception
    {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return (Pattern)field.get(null);
    }
}
