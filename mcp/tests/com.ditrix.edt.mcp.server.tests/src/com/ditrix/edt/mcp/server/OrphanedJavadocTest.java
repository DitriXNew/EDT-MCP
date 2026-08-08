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
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * Ratchet against ORPHANED javadoc: a {@code /** ... *}{@code /} block that documents
 * nothing, because a new member was inserted BETWEEN the block and the declaration it
 * was written for. The compiler cannot see it — javadoc is prose, and the inserted
 * member usually brought its own block — so the documentation silently detaches and the
 * member it belonged to is left undocumented.
 * <p>
 * Javadoc binds the comment that immediately precedes a declaration's FIRST token — its
 * first annotation or modifier. Measured, not assumed: {@code javadoc} was run on each
 * shape below and asked which text it rendered. Two blocks are therefore dropped:
 * <ul>
 *   <li>one whose next meaningful line is ANOTHER javadoc block — the later block is the
 *       nearer one, so the earlier documents nothing;</li>
 *   <li>one that sits AFTER that first token, i.e. between {@code @Deprecated} and the
 *       member. Here it is the block BEFORE the annotation that survives, which is why
 *       reporting "the first of the pair" would name the wrong one.</li>
 * </ul>
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
        "proxy/src/main/java", //$NON-NLS-1$
        "proxy/src/test/java" //$NON-NLS-1$
    };

    /**
     * What the detector cannot see, printed WITH every refusal. Only the first entry can
     * produce a wrong accusation; the rest can only ever miss one, which is the trade this
     * detector makes on purpose — a ratchet that reddens on legal code is switched off by
     * the first person it inconveniences.
     * <p>
     * The unicode-escape gap is left open deliberately, not postponed: the input is
     * unreachable here (every {@code \\uXXXX} in this repository sits inside a literal or a
     * comment, never in a structural position — checked, not assumed), and the cure is
     * worse than the disease, since translating escapes moves the very line numbers this
     * detector reports by.
     */
    private static final String KNOWN_LIMITS =
        "If you believe this is a FALSE alarm, these are the detector's known blind spots:\n" //$NON-NLS-1$
            + "  - a structural token spelled as a unicode escape (\\u003b for ';') is not translated,\n" //$NON-NLS-1$
            + "    so a declaration head can stay open past it. This is the ONLY one that can accuse\n" //$NON-NLS-1$
            + "    wrongly; if it bit you, say so on the issue rather than working around it.\n" //$NON-NLS-1$
            + "  - a head opened by a type-parameter list alone (<T>) or by non-sealed is not seen;\n" //$NON-NLS-1$
            + "  - 'default' is not a head-opening modifier (a 'default:' switch label is commoner);\n" //$NON-NLS-1$
            + "  - a ',' at depth 0 closes the head, so 'throws A, B', 'implements A, B', '<A, B>'\n" //$NON-NLS-1$
            + "    and a multi-declarator field give it up early.\n" //$NON-NLS-1$
            + "The last three can only MISS a defect, never report one. Details: OrphanedJavadocTest."; //$NON-NLS-1$

    /**
     * The modifiers that OPEN a declaration head. {@code default} is deliberately absent:
     * a {@code default:} switch label is far commoner than a {@code default} method
     * signature split across lines, and treating it as a head would refuse legal code —
     * leaving it out only costs a miss.
     */
    private static final Set<String> MODIFIERS = Set.of("public", "protected", "private", "static", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "final", "abstract", "synchronized", "native", "transient", "volatile", "strictfp", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        "sealed"); //$NON-NLS-1$

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
        assertTrue(refusalText(problems), problems.isEmpty());
    }

    /**
     * The whole refusal, in one place so it can be asserted rather than hoped for. It
     * names {@link #KNOWN_LIMITS} because the person who needs to know what the detector
     * CANNOT see is the one holding the refusal, not the one who opens this file — a
     * refusal nobody can argue with costs somebody an hour.
     *
     * @param problems the offending files, already formatted
     * @return the assertion message
     */
    static String refusalText(List<String> problems)
    {
        return "Javadoc blocks that document nothing (a member was inserted between the block and " //$NON-NLS-1$
            + "its declaration). MOVE each block back to the declaration it describes - do NOT just " //$NON-NLS-1$
            + "delete it, it is usually that declaration's only documentation:\n  " //$NON-NLS-1$
            + String.join("\n  ", problems) //$NON-NLS-1$
            + "\n\n" + KNOWN_LIMITS; //$NON-NLS-1$
    }

    /**
     * The refusal has to be arguable: it must name the offending places AND what the
     * detector is known not to see. Asserted rather than assumed, because the blind spots
     * are easy to drop from the message while leaving them true.
     */
    @Test
    public void theRefusalNamesBothTheFindingsAndTheBlindSpots()
    {
        String text = refusalText(List.of("Foo.java -> orphaned javadoc starting at line(s) [42]")); //$NON-NLS-1$
        assertTrue("the refusal must name the offending place", text.contains("Foo.java")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("and the line it is accusing", text.contains("[42]")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("it must say what to do - move the block, not delete it", //$NON-NLS-1$
            text.contains("MOVE each block back")); //$NON-NLS-1$
        assertTrue("and it must carry the known blind spots, or a false alarm is unarguable", //$NON-NLS-1$
            text.contains(KNOWN_LIMITS));
        assertTrue("naming the one blind spot that can accuse wrongly", //$NON-NLS-1$
            text.contains("unicode escape")); //$NON-NLS-1$
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
     * Positive control for the OTHER shape of the same accident: a block that sits after
     * the declaration's first token. Which of the two blocks survives is not a matter of
     * taste — {@code javadoc} was run on exactly these sources, and the one BEFORE the
     * annotation is the one it renders — so the block reported here is the discarded one.
     */
    @Test
    public void detectorFindsABlockInsideADeclarationPrefix()
    {
        String annotated = String.join("\n", //$NON-NLS-1$
            "class A", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    /** Attached: javadoc renders THIS one. */", //$NON-NLS-1$
            "    @Deprecated", //$NON-NLS-1$
            "    /** Dropped: it is inside the declaration. */", //$NON-NLS-1$
            "    void m() {}", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("the block AFTER the annotation is the discarded one", //$NON-NLS-1$
            List.of(Integer.valueOf(5)), orphanedJavadocLines(annotated));

        // An annotation with arguments, wrapped: the prefix does not end at the newline.
        String wrapped = String.join("\n", //$NON-NLS-1$
            "class B", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    /** Attached. */", //$NON-NLS-1$
            "    @SuppressWarnings({\"unchecked\",", //$NON-NLS-1$
            "        \"rawtypes\"})", //$NON-NLS-1$
            "    /** Dropped. */", //$NON-NLS-1$
            "    void m() {}", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("a wrapped annotation is still a declaration prefix", //$NON-NLS-1$
            List.of(Integer.valueOf(6)), orphanedJavadocLines(wrapped));

        // A signature split after its modifiers.
        String split = String.join("\n", //$NON-NLS-1$
            "class C", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    /** Attached. */", //$NON-NLS-1$
            "    public static", //$NON-NLS-1$
            "    /** Dropped. */", //$NON-NLS-1$
            "    void m() {}", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("modifiers open the declaration just as an annotation does", //$NON-NLS-1$
            List.of(Integer.valueOf(5)), orphanedJavadocLines(split));

        // The head does not close at the annotation's own braces: they are inside its
        // argument list, not the member's body.
        String braced = String.join("\n", //$NON-NLS-1$
            "class D", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    /** Attached. */", //$NON-NLS-1$
            "    @SuppressWarnings({", //$NON-NLS-1$
            "        \"unchecked\"", //$NON-NLS-1$
            "    })", //$NON-NLS-1$
            "    /** Dropped. */", //$NON-NLS-1$
            "    public void m() {}", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("an annotation's own braces must not close the head", //$NON-NLS-1$
            List.of(Integer.valueOf(7)), orphanedJavadocLines(braced));

        // A modifier can be followed by an annotation on the same line.
        String mixed = String.join("\n", //$NON-NLS-1$
            "class E", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    /** Attached. */", //$NON-NLS-1$
            "    public @Deprecated", //$NON-NLS-1$
            "    /** Dropped. */", //$NON-NLS-1$
            "    void m() {}", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("a modifier followed by an annotation is still one open head", //$NON-NLS-1$
            List.of(Integer.valueOf(5)), orphanedJavadocLines(mixed));

        // Three blocks, ONE line, two of them dropped: the report counts BLOCKS, so the
        // budget cannot be gamed by writing them on a single line.
        assertEquals("two blocks sharing a line are two findings, not one", //$NON-NLS-1$
            List.of(Integer.valueOf(1), Integer.valueOf(1)),
            orphanedJavadocLines("/** one */ /** two */ /** three */ int f;")); //$NON-NLS-1$

        // The accusation carries a LINE, and a wrong one sends the reader somewhere else
        // entirely. Both multi-line comment forms must therefore be counted through.
        String afterLongComments = String.join("\n", //$NON-NLS-1$
            "class F", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    /*", //$NON-NLS-1$
            "     * an ordinary", //$NON-NLS-1$
            "     * multi-line comment", //$NON-NLS-1$
            "     */", //$NON-NLS-1$
            "    /**", //$NON-NLS-1$
            "     * a multi-line javadoc that documents m", //$NON-NLS-1$
            "     */", //$NON-NLS-1$
            "    void m() {}", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "    /** Orphan. */", //$NON-NLS-1$
            "    /** Documents f. */", //$NON-NLS-1$
            "    int f;", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("the line must be counted through every multi-line comment above it", //$NON-NLS-1$
            List.of(Integer.valueOf(12)), orphanedJavadocLines(afterLongComments));
    }

    /**
     * Negative control for the shapes the two new branches could wrongly refuse. A ratchet
     * that reddens on legal code blocks work that is not even wrong, and is switched off by
     * the first person it inconveniences — so a false refusal costs more than a miss.
     */
    @Test
    public void detectorAcceptsTextBlocksAndAnnotatedMembers()
    {
        // A Java 17 text block holding Java source: its content is DATA. Without the text
        // block being blanked, these two lines read as consecutive javadoc.
        String fixture = String.join("\n", //$NON-NLS-1$
            "class A", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    private static final String SOURCE = \"\"\"", //$NON-NLS-1$
            "        /** first */", //$NON-NLS-1$
            "        /** second */", //$NON-NLS-1$
            "        int f;", //$NON-NLS-1$
            "        \"\"\";", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "    /** Documents m. */", //$NON-NLS-1$
            "    void m() {}", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("a text block's contents are data, not javadoc", //$NON-NLS-1$
            List.of(), orphanedJavadocLines(fixture));

        // …and it must END where Java ends it. This block holds an ODD number of quote
        // characters, so lexing it as ordinary string literals re-pairs every quote after
        // it and swallows the REAL orphan below — the assertion above cannot tell the two
        // apart on its own (its quotes happen to pair up either way), this one can.
        String oddQuotes = String.join("\n", //$NON-NLS-1$
            "class B", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    private static final String Q = \"\"\"", //$NON-NLS-1$
            "        a \" b", //$NON-NLS-1$
            "        \"\"\";", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "    /** Orphan. */", //$NON-NLS-1$
            "    /** Documents m. */", //$NON-NLS-1$
            "    void m() {}", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("a text block ends where Java ends it, so the orphan after it is found", //$NON-NLS-1$
            List.of(Integer.valueOf(7)), orphanedJavadocLines(oddQuotes));

        // Enum constants end in ',' - an unfinished LIST, not an unfinished declaration.
        String constants = String.join("\n", //$NON-NLS-1$
            "enum E", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    /** Documents A. */", //$NON-NLS-1$
            "    A,", //$NON-NLS-1$
            "    /** Documents B. */", //$NON-NLS-1$
            "    B,", //$NON-NLS-1$
            "    /** Documents C. */", //$NON-NLS-1$
            "    C;", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("enum constants must not read as an open declaration", //$NON-NLS-1$
            List.of(), orphanedJavadocLines(constants));

        // Two ways a naive backward walk mistakes a FINISHED member for an open
        // declaration and then trips over the @Override above it:
        //   - this repository ends almost every line with a trailing NLS marker, which
        //     hides the '}' that closed the member;
        //   - the "://" literal contains the two characters that start a line comment, so
        //     a comment strip that ignores string literals eats the rest of the line.
        // Both are why the backward walk runs over a comment-blanked, quote-aware view.
        String suppressed = String.join("\n", //$NON-NLS-1$
            "class A", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    @Override", //$NON-NLS-1$
            "    public String scheme() { return \"://\"; } //" + "$NON-NLS-1$", //$NON-NLS-1$ //$NON-NLS-2$
            "", //$NON-NLS-1$
            "    /** Documents m. */", //$NON-NLS-1$
            "    void m() {}", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("a trailing NLS marker must not hide the previous member's '}'", //$NON-NLS-1$
            List.of(), orphanedJavadocLines(suppressed));

        // An enum whose brace shares the line with its first constant: the head opened by
        // 'public' is closed by that '{', so the constants' own javadoc is attached.
        String inlineBrace = String.join("\n", //$NON-NLS-1$
            "/** Documents E. */", //$NON-NLS-1$
            "@Deprecated", //$NON-NLS-1$
            "public enum E { A,", //$NON-NLS-1$
            "    /** Documents B. */", //$NON-NLS-1$
            "    B;", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("a brace sharing the declaration's line still closes the head", //$NON-NLS-1$
            List.of(), orphanedJavadocLines(inlineBrace));

        // The ';' that ends an annotated text-block field lives on the block's CLOSING
        // line - the one a line-based scanner is most tempted to throw away whole.
        String annotatedTextBlock = String.join("\n", //$NON-NLS-1$
            "public class A", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    @Deprecated", //$NON-NLS-1$
            "    public static String s = \"\"\"", //$NON-NLS-1$
            "        data", //$NON-NLS-1$
            "        \"\"\";", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "    /** Documents m. */", //$NON-NLS-1$
            "    public void m() {}", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("the text block's closing line still carries the field's ';'", //$NON-NLS-1$
            List.of(), orphanedJavadocLines(annotatedTextBlock));

        // An escaped triple quote does NOT close a text block, so what follows is data.
        String escapedQuotes = String.join("\n", //$NON-NLS-1$
            "class A", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    String s = \"\"\"", //$NON-NLS-1$
            "        \\\"\"\"", //$NON-NLS-1$
            "        /** data one */", //$NON-NLS-1$
            "        /** data two */", //$NON-NLS-1$
            "        \"\"\";", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("an escaped triple quote does not end the text block", //$NON-NLS-1$
            List.of(), orphanedJavadocLines(escapedQuotes));

        // A switch's 'default:' label must not read as a declaration modifier.
        String switchDefault = String.join("\n", //$NON-NLS-1$
            "class A", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    void m(int x)", //$NON-NLS-1$
            "    {", //$NON-NLS-1$
            "        switch (x)", //$NON-NLS-1$
            "        {", //$NON-NLS-1$
            "            default:", //$NON-NLS-1$
            "                /** legal, if odd, and documents nothing by design */", //$NON-NLS-1$
            "                break;", //$NON-NLS-1$
            "        }", //$NON-NLS-1$
            "    }", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("a 'default:' label is not a declaration head", //$NON-NLS-1$
            List.of(), orphanedJavadocLines(switchDefault));

        // An ANNOTATED enum constant: the head it opens is closed by the ',' that ends the
        // constant, not by a ';' - without that, the next constant's own javadoc is accused.
        String annotatedConstant = String.join("\n", //$NON-NLS-1$
            "public enum E", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    @Deprecated A,", //$NON-NLS-1$
            "    /** Documents B. */ B", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("a ',' closes the head an annotated enum constant opened", //$NON-NLS-1$
            List.of(), orphanedJavadocLines(annotatedConstant));

        // Truncated sources must terminate and accuse nobody, not hang or throw.
        assertEquals("an unterminated string literal", //$NON-NLS-1$
            List.of(), orphanedJavadocLines("class A { String s = \"oops")); //$NON-NLS-1$
        assertEquals("an unterminated text block", //$NON-NLS-1$
            List.of(), orphanedJavadocLines("class A { String s = \"\"\"\n  /** x */\n  /** y */")); //$NON-NLS-1$
        assertEquals("an unterminated javadoc block", //$NON-NLS-1$
            List.of(), orphanedJavadocLines("class A {\n/** never closed\n")); //$NON-NLS-1$
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
     * One left-to-right lex of the file, yielding two kinds of token: JAVADOC comments
     * and CODE. A javadoc comment documents nothing when either
     * <ul>
     *   <li>the next token is another javadoc comment — no code came between, so the
     *       later block is the nearer one and this one is discarded; or</li>
     *   <li>it appears while a declaration HEAD is open — after the declaration's first
     *       token (an annotation or a modifier) and before the {@code ;}, <code>{</code>
     *       or <code>}</code> that closes it. There the block BEFORE the head is the one
     *       javadoc renders, so the one inside is the discarded one.</li>
     * </ul>
     * Lexing rather than matching line prefixes is what keeps a text block holding Java
     * source, a {@code "://"} literal or a {@code /**} inside a string from being read as
     * documentation — a ratchet that reddens on legal code is worse than one that misses,
     * because it blocks work that is not even wrong.
     * <p>
     * What it cannot see is listed ONCE, in {@link #KNOWN_LIMITS}, which every refusal
     * prints — so the reader holding the refusal and the reader opening this file get the
     * same list and it cannot drift between them.
     *
     * @param source the contents of one {@code .java} file
     * @return the 1-based start lines of the javadoc blocks that document nothing, ascending
     */
    static List<Integer> orphanedJavadocLines(String source)
    {
        // Keyed by the block's OFFSET, valued by its line: two blocks can share a line
        // (/** a */ /** b */ /** c */ int f;) and keying by line would report one of two.
        SortedMap<Integer, Integer> orphans = new TreeMap<>();
        int i = 0;
        int line = 1;
        int depth = 0;
        boolean headOpen = false;
        int pending = -1;
        int pendingLine = -1;
        StringBuilder word = new StringBuilder();
        while (i < source.length())
        {
            char c = source.charAt(i);
            if (Character.isJavaIdentifierPart(c))
            {
                word.append(c);
                i++;
                continue;
            }
            if (word.length() > 0)
            {
                // A word is a code token; a modifier one also OPENS a declaration head.
                pending = -1;
                pendingLine = -1;
                if (depth == 0 && MODIFIERS.contains(word.toString()))
                {
                    headOpen = true;
                }
                word.setLength(0);
            }
            char next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (c == '\n')
            {
                line++;
                i++;
                continue;
            }
            if (Character.isWhitespace(c))
            {
                i++;
                continue;
            }
            if (c == '/' && next == '/')
            {
                while (i < source.length() && source.charAt(i) != '\n')
                {
                    i++;
                }
                continue;
            }
            if (c == '/' && next == '*')
            {
                int startLine = line;
                int startOffset = i;
                // "/**" opens javadoc, but "/**/" is merely an EMPTY block comment.
                boolean javadoc = i + 2 < source.length() && source.charAt(i + 2) == '*'
                    && (i + 3 >= source.length() || source.charAt(i + 3) != '/');
                i += 2;
                while (i + 1 < source.length()
                    && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/'))
                {
                    if (source.charAt(i) == '\n')
                    {
                        line++;
                    }
                    i++;
                }
                i = Math.min(i + 2, source.length());
                if (javadoc)
                {
                    if (pending >= 0)
                    {
                        orphans.put(Integer.valueOf(pending), Integer.valueOf(pendingLine));
                    }
                    if (headOpen)
                    {
                        orphans.put(Integer.valueOf(startOffset), Integer.valueOf(startLine));
                    }
                    pending = startOffset;
                    pendingLine = startLine;
                }
                continue;
            }
            // Everything below is a code token, so a javadoc block before it is attached.
            pending = -1;
            pendingLine = -1;
            if (c == '"' && source.startsWith("\"\"\"", i)) //$NON-NLS-1$
            {
                int after = skipTextBlock(source, i);
                line += countNewlines(source, i, after);
                i = after;
                continue;
            }
            if (c == '"' || c == '\'')
            {
                int after = skipLiteral(source, i, c);
                line += countNewlines(source, i, after);
                i = after;
                continue;
            }
            if (c == '@')
            {
                if (depth == 0)
                {
                    headOpen = true;
                }
                i++;
                continue;
            }
            if (c == '(')
            {
                depth++;
            }
            else if (c == ')')
            {
                depth = Math.max(0, depth - 1);
            }
            else if (depth == 0 && (c == ';' || c == '{' || c == '}' || c == ','))
            {
                headOpen = false;
            }
            i++;
        }
        return new ArrayList<>(orphans.values());
    }

    /** @return the index just past the text block that opens at {@code i} */
    private static int skipTextBlock(String source, int i)
    {
        int at = i + 3;
        while (at < source.length())
        {
            if (source.charAt(at) == '\\')
            {
                // An escaped quote cannot close the block: \""" is three literal quotes.
                at += 2;
                continue;
            }
            if (source.startsWith("\"\"\"", at)) //$NON-NLS-1$
            {
                return at + 3;
            }
            at++;
        }
        return source.length();
    }

    /** @return the index just past the string/char literal that opens at {@code i} */
    private static int skipLiteral(String source, int i, char quote)
    {
        int at = i + 1;
        while (at < source.length() && source.charAt(at) != quote)
        {
            at += source.charAt(at) == '\\' ? 2 : 1;
        }
        return Math.min(at + 1, source.length());
    }

    /** Newlines a literal swallowed, so the line counter keeps up with it. */
    private static int countNewlines(String source, int from, int to)
    {
        int count = 0;
        for (int at = from; at < to && at < source.length(); at++)
        {
            if (source.charAt(at) == '\n')
            {
                count++;
            }
        }
        return count;
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
