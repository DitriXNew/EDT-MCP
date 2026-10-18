/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/** Stack depth regression and language preservation for the inline-pragma refusal. */
public class BslModuleUtilsSonarFindingsTest
{
    // The expression shipped at eb616e1c is an independent compatibility oracle on short inputs.
    private static final Pattern LEGACY = Pattern.compile(
        "^\\s*(?:&[\\p{L}\\p{N}_]+(?:\\([^)]*\\))?\\s+)+" //$NON-NLS-1$
            + "(?:\u0410\u0441\u0438\u043D\u0445\\s+|Async\\s+)?(?:\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430|\u0424\u0443\u043D\u043A\u0446\u0438\u044F|Procedure|Function)" //$NON-NLS-1$
            + "(?![\\p{L}\\p{N}_])", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    @Test(timeout = 10000)
    public void largePragmaRunIsRefusedWithoutStackOverflow()
    {
        String pragmas = "&AtClient ".repeat(4096); //$NON-NLS-1$
        for (String declaration : List.of("Procedure Added()", //$NON-NLS-1$
            "\u0410\u0441\u0438\u043D\u0445 \u0424\u0443\u043D\u043A\u0446\u0438\u044F \u0414\u043E\u0431\u0430\u0432\u043B\u0435\u043D\u043E()")) //$NON-NLS-1$
        {
            assertEquals(0, BslModuleUtils.unaddressableDeclarationLine(List.of(pragmas + declaration)));
        }
    }

    @Test(timeout = 10000)
    public void largePragmaOnlyLineStaysAddressable()
    {
        assertEquals(-1, BslModuleUtils.unaddressableDeclarationLine(List.of(
            "&AtClient ".repeat(4096), "Procedure Added()", "EndProcedure"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void pragmaDeclarationLanguageMatchesLegacyExpression() throws Exception
    {
        Pattern current = currentPattern();
        String[] prefixes = {"", " ", "\t", "\r\n", "\f", "\u000B"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        String[] pragmas = {"&A ", "&1_ ", "&AtClient &Before(x) ", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "&\u041D\u0430\u041A\u043B\u0438\u0435\u043D\u0442\u0435 ", "&A()\t", "&A(a(b) ", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "&A(a\nb) ", "&A(x)", "&A&Other ", "&A( ", "&A\u00A0", ""}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        String[] keywords = {"Procedure", "function", "Async Procedure", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430", //$NON-NLS-1$
            "\u0410\u0441\u0438\u043D\u0445 \u0424\u0443\u043D\u043A\u0446\u0438\u044F", "NotAMethod"}; //$NON-NLS-1$ //$NON-NLS-2$
        String[] suffixes = {" Added()", "_name", "2", "\u0410", ".", ""}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        for (String prefix : prefixes)
        {
            for (String pragma : pragmas)
            {
                for (String keyword : keywords)
                {
                    for (String suffix : suffixes)
                    {
                        String line = prefix + pragma + keyword + suffix;
                        Matcher expected = LEGACY.matcher(line);
                        Matcher actual = current.matcher(line);
                        boolean matched = expected.find();
                        assertEquals(line, matched, actual.find());
                        if (matched)
                        {
                            assertEquals(line, expected.end(), actual.end());
                        }
                    }
                }
            }
        }
        assertTrue(current.matcher("&AtClient Procedure Added()").find()); //$NON-NLS-1$
        assertFalse(current.matcher("&AtClient ProcedureName()").find()); //$NON-NLS-1$
    }

    private static Pattern currentPattern() throws Exception
    {
        Field field = BslModuleUtils.class.getDeclaredField("PRAGMA_ON_DECLARATION_LINE_PATTERN"); //$NON-NLS-1$
        field.setAccessible(true);
        return (Pattern)field.get(null);
    }
}
