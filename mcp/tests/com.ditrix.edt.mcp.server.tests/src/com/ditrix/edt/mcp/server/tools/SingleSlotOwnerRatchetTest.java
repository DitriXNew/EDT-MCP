/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * Ratchet: EDT's single comparison slot has ONE owner, and what became of it is never re-decided.
 *
 * <h2>Why a ratchet and not a review</h2>
 * This feature has had three review rounds on one defect family - nine findings, then eight, then
 * six - and every instance was the same shape: a site that ended a comparison decided for itself
 * whether the slot had come back and what to say about it. Patching them one at a time produced a
 * new instance per new call site, because the DECISION was what had been duplicated. The
 * construction that replaced it is {@code ComparisonSessionRegistry.handBack} plus the
 * {@code SlotHandback} it answers with, and most of it the compiler already enforces: the
 * platform's two lifetime verbs are package-scoped on the facade, the session map is private, and
 * the value's factory is package-scoped so only the owner can mint a verdict.
 * <p>
 * Two things the compiler cannot say are guarded here.
 * <ol>
 *   <li><b>A hand-back whose answer is dropped.</b> Java lets any call be an expression statement,
 *       so {@code registry.handBack(id, CLOSED);} compiles and silently throws away the only record
 *       of what happened. Three of the six findings in the last round were exactly that.</li>
 *   <li><b>A verdict branched on outside the owner.</b> {@code SlotHandback} offers
 *       {@code slotIsFree()} and {@code wasRegistered()} for a reason: the sites that split the
 *       five verdicts themselves split them slightly differently, and one of them counted a failed
 *       hand-back as a stop. Naming a slot verdict outside the two files that own the decision is
 *       that split coming back.</li>
 * </ol>
 *
 * <h2>Non-vacuity</h2>
 * Both rules must have zero occurrences in correct production code, so their green cannot be
 * anchored on a production match without demanding that the thing they ban keeps existing. Both
 * detectors are proved on PLANTED sources instead, and the scan itself is proved by
 * {@link #theScanReadTheBundleItClaimsToGuard} - the same shape
 * {@code NoMergeStarterRatchetTest} uses, for the same reason.
 */
public class SingleSlotOwnerRatchetTest
{
    /** The bundle whose sources are guarded. */
    private static final String BUNDLE_SOURCE_ROOT = "mcp/bundles/com.ditrix.edt.mcp.server/src"; //$NON-NLS-1$

    /** U+FEFF, the code point a UTF-8 byte-order mark decodes to. */
    private static final int BYTE_ORDER_MARK = 0xFEFF;

    private static final String HANDBACK =
        "com/ditrix/edt/mcp/server/utils/compare/SlotHandback.java"; //$NON-NLS-1$

    private static final String REGISTRY =
        "com/ditrix/edt/mcp/server/utils/compare/ComparisonSessionRegistry.java"; //$NON-NLS-1$

    private static final String ENGINE =
        "com/ditrix/edt/mcp/server/utils/compare/ComparisonEngine.java"; //$NON-NLS-1$

    /**
     * The only files that may name a verdict about the slot. The first defines them; the second is
     * the one owner that decides which one applies.
     */
    private static final Set<String> VERDICT_OWNERS =
        new LinkedHashSet<>(Arrays.asList(HANDBACK, REGISTRY));

    /**
     * The verdicts that are claims about EDT's slot. {@code NOT_REGISTERED} is deliberately in the
     * list too: "your id names nothing here" is the one answer a caller most easily mistakes for a
     * freed slot, and {@code wasRegistered()} exists so it never has to be named.
     */
    private static final String[] SLOT_VERDICTS = {
        "FREED", //$NON-NLS-1$
        "ALREADY_FREE", //$NON-NLS-1$
        "NOT_FREED", //$NON-NLS-1$
        "UNREACHABLE", //$NON-NLS-1$
        "NOT_REGISTERED" //$NON-NLS-1$
    };

    /**
     * A hand-back call that IS the whole statement, so its answer goes nowhere.
     * <p>
     * Matched on the statement shape rather than on the name alone: {@code x = f.handBack(...);},
     * {@code return f.handBack(...);} and {@code "..." + f.handBack(...).sentence()} all keep the
     * answer and must pass, while a bare call must not.
     */
    private static final Pattern DISCARDED_HANDBACK =
        Pattern.compile("(?m)^[ \\t]*[A-Za-z0-9_$.()\\[\\]]*\\bhandBack[ \\t]*\\([^;{}]*\\)[ \\t]*;[ \\t]*$"); //$NON-NLS-1$

    /**
     * A verdict named with its type, which is how this tree writes one. A bare {@code UNREACHABLE}
     * is deliberately NOT matched: an unrelated enum in {@code EditorScreenshotHelper} has a
     * constant by that name, and a rule that reddens on correct code is the rule somebody switches
     * off.
     *
     * @param verdict the literal
     * @return the pattern that finds it
     */
    private static Pattern verdictReference(String verdict)
    {
        return Pattern.compile("(?<![A-Za-z0-9_$])Verdict\\." + Pattern.quote(verdict) //$NON-NLS-1$
            + "(?![A-Za-z0-9_$])"); //$NON-NLS-1$
    }

    // === rule 1: no hand-back answer is thrown away ===

    @Test
    public void noSourceFileThrowsAwayWhatAHandBackObserved()
    {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, String> file : bundleSources().entrySet())
        {
            for (int line : linesMatching(file.getValue(), DISCARDED_HANDBACK))
            {
                violations.add(file.getKey() + ':' + line);
            }
        }
        if (!violations.isEmpty())
        {
            fail("A hand-back answers what became of EDT's single comparison slot, and these " //$NON-NLS-1$
                + "calls drop that answer. Ending the comparison is not the same as freeing the " //$NON-NLS-1$
                + "slot: the service can be unregistered, the stop can fail, and the id can name " //$NON-NLS-1$
                + "nothing. Publish SlotHandback.sentence(), or branch on slotIsFree():\n  " //$NON-NLS-1$
                + String.join("\n  ", violations)); //$NON-NLS-1$
        }
    }

    /** The detector must see a planted occurrence, or its silence means nothing. */
    @Test
    public void theDiscardDetectorSeesAPlantedOccurrence()
    {
        String planted = "class X\n{\n    void go()\n    {\n" //$NON-NLS-1$
            + "        registry.handBack(id, Ending.CLOSED);\n    }\n}\n"; //$NON-NLS-1$

        assertEquals(List.of(Integer.valueOf(5)), linesMatching(planted, DISCARDED_HANDBACK));
    }

    /**
     * ...and it must NOT see the shapes that keep the answer. Every one of these is in the tree,
     * so a detector that caught them would redden correct code on the first run.
     */
    @Test
    public void theDiscardDetectorLeavesAnAnsweredHandBackAlone()
    {
        assertTrue(linesMatching("        SlotHandback h = backend.handBack(id, ending);\n", //$NON-NLS-1$
            DISCARDED_HANDBACK).isEmpty());
        assertTrue(linesMatching("        return registry.handBack(id, ending);\n", //$NON-NLS-1$
            DISCARDED_HANDBACK).isEmpty());
        assertTrue(linesMatching("            + registry.handBack(id, ending).sentence(), e);\n", //$NON-NLS-1$
            DISCARDED_HANDBACK).isEmpty());
        assertTrue("the interface declaration is not a call", //$NON-NLS-1$
            linesMatching("        SlotHandback handBack(String comparisonId, Ending ending);\n", //$NON-NLS-1$
                DISCARDED_HANDBACK).isEmpty());
    }

    // === rule 2: only the owner names a verdict about the slot ===

    @Test
    public void noSourceFileOutsideTheOwnerNamesASlotVerdict()
    {
        Map<String, String> sources = bundleSources();
        List<String> violations = new ArrayList<>();
        int seenInOwners = 0;
        for (Map.Entry<String, String> file : sources.entrySet())
        {
            for (String verdict : SLOT_VERDICTS)
            {
                List<Integer> hits = linesMatching(file.getValue(), verdictReference(verdict));
                if (VERDICT_OWNERS.contains(file.getKey()))
                {
                    seenInOwners += hits.size();
                    continue;
                }
                for (int line : hits)
                {
                    violations.add(file.getKey() + ':' + line + " names Verdict." + verdict); //$NON-NLS-1$
                }
            }
        }
        // The positive control: the owners DO name them, so a scan that read nothing, or a
        // spelling that stopped matching, cannot pass this test in silence.
        assertTrue("the owners must still name the verdicts, or this rule is about nothing", //$NON-NLS-1$
            seenInOwners > 0);
        if (!violations.isEmpty())
        {
            fail("Only the owner of the hand-back decides what became of EDT's single comparison " //$NON-NLS-1$
                + "slot. A caller asks SlotHandback.slotIsFree() or wasRegistered() and publishes " //$NON-NLS-1$
                + "its sentence; splitting the five verdicts per site is how two sites came to " //$NON-NLS-1$
                + "split them differently, one of them counting a failed hand-back as a stop:\n  " //$NON-NLS-1$
                + String.join("\n  ", violations)); //$NON-NLS-1$
        }
    }

    @Test
    public void theVerdictDetectorSeesAPlantedOccurrence()
    {
        String planted = "        if (outcome == SlotHandback.Verdict.NOT_FREED)\n"; //$NON-NLS-1$

        assertEquals(List.of(Integer.valueOf(1)),
            linesMatching(planted, verdictReference("NOT_FREED"))); //$NON-NLS-1$
    }

    /**
     * The boundary the rule must NOT cross: an unrelated enum constant spelled the same way.
     * {@code EditorScreenshotHelper.RenderOutcome.UNREACHABLE} is real, and a substring ban would
     * have reddened it on the day this test was written.
     */
    @Test
    public void theVerdictDetectorLeavesAnUnrelatedEnumAlone()
    {
        assertTrue(linesMatching("            return RenderOutcome.UNREACHABLE;\n", //$NON-NLS-1$
            verdictReference("UNREACHABLE")).isEmpty()); //$NON-NLS-1$
    }

    // === rule 3: the platform's lifetime verb stays out of reach of a tool ===

    /**
     * {@code ComparisonEngine.end} must not be public.
     * <p>
     * Ending a comparison and dropping its record are halves of ONE decision, and every defect in
     * this family came from a site performing one half and reporting the other. Package scope is
     * what makes the second half unreachable from a tool - it cannot even name the method - and
     * that is a property of one modifier, which is exactly the kind of thing that gets widened by
     * accident while chasing a compile error.
     */
    @Test
    public void theFacadesLifetimeVerbIsNotReachableFromATool()
    {
        String engine = bundleSources().get(ENGINE);
        assertNotNull("the scan did not read " + ENGINE, engine); //$NON-NLS-1$
        List<Integer> declared = linesMatching(engine,
            Pattern.compile("(?m)^[ \\t]*(public[ \\t]+)?(void|[A-Za-z0-9_$.<>]+)[ \\t]+end[ \\t]*\\(")); //$NON-NLS-1$
        assertEquals("the facade must declare exactly one hand-back verb", 1, declared.size()); //$NON-NLS-1$
        assertFalse("ComparisonEngine.end must stay package-scoped: a public one lets a tool end " //$NON-NLS-1$
            + "a comparison without dropping its record, which is half of the one decision this " //$NON-NLS-1$
            + "construction exists to keep whole", //$NON-NLS-1$
            engine.contains("public void end(")); //$NON-NLS-1$
    }

    // === the ratchet's own instruments ===

    @Test
    public void theScanReadTheBundleItClaimsToGuard()
    {
        Map<String, String> sources = bundleSources();
        assertTrue("scanned only " + sources.size() + " files - the locator found the wrong root", //$NON-NLS-1$ //$NON-NLS-2$
            sources.size() > 100);
        assertTrue("the value type was not scanned", sources.containsKey(HANDBACK)); //$NON-NLS-1$
        assertTrue("the owner was not scanned", sources.containsKey(REGISTRY)); //$NON-NLS-1$
        assertTrue("the facade was not scanned", sources.containsKey(ENGINE)); //$NON-NLS-1$
    }

    // === detection ===

    private static List<Integer> linesMatching(String source, Pattern pattern)
    {
        List<Integer> lines = new ArrayList<>();
        Matcher matcher = pattern.matcher(source);
        while (matcher.find())
        {
            lines.add(Integer.valueOf(lineOf(source, matcher.start())));
        }
        return lines;
    }

    private static int lineOf(String source, int offset)
    {
        int line = 1;
        for (int at = 0; at < offset && at < source.length(); at++)
        {
            if (source.charAt(at) == '\n')
            {
                line++;
            }
        }
        return line;
    }

    // === source scan ===

    /** @return every {@code .java} file under the bundle source root, keyed by its relative path */
    private static Map<String, String> bundleSources()
    {
        File root = locate(BUNDLE_SOURCE_ROOT);
        if (root == null)
        {
            fail("could not locate '" + BUNDLE_SOURCE_ROOT + "' by walking up from user.dir=" //$NON-NLS-1$ //$NON-NLS-2$
                + System.getProperty("user.dir")); //$NON-NLS-1$
        }
        Map<String, String> sources = new LinkedHashMap<>();
        Path base = root.toPath();
        try (Stream<Path> files = Files.walk(base))
        {
            files.filter(p -> p.getFileName().toString().endsWith(".java")) //$NON-NLS-1$
                .sorted()
                .forEach(p -> sources.put(base.relativize(p).toString().replace('\\', '/'), read(p)));
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
        return sources;
    }

    private static String read(Path path)
    {
        try
        {
            String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            // A UTF-8 BOM survives decoding as U+FEFF and would push the first line's content off
            // the start of its line. Compared as a code point rather than written as a character
            // literal: the raw character is invisible in a diff, and Java expands \\uXXXX escapes
            // before it lexes, so an escape here is the same invisible character by the time the
            // compiler sees it.
            return text.isEmpty() || text.charAt(0) != BYTE_ORDER_MARK ? text : text.substring(1);
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
            if (candidate.exists())
            {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        return null;
    }
}
