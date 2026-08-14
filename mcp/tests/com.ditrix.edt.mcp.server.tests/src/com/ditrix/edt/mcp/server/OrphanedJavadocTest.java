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
 * This does NOT fail the build — see {@link #FAIL_THE_BUILD} for the measurement behind
 * that decision, and {@link #KNOWN_LIMITS} for what it gets wrong in both directions. It
 * reports, and the report is worth having: it found four real orphans here in the week it
 * was written, each of them the only documentation its method had.
 * <p>
 * {@link #KNOWN_ORPHANS} is the allow-list a GATE would need, kept and tested so that
 * turning one on later is a one-line decision rather than a rewrite. It is empty.
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
     * It is EMPTY, and that is the point: the ratchet starts with no debt, so the first entry
     * anyone adds is a deliberate act with a reason attached rather than one more line on a
     * list nobody reads. Both entries it used to carry are gone — {@code ToolSettingsService}
     * because master returned the block to its declaration, and {@code PreferenceConstants}
     * because the PR that was editing that file landed and the block could finally be dealt
     * with. The mechanism is still exercised on synthetic input by
     * {@link #aPardonDoesNotTransferToADifferentBlock} and its neighbours, so an empty map
     * here does not leave it untested.
     */
    private static final Map<String, List<String>> KNOWN_ORPHANS = new HashMap<>();

    /**
     * Whether a finding FAILS the build. It does NOT, and the reason is measured rather than
     * modest: this detector is a scanner, not a Java lexer, and review kept finding legal code
     * it got wrong — parentheses, angle brackets, array dimensions, extends/implements/throws
     * clauses, unicode escapes, CR-only line endings, a block in front of a {@code package} or
     * {@code import}. Each was fixed and the next arrived. The series does not converge,
     * because the job it had drifted into is the whole lexical grammar of Java.
     * <p>
     * A gate that reddens on correct code blocks every contributor and gets switched off by
     * the first person it inconveniences, so it would have to be right about ALL of Java
     * before it may fail anything. As a REPORT the same detector costs nothing and is still
     * useful: it found four real orphans in this repository the week it was written, and its
     * mistakes are noise in a log instead of somebody's blocked afternoon.
     * <p>
     * Flip this to {@code true} to make it a gate — after reading {@link #KNOWN_LIMITS}, and
     * ideally after replacing the scanner with a real lexer. Everything a gate needs is
     * already here and tested: the allow-list keyed by SITE
     * ({@link #aPardonDoesNotTransferToADifferentBlock},
     * {@link #twoBlocksWithTheSameWordsInOneFileAreTwoSites}), the stale-entry check, and the
     * refusal text that prints the limits with every finding.
     */
    private static final boolean FAIL_THE_BUILD = false;

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
     * What this report gets wrong, printed WITH every finding — so the person reading the
     * report and the person opening this file see the same list, and it cannot drift.
     * <p>
     * It runs in BOTH directions, and that honesty is the point. The detector answers one
     * question, "can a member be declared HERE?", and answers NO to everything it does not
     * recognise, so most of its errors are silence. But it is a scanner and not a lexer, and a
     * handful of shapes — a unicode-escaped brace, a CR-only file, a pair of blocks in front of
     * an {@code import} — still make it name something that is not an orphan.
     * <p>
     * That asymmetry is exactly why {@link #FAIL_THE_BUILD} is false. A report may be wrong
     * sometimes; a gate may not. Six review rounds closed one family after another —
     * parentheses, angle brackets, array dimensions, the header clauses — and the next one
     * always arrived, because the list being enumerated was the lexical grammar of Java. The
     * list below is where that stopped, written down instead of chased.
     */
    private static final String KNOWN_LIMITS =
        "This is a REPORT, not a gate: it does not fail the build. It is a scanner, not a Java\n" //$NON-NLS-1$
            + "lexer, so it is wrong in BOTH directions on some legal code. Measured, not guessed:\n" //$NON-NLS-1$
            + "It can name something that is not an orphan:\n" //$NON-NLS-1$
            + "  - a pair of blocks in front of a 'package' or an 'import', which document nothing but\n" //$NON-NLS-1$
            + "    sit where a top-level type could.\n" //$NON-NLS-1$
            + "  - a pair in front of an initializer block, or after a brace that merely closed a field\n" //$NON-NLS-1$
            + "    initializer ('int[] a = {1} /** x */ /** y */;').\n" //$NON-NLS-1$
            + "It can stay silent where there IS one:\n" //$NON-NLS-1$
            + "  - anything past a declaration's first token: inside '(', '<' or '[', in an\n" //$NON-NLS-1$
            + "    extends/implements/throws clause, or in an initializer expression.\n" //$NON-NLS-1$
            + "  - a block after a modifier, a type name or punctuation - only an ANNOTATION may stand\n" //$NON-NLS-1$
            + "    between the two halves of a reported pair - and a lone block inside a declaration.\n" //$NON-NLS-1$
            + "  - a structural token spelled as a unicode escape (\\u007b for '{'), which Java\n" //$NON-NLS-1$
            + "    translates before lexing and this does not, so a type body is never entered.\n" //$NON-NLS-1$
            + "  - a file whose lines end with a bare CR: '//' swallows the rest of it, and every line\n" //$NON-NLS-1$
            + "    number it could still report would be 1.\n" //$NON-NLS-1$
            + "  - an annotation TYPE body ('@interface A { .. }'), an anonymous class body, a record\n" //$NON-NLS-1$
            + "    whose name does not directly follow the keyword (generic, or with a comment between),\n" //$NON-NLS-1$
            + "    a second declarator, an enum constant after the first, and a block left after the\n" //$NON-NLS-1$
            + "    last declaration in a file.\n" //$NON-NLS-1$
            + "The allow-list keys on the block's text plus the head of the declaration below it. That\n" //$NON-NLS-1$
            + "separates two sites in one file unless their following declarations are worded the same\n" //$NON-NLS-1$
            + "as well - two 'int f' in two types would still share one entry.\n" //$NON-NLS-1$
            + "Making this a gate needs a real Java lexer first; see FAIL_THE_BUILD.\n" //$NON-NLS-1$
            + "Details: OrphanedJavadocTest."; //$NON-NLS-1$

    @Test
    public void reportsOrphanedJavadocFoundInThisRepository()
    {
        List<String> problems = unpardonedAcross(scanSources(), KNOWN_ORPHANS);
        if (problems.isEmpty())
        {
            return;
        }
        // Printed, so a finding is visible in the build log and can be acted on; asserted only
        // when somebody has decided this may fail the build.
        System.out.println(refusalText(problems)); // NOSONAR: the report IS the output here
        assertTrue(refusalText(problems), !FAIL_THE_BUILD);
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

        // The other branch of the same reduction: the file IS scanned, and the pardon names a
        // block that is no longer orphaned there. Asserted on synthetic input because
        // KNOWN_ORPHANS is empty - the repository cannot exercise this path at all, and a
        // reduction with one branch covered and one not is how a stale entry survives.
        Map<String, List<String>> fixedButStillListed = new LinkedHashMap<>();
        fixedButStillListed.put("a/A.java", List.of("A block somebody has since fixed.")); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> outlived = stalePardonsAcross(scanned, fixedButStillListed);
        assertEquals("a pardon for a scanned file whose block is gone is stale too", //$NON-NLS-1$
            1, outlived.size());
        assertTrue("and it names the block, not just the file", //$NON-NLS-1$
            outlived.get(0).contains("no longer orphaned")); //$NON-NLS-1$
        assertTrue("while a pardon whose block is still there is not stale", //$NON-NLS-1$
            stalePardonsAcross(scanned, Map.of("a/A.java", List.of("Pardoned in A."))).isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
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
     * The whole report, in one place so it can be asserted rather than hoped for. It names
     * {@link #KNOWN_LIMITS} because the person who needs to know what this CANNOT see is
     * the one reading the report, not the one who opens this file — and because some of
     * what it prints will be wrong, which the reader has to be told.
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
        assertEquals("identified by its own text AND the declaration it stands in front of", //$NON-NLS-1$
            "The pardoned block. @ int f", was.get(0).identity); //$NON-NLS-1$

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
        if (stale.isEmpty())
        {
            return;
        }
        String report = "Stale KNOWN_ORPHANS entries - drop them to tighten the report:\n  " //$NON-NLS-1$
            + String.join("\n  ", stale); //$NON-NLS-1$
        System.out.println(report); // NOSONAR: the report IS the output here
        assertTrue(report, !FAIL_THE_BUILD);
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

        // An ANNOTATED enum constant. Only the FIRST position in an enum body is a member
        // position here; a ',' does not hand it back, so the constants after it are not
        // judged at all. A miss, and the reason this shape is quiet.
        String annotatedConstant = String.join("\n", //$NON-NLS-1$
            "public enum E", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    @Deprecated A,", //$NON-NLS-1$
            "    /** Documents B. */ B", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        assertEquals("an enum constant is not a member position, annotated or not", //$NON-NLS-1$
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

        // A brace INSIDE an argument list is not a member boundary either, even though the
        // body enclosing it is a type body. Without the parenthesis-depth check the array
        // initializer below would hand back the member position and its two ordinary notes
        // would be reported.
        assertEquals("a brace inside an argument list does not restore the member position", //$NON-NLS-1$
            List.of(), orphanedJavadocLines(String.join("\n", //$NON-NLS-1$
                "class A", //$NON-NLS-1$
                "{", //$NON-NLS-1$
                "    Object f = bar(new Object[] { /** one */ /** two */ null });", //$NON-NLS-1$
                "}"))); //$NON-NLS-1$

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
     * Angle brackets hold TYPES, never members — a type argument list
     * ({@code Map<String, Integer>}), a type parameter list ({@code class C<T, U>}), a
     * wildcard bound. Parentheses could not see them, so a block inside one was judged as
     * though it stood among a type's members: two adjacent blocks read as a discarded pair,
     * and a {@code TYPE_PARAMETER} annotation after the list's comma read as the first token
     * of a fresh declaration. Both were reported by review with a {@code javac --release 17}
     * check, so the input is legal and one such class anywhere in a scanned source root would
     * redden the build.
     * <p>
     * One rule for the whole family, not one per shape: the two the review named are here
     * alongside the two it did not (a bounded wildcard, a generic METHOD's type parameters),
     * and all four go quiet together.
     */
    @Test
    public void detectorNeverAccusesInsideAngleBrackets()
    {
        String[][] shapes = {
            {"a type argument list", //$NON-NLS-1$
                "class C", "{", "    Map</** first */ /** second */ String, Integer> v;", "}"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            {"an annotated type PARAMETER", //$NON-NLS-1$
                "class C<T, /** first */ @TA /** second */ U>", "{", "", "}"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            {"a generic METHOD's type parameters", //$NON-NLS-1$
                "class C", "{", "    <T, /** first */ @TA /** second */ U> void m() {}", "}"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            {"a bounded wildcard", //$NON-NLS-1$
                "class C", "{", "    List<? extends /** a */ /** b */ Number> xs;", "}"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            {"a superclass' type arguments", //$NON-NLS-1$
                "class C extends B</** a */ /** b */ String>", "{", "", "}"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            // Two more brackets, reported after the angle brackets were handled and the
            // reason the predicate was inverted rather than extended a third time.
            {"an array dimension", //$NON-NLS-1$
                "class C", "{", "    String[/** first */ /** second */] values;", "}"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            {"an extends clause", //$NON-NLS-1$
                "class C extends /** first */ /** second */ Base", "{", "", "}"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            {"an implements clause", //$NON-NLS-1$
                "class C implements /** first */ /** second */ Base", "{", "", "}"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            {"a throws clause", //$NON-NLS-1$
                "class C", "{", "    void m() throws /** a */ /** b */ E {}", "}"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        };
        for (String[] shape : shapes)
        {
            assertEquals("a block inside " + shape[0] + " is not among a type's members", //$NON-NLS-1$ //$NON-NLS-2$
                List.of(), orphanedJavadocLines(
                    String.join("\n", shape[1], shape[2], shape[3], shape[4]))); //$NON-NLS-1$
        }

        // The suppression must END, or one generic signature would silence the whole file.
        // Each control below puts a REAL orphan after the angle brackets: the first two close
        // theirs properly, the third never closes it at all ('1 < 2' is a comparison), and the
        // fourth spells a '>' with no '<' before it, in a lambda arrow.
        String[][] controls = {
            {"a closed type argument list", "    Map<String, Integer> v;"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"nested type arguments closed by '>>'", "    Map<String, List<Integer>> v;"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"an unmatched '<' that is really a comparison", //$NON-NLS-1$
                "    static final boolean B = 1 < 2;"}, //$NON-NLS-1$
            {"a lambda arrow's '>'", "    Runnable r = () -> doIt();"}, //$NON-NLS-1$ //$NON-NLS-2$
        };
        for (String[] control : controls)
        {
            String source = String.join("\n", //$NON-NLS-1$
                "class C", //$NON-NLS-1$
                "{", //$NON-NLS-1$
                control[1],
                "", //$NON-NLS-1$
                "    /** Orphan. */", //$NON-NLS-1$
                "    /** Documents f. */", //$NON-NLS-1$
                "    int f;", //$NON-NLS-1$
                "}"); //$NON-NLS-1$
            assertEquals("after " + control[0] + " the rest of the type is judged again", //$NON-NLS-1$ //$NON-NLS-2$
                List.of(Integer.valueOf(5)), orphanedJavadocLines(source));
        }

        // Once the declaration has started, its own middle is not a member position either -
        // a MISS, and the deliberate direction. Reporting it would need "where can a member
        // NOT be", which is the open-ended question this predicate exists to avoid asking.
        assertEquals("past the type's first token the member position is gone - a miss", //$NON-NLS-1$
            List.of(), orphanedJavadocLines(String.join("\n", //$NON-NLS-1$
                "class C", //$NON-NLS-1$
                "{", //$NON-NLS-1$
                "    Map<String, Integer> /** one */ /** two */ v;", //$NON-NLS-1$
                "}"))); //$NON-NLS-1$

        assertEquals("nor in an annotation element's default expression", //$NON-NLS-1$
            List.of(), orphanedJavadocLines(String.join("\n", //$NON-NLS-1$
                "@interface A", //$NON-NLS-1$
                "{", //$NON-NLS-1$
                "    boolean v() default 3 > 2 && 1 < /** first */ /** second */ 2;", //$NON-NLS-1$
                "}"))); //$NON-NLS-1$

        // The type HEAD's own list must not swallow the body either.
        assertEquals("a generic type head is suppressed, its body is not", //$NON-NLS-1$
            List.of(Integer.valueOf(3)), orphanedJavadocLines(String.join("\n", //$NON-NLS-1$
                "class C<T, U> implements Map<T, U>", //$NON-NLS-1$
                "{", //$NON-NLS-1$
                "    /** Orphan. */", //$NON-NLS-1$
                "    /** Documents f. */", //$NON-NLS-1$
                "    int f;", //$NON-NLS-1$
                "}"))); //$NON-NLS-1$
    }

    /**
     * A pardon names a SITE, and a site is more than a file plus some words. Fix the pardoned
     * block, then add a new orphan carrying VERBATIM the same text somewhere else in the same
     * file: with the text alone the pardon transfers to it, and the stale-pardon check sees
     * nothing missing — both halves of the promise broken at once, in silence.
     * <p>
     * The site therefore carries the head of the declaration the block stands in front of.
     * That anchor is stable against the edit the identity was invented to survive — one ABOVE
     * the block, which moves its line and nothing else — while differing between two places
     * whose members differ. It does NOT separate two places whose following declarations are
     * worded identically as well: two {@code int f} in two types in one file still share a
     * site. Said in {@link #KNOWN_LIMITS} rather than left to be discovered.
     */
    @Test
    public void twoBlocksWithTheSameWordsInOneFileAreTwoSites()
    {
        String before = String.join("\n", //$NON-NLS-1$
            "class A", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    /** Left behind. */", //$NON-NLS-1$
            "    /** Documents f. */", //$NON-NLS-1$
            "    int f;", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "    /** Documents g. */", //$NON-NLS-1$
            "    void g() {}", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        List<Orphan> was = orphanedJavadoc(before);
        assertEquals("one orphan to start with", 1, was.size()); //$NON-NLS-1$

        // The pardoned block is FIXED, and the very same words reappear in front of a
        // DIFFERENT member. Same file, same text, same count - only the place differs.
        String after = String.join("\n", //$NON-NLS-1$
            "class A", //$NON-NLS-1$
            "{", //$NON-NLS-1$
            "    /** Documents f. */", //$NON-NLS-1$
            "    int f;", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "    /** Left behind. */", //$NON-NLS-1$
            "    /** Documents g. */", //$NON-NLS-1$
            "    void g() {}", //$NON-NLS-1$
            "}"); //$NON-NLS-1$
        List<Orphan> now = orphanedJavadoc(after);
        assertEquals("still exactly one - a count cannot tell these two apart", //$NON-NLS-1$
            1, now.size());
        assertEquals("and the TEXT cannot either", //$NON-NLS-1$
            identityOf("/** Left behind. */", 0, 19), //$NON-NLS-1$
            identityOf("/** Left behind. */", 0, 19)); //$NON-NLS-1$
        assertTrue("but the SITE differs, which is what the allow-list keys on", //$NON-NLS-1$
            !was.get(0).identity.equals(now.get(0).identity));

        List<String> pardoned = List.of(was.get(0).identity);
        assertTrue("the pardoned site stays pardoned", //$NON-NLS-1$
            unpardoned("A.java", was, pardoned).isEmpty()); //$NON-NLS-1$
        assertEquals("the new site is NOT covered by the old pardon", //$NON-NLS-1$
            1, unpardoned("A.java", now, pardoned).size()); //$NON-NLS-1$
        assertEquals("and the pardon that no longer matches is stale", //$NON-NLS-1$
            1, stalePardons("A.java", now, pardoned).size()); //$NON-NLS-1$

        // The anchor must still survive the edit an identity exists to survive: one ABOVE the
        // block. Line numbers do not - measured, when an unrelated PR added a line and the one
        // allow-listed block moved from 94 to 95 while its entry went on matching.
        List<Orphan> shifted = orphanedJavadoc("// a new line at the top\n" + before); //$NON-NLS-1$
        assertEquals("an edit above the block moves its line", //$NON-NLS-1$
            was.get(0).line + 1, shifted.get(0).line);
        assertEquals("but must not change which SITE it is", //$NON-NLS-1$
            was.get(0).identity, shifted.get(0).identity);
    }

    /**
     * The predicate answers "a member can be declared HERE", and it answers NO for anything it
     * does not recognise. That direction is the whole design: the opposite question — where
     * can a member NOT be — has no finite answer, and every attempt to enumerate it produced a
     * fresh false accusation on legal code (parentheses, then angle brackets, then array
     * dimensions, then the {@code extends}/{@code implements}/{@code throws} clauses), which
     * for something that runs on every build is the one failure that must not happen.
     * <p>
     * So this asserts the DEFAULT, on constructs the detector has no idea about — including
     * syntax that does not exist in Java 17 at all. A future language feature must arrive as a
     * miss, never as a refusal.
     */
    @Test
    public void anUnrecognisedConstructIsNotAMemberPosition()
    {
        String[][] unknown = {
            {"a bracket kind with no meaning here", "    Foo[[/** a */ /** b */]] x;"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"an operator this detector never models", "    int f = a ?: /** a */ /** b */ b;"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"a made-up contextual keyword", "    sealed permits /** a */ /** b */ Foo;"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"a clause borrowed from another language", "    class D of /** a */ /** b */ E"}, //$NON-NLS-1$ //$NON-NLS-2$
        };
        for (String[] shape : unknown)
        {
            assertEquals("an unrecognised construct - " + shape[0] //$NON-NLS-1$
                + " - must answer 'not a declaration', not 'declaration'", //$NON-NLS-1$
                List.of(), orphanedJavadocLines("class C\n{\n" + shape[1] + "\n}")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // ...and the position it DOES recognise still answers yes, so this cannot pass by the
        // predicate having collapsed to a constant false.
        assertEquals("the one position it proves is still proved", //$NON-NLS-1$
            List.of(Integer.valueOf(3)), orphanedJavadocLines(
                "class C\n{\n    /** Orphan. */\n    /** Documents f. */\n    int f;\n}")); //$NON-NLS-1$
    }

    /**
     * The identity keeps the block's own asterisks and slashes, and drops only the STRUCTURE:
     * the {@code /**}, the {@code *}{@code /} and the {@code *} margin of a continuation line.
     * Removing them everywhere made two different blocks share one identity, which defeats
     * the point of keying the allow-list on identity at all — the pardon of a fixed block
     * would transfer to a look-alike while {@link #stalePardons} reported nothing missing.
     */
    @Test
    public void identityKeepsTheProsesOwnAsterisksAndSlashes()
    {
        String plain = identityOf("/** Alpha beta. */", 0, 18); //$NON-NLS-1$
        String emphasised = identityOf("/** *Alpha* beta. */", 0, 20); //$NON-NLS-1$
        assertEquals("the structure is still removed", "Alpha beta.", plain); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("and emphasis is part of the text", "*Alpha* beta.", emphasised); //$NON-NLS-1$ //$NON-NLS-2$

        // The collision this exists to prevent, asserted on the DECISION and not only on the
        // strings: one pardon must no longer cover both blocks.
        List<String> pardoned = List.of(plain);
        assertTrue("the pardoned block stays pardoned", //$NON-NLS-1$
            unpardoned("A.java", List.of(new Orphan(3, plain)), pardoned).isEmpty()); //$NON-NLS-1$
        assertEquals("the look-alike is a different block and must be reported", //$NON-NLS-1$
            1, unpardoned("A.java", List.of(new Orphan(9, emphasised)), pardoned).size()); //$NON-NLS-1$

        // A slash inside the prose counts too - a URL is the everyday case.
        assertTrue("two blocks differing only by a slash are different identities", //$NON-NLS-1$
            !identityOf("/** See http://a.example */", 0, 27) //$NON-NLS-1$
                .equals(identityOf("/** See http:a.example */", 0, 25))); //$NON-NLS-1$

        // ...while the javadoc margin of a continuation line is still structure.
        assertEquals("the '*' margin is dropped, the text is not", //$NON-NLS-1$
            "Alpha. <p>Beta.", identityOf("/**\n * Alpha.\n * <p>Beta.\n */", 0, 29)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a continuation line that begins with emphasis keeps it", //$NON-NLS-1$
            "Alpha. *Beta* gamma.", //$NON-NLS-1$
            identityOf("/**\n * Alpha.\n * *Beta* gamma.\n */", 0, 34)); //$NON-NLS-1$
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
     * One left-to-right scan of the file, yielding two kinds of token: JAVADOC comments and
     * CODE. A block documents nothing when
     * <ul>
     *   <li>the next token is another javadoc block — no code came between, so the later one
     *       is the nearer and this one is discarded; unless an ANNOTATION separates them, in
     *       which case javadoc keeps the earlier and the LATER one is discarded; or</li>
     *   <li>it is still pending when a <code>}</code> closes a TYPE body — the member it was
     *       written for was deleted and left it behind.</li>
     * </ul>
     * Both are gated on {@code atMemberStart}: a single POSITIVE proof that a member could be
     * declared at that point. It is true at the start of a file and immediately after a member
     * boundary inside a type body, and ANY code token that is not part of an annotation takes
     * it away.
     * <p>
     * That direction is the design. The gate used to ask the opposite question — is this one
     * of the places a declaration cannot be — and enumerate them: parentheses, then angle
     * brackets, then array dimensions, then the {@code extends}/{@code implements}/
     * {@code throws} clauses. Each was fixed and the next arrived, because that list is the
     * lexical grammar of Java and has no end. Proving the position instead makes an
     * unrecognised construct answer "not a declaration" by default, so a shape nobody has
     * written yet arrives as a miss.
     * <p>
     * Scanning rather than matching line prefixes is what keeps a text block holding Java
     * source, a {@code "://"} literal or a {@code /**} inside a string from being read as
     * documentation. It is still a scanner and not a lexer, and what that costs is listed
     * ONCE, in {@link #KNOWN_LIMITS}, which every report prints — so the reader holding the
     * report and the reader opening this file get the same list.
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
        int pending = -1;
        int pendingLine = -1;
        String pendingText = null;
        // Whether an ANNOTATION stood between the pending block and this one. It decides
        // WHICH half of the pair is the orphan: javadoc renders the block before the
        // declaration's first token and drops the one after it, so with an annotation between
        // them the LATER block is the discarded one, and without it the EARLIER one is.
        boolean annotationSincePending = false;
        // One entry per brace met at parenthesis depth 0: true when it opened a TYPE body.
        Deque<Boolean> typeBody = new ArrayDeque<>();
        boolean sawTypeKeyword = false;
        boolean afterDot = false;
        // The whole predicate, and it is POSITIVE: true only where a member declaration is
        // known to be able to begin - at the start of a file, and immediately after a member
        // boundary inside a type body. Any code token that is not part of an annotation takes
        // it away, and only another boundary gives it back.
        //
        // It replaced a list of places a declaration could NOT be - inside '(', inside '<',
        // past a '=' - and that list was the defect. It could never be finished: parentheses,
        // then angle brackets, then array dimensions, then 'extends'/'implements'/'throws'
        // each arrived as a fresh false accusation on legal code, which for a build gate is
        // the one failure that must not happen. The set of places a member CAN be declared is
        // small and closed, so proving that instead makes an unknown construct answer "not a
        // declaration" by default. The price is paid in misses, which is the safe direction.
        // Note it already IMPLIES inTypeBody: it is only ever set true together with it,
        // and the two can only change at the same tokens. Naming both would read as two
        // conditions where there is one, and the spare conjunct would be unfalsifiable.
        boolean atMemberStart = true;
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
                pending = -1;
                pendingLine = -1;
                atMemberStart = false;
                String token = word.toString();
                // 'Foo.class' is a class LITERAL, not a type declaration - hence afterDot.
                // Only at depth 0, because only a '{' at depth 0 is ever pushed.
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
                    if (pending >= 0 && atMemberStart)
                    {
                        if (annotationSincePending)
                        {
                            // The pair is split by the declaration's first token, so the block
                            // javadoc keeps is the earlier one and THIS is the discarded half.
                            orphans.put(Integer.valueOf(startOffset),
                                new Orphan(startLine, siteOf(source, startOffset, i)));
                        }
                        else
                        {
                            orphans.put(Integer.valueOf(pending),
                                new Orphan(pendingLine, pendingText));
                        }
                    }
                    pending = startOffset;
                    pendingLine = startLine;
                    pendingText = siteOf(source, startOffset, i);
                    annotationSincePending = false;
                }
                continue;
            }
            int wasPending = pending;
            int wasPendingLine = pendingLine;
            String wasPendingText = pendingText;
            boolean wasAtMemberStart = atMemberStart;
            pending = -1;
            pendingLine = -1;
            if (c == '"' && source.startsWith("\"\"\"", i)) //$NON-NLS-1$
            {
                int after = skipTextBlock(source, i);
                line += countNewlines(source, i, after);
                i = after;
                atMemberStart = false;
                continue;
            }
            if (c == '"' || c == '\'')
            {
                int after = skipLiteral(source, i, c);
                line += countNewlines(source, i, after);
                i = after;
                atMemberStart = false;
                continue;
            }
            if (c == '@')
            {
                // An annotation is the ONE thing that may stand between a block and the
                // declaration it documents without ending the member position - it is part of
                // the declaration. Consumed whole (name plus any argument list) so that
                // nothing inside it is mistaken for the declaration itself.
                int after = skipAnnotation(source, i);
                line += countNewlines(source, i, after);
                i = after;
                pending = wasPending;
                pendingLine = wasPendingLine;
                pendingText = wasPendingText;
                atMemberStart = wasAtMemberStart;
                annotationSincePending = true;
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
            else if (c == '{' && depth == 0)
            {
                typeBody.push(Boolean.valueOf(sawTypeKeyword));
                sawTypeKeyword = false;
            }
            else if (c == '}' && depth == 0)
            {
                // A block still pending at the end of a TYPE body documents nothing: the
                // member it was written for was deleted and left it behind.
                if (wasPending >= 0 && wasAtMemberStart)
                {
                    orphans.put(Integer.valueOf(wasPending), new Orphan(wasPendingLine, wasPendingText));
                }
                typeBody.poll();
                sawTypeKeyword = false;
            }
            else if (depth == 0 && (c == ';' || c == ','))
            {
                // A ',' does NOT end a type head, it separates a LIST inside one:
                // 'implements A, B', '<T, U>', 'permits A, B'. Clearing the type flag there
                // made the '{' that follows push a non-type body, and every declaration of
                // that type went unjudged.
                sawTypeKeyword = c == ',' && sawTypeKeyword;
            }
            // A ';' or a '}' ends a member, and a '{' opens a body: after any of them the
            // next declaration may begin - if we are in a TYPE body. Everything else is a
            // code token, and a code token means we are no longer at a member position.
            atMemberStart = (c == ';' || c == '}' || c == '{') && depth == 0
                && inTypeBody(typeBody);
            afterDot = c == '.';
            i++;
        }
        return new ArrayList<>(orphans.values());
    }

    /**
     * The index just past the annotation that starts at {@code at}: its name, qualified or
     * not, and its argument list if it has one. Consumed as ONE token because an annotation is
     * part of the declaration it precedes — it must not end the member position the way an
     * ordinary code token does — and because nothing inside its arguments should be read as
     * code.
     *
     * @param source the whole file
     * @param at the offset of the {@code @}
     * @return the offset just past the annotation, or just past the {@code @} when what
     *         follows is not an annotation name
     */
    private static int skipAnnotation(String source, int at)
    {
        int i = skipWhitespace(source, at + 1);
        int nameEnd = -1;
        while (i < source.length() && Character.isJavaIdentifierStart(source.charAt(i)))
        {
            while (i < source.length() && Character.isJavaIdentifierPart(source.charAt(i)))
            {
                i++;
            }
            nameEnd = i;
            int afterName = skipWhitespace(source, i);
            if (afterName < source.length() && source.charAt(afterName) == '.')
            {
                i = skipWhitespace(source, afterName + 1);
                continue;
            }
            i = afterName;
            break;
        }
        if (nameEnd < 0)
        {
            return at + 1;
        }
        if (i >= source.length() || source.charAt(i) != '(')
        {
            return nameEnd;
        }
        int parens = 0;
        while (i < source.length())
        {
            char c = source.charAt(i);
            if (c == '"')
            {
                i = source.startsWith("\"\"\"", i) ? skipTextBlock(source, i) //$NON-NLS-1$
                    : skipLiteral(source, i, '"');
                continue;
            }
            if (c == '\'')
            {
                i = skipLiteral(source, i, '\'');
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/')
            {
                while (i < source.length() && source.charAt(i) != '\n')
                {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*')
            {
                i += 2;
                while (i + 1 < source.length()
                    && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/'))
                {
                    i++;
                }
                i = Math.min(i + 2, source.length());
                continue;
            }
            i++;
            if (c == '(')
            {
                parens++;
            }
            else if (c == ')')
            {
                parens--;
                if (parens == 0)
                {
                    break;
                }
            }
        }
        return i;
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
     * <p>
     * Only the STRUCTURE is removed — the opening {@code /**}, the closing {@code *}{@code /}
     * and the {@code *} margin at the head of each continuation line. Asterisks and slashes
     * inside the prose are kept: dropping them everywhere made {@code /** Alpha beta. *}{@code /}
     * and {@code /** *Alpha* beta. *}{@code /} the same identity, which is exactly the
     * collision an identity is supposed to prevent — a pardon would transfer between them and
     * {@link #stalePardons} would not notice the original had gone.
     *
     * @param source the whole file
     * @param from the offset of the block's {@code /**}
     * @param to the offset just past its {@code *}{@code /}
     * @return the block's text without its structure, whitespace-normalised
     */
    static String identityOf(String source, int from, int to)
    {
        String body = source.substring(from, Math.min(to, source.length()));
        if (body.startsWith("/**")) //$NON-NLS-1$
        {
            body = body.substring(3);
        }
        if (body.endsWith("*/")) //$NON-NLS-1$
        {
            body = body.substring(0, body.length() - 2);
        }
        StringBuilder out = new StringBuilder();
        String[] lines = body.split("\n", -1); //$NON-NLS-1$
        for (int at = 0; at < lines.length; at++)
        {
            String line = lines[at];
            if (at > 0)
            {
                // The javadoc margin, and ONLY it: one leading '*' after the indent, so a line
                // that really begins with emphasis (' * *bold* text') keeps its own asterisks.
                int cut = 0;
                while (cut < line.length() && Character.isWhitespace(line.charAt(cut)))
                {
                    cut++;
                }
                line = cut < line.length() && line.charAt(cut) == '*' ? line.substring(cut + 1)
                    : line.substring(cut);
            }
            for (String word : line.trim().split("\\s+")) //$NON-NLS-1$
            {
                if (!word.isEmpty())
                {
                    if (out.length() > 0)
                    {
                        out.append(' ');
                    }
                    out.append(word);
                }
            }
        }
        return out.toString();
    }

    /**
     * The full identity of a SITE: the block's own text plus the head of the declaration that
     * follows it. The text alone was not enough — fix the allow-listed block, then add a new
     * orphan with verbatim the same words elsewhere in the same file, and the pardon would
     * quietly transfer to it while the stale-pardon check saw nothing missing. That is the
     * exact hole an identity exists to close, and file plus text does not close it.
     * <p>
     * The anchor is the following declaration rather than a line number on purpose: an edit
     * ABOVE the block must not change its identity, and a line number changes with every such
     * edit. It was measured doing so — the one allow-listed block moved from line 94 to line
     * 95 when an unrelated PR landed, and the entry still matched.
     *
     * @param source the whole file
     * @param from the offset of the block's {@code /**}
     * @param to the offset just past its {@code *}{@code /}
     * @return the identity of this site
     */
    static String siteOf(String source, int from, int to)
    {
        return identityOf(source, from, to) + " @ " + anchorAfter(source, to); //$NON-NLS-1$
    }

    /**
     * The head of the next declaration after {@code from}: the code up to the {@code ;} or
     * <code>{</code> that ends it, with comments skipped and whitespace normalised. Truncated,
     * because it is a place marker and not a parse.
     *
     * @param source the whole file
     * @param from the offset to look forward from
     * @return the following declaration's head, or a marker when none follows
     */
    static String anchorAfter(String source, int from)
    {
        StringBuilder out = new StringBuilder();
        int i = from;
        boolean space = true;
        while (i < source.length())
        {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
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
                i += 2;
                while (i + 1 < source.length()
                    && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/'))
                {
                    i++;
                }
                i = Math.min(i + 2, source.length());
                continue;
            }
            if (c == ';' || c == '{' || c == '}')
            {
                break;
            }
            if (Character.isWhitespace(c))
            {
                space = true;
                i++;
                continue;
            }
            if (space && out.length() > 0)
            {
                out.append(' ');
            }
            space = false;
            out.append(c);
            i++;
        }
        // A block at the end of a type body has no declaration below it, and that absence is
        // itself the place: there is exactly one such site per body.
        return out.length() == 0 ? "<end of body>" : out.toString(); //$NON-NLS-1$
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
