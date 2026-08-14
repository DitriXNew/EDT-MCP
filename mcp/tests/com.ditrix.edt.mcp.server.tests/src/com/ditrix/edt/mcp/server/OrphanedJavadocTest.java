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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
 *   <li>one that sits AFTER an {@code @Deprecated}, between it and the member. Here it is
 *       the block BEFORE the annotation that survives, which is why reporting "the first
 *       of the pair" would name the wrong one. Only an annotation carries this rule: a
 *       modifier or a type name opens a head too, but it also opens a {@code static { }}
 *       or an {@code int f = expr}, which no javadoc can document.</li>
 * </ul>
 * <p>
 * <b>The fix is to MOVE the block back, not to delete it.</b> Across #341/#345/#353,
 * five of six such blocks were the ONLY documentation their method had — a mechanical
 * clean-up would have thrown the documentation away. Read the block, find the
 * declaration it describes (usually the one below the member that was inserted after
 * it), and put it there. Delete it only when the declaration it describes is gone, or
 * when a newer block on the same declaration supersedes it — and say so in the PR.
 * <p>
 * {@link #KNOWN_ORPHANS} is a shrinking allow-list. Deliberately NOT a per-file count in
 * the shape {@code BuiltInToolTestCoverageTest} uses — it names each pardoned block by its
 * own text, so fixing the listed one while introducing another cannot pass on the
 * arithmetic — and {@link #allowListHasNoStaleEntries} fails once a listed block is gone,
 * so an entry cannot outlive the debt it records.
 */
public class OrphanedJavadocTest
{
    /**
     * The sites allowed to stay, BY IDENTITY - file plus {@link #identityOf} of the block
     * itself. Deliberately not a count: a budget of "one per file" answers "how many", and
     * the question that matters is "the same one?". Fixing the listed block while
     * introducing another in the same file keeps the count at one and would slip through;
     * against identities the new block is unlisted (red) and the pardoned one has vanished
     * (also red, via {@link #allowListHasNoStaleEntries}).
     * <p>
     * The one entry left is the last site of issue #353. It is deferred rather than fixed
     * because an open PR edits exactly that file, and a one-line javadoc move is not worth a
     * merge conflict in somebody else's branch; it comes out as soon as that PR lands. Its
     * sibling entry for {@code ToolSettingsService} is already gone - the block was returned
     * to its declaration in master, which {@link #allowListHasNoStaleEntries} then reported.
     */
    private static final Map<String, List<String>> KNOWN_ORPHANS = new HashMap<>();
    static
    {
        KNOWN_ORPHANS.put("mcp/bundles/com.ditrix.edt.mcp.server/src" //$NON-NLS-1$
            + "/com/ditrix/edt/mcp/server/preferences/PreferenceConstants.java", //$NON-NLS-1$
            List.of(
                "Default: all tools enabled (empty string = no disabled tools)" //$NON-NLS-1$
            ));
    }

    /** How much of a block's text the REPORT shows; the identity is always the whole of it. */
    private static final int DISPLAY_LENGTH = 60;

    /**
     * The RESERVED words whose declaration opens a TYPE body rather than a block of code.
     * {@code record} is deliberately absent: it is a CONTEXTUAL keyword, so it is also a
     * legal method, parameter and variable name, and treating it unconditionally as a type
     * turned the body of every method called {@code record} into a place where this ratchet
     * accused ordinary comments. {@link #opensRecordDeclaration} decides that one by looking
     * at what follows.
     */
    private static final Set<String> TYPE_KEYWORDS =
        Set.of("class", "interface", "enum"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /** The contextual keyword judged by {@link #opensRecordDeclaration} rather than by itself. */
    private static final String RECORD = "record"; //$NON-NLS-1$

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
     * <p>
     * Seven further ways to accuse wrongly were measured on the real detector and CLOSED
     * rather than listed, because each was reachable in ordinary code: a block in the body of
     * a method called {@code record} (contextual keyword, see {@link #opensRecordDeclaration});
     * two consecutive blocks in executable code; a block inside an unclosed {@code (}, e.g. in
     * a lambda passed as an argument; a block in a parameter list; a block in a field
     * INITIALIZER, which is an expression and not part of the declaration head; a block before
     * a head that nothing preceded; and a PAIR of blocks around the {@code static} of an
     * initializer, which no javadoc can ever attach to. Four rules settle all seven — an
     * accusation needs a place where a DECLARATION could stand ({@code depth == 0}, inside a
     * type body or at file level); it must not be past a declaration's {@code =}; the head
     * accusation needs a pair to choose between; and that pair must be split by an
     * ANNOTATION, the one head-opening token that cannot also begin a non-declaration.
     * <p>
     * Every one was found by REVIEW of this change, not by the corpus: the repository scan
     * reported the same single site before and after all seven. A shape nobody has written
     * yet is exactly the shape a ratchet meets on the day somebody writes it — which is why
     * the list below is the honest deliverable, not the fixes.
     * <p>
     * Where the fixing stopped, and why: review kept producing further legal shapes, each
     * more contrived than the last, and every one of them was UNREACHABLE here (measured, not
     * assumed — no {@code TYPE_USE} annotation, no {@code @interface}, no block inside a
     * {@code throws} clause, and the repository scan never moved). Past that point another
     * mechanism buys nothing and costs a reader: three shapes are therefore LISTED above
     * rather than closed. Anyone who meets one has the list in the refusal and can say so.
     */
    private static final String KNOWN_LIMITS =
        "If you believe this is a FALSE alarm, these are the detector's known blind spots:\n" //$NON-NLS-1$
            + "  - a structural token spelled as a unicode escape (\\u003b for ';') is not translated,\n" //$NON-NLS-1$
            + "    so a declaration head can stay open past it. This is the only shape KNOWN to be\n" //$NON-NLS-1$
            + "    able to accuse wrongly; if it bit you, say so on the issue rather than working\n" //$NON-NLS-1$
            + "    around it - and if something else did, that is a defect worth the same report.\n" //$NON-NLS-1$
            + "  - two adjacent blocks standing directly in front of a '{ }' initializer are read\n" //$NON-NLS-1$
            + "    as a pair of declarations.\n" //$NON-NLS-1$
            + "  - a ',' at depth 0 closes the head, so a TYPE_USE annotation later in the SAME\n" //$NON-NLS-1$
            + "    declaration ('throws A, /** x */ @TA /** y */ B') reopens it as if it were the\n" //$NON-NLS-1$
            + "    declaration's first token.\n" //$NON-NLS-1$
            + "  - 'default' in an annotation element does not begin an initializer the way '=' does,\n" //$NON-NLS-1$
            + "    so a block in that expression is judged as part of the declaration.\n" //$NON-NLS-1$
            + "None of those three is reachable in this repository (no TYPE_USE annotation, no\n" //$NON-NLS-1$
            + "'@interface' declaration, no block inside a 'throws' clause - checked, not assumed).\n" //$NON-NLS-1$
            + "The rest can only MISS:\n" //$NON-NLS-1$
            + "  - the head accusation applies only after an ANNOTATION, and only with a block on\n" //$NON-NLS-1$
            + "    BOTH sides of it. A block after a modifier, a type name or punctuation is a\n" //$NON-NLS-1$
            + "    deliberate miss: those also begin 'static { }' and 'int f = expr', which have no\n" //$NON-NLS-1$
            + "    declaration to document.\n" //$NON-NLS-1$
            + "  - a ',', a ';' or a ':' at depth 0 closes the declaration HEAD, so 'throws A, B'\n" //$NON-NLS-1$
            + "    and a multi-declarator field give it up early.\n" //$NON-NLS-1$
            + "  - everything from a declaration's '=' to its ';' is treated as an expression, so a\n" //$NON-NLS-1$
            + "    later declarator ('int a = 1, /** here */ b = 2;') is not judged.\n" //$NON-NLS-1$
            + "  - a record whose name is not directly followed by its component list - a generic\n" //$NON-NLS-1$
            + "    one ('record Pair<A, B>(..)'), or one with a comment after the keyword - is not\n" //$NON-NLS-1$
            + "    recognised as a type, so its members go unjudged.\n" //$NON-NLS-1$
            + "  - an anonymous class body counts as code, and nothing inside a '(' is judged at all.\n" //$NON-NLS-1$
            + "  - a block left after the LAST declaration in a file is never flushed, so an orphan\n" //$NON-NLS-1$
            + "    at end of file is not reported.\n" //$NON-NLS-1$
            + "Details: OrphanedJavadocTest."; //$NON-NLS-1$

    @Test
    public void noOrphanedJavadocOutsideTheAllowList()
    {
        List<String> problems = unpardonedAcross(scanSources(), KNOWN_ORPHANS);
        assertTrue(refusalText(problems), problems.isEmpty());
    }

    /**
     * The pardon decision itself, as a function, so it can be exercised on the case it
     * exists for instead of only on the repository (where every pardoned file happens to
     * hold exactly its pardoned block, and a count would look identical).
     *
     * @param path the file, for the message
     * @param orphans what the detector found there
     * @param pardoned the fingerprints this file is allowed to keep
     * @return one message per orphan nobody pardoned
     */
    static List<String> unpardoned(String path, List<Orphan> orphans, List<String> pardoned)
    {
        // A MULTISET, consumed one pardon per block: with a set, two identical blocks would
        // both be covered by the single pardon written for one of them.
        List<String> remaining = new ArrayList<>(pardoned);
        List<String> problems = new ArrayList<>();
        for (Orphan orphan : orphans)
        {
            // BY IDENTITY, never by how many: "one orphan is allowed here" would pardon a
            // brand-new block the moment the pardoned one is fixed.
            if (!remaining.remove(orphan.identity))
            {
                problems.add(path + ':' + orphan.line + " -> orphaned javadoc \"" //$NON-NLS-1$
                    + display(orphan.identity) + '"');
            }
        }
        return problems;
    }

    /**
     * The other direction, also as a function: which pardons no longer name a block that
     * is still orphaned.
     *
     * @param path the file, for the message
     * @param orphans what the detector found there
     * @param pardoned the fingerprints this file is allowed to keep
     * @return one message per pardon that has outlived its block
     */
    static List<String> stalePardons(String path, List<Orphan> orphans, List<String> pardoned)
    {
        List<String> present = new ArrayList<>();
        for (Orphan orphan : orphans)
        {
            present.add(orphan.identity);
        }
        List<String> stale = new ArrayList<>();
        for (String one : pardoned)
        {
            // Also a multiset: two pardons for one surviving block leave one of them stale.
            if (!present.remove(one))
            {
                stale.add(path + ": pardons a block that is no longer orphaned - \"" //$NON-NLS-1$
                    + display(one) + '"');
            }
        }
        return stale;
    }

    /**
     * Every file's findings, with each file's OWN pardons. Extracted so that "look the
     * pardons up by file" is a decision a test can revert — a global union of every pardon
     * would let one file's entry excuse another file's block, and nothing would say so.
     *
     * @param scanned every scanned file mapped to its orphans
     * @param pardons the allow-list
     * @return one message per orphan nobody pardoned
     */
    static List<String> unpardonedAcross(Map<String, List<Orphan>> scanned,
        Map<String, List<String>> pardons)
    {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, List<Orphan>> entry : scanned.entrySet())
        {
            problems.addAll(unpardoned(entry.getKey(), entry.getValue(),
                pardons.getOrDefault(entry.getKey(), List.of())));
        }
        return problems;
    }

    /**
     * The pardons that no longer name a block that is still orphaned — including the ones
     * whose FILE is gone from the scan, which is the case a per-file walk over the scan
     * results would silently skip.
     *
     * @param scanned every scanned file mapped to its orphans
     * @param pardons the allow-list
     * @return one message per pardon that has outlived its block
     */
    static List<String> stalePardonsAcross(Map<String, List<Orphan>> scanned,
        Map<String, List<String>> pardons)
    {
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : pardons.entrySet())
        {
            if (!scanned.containsKey(entry.getKey()))
            {
                stale.add(entry.getKey() + ": allow-listed but no such source file was scanned"); //$NON-NLS-1$
                continue;
            }
            stale.addAll(stalePardons(entry.getKey(), scanned.get(entry.getKey()), entry.getValue()));
        }
        return stale;
    }

    /**
     * @param identity a block's full identity
     * @return at most {@link #DISPLAY_LENGTH} characters of it, elided when it is longer
     */
    static String display(String identity)
    {
        return identity.length() <= DISPLAY_LENGTH ? identity
            : identity.substring(0, DISPLAY_LENGTH) + "..."; //$NON-NLS-1$
    }

    /**
     * The pardon must name a SITE, not a quantity — asserted on the DECISION, not on the
     * identities that feed it. The case it exists for: someone fixes the block a file is
     * allow-listed for and introduces a different one in the same file. The count is still
     * one, so a budget waves it through; an identity does not.
     */
    @Test
    public void aPardonDoesNotTransferToADifferentBlock()
    {
        List<String> pardoned = List.of("The pardoned block."); //$NON-NLS-1$
        List<Orphan> sameBlock = List.of(new Orphan(3, "The pardoned block.")); //$NON-NLS-1$
        List<Orphan> differentBlock = List.of(new Orphan(6, "A NEW orphan nobody pardoned.")); //$NON-NLS-1$

        assertTrue("the pardoned block itself must stay pardoned", //$NON-NLS-1$
            unpardoned("A.java", sameBlock, pardoned).isEmpty()); //$NON-NLS-1$
        assertEquals("a DIFFERENT block, same count, must be reported", //$NON-NLS-1$
            1, unpardoned("A.java", differentBlock, pardoned).size()); //$NON-NLS-1$
        assertTrue("and the message must name it", //$NON-NLS-1$
            unpardoned("A.java", differentBlock, pardoned).get(0).contains("A NEW orphan")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("a pardon whose block is still there is not stale", //$NON-NLS-1$
            stalePardons("A.java", sameBlock, pardoned).isEmpty()); //$NON-NLS-1$
        assertEquals("but once that block is fixed the pardon must go", //$NON-NLS-1$
            1, stalePardons("A.java", differentBlock, pardoned).size()); //$NON-NLS-1$
    }

    /**
     * The identity is the WHOLE block, not its opening words. Two blocks can share a long
     * opening — copy-paste is how — and a prefix would let the second inherit the first's
     * pardon: fix the pardoned block, add the look-alike, and every check stays green.
     */
    @Test
    public void aPardonDoesNotTransferToABlockThatMerelyStartsTheSameWay()
    {
        String shared = "The tool-enablement migration runs once per store, lazily, on the first read"; //$NON-NLS-1$
        assertTrue("the shared opening must be longer than the report shows", //$NON-NLS-1$
            shared.length() > DISPLAY_LENGTH);
        String pardonedBlock = shared + " and adds the tool to a stored list."; //$NON-NLS-1$
        String lookAlike = shared + " and removes the tool from a stored list."; //$NON-NLS-1$
        assertEquals("the two differ only past the displayed prefix", //$NON-NLS-1$
            display(pardonedBlock), display(lookAlike));

        List<String> pardoned = List.of(pardonedBlock);
        assertTrue("the pardoned block is still pardoned", //$NON-NLS-1$
            unpardoned("A.java", List.of(new Orphan(3, pardonedBlock)), pardoned).isEmpty()); //$NON-NLS-1$
        assertEquals("the look-alike is a DIFFERENT block and must be reported", //$NON-NLS-1$
            1, unpardoned("A.java", List.of(new Orphan(9, lookAlike)), pardoned).size()); //$NON-NLS-1$
        assertEquals("and the pardon it did not match is stale", //$NON-NLS-1$
            1, stalePardons("A.java", List.of(new Orphan(9, lookAlike)), pardoned).size()); //$NON-NLS-1$
    }

    /**
     * One pardon covers one block. Two identical blocks are two debts, and writing the
     * pardon once must not settle both — a set would, a multiset does not.
     */
    @Test
    public void onePardonCoversOneBlock()
    {
        List<Orphan> twins = List.of(new Orphan(3, "Same text."), new Orphan(9, "Same text.")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("one pardon, two identical blocks - the second is still owed", //$NON-NLS-1$
            1, unpardoned("A.java", twins, List.of("Same text.")).size()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("two pardons cover both", //$NON-NLS-1$
            unpardoned("A.java", twins, List.of("Same text.", "Same text.")).isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("and a third pardon has nothing left to cover", //$NON-NLS-1$
            1, stalePardons("A.java", twins, //$NON-NLS-1$
                List.of("Same text.", "Same text.", "Same text.")).size()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * A pardon belongs to ONE file, and a pardon whose file has left the scan must be
     * reported rather than quietly kept. Both are decisions of the cross-file reduction,
     * invisible to a test that hands one file's pardons straight to {@link #unpardoned}.
     */
    @Test
    public void pardonsAreSelectedPerFile()
    {
        Map<String, List<Orphan>> scanned = new LinkedHashMap<>();
        scanned.put("a/A.java", List.of(new Orphan(3, "Pardoned in A."))); //$NON-NLS-1$ //$NON-NLS-2$
        scanned.put("b/B.java", List.of(new Orphan(4, "Pardoned in A."))); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, List<String>> pardons = new LinkedHashMap<>();
        pardons.put("a/A.java", List.of("Pardoned in A.")); //$NON-NLS-1$ //$NON-NLS-2$

        List<String> problems = unpardonedAcross(scanned, pardons);
        assertEquals("A's pardon must not excuse the same block in B", 1, problems.size()); //$NON-NLS-1$
        assertTrue("and the one reported is B's", problems.get(0).startsWith("b/B.java")); //$NON-NLS-1$ //$NON-NLS-2$

        // A pardon for a file nobody scanned: a renamed or deleted file must not leave its
        // pardon lying around for whatever takes its path next.
        Map<String, List<String>> orphanedPardon = new LinkedHashMap<>();
        orphanedPardon.put("gone/Gone.java", List.of("Pardoned in a file that no longer exists.")); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> stale = stalePardonsAcross(scanned, orphanedPardon);
        assertEquals("a pardon whose file was not scanned is stale", 1, stale.size()); //$NON-NLS-1$
        assertTrue("and it says so", stale.get(0).contains("no such source file was scanned")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The report shows a prefix, and the length it advertises has to be the length it
     * produces — this whole change exists because the compiler does not read prose.
     */
    @Test
    public void theDisplayedPrefixHonoursItsAdvertisedLength()
    {
        StringBuilder longIdentity = new StringBuilder();
        while (longIdentity.length() < DISPLAY_LENGTH * 2)
        {
            longIdentity.append("word "); //$NON-NLS-1$
        }
        String shown = display(longIdentity.toString());
        assertEquals("exactly DISPLAY_LENGTH characters, plus the ellipsis", //$NON-NLS-1$
            DISPLAY_LENGTH + 3, shown.length());
        assertTrue("elided", shown.endsWith("...")); //$NON-NLS-1$ //$NON-NLS-2$

        String short_ = "short"; //$NON-NLS-1$
        assertEquals("a short identity is shown whole, with no ellipsis", //$NON-NLS-1$
            short_, display(short_));
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
        return "Javadoc blocks that document nothing - a member was inserted between the block " //$NON-NLS-1$
            + "and its declaration, or the declaration itself was removed. MOVE each block back to " //$NON-NLS-1$
            + "the declaration it describes - do NOT just " //$NON-NLS-1$
            + "delete it, it is usually that declaration's only documentation:\n  " //$NON-NLS-1$
            + String.join("\n  ", problems) //$NON-NLS-1$
            + "\n\n" + KNOWN_LIMITS; //$NON-NLS-1$
    }

    /**
     * The pardon must name a SITE, not a quantity. The case it exists for: someone fixes
     * the block this file is allow-listed for and introduces a different one in the same
     * file — the count is still one, and a budget would wave it through.
     */
    @Test
    public void thePardonIsForOneBLOCK_notForAQUANTITY()
    {
        String before = String.join("\n", //$NON-NLS-1$
            "class A", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    /** The pardoned block. */", //$NON-NLS-1$
            "    /** Documents f. */", //$NON-NLS-1$
            "    int f;", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "    /** Documents g. */", //$NON-NLS-1$
            "    void g() {}", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        List<Orphan> was = orphanedJavadoc(before);
        assertEquals("one orphan to start with", 1, was.size()); //$NON-NLS-1$
        assertEquals("identified by its own opening words", //$NON-NLS-1$
            "The pardoned block.", was.get(0).identity); //$NON-NLS-1$

        // Same file, same COUNT, different block: the pardon must not transfer.
        String after = String.join("\n", //$NON-NLS-1$
            "class A", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    /** Documents f. */", //$NON-NLS-1$
            "    int f;", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "    /** A NEW orphan nobody pardoned. */", //$NON-NLS-1$
            "    /** Documents g. */", //$NON-NLS-1$
            "    void g() {}", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        List<Orphan> now = orphanedJavadoc(after);
        assertEquals("still exactly one - a count cannot tell these two apart", //$NON-NLS-1$
            was.size(), now.size());
        assertTrue("but the identity differs, which is what the allow-list keys on", //$NON-NLS-1$
            !was.get(0).identity.equals(now.get(0).identity));

        // The line moves when anything above it is edited; the identity must not.
        String shifted = "// a new line at the top\n" + before; //$NON-NLS-1$
        assertEquals("an edit above the block moves its line", //$NON-NLS-1$
            was.get(0).line + 1, orphanedJavadoc(shifted).get(0).line);
        assertEquals("but must not change which block it is", //$NON-NLS-1$
            was.get(0).identity, orphanedJavadoc(shifted).get(0).identity);
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
     * Keeps the pardon honest in the other direction: a pardoned block that is gone (fixed,
     * reworded or deleted) must lose its entry, and every entry must name a file that is
     * actually scanned — so a typo, a renamed file or a fix cannot leave a pardon lying
     * around for the NEXT block to inherit.
     */
    @Test
    public void allowListHasNoStaleEntries()
    {
        List<String> stale = stalePardonsAcross(scanSources(), KNOWN_ORPHANS);
        assertTrue("Stale KNOWN_ORPHANS entries - drop them to tighten the ratchet:\n  " //$NON-NLS-1$
            + String.join("\n  ", stale), stale.isEmpty()); //$NON-NLS-1$
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
     * Positive control for the OTHER shape of the same accident: a block that sits after an
     * ANNOTATION. Which of the two blocks survives is not a matter of taste — {@code javadoc}
     * was run on exactly these sources, and the one BEFORE the annotation is the one it
     * renders — so the block reported here is the discarded one.
     * <p>
     * Only an annotation, deliberately. A head can also be opened by a modifier, a type name
     * or punctuation, and those forms are documented misses in
     * {@link #aBlockAfterANonAnnotationFirstTokenIsADocumentedMiss}: an annotation is the one
     * head-opening token that cannot ALSO begin something undocumentable, such as a
     * {@code static { }} initializer or an {@code int f = expr}, and those were the source of
     * every wrong accusation this detector was measured making.
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

        // A block left at the end of a TYPE body after its member was deleted: the '}'
        // cannot be the declaration it was written for, so nobody documents it. This is
        // the very accident the issue was filed for, so it must not go quiet.
        assertEquals("a block left at the end of a type body is an orphan", //$NON-NLS-1$
            List.of(Integer.valueOf(1)),
            orphanedJavadocLines("class A { /** old member left behind */ }")); //$NON-NLS-1$

        // Members of a NESTED type are still judged - the rule is about type bodies, not
        // about the outermost one.
        String nested = String.join("\n", //$NON-NLS-1$
            "class A", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    class Inner", //$NON-NLS-1$
            "    {", //$NON-NLS-1$
            "        /** Attached. */", //$NON-NLS-1$
            "        @Deprecated", //$NON-NLS-1$
            "        /** Dropped. */", //$NON-NLS-1$
            "        void m() {}", //$NON-NLS-1$
            "    }", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("a nested type is a type body too", //$NON-NLS-1$
            List.of(Integer.valueOf(7)), orphanedJavadocLines(nested));
    }

    /**
     * The forms the head accusation deliberately gives up. All of them were measured on the
     * real {@code javadoc} tool and it does drop the second block in each - so these ARE
     * orphans, and they are missed on purpose.
     * <p>
     * The reason is the shape of the evidence. In every one of them the head is opened by a
     * modifier, a type name or punctuation, and each of those ALSO begins something that has
     * no documentable declaration at all: {@code static { }}, an instance initializer, a
     * field with an initializer expression. Five review rounds produced six different legal
     * shapes that were wrongly accused through exactly that door, and none through an
     * annotation. Set against that, the head accusation had never once caught a real site:
     * all 20 sites this ratchet has actually found - the 16 cleaned up under #353 and the 4
     * in this change - were consecutive-block cases, which the other rule reports.
     * <p>
     * So the trade is: give up a shape nobody has written for a door nobody can walk through.
     * If a real one of these ever turns up, this test is where its evidence belongs.
     */
    @Test
    public void aBlockAfterANonAnnotationFirstTokenIsADocumentedMiss()
    {
        String[][] shapes = {
            {"a signature split after its modifiers", "    public static", "    void m() {}"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {"a modifier followed by an annotation", "    public @Deprecated", "    void m() {}"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {"a package-private field", "    String", "    f;"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {"a package-private generic method", "    <T> T", "    g(T t) { return t; }"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {"a punctuation first token", "    <", "    T> T g(T t) { return t; }"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        };
        for (String[] shape : shapes)
        {
            String source = String.join("\n", //$NON-NLS-1$
                "class C", //$NON-NLS-1$
                "{", //$NON-NLS-1$
                "    /** Attached: javadoc renders this one. */", //$NON-NLS-1$
                shape[1],
                "    /** Dropped by javadoc, and MISSED by this detector on purpose. */", //$NON-NLS-1$
                shape[2],
                "}"); //$NON-NLS-1$
            assertEquals(shape[0] + " is a documented miss, not a finding", //$NON-NLS-1$
                List.of(), orphanedJavadocLines(source));
        }

        // The same source with the first token replaced by an ANNOTATION is still reported,
        // so this test cannot pass by the detector having stopped working.
        assertEquals("the annotation form of the very same accident is still caught", //$NON-NLS-1$
            List.of(Integer.valueOf(5)), orphanedJavadocLines(String.join("\n", //$NON-NLS-1$
                "class C", //$NON-NLS-1$
                "{", //$NON-NLS-1$
                "    /** Attached: javadoc renders this one. */", //$NON-NLS-1$
                "    @Deprecated", //$NON-NLS-1$
                "    /** Dropped by javadoc, and reported. */", //$NON-NLS-1$
                "    void m() {}", //$NON-NLS-1$
                "}"))); //$NON-NLS-1$
    }

    /**
     * Executable code is not a place where a declaration can be, so a {@code /** *}{@code /}
     * block there is an ordinary comment and must never be accused. Measured on the real
     * tool first: {@code javadoc} renders none of these — but "renders nothing" is not the
     * same as "is an orphan", because there is no declaration to move them back TO, and the
     * refusal would tell the reader to do something impossible.
     * <p>
     * These are all one bug: the head was left open past the {@code )} of a condition. They
     * are fixed by one rule — a head can only be open in a TYPE body — so this asserts the
     * whole family, not the one shape that was reported.
     */
    @Test
    public void detectorNeverAccusesACommentInsideAMethodBody()
    {
        String[][] shapes = {
            {"an unbraced if", "        if (ready)", "            doIt();"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {"an unbraced while", "        while (ready)", "            doIt();"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {"an unbraced for", "        for (int i = 0; i < 3; i++)", "            doIt();"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {"an unbraced do", "        do", "            doIt();"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {"an unbraced else", "        if (ready) doIt(); else", "            doIt();"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {"try-with-resources", "        try (AutoCloseable r = open())", "            doIt();"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {"a lambda without braces", "        run(() ->", "            doIt());"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {"a switch arrow", "        switch (n) { case 1 ->", "            doIt(); }"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {"a plain statement", "        doIt();", "        doIt();"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        };
        for (String[] shape : shapes)
        {
            String source = String.join("\n", //$NON-NLS-1$
                "class A", //$NON-NLS-1$
                "{", //$NON-NLS-1$
                "    void m(boolean ready, int n) throws Exception", //$NON-NLS-1$
                "    {", //$NON-NLS-1$
                shape[1],
                "            /** an ordinary comment, spelled with two stars */", //$NON-NLS-1$
                shape[2],
                "    }", //$NON-NLS-1$
                "}"); //$NON-NLS-1$
            assertEquals("a comment after " + shape[0] + " is not a declaration's javadoc", //$NON-NLS-1$ //$NON-NLS-2$
                List.of(), orphanedJavadocLines(source));
        }

        // Nor is a block trailing at the end of a method body - only a TYPE body's is.
        String trailing = String.join("\n", //$NON-NLS-1$
            "class A", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    void m()", //$NON-NLS-1$
            "    {", //$NON-NLS-1$
            "        doIt();", //$NON-NLS-1$
            "        /** a trailing note, not documentation */", //$NON-NLS-1$
            "    }", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("a trailing comment in a method body is not an orphan", //$NON-NLS-1$
            List.of(), orphanedJavadocLines(trailing));

        // An anonymous class body is reached through 'new', not a type keyword, so it is
        // treated as code: a miss there is the safe direction.
        String anonymous = String.join("\n", //$NON-NLS-1$
            "class A", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    void m()", //$NON-NLS-1$
            "    {", //$NON-NLS-1$
            "        run(new Runnable() {", //$NON-NLS-1$
            "            /** a note */", //$NON-NLS-1$
            "            public void run() {}", //$NON-NLS-1$
            "        });", //$NON-NLS-1$
            "    }", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("an anonymous class body is code", List.of(), orphanedJavadocLines(anonymous)); //$NON-NLS-1$

        // 'Foo.class' is a class LITERAL, not a declaration. The brace that follows must
        // stay a block of code. Deliberately with no ';' or ',' between the literal and the
        // brace: those reset the flag on their own, and a fixture they can rescue proves
        // nothing about the rule being tested.
        String classLiteral = String.join("\n", //$NON-NLS-1$
            "class A", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    void m(Object o)", //$NON-NLS-1$
            "    {", //$NON-NLS-1$
            "        if (o == String.class)", //$NON-NLS-1$
            "        {", //$NON-NLS-1$
            "            doIt();", //$NON-NLS-1$
            "            /** a trailing note, not documentation */", //$NON-NLS-1$
            "        }", //$NON-NLS-1$
            "    }", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("a class literal does not open a type body", //$NON-NLS-1$
            List.of(), orphanedJavadocLines(classLiteral));

        // Three blocks, ONE line, two of them dropped: the report counts BLOCKS, so the
        // allow-list cannot be satisfied wholesale by writing them on a single line.
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
     * Negative control for the four accusations this detector was measured MAKING on legal
     * code. Each fixture below reddened the build before the guard "an accusation needs a
     * place where a declaration could stand" was added, and each of the four shapes occurs
     * in this repository — they are closed defects, not hypotheticals.
     * <p>
     * Written as one test because they are one bug: two of the three accusation paths were
     * asking "is a block pending?" without asking "could a member be here at all?".
     */
    @Test
    public void detectorNeverAccusesWhereNoDeclarationCouldStand()
    {
        // 1. Two ordinary notes in EXECUTABLE code. The "a javadoc block followed by another
        // one" rule used to fire anywhere, including where there is no declaration to move
        // either block back to - which is precisely what the refusal tells the reader to do.
        String[][] bodies = {
            {"a method body", "    void m()", "    {", "        doIt();", "    }"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            {"a static initializer", "    static", "    {", "        doIt();", "    }"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        };
        for (String[] body : bodies)
        {
            String source = String.join("\n", //$NON-NLS-1$
                "class A", //$NON-NLS-1$
                "{", //$NON-NLS-1$
                body[1],
                body[2],
                "        /** note one */", //$NON-NLS-1$
                "        /** note two */", //$NON-NLS-1$
                body[3],
                body[4],
                "}"); //$NON-NLS-1$
            assertEquals("two ordinary notes in " + body[0] + " document nothing by design", //$NON-NLS-1$ //$NON-NLS-2$
                List.of(), orphanedJavadocLines(source));
        }

        // ...including through a lambda and an anonymous class, whose bodies are code too.
        String nestedBodies = String.join("\n", //$NON-NLS-1$
            "class A", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    void m()", //$NON-NLS-1$
            "    {", //$NON-NLS-1$
            "        run(() -> {", //$NON-NLS-1$
            "            /** note one */", //$NON-NLS-1$
            "            /** note two */", //$NON-NLS-1$
            "            doIt();", //$NON-NLS-1$
            "        });", //$NON-NLS-1$
            "        run(new Runnable() {", //$NON-NLS-1$
            "            public void run()", //$NON-NLS-1$
            "            {", //$NON-NLS-1$
            "                /** note three */", //$NON-NLS-1$
            "                /** note four */", //$NON-NLS-1$
            "                doIt();", //$NON-NLS-1$
            "            }", //$NON-NLS-1$
            "        });", //$NON-NLS-1$
            "    }", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("a lambda body and an anonymous class body are code as well", //$NON-NLS-1$
            List.of(), orphanedJavadocLines(nestedBodies));

        // 2. 'record' is a CONTEXTUAL keyword, so it is also a legal method, parameter and
        // variable name - this repository uses it as all three. Reading it as a type turned
        // the body of every such method into a place where a member could be declared.
        String[][] contextualRecord = {
            {"a method named 'record'", "    void record(String s)", "    {", "        doIt();"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            {"a parameter named 'record'", "    void m(Rec record)", "    {", "        use(record);"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        };
        for (String[] shape : contextualRecord)
        {
            String source = String.join("\n", //$NON-NLS-1$
                "class A", //$NON-NLS-1$
                "{", //$NON-NLS-1$
                shape[1],
                shape[2],
                shape[3],
                "        /** a trailing note, not documentation */", //$NON-NLS-1$
                "    }", //$NON-NLS-1$
                "}"); //$NON-NLS-1$
            assertEquals(shape[0] + " does not open a type body", //$NON-NLS-1$
                List.of(), orphanedJavadocLines(source));
        }

        // 'record instanceof X' is the shape that tells "a NAME follows" apart from "a record
        // DECLARATION follows", because an identifier follows in both. The '{' is deliberately
        // the very next token: with a ';' or a ',' in between, the type flag is cleared anyway
        // and the fixture would survive a detector that had stopped requiring the component
        // list - proving nothing about the rule it exists for.
        String instanceOf = String.join("\n", //$NON-NLS-1$
            "class A", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    void m(Object record)", //$NON-NLS-1$
            "    {", //$NON-NLS-1$
            "        if (record instanceof String)", //$NON-NLS-1$
            "        {", //$NON-NLS-1$
            "            doIt();", //$NON-NLS-1$
            "            /** a trailing note, not documentation */", //$NON-NLS-1$
            "        }", //$NON-NLS-1$
            "    }", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("'record' followed by an identifier is not a record declaration", //$NON-NLS-1$
            List.of(), orphanedJavadocLines(instanceOf));

        // 3. Inside an unclosed '(' there are expressions, never members. At CLASS level the
        // enclosing body is a type body, so without this the lambda's contents inherited it.
        String inParentheses = String.join("\n", //$NON-NLS-1$
            "class A", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    private static final Runnable R = wrap(() -> {", //$NON-NLS-1$
            "        doIt();", //$NON-NLS-1$
            "        /** an ordinary note inside a lambda body */", //$NON-NLS-1$
            "        doIt();", //$NON-NLS-1$
            "    });", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("a class-level lambda in an argument list is still code", //$NON-NLS-1$
            List.of(), orphanedJavadocLines(inParentheses));

        // 4. A parameter list is the same case one level down: legal, if odd, and there is no
        // declaration below the block to move it to. Two blocks, again, because one is already
        // covered by the pair rule and would not exercise the parenthesis depth at all.
        assertEquals("a block inside a parameter list is not a member's javadoc", //$NON-NLS-1$
            List.of(), orphanedJavadocLines(
                "class A { void m(/** one */ /** two */ int x) {} }")); //$NON-NLS-1$

        // 5. A type keyword read INSIDE an argument list must not survive the ')' that ends
        // it. Its own '{' is inside those parentheses and is never pushed, so the flag can
        // only ever leak - here onto the next lambda, whose body would then be judged as a
        // place where members live. Found by review, not by the corpus: every assertion above
        // stays green while this one reddens.
        String leakedTypeKeyword = String.join("\n", //$NON-NLS-1$
            "class PluginState", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    static final Runnable[] TASKS = {", //$NON-NLS-1$
            "        keep(() -> {", //$NON-NLS-1$
            "            class Adapter {}", //$NON-NLS-1$
            "        }),", //$NON-NLS-1$
            "        () -> {", //$NON-NLS-1$
            "            /** milliseconds */", //$NON-NLS-1$
            "            /** and a second note, so the pair rule is not what saves this */", //$NON-NLS-1$
            "            doIt();", //$NON-NLS-1$
            "        }", //$NON-NLS-1$
            "    };", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("a type keyword inside an argument list must not leak past its ')'", //$NON-NLS-1$
            List.of(), orphanedJavadocLines(leakedTypeKeyword));

        // 6. An INITIALIZER is an expression, and the declaration head ended at the name. This
        // one is older than the rest of this change - the detector accused
        // 'int f = /** why one */ 1;' from the day it was written - and it cannot be expressed
        // by closing the head at '=', because the very next identifier reopens it.
        // Each is written with TWO adjacent blocks on purpose: a single one is already covered
        // by the pair rule, so it would pass whatever the initializer state did, and the whole
        // point here is where that state begins and ends.
        String[][] initializers = {
            {"a plain field initializer", "    int f = /** one */ /** two */ 1;"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"an expression lambda", //$NON-NLS-1$
                "    IntUnaryOperator f = x -> /** one */ /** two */ x + 1;"}, //$NON-NLS-1$
            {"a ternary, before the ':'", //$NON-NLS-1$
                "    int f = ready ? /** one */ /** two */ 1 : 2;"}, //$NON-NLS-1$
            // The initializer does not end at the first ';'-like character it happens to
            // contain. Each of the next four carries a token that was once read as "the
            // declaration is over" - a brace, a ':', a ',' - after which the REST of the very
            // same statement was judged as though it were a declaration again.
            {"a ternary, after the ':'", //$NON-NLS-1$
                "    int f = ready ? 1 : 2 + /** one */ /** two */ 3;"}, //$NON-NLS-1$
            {"an array initializer inside the expression", //$NON-NLS-1$
                "    int f = new int[] { 1 }.length + /** one */ /** two */ 1;"}, //$NON-NLS-1$
            {"a comma inside the initializer's type arguments", //$NON-NLS-1$
                "    Object f = new HashMap<String, Integer>() /** one */ /** two */;"}, //$NON-NLS-1$
            {"a lambda body inside the expression", //$NON-NLS-1$
                "    int f = call(() -> { doIt(); }) + /** one */ /** two */ 1;"}, //$NON-NLS-1$
        };
        for (String[] shape : initializers)
        {
            assertEquals("a block in " + shape[0] + " has no declaration to be moved to", //$NON-NLS-1$ //$NON-NLS-2$
                List.of(), orphanedJavadocLines("class C\n{\n" + shape[1] + "\n}")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // The same rule reaching the OTHER accusation: here a block DOES precede the head, so
        // the pair rule is satisfied and only the initializer state keeps the stray block in
        // the expression from being reported.
        assertEquals("a documented field's initializer is still an expression", //$NON-NLS-1$
            List.of(), orphanedJavadocLines(
                "class C\n{\n    /** Documents f. */\n    int f = /** stray */ 1;\n}")); //$NON-NLS-1$
        // ...and the same inside a type whose head carries a comma, which is the combination
        // that only became reachable once such types started being judged at all.
        assertEquals("the same, in a type this ratchet had previously switched itself off for", //$NON-NLS-1$
            List.of(), orphanedJavadocLines(
                "class C<T, U>\n{\n    IntUnaryOperator f = x -> /** note */ x + 1;\n}")); //$NON-NLS-1$

        // Two consecutive blocks in an initializer are the same case reaching the OTHER
        // accusation, which had no such guard of its own.
        assertEquals("two blocks in an initializer are two ordinary comments", //$NON-NLS-1$
            List.of(), orphanedJavadocLines("class C { int f = /** one */ /** two */ 1; }")); //$NON-NLS-1$

        // 7. A head that NOTHING preceded. The head accusation exists to pick the right one of
        // a pair - the block before the declaration's first token is the one javadoc renders,
        // the one after it is dropped - so with no first block there is no pair and nothing to
        // report. Every shape below opens a head with nothing in front of it.
        String[][] unpairedHeads = {
            {"a static initializer", "    static", "    /** what this block sets up */", "    {}"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            {"an instance initializer", "    ", "    /** what this block sets up */", "    {}"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            {"an undocumented field", "    int", "    /** a note about the type */", "    f;"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            // The ANNOTATED form of the same thing, which is what tells the pair rule apart
            // from "the first token was an annotation": here it was, and the block is still
            // left alone because nothing stood in front of the annotation to be the other
            // half. An undocumented @Override with a note under it is ordinary code.
            {"an annotated method nobody documented", "    @Override", //$NON-NLS-1$ //$NON-NLS-2$
                "    /** a note about the override */", "    public void m() {}"}, //$NON-NLS-1$ //$NON-NLS-2$
        };
        for (String[] shape : unpairedHeads)
        {
            for (String head : new String[] {"class C", "class C<T, U>"}) //$NON-NLS-1$ //$NON-NLS-2$
            {
                String source = String.join("\n", head, "{", shape[1], shape[2], shape[3], "}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                assertEquals("nothing preceded the head of " + shape[0] + " in '" + head //$NON-NLS-1$ //$NON-NLS-2$
                    + "', so there is no pair to choose between", //$NON-NLS-1$
                    List.of(), orphanedJavadocLines(source));
            }
        }

        // 8. ...and a head that a block DID precede, where the head belongs to a construct
        // that has no documentable declaration at all. Here the pair rule is satisfied and
        // only "the first token was not an annotation" keeps this legal source quiet: the
        // earlier block was written for the field further down, the later one is an ordinary
        // note on the initializer, and javadoc renders neither.
        for (String head : new String[] {"class C", "class C<T, U>"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            String pairedAroundAnInitializer = String.join("\n", //$NON-NLS-1$
                head,
                "{", //$NON-NLS-1$
                "    /** Documents f, further down. */", //$NON-NLS-1$
                "    static", //$NON-NLS-1$
                "    /** An ordinary note about this initializer. */", //$NON-NLS-1$
                "    {}", //$NON-NLS-1$
                "", //$NON-NLS-1$
                "    int f;", //$NON-NLS-1$
                "}"); //$NON-NLS-1$
            assertEquals("a modifier can begin an initializer, so it cannot carry the head " //$NON-NLS-1$
                + "accusation - in '" + head + "'", //$NON-NLS-1$ //$NON-NLS-2$
                List.of(), orphanedJavadocLines(pairedAroundAnInitializer));
        }

        // Positive control for this whole test: every assertion above expects an EMPTY list,
        // which a detector that had stopped working entirely would also satisfy. The same two
        // notes, moved into a place where a member CAN be declared, must still be reported.
        String sameNotesInATypeBody = String.join("\n", //$NON-NLS-1$
            "class A", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    /** note one */", //$NON-NLS-1$
            "    /** note two */", //$NON-NLS-1$
            "    int f;", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("the very same two blocks ARE an orphan in a type body", //$NON-NLS-1$
            List.of(Integer.valueOf(3)), orphanedJavadocLines(sameNotesInATypeBody));

        // ...and so does a block in a declaration PREFIX of an initialized field, which is the
        // shape closest to the one the initializer rule above must NOT swallow.
        String prefixOfAnInitializedField = String.join("\n", //$NON-NLS-1$
            "class C", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    /** Attached. */", //$NON-NLS-1$
            "    @Deprecated", //$NON-NLS-1$
            "    /** Dropped. */", //$NON-NLS-1$
            "    int f = 1;", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("the initializer rule must not reach BACK over the declaration prefix", //$NON-NLS-1$
            List.of(Integer.valueOf(5)), orphanedJavadocLines(prefixOfAnInitializedField));

        // ...nor FORWARD past the ';' that ends the field. A rule that switches the head off
        // and never switches it back on would silence the rest of the type, and every
        // assertion that expects an empty list would go on passing.
        String memberAfterAnInitializedField = String.join("\n", //$NON-NLS-1$
            "class C", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    int f = 1;", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "    /** Attached. */", //$NON-NLS-1$
            "    @Deprecated", //$NON-NLS-1$
            "    /** Dropped. */", //$NON-NLS-1$
            "    void m() {}", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("the initializer ends at its ';' - the next member is judged again", //$NON-NLS-1$
            List.of(Integer.valueOf(7)), orphanedJavadocLines(memberAfterAnInitializedField));
    }

    /**
     * The {@code record} decision reaching all the way through the lexer, on the one shape
     * found that gets from {@code record instanceof X} to a <code>{</code> at depth 0 without
     * a {@code ;}, {@code ,}, {@code :} or <code>}</code> clearing the type flag on the way:
     * a ternary whose branches are lambdas. Trusting the word unconditionally makes that
     * lambda body a supposed type body, and the ordinary note inside it is then reported when
     * the body closes.
     */
    @Test
    public void aVariableNamedRecordDoesNotTurnALambdaIntoATypeBody()
    {
        String source = String.join("\n", //$NON-NLS-1$
            "class A", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    Object record;", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "    Runnable r = record instanceof String", //$NON-NLS-1$
            "        ? () -> {", //$NON-NLS-1$
            "            /** ordinary note */", //$NON-NLS-1$
            "        }", //$NON-NLS-1$
            "        : () -> {};", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("a variable called 'record' opens no type body", //$NON-NLS-1$
            List.of(), orphanedJavadocLines(source));
    }

    /**
     * The other side of the {@code record} decision: a real record declaration must still be
     * a TYPE body. Without this, "stop trusting the word {@code record}" could be satisfied
     * by never trusting it at all, and the test above would pass on a detector that had
     * quietly stopped judging every record in the repository.
     */
    @Test
    public void detectorStillJudgesARealRecordDeclaration()
    {
        String declaration = String.join("\n", //$NON-NLS-1$
            "class A", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    private record Point(int x, int y)", //$NON-NLS-1$
            "    {", //$NON-NLS-1$
            "        /** Attached. */", //$NON-NLS-1$
            "        @Deprecated", //$NON-NLS-1$
            "        /** Dropped. */", //$NON-NLS-1$
            "        void m() {}", //$NON-NLS-1$
            "    }", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("a record body is a type body", //$NON-NLS-1$
            List.of(Integer.valueOf(7)), orphanedJavadocLines(declaration));

        // The compact form this repository actually writes: a body on one line.
        String compact = String.join("\n", //$NON-NLS-1$
            "class A", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    private record Point(int x, int y) {}", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "    /** Orphan. */", //$NON-NLS-1$
            "    /** Documents f. */", //$NON-NLS-1$
            "    int f;", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("a record declaration must not disturb the members after it", //$NON-NLS-1$
            List.of(Integer.valueOf(5)), orphanedJavadocLines(compact));
    }

    /**
     * The {@code record} decision, asserted on the FUNCTION as well as through the lexer
     * ({@link #aVariableNamedRecordDoesNotTurnALambdaIntoATypeBody} does that end to end).
     * Here, so that each case is named and the two deliberate MISSES are on the record as
     * decisions rather than surprises.
     * <p>
     * Every call goes through real source text with the offset the lexer would really pass —
     * the index just past the word — because a fixture that starts at 0 with no separator
     * ({@code "Point(int x)"}) is not a call this lexer can make: without a delimiter,
     * {@code recordPoint} is one identifier and the helper is never reached.
     */
    @Test
    public void recordIsATypeOnlyWhenAComponentListFollows()
    {
        assertTrue("a record declaration: its name and component list follow", //$NON-NLS-1$
            opensRecordDeclaration(("record Point(int x, int y) {}"))); //$NON-NLS-1$
        assertTrue("a line break between the keyword and the name is still a declaration", //$NON-NLS-1$
            opensRecordDeclaration(("record\n    Point(int x)"))); //$NON-NLS-1$

        assertTrue("a METHOD called 'record': an argument list follows, not a name", //$NON-NLS-1$
            !opensRecordDeclaration(("void record(String s)"))); //$NON-NLS-1$
        assertTrue("a VARIABLE called 'record', passed as an argument", //$NON-NLS-1$
            !opensRecordDeclaration(("use(record)"))); //$NON-NLS-1$
        assertTrue("a VARIABLE called 'record', tested with instanceof - an identifier " //$NON-NLS-1$
            + "follows it too, which is why the component list is what decides", //$NON-NLS-1$
            !opensRecordDeclaration(("record instanceof String;"))); //$NON-NLS-1$
        assertTrue("'record' with nothing but whitespace left must not read past the end", //$NON-NLS-1$
            !opensRecordDeclaration(("record   \n"))); //$NON-NLS-1$

        // The two documented MISSES: both make the detector judge less, never accuse more.
        assertTrue("a GENERIC record is not recognised - documented in KNOWN_LIMITS", //$NON-NLS-1$
            !opensRecordDeclaration(("record Pair<A, B>(A a, B b) {}"))); //$NON-NLS-1$
        assertTrue("nor one with a comment between the keyword and the name", //$NON-NLS-1$
            !opensRecordDeclaration(("record /* carrier */ R(int x) {}"))); //$NON-NLS-1$
    }

    /**
     * Calls {@link #opensRecordDeclaration} the way the lexer does: on the whole text, at the
     * offset just past the word {@code record}. Keeping the offset REAL is the point — passing
     * a pre-trimmed suffix and a zero would leave the argument itself unexercised.
     *
     * @param source source text containing the word {@code record}
     * @return the helper's verdict at the offset just past that word
     */
    private static boolean opensRecordDeclaration(String source)
    {
        int at = source.indexOf(RECORD);
        assertTrue("the fixture must actually contain the word", at >= 0); //$NON-NLS-1$
        return opensRecordDeclaration(source, at + RECORD.length());
    }

    /**
     * A {@code ,} inside a TYPE HEAD separates a list, it does not end the head: it is how
     * {@code implements A, B} and {@code <T, U>} are spelled. Treating it as the end made the
     * {@code {} that followed open a body of CODE, and with that every declaration of the
     * type went unjudged — silently, for the whole file. Five files in this repository carry
     * that shape, so this is the difference between a ratchet and a ratchet that is off.
     */
    @Test
    public void detectorJudgesATypeWhoseHeadContainsAComma()
    {
        String[][] heads = {
            {"implements A, B", "class C implements A, B"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"a generic parameter list", "class C<T, U>"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"an interface extending two", "interface C extends A, B"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"no comma at all (control)", "class C implements A"}, //$NON-NLS-1$ //$NON-NLS-2$
        };
        for (String[] head : heads)
        {
            String insideHead = String.join("\n", //$NON-NLS-1$
                head[1],
                "{", //$NON-NLS-1$
                "    /** Attached. */", //$NON-NLS-1$
                "    @Deprecated", //$NON-NLS-1$
                "    /** Dropped. */", //$NON-NLS-1$
                "    void m() {}", //$NON-NLS-1$
                "}"); //$NON-NLS-1$
            assertEquals("a block inside a declaration head, in a type declared with " //$NON-NLS-1$
                + head[0], List.of(Integer.valueOf(5)), orphanedJavadocLines(insideHead));

            String endOfBody = String.join("\n", //$NON-NLS-1$
                head[1],
                "{", //$NON-NLS-1$
                "    int f;", //$NON-NLS-1$
                "    /** old member left behind */", //$NON-NLS-1$
                "}"); //$NON-NLS-1$
            assertEquals("a block left at the end of a type declared with " + head[0], //$NON-NLS-1$
                List.of(Integer.valueOf(4)), orphanedJavadocLines(endOfBody));
        }
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
     *   <li>it appears while a declaration HEAD opened by an ANNOTATION is still open —
     *       before the {@code ;}, {@code ,}, {@code :}, <code>{</code> or <code>}</code> that
     *       closes it — AND a block stood immediately before that annotation. There the
     *       earlier block is the one javadoc renders, so this one is the discarded half of a
     *       pair. Without the pair there is nothing to choose between; and a head opened by a
     *       modifier or a type name is not judged at all, because those also begin things no
     *       javadoc can document; or</li>
     *   <li>it is still pending when a <code>}</code> closes a TYPE body — the member it was
     *       written for was deleted and left it behind.</li>
     * </ul>
     * All three are gated on the block standing where a DECLARATION could stand: inside a
     * type body, outside any unclosed {@code (}, and before a declaration's {@code =} rather
     * than in the initializer that follows it. Executable code and expressions hold no
     * members, so a block there is an ordinary comment — and a refusal naming one would ask
     * the reader to move it back to a declaration that does not exist. Every shape this gate
     * rules out was a real accusation this detector used to make on legal code.
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
     * @return the javadoc blocks that document nothing, in source order
     */
    static List<Orphan> orphanedJavadoc(String source)
    {
        // Keyed by the block's OFFSET: two blocks can share a line
        // (/** a */ /** b */ /** c */ int f;) and keying by line would report one of two.
        SortedMap<Integer, Orphan> orphans = new TreeMap<>();
        int i = 0;
        int line = 1;
        int depth = 0;
        boolean headOpen = false;
        int pending = -1;
        int pendingLine = -1;
        String pendingText = null;
        // One entry per brace met at parenthesis depth 0: true when it opened a TYPE body.
        // Braces inside an argument list are not tracked at all - nothing in there is judged.
        // A declaration - and
        // so a declaration head - can only live in one of those; everything a method body
        // holds is executable code, where a /** */ block is an ordinary comment.
        Deque<Boolean> typeBody = new ArrayDeque<>();
        boolean sawTypeKeyword = false;
        boolean afterDot = false;
        // Set by an '=' at depth 0: everything from there to the ';' is the INITIALIZER, an
        // expression. The declaration ITSELF ended at the name, so a block in there documents
        // nothing and has nothing to be moved back to - 'int f = /** why one */ 1;' was
        // accused without it.
        //
        // It is SAVED AND RESTORED around every brace, not cleared at one, because an
        // initializer can contain braces of its own: an array initializer, a lambda body, an
        // anonymous class. Clearing at the brace ended the initializer early and the rest of
        // the SAME statement was judged as a declaration again -
        // 'int f = new int[] { 1 }.length + /** note */ 1;'. Only the ';' clears this flag:
        // a real Java declaration can also end at the ',' before another declarator, but
        // that ',' is indistinguishable here from one inside the initializer's own type
        // arguments, so the conservative reading costs a MISS and never an accusation.
        boolean pastAssignment = false;
        Deque<Boolean> assignmentOutside = new ArrayDeque<>();
        // Whether a javadoc block stood immediately BEFORE the token that opened the current
        // declaration head. The head accusation exists only to pick the right one of a PAIR -
        // javadoc renders the block before the first token and drops the one after it - so
        // without a first block there is no pair, nothing to disambiguate, and no accusation
        // to make. This is what tells 'int f = 1' and 'static { }' (whose heads open with
        // nothing in front of them) apart from '@Deprecated' standing between two blocks.
        boolean headHadDoc = false;
        // Whether the token that opened the current head was an ANNOTATION. An annotation is
        // the only head-opening token that CANNOT introduce something other than a
        // declaration: a modifier also begins 'static { }', a type name also begins
        // 'int f = expr', and punctuation begins anything at all. Every wrong accusation this
        // detector was measured making came through one of those, never through '@'.
        boolean headOpenedByAnnotation = false;
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
                // A word is a code token, and ANY first token of a declaration opens its
                // head - the type of a package-private member just as much as 'public'.
                if (!headOpen)
                {
                    headHadDoc = pending >= 0;
                    headOpenedByAnnotation = false;
                }
                pending = -1;
                pendingLine = -1;
                headOpen = true;
                String token = word.toString();
                // 'Foo.class' is a class LITERAL, not a type declaration - hence afterDot.
                //
                // Only at depth 0, because only a '{' at depth 0 is ever pushed: a type
                // declared inside an argument list (a local class in a lambda) has its brace
                // in there too, so the flag could never be spent - it would merely SURVIVE
                // the closing ')' and mark the next unrelated '{' as a type body.
                if (!afterDot && depth == 0 && (TYPE_KEYWORDS.contains(token)
                    || (RECORD.equals(token) && opensRecordDeclaration(source, i))))
                {
                    sawTypeKeyword = true;
                }
                afterDot = false;
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
                    // Both accusations need a place where a DECLARATION could stand: inside a
                    // type body, not inside an unclosed '(' and not past a declaration's '='.
                    // An argument list, a condition, an annotation's values and an initializer
                    // all hold expressions, never members, so a block in one is an ordinary
                    // comment - and there is no declaration below it to be moved back to,
                    // which is the only thing this ratchet ever asks anyone to do.
                    boolean whereADeclarationCouldBe =
                        depth == 0 && !pastAssignment && inTypeBody(typeBody);
                    if (pending >= 0 && whereADeclarationCouldBe)
                    {
                        orphans.put(Integer.valueOf(pending), new Orphan(pendingLine, pendingText));
                    }
                    if (headOpen && headOpenedByAnnotation && headHadDoc
                        && whereADeclarationCouldBe)
                    {
                        orphans.put(Integer.valueOf(startOffset),
                            new Orphan(startLine, identityOf(source, startOffset, i)));
                    }
                    pending = startOffset;
                    pendingLine = startLine;
                    pendingText = identityOf(source, startOffset, i);
                }
                continue;
            }
            // Everything below is a code token, so a javadoc block before it is attached -
            // unless it is a '}', which closes a body instead of starting a declaration.
            int wasPending = pending;
            int wasPendingLine = pendingLine;
            String wasPendingText = pendingText;
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
                if (!headOpen)
                {
                    headHadDoc = wasPending >= 0;
                    headOpenedByAnnotation = true;
                }
                headOpen = true;
                i++;
                continue;
            }
            boolean wasHeadOpen = headOpen;
            if (c == '(')
            {
                depth++;
                headOpen = true;
            }
            else if (c == '{' && depth == 0)
            {
                typeBody.push(Boolean.valueOf(sawTypeKeyword));
                assignmentOutside.push(Boolean.valueOf(pastAssignment));
                sawTypeKeyword = false;
                headOpen = false;
                pastAssignment = false;
            }
            else if (c == '}' && depth == 0)
            {
                // A block still pending at the end of a TYPE body documents nothing: the
                // member it was written for was deleted and left it behind. Inside a method
                // body the same shape is an ordinary trailing comment, so it is left alone.
                if (wasPending >= 0 && !pastAssignment && inTypeBody(typeBody))
                {
                    orphans.put(Integer.valueOf(wasPending), new Orphan(wasPendingLine, wasPendingText));
                }
                typeBody.poll();
                Boolean outside = assignmentOutside.poll();
                pastAssignment = outside != null && outside.booleanValue();
                sawTypeKeyword = false;
                headOpen = false;
            }
            else if (c == ')')
            {
                depth = Math.max(0, depth - 1);
                headOpen = true;
            }
            else if (depth == 0 && (c == ';' || c == ',' || c == ':'))
            {
                // The end of whatever came before. ':' is here for a LABEL - 'default:' and
                // 'case X:' would otherwise leave the head open over the statements below.
                //
                // A ',' does NOT end a type head, it separates a LIST inside one:
                // 'implements A, B', 'extends A, B', '<T, U>', 'permits A, B'. Clearing the
                // type flag there made the '{' that follows push a non-type body, and with it
                // every declaration of that type went unjudged. ';' and ':' cannot appear in a
                // type head at all, so they still end one.
                sawTypeKeyword = c == ',' && sawTypeKeyword;
                headOpen = false;
                // Only the ';' ends the declaration. A ',' at depth 0 can just as easily be
                // inside the initializer's own type arguments ('new HashMap<String, Integer>()')
                // and a ':' inside its ternary, and treating either as the end put the rest of
                // the expression back under judgement. The cost is a MISS on the second
                // declarator of 'int a = 1, /** note */ b = 2;', which is the safe direction.
                pastAssignment = c != ';' && pastAssignment;
            }
            else if (c == '=' && depth == 0)
            {
                // Past here lies the INITIALIZER, and it is an expression. A word reopens
                // headOpen on the very next token, so this cannot be expressed by clearing
                // headOpen once - it has to be remembered until the declaration really ends.
                pastAssignment = true;
                headOpen = true;
            }
            else
            {
                headOpen = true;
            }
            if (!wasHeadOpen && headOpen)
            {
                headHadDoc = wasPending >= 0;
                headOpenedByAnnotation = false;
            }
            afterDot = c == '.';
            i++;
        }
        return new ArrayList<>(orphans.values());
    }

    /**
     * Whether the {@code record} just read is the keyword of a record DECLARATION rather than
     * an ordinary identifier. {@code record} is contextual, so it is also a legal method,
     * parameter and variable name - and this repository uses it as all three.
     * <p>
     * A declaration is recognised by what follows: the record's name, then its component
     * list. Requiring the {@code (} is what separates it from {@code record instanceof
     * String}, where an identifier follows too. The cost is a MISS on a generic record
     * ({@code record Pair<A, B>(...)}, whose name is followed by {@code <}) and on one with a
     * comment wedged in ({@code record /* c *}{@code / R(...)}), which is the safe direction:
     * a shape this cannot see goes unjudged, it is never accused.
     *
     * Package-private, and tested DIRECTLY by
     * {@link #recordIsATypeOnlyWhenAComponentListFollows}, because the guard is defensive:
     * once a type keyword is only trusted at depth 0, no legal shape was found that reaches a
     * depth-0 <code>{</code> from {@code record instanceof X} without passing a {@code ;},
     * {@code ,}, {@code :} or <code>}</code> that clears the flag anyway. An unreachable
     * decision left untested is one the next reader deletes as dead weight.
     *
     * @param source the whole file
     * @param at the offset just past the word {@code record}
     * @return {@code true} when a record declaration starts here
     */
    static boolean opensRecordDeclaration(String source, int at)
    {
        int i = skipWhitespace(source, at);
        if (i >= source.length() || !Character.isJavaIdentifierStart(source.charAt(i)))
        {
            return false;
        }
        while (i < source.length() && Character.isJavaIdentifierPart(source.charAt(i)))
        {
            i++;
        }
        i = skipWhitespace(source, i);
        return i < source.length() && source.charAt(i) == '(';
    }

    /** @return the index of the first non-whitespace character at or after {@code at} */
    private static int skipWhitespace(String source, int at)
    {
        int i = at;
        while (i < source.length() && Character.isWhitespace(source.charAt(i)))
        {
            i++;
        }
        return i;
    }

    /**
     * Whether the innermost open brace is a TYPE body (or we are at file level, where types
     * themselves are declared). Executable code is everything else, and a {@code /** *}{@code /}
     * block there documents nothing by construction - accusing it would redden the build on
     * legal code, which is the one failure this ratchet must never have.
     *
     * @param typeBody one entry per brace met at parenthesis depth 0
     * @return {@code true} when a declaration could appear here
     */
    private static boolean inTypeBody(Deque<Boolean> typeBody)
    {
        return typeBody.isEmpty() || typeBody.peek().booleanValue();
    }

    /**
     * @param source the contents of one {@code .java} file
     * @return the 1-based start lines of the blocks that document nothing, in source order
     */
    static List<Integer> orphanedJavadocLines(String source)
    {
        List<Integer> lines = new ArrayList<>();
        for (Orphan orphan : orphanedJavadoc(source))
        {
            lines.add(Integer.valueOf(orphan.line));
        }
        return lines;
    }

    /**
     * One block that documents nothing: WHERE it is (for the reader) and WHICH one it is
     * (for the allow-list). The line moves whenever anything above it is edited, so it can
     * report but must never identify; the {@link #identityOf} does the identifying.
     */
    static final class Orphan
    {
        final int line;

        final String identity;

        Orphan(int line, String identity)
        {
            this.line = line;
            this.identity = identity;
        }
    }

    /**
     * The identity of a site: the block's own text, whitespace-normalised, WHOLE. Chosen so
     * that an edit ABOVE the block - which moves its line and nothing else - leaves it
     * unchanged, while replacing the block with a different one does not. Rewording the
     * block also changes it, and that is intended: an allow-listed block that was rewritten
     * deserves a fresh look rather than an inherited pardon.
     * <p>
     * Deliberately NOT truncated. A prefix is enough to read but not to identify: two blocks
     * opening with the same words would share one pardon, and fixing the pardoned one while
     * adding the other would keep every check green. {@link #display} does the shortening,
     * and only for the message.
     *
     * @param source the whole file
     * @param from the offset of the block's {@code /**}
     * @param to the offset just past its {@code *}{@code /}
     * @return the whole block's text, whitespace-normalised
     */
    static String identityOf(String source, int from, int to)
    {
        String body = source.substring(from, Math.min(to, source.length()));
        StringBuilder out = new StringBuilder();
        boolean space = true;
        for (int at = 0; at < body.length(); at++)
        {
            char c = body.charAt(at);
            if (c == '/' || c == '*')
            {
                continue;
            }
            if (Character.isWhitespace(c))
            {
                space = true;
                continue;
            }
            if (space && out.length() > 0)
            {
                out.append(' ');
            }
            space = false;
            out.append(c);
        }
        return out.toString();
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
    private static Map<String, List<Orphan>> scanSources()
    {
        Map<String, List<Orphan>> result = new LinkedHashMap<>();
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

    private static void scanRoot(String rootPath, File root, Map<String, List<Orphan>> into)
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
                    orphanedJavadoc(read(p))));
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
