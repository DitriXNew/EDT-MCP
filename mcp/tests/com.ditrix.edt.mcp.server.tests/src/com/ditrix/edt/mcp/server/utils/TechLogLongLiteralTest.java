// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.ditrix.edt.mcp.server.utils.techlog.TechLogAnalysis;
import com.ditrix.edt.mcp.server.utils.techlog.TechLogReader;

/**
 * Quoted SQL tokens of any length normalize without recursion: as regex repetitions they threw
 * StackOverflowError from a couple of thousand characters, and the error escaped the scan.
 */
public class TechLogLongLiteralTest
{
    /** Far past the regex's breaking point on any thread stack, still under the reader's 4 MB line cap. */
    private static final int LONG = 200_000;

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void aLongStringLiteralBecomesOnePlaceholder()
    {
        String x = "x".repeat(LONG);
        assertEquals("select ? from t", TechLogAnalysis.normalizeSql("SELECT '" + x + "' FROM t"));
        assertEquals("where a = ?", TechLogAnalysis.normalizeSql("WHERE a = N'" + "it''s ".repeat(LONG / 6) + "'"));
        assertEquals("where a = ?", TechLogAnalysis.normalizeSql("WHERE a = E'" + "\\'".repeat(LONG / 2) + "'"));
    }

    @Test public void longQuotedIdentifiersStayVerbatim()
    {
        String x = "Ab".repeat(LONG / 2);
        assertEquals("select \"" + x + "\" from t", TechLogAnalysis.normalizeSql("SELECT \"" + x + "\" FROM t"));
        assertEquals("select [" + x + "]] " + x + "]", TechLogAnalysis.normalizeSql("SELECT [" + x + "]] " + x + "]"));
        // Whitespace inside an identifier is part of the name; outside it collapses.
        assertEquals("select \"a  b\" , [c \t d]", TechLogAnalysis.normalizeSql("SELECT  \"a  b\"  ,\n[c \t d]"));
    }

    @Test public void anUnclosedLiteralBacksOffToItsLastDoubledQuoteAsTheRegexDid()
    {
        // The regex's greedy back-off: the run never closes, so the last '' closes it instead.
        assertEquals("x = ?'s", TechLogAnalysis.normalizeSql("x = 'it''s"));
        assertEquals("x = ?'tail", TechLogAnalysis.normalizeSql("x = 'a''b''tail"));
        assertEquals("x = 'abc", TechLogAnalysis.normalizeSql("x = 'abc"));
        // A backslash before a line break escapes nothing; the dot of \\. never matched it.
        assertEquals("x = 'a\\ b", TechLogAnalysis.normalizeSql("x = 'a\\\nb"));
        assertEquals("x = 'a\\ '", TechLogAnalysis.normalizeSql("x = 'a\\\n'"));
        assertEquals("x = ?", TechLogAnalysis.normalizeSql("x = 'a\\'b'"));
    }

    @Test public void aScanOfAnEventCarryingALongLiteralCompletes() throws Exception
    {
        String sql = "select t1._fld1 from _reference1 t1 where t1._fld2 = '" + "x".repeat(LONG) + "'";
        String line = "{\"ts\":\"2026-11-15T23:43:10.000000\",\"duration\":\"5000000\",\"name\":\"SDBL\",\"Sdbl\":\"" + sql + "\"}";
        Path log = temporary.newFile("26111523.log").toPath();
        Files.writeString(log, line + "\n", StandardCharsets.UTF_8);
        var index = TechLogReader.read(log, 8, 10, null, null);
        assertEquals(1, index.events.size());
        assertEquals(0, index.rejected);
        assertTrue(index.groups.values().stream()
            .anyMatch(g -> g.key.equals("SDBL: select t1._fld1 from _reference1 t1 where t1._fld2 = ?")));
    }
}
