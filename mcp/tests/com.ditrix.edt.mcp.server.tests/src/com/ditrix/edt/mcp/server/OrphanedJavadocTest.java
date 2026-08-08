/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * Ratchet against ORPHANED javadoc: a {@code /** ... *}{@code /} block that documents
 * nothing, because a new member was inserted BETWEEN the block and the declaration it
 * was written for. The compiler cannot see it — javadoc is prose, and the inserted
 * member usually brought its own block — so the documentation silently detaches and the
 * member it belonged to is left undocumented.
 * <p>
 * Detected shape: a javadoc block whose next meaningful line is ANOTHER javadoc block.
 * That is only ever the accident above; two javadoc blocks in a row document nothing
 * legal in Java.
 * <p>
 * <b>The fix is to MOVE the block back, not to delete it.</b> Across #341/#345/#353,
 * five of six such blocks were the ONLY documentation their method had — a mechanical
 * clean-up would have thrown the documentation away. Read the block, find the
 * declaration it describes (usually the one below the member that was inserted after
 * it), and put it there. Delete it only when the declaration it describes is gone, or
 * when a newer block on the same declaration supersedes it — and say so in the PR.
 * <p>
 * {@link #KNOWN_ORPHANS} is a shrinking budget, in the shape
 * {@code BuiltInToolTestCoverageTest} uses: a file may carry the listed number of
 * orphans and no more, and {@link #allowListHasNoStaleEntries} fails once a listed file
 * is cleaner than its budget, so an entry cannot outlive the debt it records.
 */
public class OrphanedJavadocTest
{
    /**
     * Files allowed to still carry orphaned blocks, with how many. RATCHET: the number
     * may only go DOWN. Both entries are the last two sites of issue #353, deferred
     * because the open PR #330 edits exactly these two files and the cleanup would
     * collide with it; they come out as soon as that PR lands.
     */
    private static final Map<String, Integer> KNOWN_ORPHANS = new HashMap<>();
    static
    {
        KNOWN_ORPHANS.put("mcp/bundles/com.ditrix.edt.mcp.server/src" //$NON-NLS-1$
            + "/com/ditrix/edt/mcp/server/preferences/PreferenceConstants.java", Integer.valueOf(1)); //$NON-NLS-1$
        KNOWN_ORPHANS.put("mcp/bundles/com.ditrix.edt.mcp.server/src" //$NON-NLS-1$
            + "/com/ditrix/edt/mcp/server/preferences/ToolSettingsService.java", Integer.valueOf(1)); //$NON-NLS-1$
    }

    /** The source trees this ratchet covers; the first two must exist. */
    private static final String[] SOURCE_ROOTS = {
        "mcp/bundles/com.ditrix.edt.mcp.server/src", //$NON-NLS-1$
        "mcp/tests/com.ditrix.edt.mcp.server.tests/src", //$NON-NLS-1$
        "proxy/src/main/java" //$NON-NLS-1$
    };

    @Test
    public void noOrphanedJavadocOutsideTheAllowList()
    {
        Map<String, List<Integer>> scanned = scanSources();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> entry : scanned.entrySet())
        {
            int budget = KNOWN_ORPHANS.containsKey(entry.getKey())
                ? KNOWN_ORPHANS.get(entry.getKey()).intValue() : 0;
            if (entry.getValue().size() > budget)
            {
                problems.add(entry.getKey() + " -> orphaned javadoc starting at line(s) " //$NON-NLS-1$
                    + entry.getValue() + (budget > 0 ? " (allow-listed: " + budget + ')' : "")); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        assertTrue("Javadoc blocks that document nothing (a member was inserted between the block and " //$NON-NLS-1$
            + "its declaration). MOVE each block back to the declaration it describes - do NOT just " //$NON-NLS-1$
            + "delete it, it is usually that declaration's only documentation:\n  " //$NON-NLS-1$
            + String.join("\n  ", problems), problems.isEmpty()); //$NON-NLS-1$
    }

    /**
     * Keeps the budget honest in the other direction: a file that is now clean (or
     * cleaner) must lose its entry, and every entry must name a file that is actually
     * scanned — so a typo or a renamed file cannot silently disable the check.
     */
    @Test
    public void allowListHasNoStaleEntries()
    {
        Map<String, List<Integer>> scanned = scanSources();
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : KNOWN_ORPHANS.entrySet())
        {
            if (!scanned.containsKey(entry.getKey()))
            {
                stale.add(entry.getKey() + ": allow-listed but no such source file was scanned"); //$NON-NLS-1$
                continue;
            }
            int actual = scanned.get(entry.getKey()).size();
            if (actual < entry.getValue().intValue())
            {
                stale.add(entry.getKey() + ": allow-listed for " + entry.getValue() + " orphan(s) but has " //$NON-NLS-1$ //$NON-NLS-2$
                    + actual);
            }
        }
        assertTrue("Stale KNOWN_ORPHANS entries - lower the number or drop the entry to tighten the " //$NON-NLS-1$
            + "ratchet:\n  " + String.join("\n  ", stale), stale.isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Positive control: the detector must actually FIRE on the defect. A check whose
     * failure mode looks exactly like its "all clear" answer proves nothing, so the
     * shape this ratchet exists for is asserted on synthetic input every build.
     */
    @Test
    public void detectorFindsAnOrphanedBlock()
    {
        String source = String.join("\n", //$NON-NLS-1$
            "class A", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    /**", //$NON-NLS-1$
            "     * Documents the method BELOW the inserted constant.", //$NON-NLS-1$
            "     */", //$NON-NLS-1$
            "    /** The constant somebody inserted here. */", //$NON-NLS-1$
            "    private static final int C = 1;", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "    void m() {}", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("the orphaned block starts on line 3", //$NON-NLS-1$
            List.of(Integer.valueOf(3)), orphanedJavadocLines(source));

        // The same accident with a blank line left between the two blocks.
        String spaced = String.join("\n", //$NON-NLS-1$
            "class B", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    /** Orphan. */", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "    /** Documents the field. */", //$NON-NLS-1$
            "    int f;", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("a blank line between the blocks must not hide the orphan", //$NON-NLS-1$
            List.of(Integer.valueOf(3)), orphanedJavadocLines(spaced));

        // Nor may a note wedged in between: neither a line comment nor an ordinary block
        // comment is a declaration, so the first block still documents nothing.
        String commented = String.join("\n", //$NON-NLS-1$
            "class C", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    /** Orphan. */", //$NON-NLS-1$
            "    // a note somebody left between the two", //$NON-NLS-1$
            "    /* and an ordinary", //$NON-NLS-1$
            "       block comment */", //$NON-NLS-1$
            "    /** Documents the field. */", //$NON-NLS-1$
            "    int f;", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("a comment between the blocks must not hide the orphan", //$NON-NLS-1$
            List.of(Integer.valueOf(3)), orphanedJavadocLines(commented));

        // CRLF sources (this repository's working tree) must be read the same way.
        assertEquals("CRLF input must be detected identically", //$NON-NLS-1$
            List.of(Integer.valueOf(3)), orphanedJavadocLines(spaced.replace("\n", "\r\n"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Negative control: well-formed documentation, an annotated member, an empty
     * {@code /**}{@code /} comment and a line comment between two blocks must NOT be
     * reported — a detector that flags legal code gets switched off.
     */
    @Test
    public void detectorAcceptsWellFormedJavadoc()
    {
        String source = String.join("\n", //$NON-NLS-1$
            "/**", //$NON-NLS-1$
            " * File header.", //$NON-NLS-1$
            " */", //$NON-NLS-1$
            "package p;", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "/** Documents the class. */", //$NON-NLS-1$
            "class C", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    /** Documents the field. */", //$NON-NLS-1$
            "    int f;", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "    /** Documents the annotated method. */", //$NON-NLS-1$
            "    @Override", //$NON-NLS-1$
            "    public String toString() { return \"c\"; }", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "    /** Documents g. */", //$NON-NLS-1$
            "    // an ordinary comment is not a declaration, but it is not javadoc either", //$NON-NLS-1$
            "    void g() {}", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "    /**/", //$NON-NLS-1$
            "    /** An empty block comment above me is not an orphan of mine. */", //$NON-NLS-1$
            "    void h() {}", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "    /** i */ int i;", //$NON-NLS-1$
            "    /** j */ int j;", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("well-formed javadoc must not be reported", //$NON-NLS-1$
            List.of(), orphanedJavadocLines(source));

        // The compact form again, this time with the declaration after a MULTI-line block.
        String compact = String.join("\n", //$NON-NLS-1$
            "class D", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    /**", //$NON-NLS-1$
            "     * Documents k, which sits on the closing line.", //$NON-NLS-1$
            "     */ int k;", //$NON-NLS-1$
            "    /** Documents l. */", //$NON-NLS-1$
            "    int l;", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("a declaration on the block's closing line is documented, not orphaned", //$NON-NLS-1$
            List.of(), orphanedJavadocLines(compact));
    }

    // === detector ===

    /**
     * @param source the contents of one {@code .java} file
     * @return the 1-based start lines of every javadoc block whose next meaningful line
     *         is another javadoc block
     */
    static List<Integer> orphanedJavadocLines(String source)
    {
        String[] lines = source.split("\n", -1); //$NON-NLS-1$
        List<Integer> orphans = new ArrayList<>();
        int i = 0;
        while (i < lines.length)
        {
            int end = javadocEnd(lines, i);
            if (end < 0)
            {
                i++;
                continue;
            }
            if (tailAfterJavadoc(lines[end]).isEmpty() && isJavadocStart(nextMeaningful(lines, end)))
            {
                orphans.add(Integer.valueOf(i + 1));
            }
            i = end + 1;
        }
        return orphans;
    }

    /**
     * What follows the block's own closing {@code *}{@code /} on that same line. A
     * compact {@code /** f *}{@code /} int f;} documents the declaration sitting right
     * there, so the NEXT line is somebody else's business and the block is not orphaned.
     */
    private static String tailAfterJavadoc(String closingLine)
    {
        int close = closingLine.indexOf("*/"); //$NON-NLS-1$
        return close < 0 ? "" : closingLine.substring(close + 2).trim(); //$NON-NLS-1$
    }

    /**
     * The first line after {@code end} that could carry a declaration. Blank lines, line
     * comments and ordinary {@code /* ... *}{@code /} block comments are skipped: none of
     * them is a declaration, so a note wedged between the block and the member that
     * displaced it must not hide the orphan.
     *
     * @return that line, or {@code ""} at end of file
     */
    private static String nextMeaningful(String[] lines, int end)
    {
        int next = end + 1;
        while (next < lines.length)
        {
            String trimmed = lines[next].trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//")) //$NON-NLS-1$
            {
                next++;
                continue;
            }
            if (trimmed.startsWith("/*") && !isJavadocStart(lines[next])) //$NON-NLS-1$
            {
                // An ordinary block comment: skip past its end, wherever that is.
                while (next < lines.length && !lines[next].contains("*/")) //$NON-NLS-1$
                {
                    next++;
                }
                if (next < lines.length && tailAfterJavadoc(lines[next]).isEmpty())
                {
                    next++;
                    continue;
                }
                return next < lines.length ? tailAfterJavadoc(lines[next]) : ""; //$NON-NLS-1$
            }
            return lines[next];
        }
        return ""; //$NON-NLS-1$
    }

    /** {@code /**}{@code /} is an EMPTY block comment, not javadoc - it documents nothing by design. */
    private static boolean isJavadocStart(String line)
    {
        String trimmed = line.trim();
        return trimmed.startsWith("/**") && !trimmed.startsWith("/**/"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * @return the index of the line closing the javadoc block that starts at
     *         {@code from}, or {@code -1} when no block starts there (or it is unterminated)
     */
    private static int javadocEnd(String[] lines, int from)
    {
        if (!isJavadocStart(lines[from]))
        {
            return -1;
        }
        if (lines[from].trim().indexOf("*/", 2) >= 0) //$NON-NLS-1$
        {
            return from;
        }
        for (int j = from + 1; j < lines.length; j++)
        {
            if (lines[j].contains("*/")) //$NON-NLS-1$
            {
                return j;
            }
        }
        return -1;
    }

    // === source scan ===

    /** @return every scanned {@code .java} file (repository-relative path) mapped to its orphans */
    private static Map<String, List<Integer>> scanSources()
    {
        Map<String, List<Integer>> result = new LinkedHashMap<>();
        int scannedRoots = 0;
        for (int r = 0; r < SOURCE_ROOTS.length; r++)
        {
            File root = locate(SOURCE_ROOTS[r]);
            if (root == null)
            {
                // The first two roots are this repository's own bundles: a missing one means
                // the locator is wrong for this layout, and a silently empty scan would pass.
                if (r < 2)
                {
                    fail("could not locate the source root '" + SOURCE_ROOTS[r] //$NON-NLS-1$
                        + "' by walking up from user.dir=" + System.getProperty("user.dir")); //$NON-NLS-1$ //$NON-NLS-2$
                }
                continue;
            }
            scannedRoots++;
            scanRoot(SOURCE_ROOTS[r], root, result);
        }
        assertTrue("no source root was scanned - the ratchet would pass vacuously", scannedRoots > 0); //$NON-NLS-1$
        assertTrue("scanned no .java file at all - the ratchet would pass vacuously", //$NON-NLS-1$
            !result.isEmpty());
        return result;
    }

    private static void scanRoot(String rootPath, File root, Map<String, List<Integer>> into)
    {
        Path base = root.toPath();
        try (Stream<Path> files = Files.walk(base))
        {
            files.filter(p -> p.getFileName().toString().endsWith(".java")) //$NON-NLS-1$
                .sorted()
                // Keyed by the ROOT-qualified path: two source roots can hold the same
                // relative path, and a bare relative key would let one silently replace
                // the other's result (and with it, its orphans).
                .forEach(p -> into.put(rootPath + '/' + base.relativize(p).toString().replace('\\', '/'),
                    orphanedJavadocLines(read(p))));
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path)
    {
        try
        {
            String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            // A UTF-8 BOM survives decoding as U+FEFF, which trim() does NOT strip - it would
            // hide a javadoc block that starts on the very first line.
            return text.isEmpty() || text.charAt(0) != '\uFEFF' ? text : text.substring(1);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    private static File locate(String relative)
    {
        File dir = new File(System.getProperty("user.dir")); //$NON-NLS-1$
        for (int i = 0; i < 12 && dir != null; i++)
        {
            File candidate = new File(dir, relative);
            if (candidate.isDirectory())
            {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        return null;
    }
}
