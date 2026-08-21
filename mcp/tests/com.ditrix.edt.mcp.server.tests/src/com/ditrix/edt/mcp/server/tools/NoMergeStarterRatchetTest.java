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
 * Ratchet: this bundle COMPARES configurations and never merges them, and that has to be a fact
 * about the tree rather than a promise in a review.
 *
 * <h2>Three independent layers, and this test guards all three</h2>
 * <ol>
 *   <li><b>OSGi cannot load the merging classes.</b> {@code META-INF/MANIFEST.MF} does not import
 *       {@link #FORBIDDEN_IMPORTS}, so they are unreachable even reflectively. This is the layer
 *       that holds when somebody edits the Java.</li>
 *   <li><b>Nothing outside the facade can hold the objects a merge would be started on.</b>
 *       {@code IComparisonManager} may be named by {@link #MANAGER_HOLDERS} and
 *       {@code IComparisonSession} by {@link #SESSION_HOLDERS} - nowhere else. This is the
 *       structural half: with no manager and no session in a file, no call in that file can be a
 *       platform merge or a platform write, whatever it happens to be named. And within the two
 *       files that DO hold a session, {@link #RULE_SETTERS} must not appear either: this half reads
 *       a comparison and records nothing onto it, so the allow-list is EMPTY.</li>
 *   <li><b>The starters are not written down at all.</b> {@link #MERGE_STARTERS} must not appear
 *       ANYWHERE under the bundle source root, with an EMPTY allow-list - a mention inside a
 *       comment is a failure too. That is deliberate: the value of the rule is that grepping the
 *       tree for those names returns nothing, and a commented-out call is one keystroke from a real
 *       one. Write "the merge starters" instead.</li>
 * </ol>
 *
 * <h2>Why a source scan and not a byte-code scan</h2>
 * The cheap check - scan the compiled classes of the registered tools - is not enough, and this
 * repository already knows why: {@code ProjectContextAdoptionRatchetTest} documents its own evasion
 * hole (an anonymous inner class is a separate class file), and a helper parked in {@code utils/}
 * is not a tool at all and would never be scanned. The dangerous call does not have to live in a
 * tool, so this walks the whole bundle source root.
 *
 * <h2>What is deliberately NOT policed by name</h2>
 * Two families of name look dangerous and are not:
 * <ul>
 *   <li>{@code getPotentialMergeProblems*} - the engine's own account of what a merge WOULD run
 *       into. This feature reads and reports it as a POSSIBILITY; that is half the value of a
 *       three-way comparison. A substring search would ban it along with the real starters, so
 *       every name here is matched with identifier boundaries and
 *       {@link #theBoundaryDoesNotCatchTheProblemReadingCalls} pins it.</li>
 *   <li>{@code setMergeRule} on one of OUR OWN types - the rules document has a method by that
 *       name, because that is what it does: it records a decision into an XML document. It is not
 *       the platform call, and cannot be: layer 2 above proves no session exists in that file. So
 *       {@link #RULE_SETTERS} are policed only WITHIN the files allowed to hold a session, where
 *       the name can only mean the platform's. Banning the spelling everywhere would be a false
 *       positive on correct code, and this repository has measured what that costs: a gate that
 *       reddens on correct code is the gate somebody switches off.</li>
 * </ul>
 *
 * <h2>Where each rule's non-vacuity comes from</h2>
 * A ban is worth its green only when the detector behind it can still go red. Two of the rules here
 * cannot borrow that proof from production code, because the count production code is required to
 * have is ZERO - {@link #MERGE_STARTERS} anywhere, {@link #RULE_SETTERS} inside a session holder.
 * Anchoring their non-vacuity on a production occurrence would make the ratchet demand that the very
 * thing it bans keeps existing, and would redden the build on the day that code is correctly
 * deleted. Both are proved on PLANTED sources instead - {@link #theDetectorSeesAPlantedOccurrence},
 * {@link #theDetectorSeesAnOccurrenceInAComment} and
 * {@link #theRuleSetterDetectorSeesPlantedOccurrences}. The type-confinement rules keep their
 * production anchor, because those types ARE required to be present somewhere.
 */
public class NoMergeStarterRatchetTest
{
    /** The bundle whose sources must be free of the merge starters. */
    private static final String BUNDLE_SOURCE_ROOT = "mcp/bundles/com.ditrix.edt.mcp.server/src"; //$NON-NLS-1$

    /** The bundle manifest, which must not import the merging packages. */
    private static final String BUNDLE_MANIFEST = "mcp/bundles/com.ditrix.edt.mcp.server/META-INF/MANIFEST.MF"; //$NON-NLS-1$

    private static final String COMPARE_CORE = "com._1c.g5.v8.dt.compare.core"; //$NON-NLS-1$

    private static final String ENGINE = "com/ditrix/edt/mcp/server/utils/compare/ComparisonEngine.java"; //$NON-NLS-1$

    private static final String VIEW = "com/ditrix/edt/mcp/server/utils/compare/ComparisonView.java"; //$NON-NLS-1$

    private static final String EDT_SERVICES = "com/ditrix/edt/mcp/server/EdtServices.java"; //$NON-NLS-1$

    /**
     * Names that must not appear ANYWHERE under {@link #BUNDLE_SOURCE_ROOT}. There is no allow-list
     * to go with them, on purpose - see the class javadoc.
     */
    private static final String[] MERGE_STARTERS = {
        "startMerge", //$NON-NLS-1$
        "startMergeIgnoringProblems", //$NON-NLS-1$
        "getMergeProblems" //$NON-NLS-1$
    };

    /**
     * The only files allowed to name {@code IComparisonManager}: the one that TRACKS the service
     * and the facade it hands the supplier to. A third would mean some other code can reach the
     * merging entry points directly.
     */
    private static final Set<String> MANAGER_HOLDERS = new LinkedHashSet<>(Arrays.asList(EDT_SERVICES, ENGINE));

    /**
     * The only files allowed to name {@code IComparisonSession}: the facade and the read-only view
     * it returns. Everything else - every tool - works through {@code ComparisonView}, which has no
     * writing method on it.
     */
    private static final Set<String> SESSION_HOLDERS = new LinkedHashSet<>(Arrays.asList(ENGINE, VIEW));

    /**
     * The platform's write calls. {@code setMergeRule} is matched as a PREFIX so that
     * {@code setMergeRuleToSubtree} is covered by the same entry.
     */
    private static final String[] RULE_SETTERS = {
        "setMergeRule", //$NON-NLS-1$
        "setCustomMergeSettings" //$NON-NLS-1$
    };

    /** Packages the manifest must never import. */
    private static final String[] FORBIDDEN_IMPORTS = {
        "com._1c.g5.v8.dt.compare.merge", //$NON-NLS-1$
        "com._1c.g5.v8.dt.compare.git.merge" //$NON-NLS-1$
    };

    /**
     * A package the manifest MUST import. This is the positive control for the manifest half: if
     * the parse silently read the wrong file, or read nothing, the forbidden-import assertions
     * would pass over an empty string and prove nothing.
     */
    private static final String REQUIRED_IMPORT = COMPARE_CORE;

    // === layer 3: the starters are not written down ===

    @Test
    public void noSourceFileNamesAMergeStarter()
    {
        Map<String, String> sources = bundleSources();
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, String> file : sources.entrySet())
        {
            for (String starter : MERGE_STARTERS)
            {
                for (int line : linesMatching(file.getValue(), wholeIdentifier(starter)))
                {
                    violations.add(file.getKey() + ':' + line + " names '" + starter + '\''); //$NON-NLS-1$
                }
            }
        }
        if (!violations.isEmpty())
        {
            fail("This bundle compares configurations and must never start a merge. Remove these " //$NON-NLS-1$
                + "occurrences - including any inside a comment, so that grepping the tree for the " //$NON-NLS-1$
                + "name keeps returning nothing:\n  " + String.join("\n  ", violations)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // === layer 2: nothing else can hold the manager or the session ===

    @Test
    public void theComparisonManagerIsNamedOnlyWhereItIsTrackedAndWrapped()
    {
        assertTypeConfinedTo("IComparisonManager", MANAGER_HOLDERS, //$NON-NLS-1$
            "The comparison manager can both compare and merge. It is tracked in one place and " //$NON-NLS-1$
                + "wrapped in one place, and nothing else may hold it."); //$NON-NLS-1$
    }

    @Test
    public void theComparisonSessionIsNamedOnlyByTheFacadeAndItsView()
    {
        assertTypeConfinedTo("IComparisonSession", SESSION_HOLDERS, //$NON-NLS-1$
            "The session both reads and REWRITES the comparison tree. Tools get ComparisonView, " //$NON-NLS-1$
                + "which exposes the reading half; a file that holds the session bypasses that."); //$NON-NLS-1$
    }

    // === layer 2, continued: not even the session holders write ===

    /**
     * No session holder records a rule onto the comparison - the facade included. This half READS a
     * comparison; a merge decision is written to EDT's merge-rules FILE, which the platform
     * re-applies when a comparison is launched with it. A rule setter in either file would be a real
     * platform write on the live tree, reachable from every tool that holds the view.
     * <p>
     * The non-vacuity of this rule is deliberately NOT taken from production code: the count there
     * must be zero, so an "it still exists somewhere" assertion would pin the ban to the thing it
     * bans. {@link #theRuleSetterDetectorSeesPlantedOccurrences} proves the detector on a planted
     * source, exactly as {@link #theDetectorSeesAPlantedOccurrence} does for the merge starters.
     */
    @Test
    public void noSessionHolderRecordsARuleOntoTheComparison()
    {
        Map<String, String> sources = bundleSources();
        List<String> violations = new ArrayList<>();
        for (String holder : SESSION_HOLDERS)
        {
            String source = sources.get(holder);
            assertNotNull("the scan did not read " + holder, source); //$NON-NLS-1$
            for (String setter : RULE_SETTERS)
            {
                for (int line : linesMatching(source, identifierPrefix(setter)))
                {
                    violations.add(holder + ':' + line + " names '" + setter + '\''); //$NON-NLS-1$
                }
            }
        }
        if (!violations.isEmpty())
        {
            fail("This half compares configurations and records nothing onto one: a merge decision " //$NON-NLS-1$
                + "goes into the merge-rules FILE, checked against the rules its node allows before " //$NON-NLS-1$
                + "it is written. Remove these:\n  " + String.join("\n  ", violations)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // === layer 1: OSGi cannot load the merging classes ===

    @Test
    public void theManifestDoesNotImportTheMergingPackages()
    {
        String imports = manifestImportPackage();
        assertNotNull("could not read Import-Package from " + BUNDLE_MANIFEST, imports); //$NON-NLS-1$
        // Positive control first: prove the parse actually produced the manifest's import list.
        assertTrue("the parsed Import-Package does not contain " + REQUIRED_IMPORT //$NON-NLS-1$
            + " - the parse, not the manifest, is what is wrong here", imports.contains(REQUIRED_IMPORT)); //$NON-NLS-1$
        for (String forbidden : FORBIDDEN_IMPORTS)
        {
            assertFalse("MANIFEST.MF imports " + forbidden //$NON-NLS-1$
                + ". Without that import OSGi cannot load the merging classes at all; with it, it " //$NON-NLS-1$
                + "can - and that is the layer that holds when somebody edits the Java.", //$NON-NLS-1$
                imports.contains(forbidden));
        }
    }

    // === the ratchet's own instruments ===

    /**
     * The scan must actually have read this bundle. A locator that resolved to nothing would make
     * every assertion above pass over an empty map.
     */
    @Test
    public void theScanReadTheBundleItClaimsToGuard()
    {
        Map<String, String> sources = bundleSources();
        assertTrue("scanned only " + sources.size() + " files - the locator found the wrong root", //$NON-NLS-1$ //$NON-NLS-2$
            sources.size() > 100);
        assertTrue("the facade was not scanned", sources.containsKey(ENGINE)); //$NON-NLS-1$
        assertTrue("the read-only view was not scanned", sources.containsKey(VIEW)); //$NON-NLS-1$
        assertTrue("the service holder was not scanned", sources.containsKey(EDT_SERVICES)); //$NON-NLS-1$
    }

    /** The detector itself: it must see a planted occurrence, or its silence means nothing. */
    @Test
    public void theDetectorSeesAPlantedOccurrence()
    {
        String planted = "class X\n{\n    void go()\n    {\n        manager.startMerge(batch, monitor);\n    }\n}\n"; //$NON-NLS-1$

        assertEquals(List.of(Integer.valueOf(5)), linesMatching(planted, wholeIdentifier("startMerge"))); //$NON-NLS-1$
    }

    /** A comment counts. This is the whole reason that allow-list is empty. */
    @Test
    public void theDetectorSeesAnOccurrenceInAComment()
    {
        String planted = "class X\n{\n    // never call getMergeProblems here\n}\n"; //$NON-NLS-1$

        assertEquals(List.of(Integer.valueOf(3)), linesMatching(planted, wholeIdentifier("getMergeProblems"))); //$NON-NLS-1$
    }

    /**
     * The names this feature legitimately READS must survive. {@code getPotentialMergeProblems*}
     * describes what a merge WOULD hit and is reported as a possibility; banning it by substring
     * would delete half the value of a three-way comparison.
     */
    @Test
    public void theBoundaryDoesNotCatchTheProblemReadingCalls()
    {
        String reader = "session.getPotentialMergeProblemsSourceNodes();\n" //$NON-NLS-1$
            + "session.getPotentialMergeProblemsDescriptions(id, ctx);\n" //$NON-NLS-1$
            + "session.hasPotentialMergeProblems(id);\n"; //$NON-NLS-1$

        assertTrue(linesMatching(reader, wholeIdentifier("getMergeProblems")).isEmpty()); //$NON-NLS-1$
    }

    /** {@code setMergeRule} is a prefix rule, so the subtree variant is covered by the same entry. */
    @Test
    public void theRuleSetterPrefixCoversTheSubtreeVariant()
    {
        String planted = "session.setMergeRuleToSubtree(id, rule);\n"; //$NON-NLS-1$

        assertEquals(List.of(Integer.valueOf(1)), linesMatching(planted, identifierPrefix("setMergeRule"))); //$NON-NLS-1$
    }

    /**
     * The non-vacuity control for {@link #noSessionHolderRecordsARuleOntoTheComparison}: EVERY
     * policed setter must be found in a planted session holder. Without it the ban would become a
     * rule about nothing the moment a pattern stopped matching, and its green would mean the
     * detector was silent rather than the tree clean.
     */
    @Test
    public void theRuleSetterDetectorSeesPlantedOccurrences()
    {
        for (String setter : RULE_SETTERS)
        {
            String planted = "class X\n{\n    void go()\n    {\n        session." + setter //$NON-NLS-1$
                + "(id, value, context);\n    }\n}\n"; //$NON-NLS-1$

            assertEquals("the detector missed a planted '" + setter + '\'', //$NON-NLS-1$
                List.of(Integer.valueOf(5)), linesMatching(planted, identifierPrefix(setter)));
        }
    }

    /**
     * Type confinement is decided by what a file can COMPILE against - an import, or a fully
     * qualified name - and not by the spelling appearing in prose. Javadoc all over this feature
     * explains what the session and the manager are; that is documentation, not access.
     */
    @Test
    public void aTypeMentionedOnlyInProseIsNotAHolder()
    {
        String prose = "/** Reads through {@code IComparisonSession.runComparisonTreeReadonlyTask}. */\n" //$NON-NLS-1$
            + "class X\n{\n}\n"; //$NON-NLS-1$

        assertFalse(holdsType(prose, "IComparisonSession")); //$NON-NLS-1$
    }

    @Test
    public void anImportOrAQualifiedNameIsAHolder()
    {
        assertTrue(holdsType("import " + COMPARE_CORE + ".IComparisonSession;\n", "IComparisonSession")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(holdsType(COMPARE_CORE + ".IComparisonSession session = null;\n", "IComparisonSession")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // === detection ===

    private static void assertTypeConfinedTo(String simpleName, Set<String> allowed, String why)
    {
        Map<String, String> sources = bundleSources();
        List<String> holders = new ArrayList<>();
        for (Map.Entry<String, String> file : sources.entrySet())
        {
            if (holdsType(file.getValue(), simpleName))
            {
                holders.add(file.getKey());
            }
        }
        List<String> unexpected = new ArrayList<>(holders);
        unexpected.removeAll(allowed);
        if (!unexpected.isEmpty())
        {
            fail(why + " Unexpected holders of " + simpleName + ":\n  " //$NON-NLS-1$ //$NON-NLS-2$
                + String.join("\n  ", unexpected)); //$NON-NLS-1$
        }
        // Non-vacuity: an allow-list that nothing matches is a rule about nothing.
        assertFalse("no file holds " + simpleName + " at all - the detector, not the tree, is wrong", //$NON-NLS-1$ //$NON-NLS-2$
            holders.isEmpty());
    }

    /**
     * @param source a java source file
     * @param simpleName the type's simple name
     * @return whether the file can compile against the type - it imports it or names it fully
     */
    private static boolean holdsType(String source, String simpleName)
    {
        Pattern importOf = Pattern.compile("^\\s*import\\s+(?:static\\s+)?" + Pattern.quote(COMPARE_CORE) //$NON-NLS-1$
            + "\\." + Pattern.quote(simpleName) + "\\s*;", Pattern.MULTILINE); //$NON-NLS-1$ //$NON-NLS-2$
        if (importOf.matcher(source).find())
        {
            return true;
        }
        // A fully qualified use needs no import. Prose in this tree writes {@code IComparisonSession}
        // without the package, so the qualified form is code.
        return !linesMatching(source, wholeIdentifier(COMPARE_CORE + '.' + simpleName)).isEmpty();
    }

    private static Pattern wholeIdentifier(String name)
    {
        return Pattern.compile("(?<![A-Za-z0-9_$])" + Pattern.quote(name) + "(?![A-Za-z0-9_$])"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Pattern identifierPrefix(String name)
    {
        return Pattern.compile("(?<![A-Za-z0-9_$])" + Pattern.quote(name) + "[A-Za-z0-9_$]*"); //$NON-NLS-1$ //$NON-NLS-2$
    }

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

    /** @return the manifest's unfolded {@code Import-Package} header, or {@code null} */
    private static String manifestImportPackage()
    {
        File manifest = locate(BUNDLE_MANIFEST);
        if (manifest == null)
        {
            return null;
        }
        // Unfold the OSGi continuation lines (a leading space continues the previous header) before
        // looking at the header, or a package name split across two lines is invisible.
        String text = read(manifest.toPath()).replace("\r\n", "\n").replace('\r', '\n'); //$NON-NLS-1$ //$NON-NLS-2$
        StringBuilder unfolded = new StringBuilder();
        for (String line : text.split("\n", -1)) //$NON-NLS-1$
        {
            if (line.startsWith(" ") && unfolded.length() > 0) //$NON-NLS-1$
            {
                unfolded.append(line, 1, line.length());
            }
            else
            {
                unfolded.append('\n').append(line);
            }
        }
        for (String header : unfolded.toString().split("\n", -1)) //$NON-NLS-1$
        {
            if (header.startsWith("Import-Package:")) //$NON-NLS-1$
            {
                return header;
            }
        }
        return null;
    }

    private static String read(Path path)
    {
        try
        {
            String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            // A UTF-8 BOM survives decoding as U+FEFF and would push the first header off the start
            // of its line.
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
            if (candidate.exists())
            {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        return null;
    }
}
