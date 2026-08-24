/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.EList;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.compare.model.CollectionElementComparisonNode;
import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.compare.model.MergeRule;
import com._1c.g5.v8.dt.compare.model.TopComparisonNode;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.tools.impl.MergeRulesTool.EngineRuleAuthority;
import com.ditrix.edt.mcp.server.tools.impl.MergeRulesTool.MergeRuleAuthority;
import com.ditrix.edt.mcp.server.tools.impl.MergeRulesTool.RuleSnapshot;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link MergeRulesTool}.
 * <p>
 * Everything here runs with NO EDT present: the tool reads and writes a file, and the one thing
 * it asks a live comparison - which rules a node allows - arrives through an injected authority,
 * stubbed here. What the tests pin is the contract that cannot be seen from the file alone:
 * <ul>
 * <li>a write with no live comparison is reported NOT VALIDATED and says how to get validation -
 * never as if the rules had been checked;</li>
 * <li>a rule the node does not allow is refused naming the node, the rule and the allowed set,
 * and NOTHING is written - a half-applied set would be a file nobody chose;</li>
 * <li>{@code CustomMerge} / {@code MergeUsingExternalTool} are refused whether or not a
 * comparison is running;</li>
 * <li>rule literals are the platform's camel-case wire literals, parsed through
 * {@code MergeRule.get(literal)}; the Java constant spelling is not one.</li>
 * </ul>
 */
public class MergeRulesToolTest
{
    /** The exact set of input parameters {@code execute()} reads. Keep in lockstep with the schema. */
    private static final String[] EXECUTE_PARAMS =
        {"mode", "filePath", "basedOn", "decisions", "comparisonId", "limit"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

    private static final String FIXTURE = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
        + "<Settings Format_version=\"2.0\">\n" //$NON-NLS-1$
        + "  <MergeSettings>\n" //$NON-NLS-1$
        + "    <Node Key=\"$$Root$$\">\n" //$NON-NLS-1$
        + "      <Properties>\n" //$NON-NLS-1$
        + "        <SkipUnchanged>true</SkipUnchanged>\n" //$NON-NLS-1$
        + "      </Properties>\n" //$NON-NLS-1$
        + "      <Node Key=\"commonModules\" MergeRule=\"GetFromOther\">\n" //$NON-NLS-1$
        + "        <Node Key=\"Alpha:Beta:Gamma\" MergeRule=\"MergePrioritizingMain\"/>\n" //$NON-NLS-1$
        + "        <Node Key=\"Added:NONE:Added\" MergeRule=\"DoNotMerge\">\n" //$NON-NLS-1$
        + "          <Node Key=\"7\" MergeRule=\"GetFromOther\" OrderSide=\"Other\"/>\n" //$NON-NLS-1$
        + "        </Node>\n" //$NON-NLS-1$
        + "      </Node>\n" //$NON-NLS-1$
        + "    </Node>\n" //$NON-NLS-1$
        + "  </MergeSettings>\n" //$NON-NLS-1$
        + "</Settings>\n"; //$NON-NLS-1$

    /** A SECOND rules file, so "the target kept its own decisions" is a distinguishable fact. */
    private static final String OTHER_FIXTURE = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
        + "<Settings Format_version=\"2.0\">\n" //$NON-NLS-1$
        + "  <MergeSettings>\n" //$NON-NLS-1$
        + "    <Node Key=\"$$Root$$\">\n" //$NON-NLS-1$
        + "      <Node Key=\"documents\" MergeRule=\"MergePrioritizingOther\"/>\n" //$NON-NLS-1$
        + "    </Node>\n" //$NON-NLS-1$
        + "  </MergeSettings>\n" //$NON-NLS-1$
        + "</Settings>\n"; //$NON-NLS-1$

    /**
     * One character XML 1.0 cannot carry. U+0001 is not whitespace, so a key holding it is not
     * blank; it IS below U+0020, so {@code String.trim} deletes it at either end - the two facts
     * that let it through every other check on the way to the file.
     */
    private static final String CONTROL_CHARACTER = "\u0001"; //$NON-NLS-1$

    /** A key whose names hold a code point above U+FFFF, which XML carries and this tool accepts. */
    private static final String ASTRAL_KEY =
        "A\ud83d\ude00:A\ud83d\ude00:A\ud83d\ude00"; //$NON-NLS-1$

    /** The Russian singular type token for a catalog, in escapes so the build cannot mangle it. */
    private static final String CATALOG_RU =
        "\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A"; //$NON-NLS-1$

    /** The Russian plural type token for catalogs. */
    private static final String CATALOGS_RU =
        "\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A\u0438"; //$NON-NLS-1$

    /**
     * The sentence a report may only say about a file that carries NO merge rule at all.
     * Pinned as a literal because what the tests below assert about it is its ABSENCE.
     */
    /** The XML attribute delimiter, kept out of the fixtures so they stay readable. */
    private static final String QUOTE = String.valueOf((char)34);

    private static final String CLAIMS_NO_RULE = "The file records no merge rule"; //$NON-NLS-1$

    /**
     * The words the unreachable-rule clause is recognised by, in BOTH reports.
     * <p>
     * They are the clause's own text and not a paraphrase of it, which is the whole point: the
     * absence pins below assert that a report does NOT carry the clause, and a phrase the tool
     * never prints makes such a pin pass on every behaviour, the always-emit one included.
     */
    private static final String UNREACHABLE_CLAUSE_MARK =
        " in a part of the node tree addressing never enters"; //$NON-NLS-1$

    /** A rule on a {@code Node} sitting BESIDE the root: in the file, at no address. */
    private static final String RULE_BESIDE_THE_ROOT =
        "<Settings Format_version=\"2.0\"><MergeSettings>" //$NON-NLS-1$
            + "<Node Key=\"$$Root$$\"/>" //$NON-NLS-1$
            + "<Node Key=\"orphan\" MergeRule=\"GetFromOther\"/>" //$NON-NLS-1$
            + "</MergeSettings></Settings>"; //$NON-NLS-1$

    /** The same one level down: a keyless node no path can come to rest on. */
    private static final String RULE_UNDER_A_KEYLESS_NODE =
        "<Settings Format_version=\"2.0\"><MergeSettings>" //$NON-NLS-1$
            + "<Node Key=\"$$Root$$\">" //$NON-NLS-1$
            + "<Node><Node Key=\"x\" MergeRule=\"DoNotMerge\"/></Node>" //$NON-NLS-1$
            + "</Node></MergeSettings></Settings>"; //$NON-NLS-1$

    /** A file that genuinely records nothing - the skeleton and not one rule. */
    private static final String NO_RULE_AT_ALL =
        "<Settings Format_version=\"2.0\"><MergeSettings>" //$NON-NLS-1$
            + "<Node Key=\"$$Root$$\"/></MergeSettings></Settings>"; //$NON-NLS-1$

    /** One rule at an address and one at none, so a report has to carry both facts. */
    private static final String A_RULE_ON_EITHER_SIDE_OF_THE_ADDRESSING =
        "<Settings Format_version=\"2.0\"><MergeSettings>" //$NON-NLS-1$
            + "<Node Key=\"$$Root$$\">" //$NON-NLS-1$
            + "<Node Key=\"commonModules\" MergeRule=\"GetFromOther\"/></Node>" //$NON-NLS-1$
            + "<Node Key=\"orphan\" MergeRule=\"DoNotMerge\"/>" //$NON-NLS-1$
            + "</MergeSettings></Settings>"; //$NON-NLS-1$

    /** A rule DEEP under a keyed node beside the root - the shape a shallow wording misses. */
    private static final String RULE_DEEP_BESIDE_THE_ROOT =
        "<Settings Format_version=" + QUOTE + "2.0" + QUOTE + "><MergeSettings>" //$NON-NLS-1$ //$NON-NLS-2$
            + "<Node Key=" + QUOTE + "$$Root$$" + QUOTE + "/>" //$NON-NLS-1$ //$NON-NLS-2$
            + "<Node Key=" + QUOTE + "orphan" + QUOTE + ">" //$NON-NLS-1$ //$NON-NLS-2$
            + "<Node Key=" + QUOTE + "deep" + QUOTE + " MergeRule=" + QUOTE + "GetFromOther" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + QUOTE + "/></Node>" //$NON-NLS-1$
            + "</MergeSettings></Settings>"; //$NON-NLS-1$

    /** No root marker at all: addressing enters nowhere, so every rule in the file is outside. */
    private static final String RULE_WITH_NO_ROOT_MARKER =
        "<Settings Format_version=" + QUOTE + "2.0" + QUOTE + "><MergeSettings>" //$NON-NLS-1$ //$NON-NLS-2$
            + "<Node Key=" + QUOTE + "a" + QUOTE + " MergeRule=" + QUOTE + "DoNotMerge" + QUOTE //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "/></MergeSettings></Settings>"; //$NON-NLS-1$

    /** Two of them, so the sentence has to agree in number with what it counted. */
    private static final String TWO_UNREACHABLE_RULES =
        "<Settings Format_version=" + QUOTE + "2.0" + QUOTE + "><MergeSettings>" //$NON-NLS-1$ //$NON-NLS-2$
            + "<Node Key=" + QUOTE + "$$Root$$" + QUOTE + "/>" //$NON-NLS-1$ //$NON-NLS-2$
            + "<Node Key=" + QUOTE + "one" + QUOTE + " MergeRule=" + QUOTE + "GetFromOther" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + QUOTE + "/>" //$NON-NLS-1$
            + "<Node Key=" + QUOTE + "two" + QUOTE + " MergeRule=" + QUOTE + "DoNotMerge" + QUOTE //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "/></MergeSettings></Settings>"; //$NON-NLS-1$

    /**
     * The entry name the stub authorities answer with - the shape
     * {@code <mainProject>_<otherProject>_<ancestorProject>} the platform builds, and the name a
     * zip this tool writes has to carry.
     */
    private static final String ENTRY_ID = "MainCfg_VendorCfg_BaseCfg"; //$NON-NLS-1$

    private Path workDir;

    @Before
    public void setUp() throws IOException
    {
        workDir = Files.createTempDirectory("merge-rules-tool-test"); //$NON-NLS-1$
    }

    @After
    public void tearDown() throws IOException
    {
        if (workDir != null && Files.exists(workDir))
        {
            try (Stream<Path> walk = Files.walk(workDir))
            {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList())
                {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    // ==================== metadata ====================

    @Test
    public void testName()
    {
        assertEquals("merge_rules", new MergeRulesTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(MergeRulesTool.NAME, new MergeRulesTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new MergeRulesTool().getResponseType());
    }

    @Test
    public void testOutputSchemaIsNullForMarkdownTool()
    {
        assertNull(new MergeRulesTool().getOutputSchema());
    }

    @Test
    public void testConnectsToInfobaseIsFalse()
    {
        assertFalse(new MergeRulesTool().connectsToInfobase());
    }

    @Test
    public void testDescriptionSteersToGuide()
    {
        String description = new MergeRulesTool().getDescription();
        assertNotNull(description);
        assertTrue("the description must point at the guide for the detail", //$NON-NLS-1$
            description.contains("get_tool_guide('merge_rules')")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionStatesTheHonestyContract()
    {
        // The one fact a caller cannot recover from the schema: a write without a live
        // comparison is NOT validated, and the answer says so.
        String description = new MergeRulesTool().getDescription();
        assertTrue("the description must say a write can be unvalidated: " + description, //$NON-NLS-1$
            description.contains("NOT VALIDATED")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaDeclaresExactlyTheParametersExecuteReads()
    {
        JsonObject properties = schemaProperties();
        List<String> declared = new ArrayList<>(properties.keySet());
        declared.sort(String::compareTo);
        List<String> expected = new ArrayList<>(List.of(EXECUTE_PARAMS));
        expected.sort(String::compareTo);
        assertEquals(expected, declared);
    }

    @Test
    public void testSchemaParametersAreLowerCamelCase()
    {
        for (String name : schemaProperties().keySet())
        {
            assertTrue("parameter '" + name + "' must be lowerCamelCase", //$NON-NLS-1$ //$NON-NLS-2$
                name.matches("[a-z][a-zA-Z0-9]*")); //$NON-NLS-1$
        }
    }

    @Test
    public void testSchemaRequiresModeAndFilePath()
    {
        JsonElement required = JsonParser.parseString(new MergeRulesTool().getInputSchema())
            .getAsJsonObject().get("required"); //$NON-NLS-1$
        assertEquals("[\"mode\",\"filePath\"]", required.toString()); //$NON-NLS-1$
    }

    /**
     * The contract on the wire has to carry all THREE validation outcomes, because the middle one
     * - a comparison that answers while its tree cannot be read - is the one a caller cannot
     * guess: it addresses a zip without checking a single rule, and it REFUSES when the caller
     * named the comparison. The description said "with a live comparison every rule is checked",
     * which describes a state this tool can be in and calls it the only one.
     */
    @Test
    public void testTheDescriptionNamesAllThreeValidationOutcomes()
    {
        String description = new MergeRulesTool().getDescription();

        assertTrue("the count itself is the part a reader keys on: " + description, //$NON-NLS-1$
            description.contains("THREE outcomes")); //$NON-NLS-1$
        assertTrue("a FINISHED tree is what a checked file needs: " + description, //$NON-NLS-1$
            description.contains("FINISHED")); //$NON-NLS-1$
        assertTrue("the middle outcome must be named as itself: " + description, //$NON-NLS-1$
            description.contains("cannot be read yet")); //$NON-NLS-1$
        assertTrue("including that naming comparisonId turns it into a refusal: " + description, //$NON-NLS-1$
            description.contains("refused outright when you passed comparisonId")); //$NON-NLS-1$
    }

    /**
     * The lower-case rule belongs to the WRITE target alone - reading is this server's own and is
     * case-insensitive. Stating it as a property of {@code filePath} made the parameter's own
     * prose contradict {@code mode: "read"}, which reads {@code .ZIP} perfectly well.
     */
    @Test
    public void testTheFilePathSchemaScopesTheLowerCaseRuleToWrites()
    {
        String prose = schemaProperties().getAsJsonObject("filePath") //$NON-NLS-1$
            .get("description").getAsString(); //$NON-NLS-1$

        assertTrue("reading is explicitly lenient: " + prose, //$NON-NLS-1$
            prose.contains("CASE does not matter")); //$NON-NLS-1$
        assertTrue("and writing is explicitly not: " + prose, //$NON-NLS-1$
            prose.contains("must be spelled in LOWER CASE")); //$NON-NLS-1$
        assertFalse("the old wording made the rule unconditional: " + prose, //$NON-NLS-1$
            prose.contains("with a LOWER-CASE extension")); //$NON-NLS-1$
    }

    /**
     * And the parameter that asks for validation says what naming it costs: a comparison whose
     * tree cannot be read is refused, not silently downgraded to an unchecked write.
     */
    @Test
    public void testTheComparisonIdSchemaSaysAnUnreadableTreeIsRefused()
    {
        String prose = schemaProperties().getAsJsonObject("comparisonId") //$NON-NLS-1$
            .get("description").getAsString(); //$NON-NLS-1$

        assertTrue("validation needs a finished tree: " + prose, prose.contains("FINISHED")); //$NON-NLS-1$
        assertTrue("and the consequence of naming one anyway: " + prose, //$NON-NLS-1$
            prose.contains("REFUSED")); //$NON-NLS-1$
    }

    @Test
    public void testModeIsAnEnumOfReadAndWrite()
    {
        JsonElement values = schemaProperties().getAsJsonObject("mode").get("enum"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("[\"read\",\"write\"]", values.toString()); //$NON-NLS-1$
    }

    @Test
    public void testFilePathDescriptionWarnsAboutOverwriting()
    {
        // InputSchemaCompactor strips parameter prose unless the parameter is in its KEEP map;
        // this warning is exactly the kind it keeps, so the words must be here to be kept.
        String description = schemaProperties().getAsJsonObject("filePath").get("description") //$NON-NLS-1$ //$NON-NLS-2$
            .getAsString();
        assertTrue("the write target's prose must warn that an existing file is overwritten: " //$NON-NLS-1$
            + description, description.contains("OVERWRITTEN")); //$NON-NLS-1$
    }

    @Test
    public void testGuideExists()
    {
        String guide = new MergeRulesTool().getGuide();
        assertNotNull(guide);
        assertFalse("merge_rules must ship guides/merge_rules.md", guide.isEmpty()); //$NON-NLS-1$
    }

    // ==================== the rule vocabulary ====================

    @Test
    public void testPlatformParsesTheCamelCaseWireLiteral()
    {
        // The file spells rules as the platform's LITERAL, which is what MergeRule.get reads.
        assertEquals(MergeRule.GET_FROM_OTHER, MergeRule.get("GetFromOther")); //$NON-NLS-1$
        assertEquals(MergeRule.DO_NOT_MERGE, MergeRule.get("DoNotMerge")); //$NON-NLS-1$
    }

    @Test
    public void testTheJavaConstantSpellingIsNotARuleLiteral()
    {
        // Pins why parsing must go through get(literal): neither lookup accepts the Java
        // constant spelling, so a codec written against getByName would be no better - and a
        // caller who sends GET_FROM_OTHER must be told the right spelling, not silently obeyed.
        assertNull(MergeRule.get("GET_FROM_OTHER")); //$NON-NLS-1$
        assertNull(MergeRule.getByName("GET_FROM_OTHER")); //$NON-NLS-1$
    }

    /**
     * Measured, so it is a ratchet and not a behavioural test: for {@code MergeRule} the EMF name
     * and the literal are the SAME string ({@code GetFromOther} both times), so
     * {@code getByName(literal)} happens to answer identically and no behavioural test can
     * separate the two lookups. What decides it is the file: the platform's serializer writes
     * {@code toString()}, i.e. the LITERAL, so the value on disk is a literal and is read as one.
     * A source scan is therefore the only instrument that holds this line.
     */
    @Test
    public void testTheSliceNeverCallsGetByName() throws IOException
    {
        for (String relative : List.of("tools/impl/MergeRulesTool.java", //$NON-NLS-1$
            "utils/compare/MergeRulesCodec.java", "utils/compare/MergeRulesDocument.java")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            String source = new String(Files.readAllBytes(sourceFile(relative)), StandardCharsets.UTF_8);
            assertFalse(relative + " must parse rule literals with get(literal), not getByName", //$NON-NLS-1$
                source.contains("getByName")); //$NON-NLS-1$
        }
    }

    // ==================== argument handling ====================

    @Test
    public void testMissingArgumentsAreRefused()
    {
        assertErrorNaming(new MergeRulesTool().execute(new HashMap<>()), "mode"); //$NON-NLS-1$
    }

    // ============ filePath / basedOn are ABSOLUTE, as the schema has always said ============
    //
    // Paths.get(value).toAbsolutePath() never fails: it resolves against the working directory of
    // the EDT PROCESS - the install directory, or wherever a launcher started it. So a relative
    // path produced no error at all. It produced a file somewhere nobody named, and the report
    // named that as a success.

    @Test
    public void testAReadOfARelativeFilePathIsRefusedRatherThanResolvedAgainstEdtsOwnDirectory()
    {
        String result = call(params("mode", "read", "filePath", "rules.xml")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertErrorNaming(result, "filePath", "ABSOLUTE", "rules.xml"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testAWriteToARelativeFilePathIsRefusedBeforeAnythingIsWritten()
    {
        String result = call(params("mode", "write", "filePath", "rules.xml", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$
        assertErrorNaming(result, "filePath", "ABSOLUTE", "rules.xml"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** The same trap on the other path parameter, and the same fix. */
    @Test
    public void testARelativeBasedOnIsRefused()
    {
        String result = call(params("mode", "write", "filePath", file("out.xml").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "basedOn", "starting-point.xml", //$NON-NLS-1$ //$NON-NLS-2$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$
        assertErrorNaming(result, "basedOn", "ABSOLUTE", "starting-point.xml"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * The refusal has to say WHY a relative path is not merely inconvenient, or the caller reads
     * it as a style rule and passes one again.
     */
    @Test
    public void testTheRelativePathRefusalNamesWhatItWouldHaveResolvedAgainst()
    {
        String result = call(params("mode", "read", "filePath", "rules.xml")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertErrorNaming(result, "working directory of the EDT process"); //$NON-NLS-1$
    }

    @Test
    public void testUnknownModeIsRefusedNamingBothModes()
    {
        String result = call(params("mode", "merge", "filePath", file("x.xml").toString())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertErrorNaming(result, "merge", "read", "write"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testReadRefusesWriteOnlyParametersInsteadOfIgnoringThem() throws IOException
    {
        Path file = seedFixture();
        String result = call(params("mode", "read", "filePath", file.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$
        assertErrorNaming(result, "decisions", "write"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== read ====================

    @Test
    public void testReadReportsTheDecisionsWithTheThreeNamesSplit() throws IOException
    {
        String result = call(params("mode", "read", "filePath", seedFixture().toString())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(result, result.startsWith("# Merge rules:")); //$NON-NLS-1$
        assertTrue("the rename's three names must be split out: " + result, //$NON-NLS-1$
            result.contains("| Alpha | Beta | Gamma |")); //$NON-NLS-1$
        assertTrue("NONE must render as an absent side, not as a name: " + result, //$NON-NLS-1$
            result.contains("| Added | (absent) | Added |")); //$NON-NLS-1$
        assertTrue("a positional child is reported as a member level: " + result, //$NON-NLS-1$
            result.contains("| member |")); //$NON-NLS-1$
        assertTrue("the payload the tool does not interpret must be accounted for: " + result, //$NON-NLS-1$
            result.contains("Preserved sections this tool does not interpret: 1")); //$NON-NLS-1$
    }

    /**
     * {@code mode: "read"} does not check the extension's CASE, and that is the contract rather
     * than an oversight.
     * <p>
     * {@code hasReadableExtension} answers what the PLATFORM will accept, and reading is the one
     * path that never hands the file to the platform: {@code MergeRulesCodec.read} decides how to
     * open the file with the case-INSENSITIVE {@code isZip}. So {@code RULES.ZIP} - a name EDT
     * itself would refuse - reads here. The tool description, the schema, the guide and the README
     * all used to state the lower-case rule as if it governed reading too; this pins the
     * behaviour they now describe.
     */
    @Test
    public void testAnUpperCaseZipIsReadRatherThanRefused() throws IOException
    {
        Path zipped = file("RULES.ZIP"); //$NON-NLS-1$
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zipped)))
        {
            out.putNextEntry(new ZipEntry("Main_Other_Ancestor.xml")); //$NON-NLS-1$
            out.write(FIXTURE.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        String result = call(params("mode", "read", "filePath", zipped.toString())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertFalse("an upper-case name is still a zip this server can open: " + result, //$NON-NLS-1$
            result.trim().startsWith("{")); //$NON-NLS-1$
        assertTrue("and the entry that was read is named: " + result, //$NON-NLS-1$
            result.contains("Main_Other_Ancestor.xml")); //$NON-NLS-1$
        assertTrue("the decisions of the archived document are reported: " + result, //$NON-NLS-1$
            result.contains("| Alpha | Beta | Gamma |")); //$NON-NLS-1$
    }

    @Test
    public void testReadNamesTheFileThatIsMissing()
    {
        Path missing = file("nothing-here.xml"); //$NON-NLS-1$
        assertErrorNaming(call(params("mode", "read", "filePath", missing.toString())), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            missing.toString(), "write"); //$NON-NLS-1$
    }

    @Test
    public void testReadRefusesAFileThatIsNotMergeSettings() throws IOException
    {
        Path file = file("other.xml"); //$NON-NLS-1$
        Files.write(file, "<Configuration Name=\"X\"/>".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        assertErrorNaming(call(params("mode", "read", "filePath", file.toString())), "Settings"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    // ============ a rule at no address is still a rule the file carries ============
    //
    // decisions() stopped returning a rule that sits outside the addressable tree, which is
    // right - there is no address to report it under. The report then said the file records
    // NO merge rule, and the preserved-section count does not cover a Node either, so the
    // rule went unmentioned everywhere: an absence claimed about a file that contradicts it.

    /**
     * A rule two levels out, under a KEYED node beside the root. The wording used to name only
     * "a node beside the marker", which is not where this one sits - and the counter has always
     * counted it, so the sentence was narrower than the number it introduced.
     *
     * @throws IOException when the fixture cannot be written
     */
    @Test
    public void testARuleDeepUnderANodeBesideTheRootIsReportedToo() throws IOException
    {
        String result = call(params("mode", "read", "filePath", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            seed("deep-beside-root.xml", RULE_DEEP_BESIDE_THE_ROOT).toString())); //$NON-NLS-1$

        assertFalse("the file carries a rule, so this sentence is false about it:\n" + result, //$NON-NLS-1$
            result.contains(CLAIMS_NO_RULE));
        assertTrue("and the report has to count it: " + result, //$NON-NLS-1$
            result.contains("1 merge rule in a part of the node tree addressing never enters")); //$NON-NLS-1$
    }

    /**
     * A file with no root marker at all. Addressing enters nowhere, so every rule in it is
     * outside - the shape the old wording could not describe, because it spoke of nodes standing
     * BESIDE a marker this file does not have.
     *
     * @throws IOException when the fixture cannot be written
     */
    @Test
    public void testAFileWithNoRootMarkerStillReportsTheRuleItCarries() throws IOException
    {
        String result = call(params("mode", "read", "filePath", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            seed("no-root.xml", RULE_WITH_NO_ROOT_MARKER).toString())); //$NON-NLS-1$

        assertFalse("the file carries a rule, so this sentence is false about it:\n" + result, //$NON-NLS-1$
            result.contains(CLAIMS_NO_RULE));
        assertTrue("and the report has to count it: " + result, //$NON-NLS-1$
            result.contains("1 merge rule in a part of the node tree addressing never enters")); //$NON-NLS-1$
    }

    /**
     * Two of them. The sentence introduces a number, so it has to agree with it: a plural count
     * under singular prose reads as a report about one rule and leaves the second unaccounted.
     *
     * @throws IOException when the fixture cannot be written
     */
    @Test
    public void testTwoUnreachableRulesAreWordedInThePlural() throws IOException
    {
        String result = call(params("mode", "read", "filePath", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            seed("two-unreachable.xml", TWO_UNREACHABLE_RULES).toString())); //$NON-NLS-1$

        assertTrue("both have to be counted: " + result, //$NON-NLS-1$
            result.contains("2 merge rules in a part of the node tree addressing never enters")); //$NON-NLS-1$
        assertTrue("and spoken of in the plural: " + result, //$NON-NLS-1$
            result.contains("those rules apply")); //$NON-NLS-1$
        assertFalse("never in the singular, which would describe only one of them:\n" + result, //$NON-NLS-1$
            result.contains("that rule applies")); //$NON-NLS-1$
    }

    @Test
    public void testAReadDoesNotClaimNoRuleWhenOneSitsBesideTheRoot() throws IOException
    {
        String result = call(params("mode", "read", "filePath", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            seed("beside-root.xml", RULE_BESIDE_THE_ROOT).toString())); //$NON-NLS-1$

        assertFalse("the file carries a rule, so this sentence is false about it:\n" + result, //$NON-NLS-1$
            result.contains(CLAIMS_NO_RULE));
        assertTrue("and the report has to say what it does carry: " + result, //$NON-NLS-1$
            result.contains("1 merge rule in a part of the node tree addressing never enters")); //$NON-NLS-1$
        assertTrue("naming the marker addressing enters at: " + result, //$NON-NLS-1$
            result.contains("$$Root$$")); //$NON-NLS-1$
        assertTrue("and that a rewrite keeps it: " + result, //$NON-NLS-1$
            result.contains("carries it through verbatim")); //$NON-NLS-1$
    }

    @Test
    public void testAReadDoesNotClaimNoRuleWhenOneSitsUnderAKeylessNode() throws IOException
    {
        String result = call(params("mode", "read", "filePath", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            seed("keyless.xml", RULE_UNDER_A_KEYLESS_NODE).toString())); //$NON-NLS-1$

        assertFalse("the file carries a rule, so this sentence is false about it:\n" + result, //$NON-NLS-1$
            result.contains(CLAIMS_NO_RULE));
        assertTrue("and the report has to say what it does carry: " + result, //$NON-NLS-1$
            result.contains("1 merge rule in a part of the node tree addressing never enters")); //$NON-NLS-1$
        assertTrue("naming the reason a path cannot rest there: " + result, //$NON-NLS-1$
            result.contains("carries no 'Key'")); //$NON-NLS-1$
    }

    /**
     * The clause is an ADDITION to the report, not a replacement for it: a file that holds
     * both kinds of rule has to have both reported.
     */
    @Test
    public void testAReadReportsAnUnreachableRuleAlongsideTheDecisionsItDoesHave()
        throws IOException
    {
        String result = call(params("mode", "read", "filePath", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            seed("both.xml", A_RULE_ON_EITHER_SIDE_OF_THE_ADDRESSING).toString())); //$NON-NLS-1$

        assertTrue("the addressable rule is a decision and belongs in the table: " + result, //$NON-NLS-1$
            result.contains("| collection |")); //$NON-NLS-1$
        assertTrue("and the one at no address still has to be named: " + result, //$NON-NLS-1$
            result.contains("1 merge rule in a part of the node tree addressing never enters")); //$NON-NLS-1$
    }

    /**
     * The control that keeps the three above from being passed by a report that always says
     * it: an ordinary file gains nothing.
     */
    @Test
    public void testAnOrdinaryReadIsUnchangedByTheUnreachableClause() throws IOException
    {
        String result = call(params("mode", "read", "filePath", seedFixture().toString())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        // Pinned on the clause's OWN words. It used to be pinned on "no address reaches", which is
        // the javadoc's phrasing and appears in no report this tool prints - so the control passed
        // on any behaviour at all, including a report that emitted the clause every time.
        assertFalse("nothing in this file is unaddressable, so the report must not say so:\n" //$NON-NLS-1$
            + result, result.contains(UNREACHABLE_CLAUSE_MARK));
    }

    /**
     * The other control: the sentence about a file that records nothing is still said about a
     * file that records nothing.
     */
    @Test
    public void testAFileWithNoRuleAtAllIsStillReportedAsRecordingNone() throws IOException
    {
        String result = call(params("mode", "read", "filePath", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            seed("empty.xml", NO_RULE_AT_ALL).toString())); //$NON-NLS-1$

        assertTrue("an empty file records no rule, and the report says so: " + result, //$NON-NLS-1$
            result.contains(CLAIMS_NO_RULE));
        assertFalse("and there is no unreachable rule to mention: " + result, //$NON-NLS-1$
            result.contains(UNREACHABLE_CLAUSE_MARK));
    }

    // ============ the WRITE report discloses it too, and against the same counter ============
    //
    // A rewrite carries an unreachable rule through verbatim, so a write started from a basedOn
    // that holds one writes a file that holds it. Neither number the write report prints covers
    // it - decisions() has no address to return it under, preservedSectionCount does not count a
    // Node as a preserved block - so the report presented the file as holding exactly the rules it
    // had just listed, and the rule nobody can address stayed invisible to the caller who had just
    // copied it forward. The read half had said so all along; only the write half was silent.

    @Test
    public void testAWriteCarryingAnUnreachableRuleForwardSaysSo() throws IOException
    {
        Path file = seed("write-unreachable.xml", RULE_BESIDE_THE_ROOT); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", file.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "basedOn", file.toString(), //$NON-NLS-1$
            "decisions", "[{\"path\":[\"commonModules\"],\"rule\":\"GetFromOther\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the write report has to count what it carried forward: " + result, //$NON-NLS-1$
            result.contains("1 merge rule" + UNREACHABLE_CLAUSE_MARK)); //$NON-NLS-1$
    }

    @Test
    public void testAWriteCarryingAnUnreachableRuleForwardSaysItIsKept() throws IOException
    {
        Path file = seed("write-unreachable.xml", RULE_BESIDE_THE_ROOT); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", file.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "basedOn", file.toString(), //$NON-NLS-1$
            "decisions", "[{\"path\":[\"commonModules\"],\"rule\":\"GetFromOther\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("and that the rewrite kept it rather than dropping it: " + result, //$NON-NLS-1$
            result.contains("carries it through verbatim")); //$NON-NLS-1$
    }

    /**
     * What makes the clause true rather than decorative: the rule really is in the file this call
     * wrote. Without this the report could be naming something the rewrite had silently dropped.
     *
     * @throws IOException when the fixture cannot be written or read back
     */
    @Test
    public void testTheUnreachableRuleTheWriteReportNamesIsReallyInTheWrittenFile()
        throws IOException
    {
        Path file = seed("write-unreachable.xml", RULE_BESIDE_THE_ROOT); //$NON-NLS-1$

        call(params("mode", "write", "filePath", file.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "basedOn", file.toString(), //$NON-NLS-1$
            "decisions", "[{\"path\":[\"commonModules\"],\"rule\":\"GetFromOther\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the rewrite must have carried the unaddressable node through:\n" + read(file), //$NON-NLS-1$
            read(file).contains("Key=\"orphan\"")); //$NON-NLS-1$
    }

    /**
     * The control: an ordinary {@code basedOn} gains nothing, so the three above cannot be passed
     * by a write report that prints the clause every time.
     *
     * @throws IOException when the fixture cannot be written
     */
    @Test
    public void testAnOrdinaryWriteIsUnchangedByTheUnreachableClause() throws IOException
    {
        Path file = seedFixture();

        String result = call(params("mode", "write", "filePath", file.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "basedOn", file.toString(), //$NON-NLS-1$
            "decisions", "[{\"path\":[\"catalogs\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse("nothing in this file is unaddressable, so the report must not say so:\n" //$NON-NLS-1$
            + result, result.contains(UNREACHABLE_CLAUSE_MARK));
    }

    // ======== "kept" counts the RULES that survived, not the addresses that were there ========
    //
    // A decision written at a path the starting document already carried OVERWRITES the rule that
    // was there, and the line above already counts it as replaced. The carried-over count was
    // printed whole regardless, so a write that replaced one of four reported "1 replaced" and
    // "4 decisions it already held were kept" in the same breath - two numbers over overlapping
    // sets, with nothing saying which, and no audit of what was really carried forward available
    // from either.

    /** What the report used to say about a starting document of four whatever happened to it. */
    private static final String WHOLE_COUNT_KEPT = "4 decisions it already held were kept"; //$NON-NLS-1$

    @Test
    public void testADecisionThatOverwroteAnExistingRuleIsNotAlsoCountedAsKept() throws IOException
    {
        Path file = seedFixture();

        String result = call(params("mode", "write", "filePath", file.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "basedOn", file.toString(), //$NON-NLS-1$
            "decisions", "[{\"path\":[\"commonModules\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        // The NEGATIVE pin: the sentence that counted the replaced one as kept must be gone, not
        // merely joined by a second sentence saying otherwise.
        assertFalse("a replaced decision may not be reported as kept as well:\n" + result, //$NON-NLS-1$
            result.contains(WHOLE_COUNT_KEPT));
        assertTrue("the two numbers have to add up to what arrived:\n" + result, //$NON-NLS-1$
            result.contains("of the 4 decisions it already held, 3 kept the rule they arrived " //$NON-NLS-1$
                + "with; this call replaced 1")); //$NON-NLS-1$
    }

    /**
     * And the replaced count it has to agree with is still printed, so the report cannot be read
     * as describing two different starting documents.
     *
     * @throws IOException when the fixture cannot be written
     */
    @Test
    public void testTheReplacedCountTheKeptCountAgreesWithIsStillReported() throws IOException
    {
        Path file = seedFixture();

        String result = call(params("mode", "write", "filePath", file.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "basedOn", file.toString(), //$NON-NLS-1$
            "decisions", "[{\"path\":[\"commonModules\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the recorded line still says what this call did:\n" + result, //$NON-NLS-1$
            result.contains("Decisions recorded: 1 (0 new, 1 replaced)")); //$NON-NLS-1$
    }

    /**
     * The control: a write that overwrites nothing still reports every carried decision as kept,
     * in the words it always used. Without it the fix could be "never say kept".
     *
     * @throws IOException when the fixture cannot be written
     */
    @Test
    public void testAWriteThatOverwroteNothingStillReportsEveryCarriedDecisionAsKept()
        throws IOException
    {
        Path file = seedFixture();

        String result = call(params("mode", "write", "filePath", file.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "basedOn", file.toString(), //$NON-NLS-1$
            "decisions", "[{\"path\":[\"catalogs\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("nothing was replaced, so all four were kept:\n" + result, //$NON-NLS-1$
            result.contains(WHOLE_COUNT_KEPT));
        assertFalse("and the split wording belongs to the replacing case alone:\n" + result, //$NON-NLS-1$
            result.contains("kept the rule they arrived with")); //$NON-NLS-1$
    }

    // ==================== write, no live comparison ====================

    @Test
    public void testWriteWithoutAComparisonSaysItIsNotValidated() throws IOException
    {
        Path target = file("rules.xml"); //$NON-NLS-1$
        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[\"commonModules\"],\"rule\":\"GetFromOther\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the report must not present an unchecked file as a checked one: " + result, //$NON-NLS-1$
            result.contains("NOT VALIDATED")); //$NON-NLS-1$
        assertTrue("and must name the way to get validation: " + result, //$NON-NLS-1$
            result.contains("compare_configurations")); //$NON-NLS-1$
        assertFalse("it must NOT claim validation", result.contains("Validated against comparison")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the file must be on disk", Files.isRegularFile(target)); //$NON-NLS-1$
        assertTrue(read(target).contains("<Node Key=\"commonModules\" MergeRule=\"GetFromOther\"/>")); //$NON-NLS-1$
    }

    @Test
    public void testADecisionThatIsNotAnObjectIsRefusedByPositionAndNothingIsWritten()
        throws IOException
    {
        Path target = file("rules.xml"); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", //$NON-NLS-1$
            "[{\"path\":[\"commonModules\"],\"rule\":\"GetFromOther\"},\"typo\"]")); //$NON-NLS-1$

        // Position, like every other malformed decision this tool refuses - and not a quiet drop
        // that would report "1 decision recorded" for a call that sent two.
        assertErrorNaming(result, "decisions", "#2"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a refused call must leave no file behind", Files.exists(target)); //$NON-NLS-1$
    }

    @Test
    public void testEveryDecisionBeingAnObjectStillWrites() throws IOException
    {
        Path target = file("rules.xml"); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", //$NON-NLS-1$
            "[{\"path\":[\"commonModules\"],\"rule\":\"GetFromOther\"}]")); //$NON-NLS-1$

        assertFalse("the well-formed array must not be caught by the new refusal: " + result, //$NON-NLS-1$
            result.contains("is not an object")); //$NON-NLS-1$
        assertTrue(Files.isRegularFile(target));
    }

    @Test
    public void testWriteRefusesToReplaceAnExistingFileWithoutBasedOn() throws IOException
    {
        Path target = seedFixture();
        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$
        assertErrorNaming(result, "basedOn", target.toString()); //$NON-NLS-1$
        assertEquals("the existing decisions must still be there", FIXTURE, read(target)); //$NON-NLS-1$
    }

    @Test
    public void testWriteRefusesToReplaceADifferentFileEvenWhenBasedOnIsGiven() throws IOException
    {
        // basedOn names WHERE THE DECISIONS COME FROM, not permission to overwrite anything else:
        // with a different target the guard has to hold, or one file's decisions get written over
        // another's and the report names only the ones that were carried in.
        Path startingPoint = seedFixture();
        Path target = file("target.xml"); //$NON-NLS-1$
        Files.write(target, OTHER_FIXTURE.getBytes(StandardCharsets.UTF_8));

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "basedOn", startingPoint.toString(), //$NON-NLS-1$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, target.toString(), startingPoint.toString(), "basedOn"); //$NON-NLS-1$
        assertEquals("the target's own decisions must survive the refusal, byte for byte", //$NON-NLS-1$
            OTHER_FIXTURE, read(target));
    }

    @Test
    public void testWriteWithBasedOnKeepsWhatWasAlreadyDecided() throws IOException
    {
        Path target = seedFixture();
        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "basedOn", target.toString(), //$NON-NLS-1$
            "decisions", "[{\"path\":[\"catalogs\",\"Products:Products:Products\"]," //$NON-NLS-1$ //$NON-NLS-2$
                + "\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$

        assertTrue(result, result.startsWith("# Merge rules written:")); //$NON-NLS-1$
        String written = read(target);
        assertTrue("the pre-existing decision must survive", //$NON-NLS-1$
            written.contains("<Node Key=\"Alpha:Beta:Gamma\" MergeRule=\"MergePrioritizingMain\"/>")); //$NON-NLS-1$
        assertTrue("the payload must survive", written.contains("<SkipUnchanged>true</SkipUnchanged>")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the new decision must be there", //$NON-NLS-1$
            written.contains("<Node Key=\"Products:Products:Products\" MergeRule=\"DoNotMerge\"/>")); //$NON-NLS-1$
    }

    @Test
    public void testWriteNeedsDecisions()
    {
        assertErrorNaming(call(params("mode", "write", "filePath", file("r.xml").toString())), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "decisions", "GetFromOther"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ============ the container: '.zip' is ADDRESSED, '.xml' is version-limited ============
    //
    // EDT 2026.2 reads merge settings from a zip alone; 2026.1 reads either. A zip is addressed
    // by its entry name - the launching comparison's own '<main>_<other>_<ancestor>' - and EDT
    // SKIPS an archive whose ENTRY is named anything else, applying nothing and saying nothing
    // (the archive's own file name is never matched against anything). So this tool
    // writes a zip only when a live comparison can name the entry, refuses one when nothing can,
    // and states in the report which container was written and which EDT reads it.

    @Test
    public void testAZipTargetIsRefusedWhenNothingCanNameItsEntry()
    {
        Path target = file("r.zip"); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, "Nothing was written", ".zip", "compare_configurations", ".xml"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertFalse("a zip nobody can address must not reach the disk: EDT would ignore it and " //$NON-NLS-1$
            + "the caller would be told the decisions were recorded", Files.exists(target)); //$NON-NLS-1$
    }

    @Test
    public void testAZipTargetIsWrittenUnderTheEntryTheLiveComparisonNames() throws Exception
    {
        Path target = file("r.zip"); //$NON-NLS-1$
        MergeRulesTool tool = new MergeRulesTool(
            id -> Optional.of(authority("cmp-7", List.of("DoNotMerge")))); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse("expected a report, got a refusal:\n" + result, result.trim().startsWith("{")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the entry has to be the comparison's own id - any other name is skipped by " //$NON-NLS-1$
            + "EDT", List.of(ENTRY_ID + ".xml"), zipEntryNames(target)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAZipTargetHoldsTheDecisionsItReports() throws Exception
    {
        Path target = file("r.zip"); //$NON-NLS-1$
        MergeRulesTool tool = new MergeRulesTool(
            id -> Optional.of(authority("cmp-7", List.of("DoNotMerge")))); //$NON-NLS-1$ //$NON-NLS-2$

        tool.execute(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        // Read back through the tool's own reader, which is the reader a caller would use.
        String read = call(params("mode", "read", "filePath", target.toString())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue("the archive must carry the rule that was reported:\n" + read, //$NON-NLS-1$
            read.contains("DoNotMerge")); //$NON-NLS-1$
    }

    @Test
    public void testTheReportOfAZipNamesTheEntryItIsAddressedTo()
    {
        Path target = file("r.zip"); //$NON-NLS-1$
        MergeRulesTool tool = new MergeRulesTool(
            id -> Optional.of(authority("cmp-7", List.of("DoNotMerge")))); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the report must name the container and the entry:\n" + result, //$NON-NLS-1$
            result.contains("Container: '.zip', entry `" + ENTRY_ID + ".xml`")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTheReportOfAnXmlSaysEdt20262DoesNotReadIt()
    {
        String result = call(params("mode", "write", "filePath", file("r.xml").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the report must name the container it wrote:\n" + result, //$NON-NLS-1$
            result.contains("Container: '.xml'")); //$NON-NLS-1$
        assertTrue("and the version that will not read it - discovering that inside the launch " //$NON-NLS-1$
            + "this file was prepared for is the failure this sentence exists to prevent:\n" //$NON-NLS-1$
            + result, result.contains("EDT 2026.2 does not read it")); //$NON-NLS-1$
    }

    @Test
    public void testAnXmlReportDoesNotClaimToBeAZip()
    {
        // Pins the ABSENCE: a container clause that always printed the zip sentence would satisfy
        // every "the report says which container" assertion above and still be wrong here.
        String result = call(params("mode", "write", "filePath", file("r.xml").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse("an xml file is addressed to nobody and must not be described as addressed:\n" //$NON-NLS-1$
            + result, result.contains("Container: '.zip'")); //$NON-NLS-1$
    }

    @Test
    public void testAZipReportDoesNotCarryTheXmlVersionCaveat() throws Exception
    {
        // The mirror of the pin above: the zip IS read by both versions, so repeating the 2026.2
        // warning there would describe a limit this file does not have.
        //
        // The absence is asserted LAST, and only after the write has been shown to have happened.
        // On its own it is satisfied by a refusal - or by any other failure - because a refusal
        // does not carry the xml caveat either, so a regression that stopped writing zips at all
        // would have left this test green.
        Path target = file("r.zip"); //$NON-NLS-1$
        MergeRulesTool tool = new MergeRulesTool(
            id -> Optional.of(authority("cmp-7", List.of("DoNotMerge")))); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse("the write must have succeeded before an absence in its report means " //$NON-NLS-1$
            + "anything - a refusal carries no xml caveat either:\n" + result, //$NON-NLS-1$
            result.trim().startsWith("{")); //$NON-NLS-1$
        assertTrue("and the file must be there:\n" + result, Files.exists(target)); //$NON-NLS-1$
        assertTrue("as the addressed container this test is about:\n" + result, //$NON-NLS-1$
            result.contains("Container: '.zip', entry `" + ENTRY_ID + ".xml`")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("holding the entry EDT will look for", //$NON-NLS-1$
            zipEntryNames(target).contains(ENTRY_ID + ".xml")); //$NON-NLS-1$
        assertFalse("a zip is read by every supported EDT:\n" + result, //$NON-NLS-1$
            result.contains("EDT 2026.2 does not read it")); //$NON-NLS-1$
    }

    @Test
    public void testATargetThatIsNeitherXmlNorZipIsRefusedNamingBoth()
    {
        Path target = file("r.txt"); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, ".zip", ".xml", "r.txt"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(Files.exists(target));
    }

    @Test
    public void testANamedComparisonThatDoesNotAnswerIsReportedAsThatEvenForAZip()
    {
        // Two refusals could fire here, and the more specific one has to win: the caller named a
        // comparison, so "nothing answered for that id" is what they can act on. Reporting "no
        // comparison could name the entry" would describe a state they did not create.
        Path target = file("r.zip"); //$NON-NLS-1$
        MergeRulesTool tool = new MergeRulesTool(id -> Optional.empty());

        String result = tool.execute(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "comparisonId", "cmp-9", //$NON-NLS-1$ //$NON-NLS-2$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, "cmp-9", "nothing answered for it"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(Files.exists(target));
    }

    // ======== the ADDRESS survives a comparison that cannot answer about rules ========
    //
    // The two are different questions of the same session: the zip entry name is the three
    // project names off the handle, the rule verdict needs a FINISHED tree. Computing the name
    // only on the path that had already proved the tree finished threw a KNOWN name away, and a
    // zip write was then refused telling the caller to start the very comparison that was already
    // holding EDT's single slot - which it would have refused to start.

    @Test
    public void testAZipIsWrittenAddressedButUnvalidatedWhenTheTreeCannotAnswer() throws Exception
    {
        Path target = file("r.zip"); //$NON-NLS-1$
        MergeRulesTool tool = new MergeRulesTool(id -> Optional.of(addressOnly("cmp-7"))); //$NON-NLS-1$

        String result = tool.execute(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse("a comparison that cannot answer about rules can still name the entry:\n" //$NON-NLS-1$
            + result, result.trim().startsWith("{")); //$NON-NLS-1$
        assertTrue("the file must exist", Files.exists(target)); //$NON-NLS-1$
        assertTrue("addressed with the live comparison's own entry name", //$NON-NLS-1$
            zipEntryNames(target).contains(ENTRY_ID + ".xml")); //$NON-NLS-1$
        assertTrue("and reported as unchecked, naming the comparison that answered:\n" + result, //$NON-NLS-1$
            result.contains("NOT VALIDATED") && result.contains("cmp-7")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("nothing here was validated:\n" + result, //$NON-NLS-1$
            result.contains("Validated against comparison")); //$NON-NLS-1$
    }

    @Test
    public void testANamedComparisonWhoseTreeCannotAnswerIsRefusedAsThatAndNotAsMissing()
    {
        // The caller asked for validation, so the write is still refused - but the refusal has to
        // describe what was observed. "Nothing answered for that id" would send them hunting for
        // a live id they already have; the tree is what is missing, and it arrives by itself.
        Path target = file("r.zip"); //$NON-NLS-1$
        MergeRulesTool tool = new MergeRulesTool(id -> Optional.of(addressOnly("cmp-7"))); //$NON-NLS-1$

        String result = tool.execute(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "comparisonId", "cmp-7", //$NON-NLS-1$ //$NON-NLS-2$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, "cmp-7", "it is registered", "tree could not be read"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse("the id is live, so the refusal must not say it answered nothing", //$NON-NLS-1$
            result.contains("nothing answered for it")); //$NON-NLS-1$
        assertFalse("and a refused write writes nothing", Files.exists(target)); //$NON-NLS-1$
    }

    // ======== the platform compares the extension EXACTLY, so a write must too ========

    @Test
    public void testAnUpperCaseZipTargetIsRefusedNamingTheCase()
    {
        // EDT 2026.2 asserts "zip".equals(FileUtil.getExtension(path)) and 2026.1 branches the
        // same way, so 'r.ZIP' is a name NEITHER reads. Accepting it produced a valid archive the
        // launch it was written for then refused - a file reported as written and usable while
        // being neither.
        Path target = file("r.ZIP"); //$NON-NLS-1$
        MergeRulesTool tool = new MergeRulesTool(
            id -> Optional.of(authority("cmp-7", List.of("DoNotMerge")))); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, ".ZIP", "lower case"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("nothing may be written under a name the platform will not read", //$NON-NLS-1$
            Files.exists(target));
    }

    @Test
    public void testAnUpperCaseXmlTargetIsRefusedNamingTheCase()
    {
        Path target = file("r.XML"); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, ".XML", "lower case"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(Files.exists(target));
    }

    @Test
    public void testALowerCaseTargetIsStillWritten() throws Exception
    {
        // The control: the refusal above is about the CASE, not about the tool having stopped
        // writing zips.
        Path target = file("r.zip"); //$NON-NLS-1$
        MergeRulesTool tool = new MergeRulesTool(
            id -> Optional.of(authority("cmp-7", List.of("DoNotMerge")))); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(result.trim().startsWith("{")); //$NON-NLS-1$
        assertTrue(Files.exists(target));
    }

    // ======== a zip is addressed by PROJECT NAMES, not by one comparison run ========

    @Test
    public void testTheZipContainerLineNamesTheProjectsRatherThanTheRun()
    {
        // What the entry name is made of is the whole risk: another comparison over the same three
        // projects - a later run, other revisions - restores this very entry and applies these
        // decisions again. A report that said "another comparison applies nothing" described the
        // opposite of the danger.
        Path target = file("r.zip"); //$NON-NLS-1$
        MergeRulesTool tool = new MergeRulesTool(
            id -> Optional.of(authority("cmp-7", List.of("DoNotMerge")))); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the address is the entry STRING, spelled from the project names:\n" + result, //$NON-NLS-1$
            result.contains("ADDRESSED BY THAT EXACT STRING")); //$NON-NLS-1$
        assertTrue("and re-use over the same projects is the risk to state:\n" + result, //$NON-NLS-1$
            result.contains("a later one over other revisions")); //$NON-NLS-1$
        assertFalse("the file's OWN name is the caller's to choose, and saying otherwise sent " //$NON-NLS-1$
            + "people renaming archives:\n" + result, //$NON-NLS-1$
            result.contains("Another comparison launched with this file applies nothing")); //$NON-NLS-1$
    }

    /**
     * The entry name is a concatenation over {@code _}, which is legal inside a project name, so
     * different triples spell the same entry - see
     * {@code ComparisonEngineTest#mergeRulesEntryIdCollidesBetweenTwoDifferentProjectTriples}. The
     * report used to close the paragraph with "a comparison over a different set of projects finds
     * no entry of its own here", which is the converse and is false. Two pins, because the two
     * halves fail differently: the withdrawn claim must be gone, and the property that replaced it
     * must be stated rather than merely omitted.
     */
    @Test
    public void testTheZipContainerLineDoesNotPromiseThatNoOtherComparisonCanFindTheFile()
    {
        Path target = file("r.zip"); //$NON-NLS-1$
        MergeRulesTool tool = new MergeRulesTool(
            id -> Optional.of(authority("cmp-7", List.of("DoNotMerge")))); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse("a DIFFERENT set of projects can spell the SAME entry:\n" + result, //$NON-NLS-1$
            result.contains("A comparison over a different set of projects")); //$NON-NLS-1$
        assertTrue("the separator's ambiguity is the fact the caller needs:\n" + result, //$NON-NLS-1$
            result.contains("not one-to-one")); //$NON-NLS-1$
        assertTrue("and only the safe direction may be asserted:\n" + result, //$NON-NLS-1$
            result.contains("spell a DIFFERENT string finds no")); //$NON-NLS-1$
    }

    /** The order is part of the address, so the line may not describe it as a set. */
    @Test
    public void testTheZipContainerLineSaysTheThreeNamesArePositional()
    {
        Path target = file("r.zip"); //$NON-NLS-1$
        MergeRulesTool tool = new MergeRulesTool(
            id -> Optional.of(authority("cmp-7", List.of("DoNotMerge")))); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("swapping main and other spells another entry, and the line says so:\n" //$NON-NLS-1$
            + result, result.contains("It is a string, not a set")); //$NON-NLS-1$
    }

    // ==================== refused rules and addresses ====================

    @Test
    public void testTheJavaConstantSpellingIsRefusedWithTheRightSpellingNamed()
    {
        Path target = file("r.xml"); //$NON-NLS-1$
        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[],\"rule\":\"GET_FROM_OTHER\"}]")); //$NON-NLS-1$ //$NON-NLS-2$
        assertErrorNaming(result, "GET_FROM_OTHER", "GetFromOther"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("nothing may be written when a decision is refused", Files.exists(target)); //$NON-NLS-1$
    }

    @Test
    public void testCustomMergeIsRefusedUnconditionally()
    {
        assertRefusedRule("CustomMerge"); //$NON-NLS-1$
    }

    @Test
    public void testMergeUsingExternalToolIsRefusedUnconditionally()
    {
        assertRefusedRule("MergeUsingExternalTool"); //$NON-NLS-1$
    }

    @Test
    public void testCustomMergeIsStillRefusedWithALiveComparisonThatAllowsIt()
    {
        // Even a node whose available set contains it: the bare literal records a decision whose
        // real content (the nested custom settings) nobody supplied here.
        MergeRulesTool tool = new MergeRulesTool(
            id -> Optional.of(authority("cmp-7", List.of("CustomMerge", "DoNotMerge")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String result = tool.execute(params("mode", "write", "filePath", file("r.xml").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "decisions", "[{\"path\":[],\"rule\":\"CustomMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$
        assertErrorNaming(result, "CustomMerge"); //$NON-NLS-1$
    }

    @Test
    public void testAPathBelowTheObjectIsRefusedWithTheReason()
    {
        String result = call(params("mode", "write", "filePath", file("r.xml").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "decisions", "[{\"path\":[\"commonModules\",\"A:A:A\",\"3\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$
        assertErrorNaming(result, "POSITION"); //$NON-NLS-1$
    }

    @Test
    public void testAPositionalKeyIsNeverAuthored()
    {
        String result = call(params("mode", "write", "filePath", file("r.xml").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "decisions", "[{\"path\":[\"12\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$
        assertErrorNaming(result, "12", "POSITION"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAnObjectKeyWithoutTheThreeNamesIsRefusedWithTheFormSpelledOut()
    {
        String result = call(params("mode", "write", "filePath", file("r.xml").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "decisions", "[{\"path\":[\"commonModules\",\"Alpha\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$
        assertErrorNaming(result, "Alpha:Alpha:Alpha", "Alpha:NONE:NONE"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTheRootMarkerMayBeSpelledOutInThePath() throws IOException
    {
        Path target = file("r.xml"); //$NON-NLS-1$
        call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[\"$$Root$$\",\"commonModules\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("spelling the root out addresses the root, not a child called '$$Root$$'", //$NON-NLS-1$
            read(target).contains("<Node Key=\"commonModules\" MergeRule=\"DoNotMerge\"/>")); //$NON-NLS-1$
    }

    // ==================== write, live comparison ====================

    @Test
    public void testWriteWithALiveComparisonReportsThatItWasValidated() throws IOException
    {
        Path target = file("r.xml"); //$NON-NLS-1$
        MergeRulesTool tool = new MergeRulesTool(
            id -> Optional.of(authority("cmp-7", List.of("GetFromOther", "DoNotMerge")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String result = tool.execute(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[\"commonModules\"],\"rule\":\"GetFromOther\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the report must name the comparison it checked against: " + result, //$NON-NLS-1$
            result.contains("Validated against comparison `cmp-7`")); //$NON-NLS-1$
        assertFalse("and must not also claim it was unchecked", result.contains("NOT VALIDATED")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(Files.isRegularFile(target));
    }

    /**
     * The "checked" claim has to cover the whole FILE, not just the decisions this call carries.
     * <p>
     * A write started from {@code basedOn} inherits decisions nobody re-sent, and they go into the
     * file the platform will read. Validating only the new ones stamped "Every rule below was
     * checked" on a document whose inherited half nobody had looked at - an inherited rule the
     * comparison does not allow is exactly as inapplicable as a fresh one.
     */
    @Test
    public void testAnInheritedDecisionTheComparisonRefusesStopsTheWholeWrite() throws IOException
    {
        Path target = seedFixture();
        String before = read(target);
        // The seeded file already carries commonModules=GetFromOther; the comparison allows only
        // DoNotMerge. The decision this call sends IS allowed, so nothing but the inherited one
        // can refuse the write.
        MergeRulesTool tool = new MergeRulesTool(
            id -> Optional.of(authority("cmp-7", List.of("DoNotMerge")))); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "basedOn", target.toString(), //$NON-NLS-1$
            "decisions", "[{\"path\":[\"documents\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, "GetFromOther", "$$Root$$ / commonModules", "cmp-7", "basedOn"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals("nothing may be written while any decision in the file is refused", before, //$NON-NLS-1$
            read(target));
    }

    /**
     * The control: inherited decisions the comparison DOES allow are not an obstacle, so the wider
     * check is "validate the document" and not "refuse anything that came from basedOn".
     */
    @Test
    public void testInheritedDecisionsTheComparisonAllowsStillWrite() throws IOException
    {
        Path target = seedFixture();
        MergeRulesTool tool = new MergeRulesTool(id -> Optional.of(authority("cmp-7", //$NON-NLS-1$
            List.of("GetFromOther", "DoNotMerge", "MergePrioritizingMain")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        String result = tool.execute(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "basedOn", target.toString(), //$NON-NLS-1$
            "decisions", "[{\"path\":[\"documents\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(result, result.startsWith("# Merge rules written:")); //$NON-NLS-1$
        assertTrue("the report must say what was actually checked: " + result, //$NON-NLS-1$
            result.contains("Every decision IN THE FILE was checked")); //$NON-NLS-1$
        assertTrue("the pre-existing decision must survive", //$NON-NLS-1$
            read(target).contains("<Node Key=\"Alpha:Beta:Gamma\" MergeRule=\"MergePrioritizingMain\"/>")); //$NON-NLS-1$
    }

    @Test
    public void testARuleTheNodeDoesNotAllowIsRefusedNamingNodeRuleAndAllowedSet()
    {
        Path target = file("r.xml"); //$NON-NLS-1$
        MergeRulesTool tool = new MergeRulesTool(
            id -> Optional.of(authority("cmp-7", List.of("DoNotMerge", "MergePrioritizingMain")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String result = tool.execute(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[\"commonModules\"],\"rule\":\"GetFromOther\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, "GetFromOther", "$$Root$$ / commonModules", "cmp-7", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "DoNotMerge, MergePrioritizingMain"); //$NON-NLS-1$
        assertFalse("an illegal rule must never reach the file", Files.exists(target)); //$NON-NLS-1$
    }

    @Test
    public void testOneIllegalRuleStopsTheWholeSet()
    {
        // The legal decision comes first; nothing may be written because the second is refused.
        Path target = file("r.xml"); //$NON-NLS-1$
        MergeRulesTool tool = new MergeRulesTool(
            id -> Optional.of(authority("cmp-7", List.of("DoNotMerge")))); //$NON-NLS-1$ //$NON-NLS-2$
        String result = tool.execute(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}," //$NON-NLS-1$ //$NON-NLS-2$
                + "{\"path\":[\"commonModules\"],\"rule\":\"GetFromOther\"}]")); //$NON-NLS-1$

        assertErrorNaming(result, "GetFromOther"); //$NON-NLS-1$
        assertFalse("a partly applied set would be a file nobody chose", Files.exists(target)); //$NON-NLS-1$
    }

    @Test
    public void testANodeTheComparisonDoesNotHaveIsRefused()
    {
        MergeRulesTool tool = new MergeRulesTool(id -> Optional.of(new MergeRuleAuthority()
        {
            @Override
            public String comparisonId()
            {
                return "cmp-7"; //$NON-NLS-1$
            }

            @Override
            public String mergeRulesEntryId()
            {
                return ENTRY_ID;
            }

            @Override
            public RuleSnapshot rulesFor(Collection<List<String>> nodePaths)
            {
                // A FINISHED tree that simply has no such node: checked, and the address absent.
                return RuleSnapshot.of(Map.of());
            }
        }));
        String result = tool.execute(params("mode", "write", "filePath", file("r.xml").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "decisions", "[{\"path\":[\"commonModules\"],\"rule\":\"GetFromOther\"}]")); //$NON-NLS-1$ //$NON-NLS-2$
        assertErrorNaming(result, "$$Root$$ / commonModules", "cmp-7"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ============ the authority is held for the whole pass, and only for it ============

    /**
     * The pass is one BM read per decision IN THE FILE, and a file built from {@code basedOn} can
     * carry hundreds. The production authority holds a registry lease across them, so that the idle
     * sweep - which any comparison-tool call in another thread can fire, counting its TTL from the
     * last touch rather than from the start of this pass - cannot reclaim the session being read
     * and stop the comparison under an active validation. A lease is only correct if it is also
     * GIVEN BACK, on every exit, which is what these four pin. They are separate methods because
     * JUnit stops at the first failed assertion.
     */
    @Test
    public void testTheAuthorityIsReleasedAfterAWriteThatPassedValidation()
    {
        RecordingAuthority authority = new RecordingAuthority("cmp-7", List.of("DoNotMerge")); //$NON-NLS-1$ //$NON-NLS-2$
        MergeRulesTool tool = new MergeRulesTool(id -> Optional.of(authority));

        String result = tool.execute(params("mode", "write", //$NON-NLS-1$ //$NON-NLS-2$
            "filePath", file("r.xml").toString(), //$NON-NLS-1$ //$NON-NLS-2$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(result, result.startsWith("# Merge rules written:")); //$NON-NLS-1$
        assertEquals("the pass ended, so what it held must be given back", 1, authority.closes); //$NON-NLS-1$
    }

    @Test
    public void testTheAuthorityIsReleasedAfterAWriteThatWasRefused()
    {
        RecordingAuthority authority = new RecordingAuthority("cmp-7", List.of("DoNotMerge")); //$NON-NLS-1$ //$NON-NLS-2$
        MergeRulesTool tool = new MergeRulesTool(id -> Optional.of(authority));

        String result = tool.execute(params("mode", "write", //$NON-NLS-1$ //$NON-NLS-2$
            "filePath", file("r.xml").toString(), //$NON-NLS-1$ //$NON-NLS-2$
            "decisions", "[{\"path\":[],\"rule\":\"GetFromOther\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, "GetFromOther"); //$NON-NLS-1$
        assertEquals("a refusal ends the pass too", 1, authority.closes); //$NON-NLS-1$
    }

    @Test
    public void testTheAuthorityIsReleasedWhenTheComparisonAnswersWithAFailure()
    {
        RecordingAuthority authority = new RecordingAuthority("cmp-7", List.of("DoNotMerge")); //$NON-NLS-1$ //$NON-NLS-2$
        authority.explode = true;
        MergeRulesTool tool = new MergeRulesTool(id -> Optional.of(authority));

        String result = tool.execute(params("mode", "write", //$NON-NLS-1$ //$NON-NLS-2$
            "filePath", file("r.xml").toString(), //$NON-NLS-1$ //$NON-NLS-2$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, "Nothing was written"); //$NON-NLS-1$
        assertEquals("the failure path must not leak what the pass held", 1, authority.closes); //$NON-NLS-1$
    }

    @Test
    public void testEveryDecisionIsCheckedBeforeTheAuthorityIsReleased()
    {
        // The one that distinguishes "held for the pass" from "closed as soon as it was obtained":
        // a release placed before the loop would leave every read running on a session the sweep is
        // free to reclaim, which is exactly the window this change closes.
        RecordingAuthority authority = new RecordingAuthority("cmp-7", List.of("DoNotMerge")); //$NON-NLS-1$ //$NON-NLS-2$
        MergeRulesTool tool = new MergeRulesTool(id -> Optional.of(authority));

        tool.execute(params("mode", "write", "filePath", file("r.xml").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}," //$NON-NLS-1$ //$NON-NLS-2$
                + "{\"path\":[\"commonModules\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$

        assertEquals("both decisions must be checked, and in ONE batch", 1, authority.reads); //$NON-NLS-1$
        assertEquals("both addresses must travel to the comparison together", 2, //$NON-NLS-1$
            authority.asked.size());
        assertEquals("no rule may be checked once the pass has released its hold", 0, //$NON-NLS-1$
            authority.readsAfterClose);
    }

    // ======== every decision is judged by ONE reading of ONE tree ========
    //
    // The tree's readiness was established in a boundary of its own, and then each decision was
    // resolved in a boundary of its own. Nothing held those boundaries together, so a file with
    // several decisions could be accepted against an old tree, a half-rebuilt one and a new one in
    // turn - while the report said every decision had been checked against the comparison, which
    // named a state the comparison was never in as a whole. The pass now takes ONE snapshot and
    // judges the whole document by it.

    @Test
    public void testTheWholeDocumentTravelsToTheComparisonInOneQuestion()
    {
        RecordingAuthority authority = new RecordingAuthority("cmp-7", List.of("DoNotMerge")); //$NON-NLS-1$ //$NON-NLS-2$
        MergeRulesTool tool = new MergeRulesTool(id -> Optional.of(authority));

        tool.execute(params("mode", "write", "filePath", file("r.xml").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}," //$NON-NLS-1$ //$NON-NLS-2$
                + "{\"path\":[\"commonModules\"],\"rule\":\"DoNotMerge\"}," //$NON-NLS-1$
                + "{\"path\":[\"catalogs\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$

        assertEquals("one question, not one per decision - anything else lets two decisions be " //$NON-NLS-1$
            + "judged by two different trees", 1, authority.reads); //$NON-NLS-1$
        assertEquals("and all three addresses have to be IN it, or the ones left out would need " //$NON-NLS-1$
            + "a second question", 3, authority.asked.size()); //$NON-NLS-1$
    }

    @Test
    public void testASecondReadingIsNeverTakenSoNoVerdictCanBeAssembledFromTwoTrees()
    {
        // The comparison's tree finishes between the first question and any second one. Under the
        // old shape that difference decided the outcome: the readiness read said "not ready" while
        // a later per-decision read answered from the finished tree, or the other way about. One
        // question means the LATER state is never consulted, so the report cannot straddle two.
        Path target = file("r.xml"); //$NON-NLS-1$
        AtomicReference<Integer> asked = new AtomicReference<>(Integer.valueOf(0));
        MergeRulesTool tool = new MergeRulesTool(id -> Optional.of(new MergeRuleAuthority()
        {
            @Override
            public String comparisonId()
            {
                return "cmp-7"; //$NON-NLS-1$
            }

            @Override
            public String mergeRulesEntryId()
            {
                return ENTRY_ID;
            }

            @Override
            public RuleSnapshot rulesFor(Collection<List<String>> nodePaths)
            {
                int count = asked.get().intValue() + 1;
                asked.set(Integer.valueOf(count));
                if (count == 1)
                {
                    return RuleSnapshot.unreadable();
                }
                Map<List<String>, List<String>> answer = new LinkedHashMap<>();
                for (List<String> path : nodePaths)
                {
                    answer.put(path, List.of("DoNotMerge")); //$NON-NLS-1$
                }
                return RuleSnapshot.of(answer);
            }
        }));

        String result = tool.execute(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}," //$NON-NLS-1$ //$NON-NLS-2$
                + "{\"path\":[\"commonModules\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$

        assertEquals("the comparison must be asked exactly once", 1, asked.get().intValue()); //$NON-NLS-1$
        assertTrue("the one reading carried no verdict, so the report says so: " + result, //$NON-NLS-1$
            result.contains("NOT VALIDATED")); //$NON-NLS-1$
        assertFalse("and it must not claim the later, finished tree checked anything: " + result, //$NON-NLS-1$
            result.contains("Validated against comparison")); //$NON-NLS-1$
    }

    @Test
    public void testTheValidatedReportSaysTheDecisionsShareOneReading()
    {
        // Its own literal. The claim "every decision was checked against the comparison" was true
        // of no single state of it while the readings were separate, and the sentence said nothing
        // about that - so the report has to carry the fact that now makes it true.
        MergeRulesTool tool = new MergeRulesTool(
            id -> Optional.of(authority("cmp-7", List.of("DoNotMerge")))); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params("mode", "write", "filePath", file("r.xml").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the report must say the decisions share one reading: " + result, //$NON-NLS-1$
            result.contains("ONE reading of a tree that reported itself FINISHED")); //$NON-NLS-1$
    }

    @Test
    public void testSiblingAddressesShareOneWalkOfTheirParentsChildren()
    {
        // The cost side of holding ONE boundary. Resolving one chain at a time re-scanned the
        // children of every node on the way down, once per decision; inside a single boundary that
        // would hold the comparison's own read open for a multiple of the work it needs, which is
        // the risk this shape had to answer for.
        ComparisonNode alpha = topNode("CommonModule.Alpha", "CommonModule.Alpha", null); //$NON-NLS-1$ //$NON-NLS-2$
        ComparisonNode beta = topNode("CommonModule.Beta", "CommonModule.Beta", null); //$NON-NLS-1$ //$NON-NLS-2$
        ComparisonNode collection = plainNode();
        withChildren(collection, alpha, beta);
        ComparisonNode root = plainNode();
        withChildren(root, collection);

        List<String> first = List.of("commonModules", "Alpha:Alpha:NONE"); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> second = List.of("commonModules", "Beta:Beta:NONE"); //$NON-NLS-1$ //$NON-NLS-2$
        Map<List<String>, ComparisonNode> found =
            MergeRulesTool.findNodes(root, List.of(first, second), node -> "commonModules"); //$NON-NLS-1$

        assertSame("both chains must still resolve", alpha, found.get(first)); //$NON-NLS-1$
        assertSame("both chains must still resolve", beta, found.get(second)); //$NON-NLS-1$
        verify(collection, times(1)).<ComparisonNode> getChildren();
    }

    @Test
    public void testAChainNoChildCarriesIsAbsentWhileItsSiblingStillResolves()
    {
        // The walk must not let one unresolvable chain take its siblings down with it, and must
        // not answer for it either: absent is what the caller renders as "not in comparison".
        ComparisonNode alpha = topNode("CommonModule.Alpha", "CommonModule.Alpha", null); //$NON-NLS-1$ //$NON-NLS-2$
        ComparisonNode collection = plainNode();
        withChildren(collection, alpha);
        ComparisonNode root = plainNode();
        withChildren(root, collection);

        List<String> here = List.of("commonModules", "Alpha:Alpha:NONE"); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> nowhere = List.of("catalogs", "Nothing:Nothing:NONE"); //$NON-NLS-1$ //$NON-NLS-2$
        Map<List<String>, ComparisonNode> found =
            MergeRulesTool.findNodes(root, List.of(here, nowhere), node -> "commonModules"); //$NON-NLS-1$

        assertSame(alpha, found.get(here));
        assertNull("a chain no child carries has no answer, not an empty one", //$NON-NLS-1$
            found.get(nowhere));
    }

    // ======== the snapshot keeps "no such node" apart from "no choice on it" ========

    @Test
    public void testASnapshotThatCouldNotBeReadCarriesNoVerdict()
    {
        assertFalse("nothing may be refused - or accepted - on the strength of it", //$NON-NLS-1$
            RuleSnapshot.unreadable().checked());
        assertTrue("and it answers for no address either", //$NON-NLS-1$
            RuleSnapshot.unreadable().rulesAt(List.of("$$Root$$")).isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testASnapshotTellsAnAbsentAddressFromOneThatOffersNothing()
    {
        RuleSnapshot snapshot = RuleSnapshot.of(Map.of(List.of("$$Root$$"), List.of())); //$NON-NLS-1$

        assertTrue("a FINISHED tree was read, so its addresses are verdicts", snapshot.checked()); //$NON-NLS-1$
        assertEquals("the node is HERE and offers nothing - an empty ALLOWED SET", List.of(), //$NON-NLS-1$
            snapshot.rulesAt(List.of("$$Root$$")).orElse(null)); //$NON-NLS-1$
        assertTrue("the node the tree does not have is ABSENT, which is a different refusal", //$NON-NLS-1$
            snapshot.rulesAt(List.of("$$Root$$", "commonModules")).isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testANamedComparisonThatIsNotLiveIsRefusedRatherThanQuietlyUnvalidated()
    {
        Path target = file("r.xml"); //$NON-NLS-1$
        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "comparisonId", "cmp-gone", //$NON-NLS-1$ //$NON-NLS-2$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$
        assertErrorNaming(result, "cmp-gone", "compare_configurations"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the caller asked for validation; writing anyway would answer a different " //$NON-NLS-1$
            + "question", Files.exists(target)); //$NON-NLS-1$
    }

    @Test
    public void testTheRefusalDoesNotClaimNoComparisonIsRunning()
    {
        // Telling the caller to START one is the reading that cannot be acted on: EDT runs a
        // single comparison per instance, so a second launch is refused while the first is still
        // there. This refusal is for the id that ANSWERED NOTHING - the unfinished tree has its
        // own refusal now, because the address it can still give makes the two different
        // situations with different actions.
        String result = call(params("mode", "write", "filePath", file("r.xml").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "comparisonId", "cmp-4", //$NON-NLS-1$ //$NON-NLS-2$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, "cmp-4", "nothing answered for it"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the refusal must not send the caller to start a comparison that may already " //$NON-NLS-1$
            + "be running: " + result, result.contains("Start one")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("and it must not attribute a cause it did not observe - an unfinished tree " //$NON-NLS-1$
            + "reaches the other branch, which names itself: " + result, //$NON-NLS-1$
            result.contains("its tree is not finished")); //$NON-NLS-1$
    }

    @Test
    public void testAFailingCheckIsReportedAsAFailureNotAsAnIllegalRule()
    {
        // The comparison threw instead of answering. That is neither "the rule is illegal" nor
        // "the rules were checked", so the tool must name the failure and write nothing - an
        // exception escaping execute() would reach the caller as a protocol error instead.
        Path target = file("r.xml"); //$NON-NLS-1$
        MergeRulesTool tool = new MergeRulesTool(id -> Optional.of(new MergeRuleAuthority()
        {
            @Override
            public String comparisonId()
            {
                return "cmp-9"; //$NON-NLS-1$
            }

            @Override
            public String mergeRulesEntryId()
            {
                return ENTRY_ID;
            }

            @Override
            public RuleSnapshot rulesFor(Collection<List<String>> nodePaths)
            {
                throw new IllegalStateException("the comparison store is closed"); //$NON-NLS-1$
            }
        }));

        String result = tool.execute(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "comparisonId", "cmp-9", //$NON-NLS-1$ //$NON-NLS-2$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, "cmp-9", "the comparison store is closed"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("an unchecked file must not be left behind by a failed check", //$NON-NLS-1$
            Files.exists(target));
    }

    @Test
    public void testASupplierThatFailsIsReportedRatherThanThrown()
    {
        // Same rule one step earlier: resolving the authority is part of the check, so a failure
        // there is a failed check and not an absent comparison.
        MergeRulesTool tool = new MergeRulesTool(id -> {
            throw new IllegalStateException("the comparison service went away"); //$NON-NLS-1$
        });

        String result = tool.execute(params("mode", "write", "filePath", file("r.xml").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, "the comparison service went away"); //$NON-NLS-1$
    }

    @Test
    public void testANodeThatCarriesNoChoiceIsRefusedWithoutAnEmptyAllowedSet()
    {
        // An EMPTY allowed set is an answer: the comparison has the node and offers no rule on
        // it. Rendering it through the "That node allows: <set>" sentence would print a sentence
        // that ends in nothing, which reads as a broken message rather than as a verdict.
        MergeRulesTool tool = new MergeRulesTool(id -> Optional.of(authority("cmp-3", List.of()))); //$NON-NLS-1$

        String result = tool.execute(params("mode", "write", "filePath", file("r.xml").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "decisions", "[{\"path\":[\"commonModules\"],\"rule\":\"GetFromOther\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, "cmp-3", "$$Root$$ / commonModules", "no merge rule"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse("a sentence naming the allowed set must not be rendered with an empty one: " //$NON-NLS-1$
            + result, result.contains("That node allows")); //$NON-NLS-1$
    }

    // ==================== the shipped wiring ====================

    @Test
    public void testTheShippedToolConsultsTheComparisonFacade()
    {
        // The no-argument constructor is the one the registry uses. Held to a supplier that can
        // never answer, the validated mode the description advertises would be a branch the
        // shipped build cannot enter - and no behavioural test run without EDT can tell the two
        // suppliers apart, because both answer "nothing" here.
        assertTrue("the shipped tool must ask the comparison facade, not a constant 'no'", //$NON-NLS-1$
            new MergeRulesTool().authoritySupplier() instanceof EngineRuleAuthority);
    }

    @Test
    public void testTheFacadeAuthorityAnswersNothingWithNoComparisonFacadeInstalled()
    {
        // Headless: the bundle is not started, so the facade is not installed. The production
        // supplier must then answer NOTHING - the write degrades to NOT VALIDATED - rather than
        // throw or invent an authority.
        assertTrue(new EngineRuleAuthority().authority(null).isEmpty());
        assertTrue(new EngineRuleAuthority().authority("cmp-1").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testTheFacadeAuthorityStillAnswersNothingWhenTheResolutionAnswersNothing()
    {
        // The half that must NOT become a refusal. An absent comparison is a READING, and the
        // honest write-up of it is NOT VALIDATED - not an error.
        assertTrue(new EngineRuleAuthority(id -> Optional.empty()).authority(null).isEmpty());
        assertTrue(new EngineRuleAuthority(id -> Optional.empty()).authority("cmp-1").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testTheFacadeAuthorityLetsAFailedResolutionReachTheCaller()
    {
        // "Nothing answered" and "something was asked and broke" are different facts. This
        // supplier used to catch RuntimeException and answer empty, which put both through the
        // one door the caller reads as "there was nothing to check against".
        EngineRuleAuthority supplier = new EngineRuleAuthority(id -> {
            throw new IllegalStateException("the comparison tree could not be polled"); //$NON-NLS-1$
        });

        try
        {
            supplier.authority(null);
            fail("a failed resolution must reach the caller, not degrade to 'no comparison'"); //$NON-NLS-1$
        }
        catch (IllegalStateException e)
        {
            assertEquals("the comparison tree could not be polled", e.getMessage()); //$NON-NLS-1$
        }
    }

    @Test
    public void testTheFacadeAuthorityLetsAFailedResolutionReachTheCallerWithAnIdNamedToo()
    {
        // Named or not named, the same failure has to arrive the same way: the whole defect was
        // that the two branches disagreed about it.
        EngineRuleAuthority supplier = new EngineRuleAuthority(id -> {
            throw new IllegalStateException("the comparison tree could not be polled"); //$NON-NLS-1$
        });

        try
        {
            supplier.authority("cmp-1"); //$NON-NLS-1$
            fail("a failed resolution must reach the caller for a named comparison too"); //$NON-NLS-1$
        }
        catch (IllegalStateException e)
        {
            assertEquals("the comparison tree could not be polled", e.getMessage()); //$NON-NLS-1$
        }
    }

    @Test
    public void testAResolutionThatFailsWithNoComparisonNamedRefusesAndWritesNothing()
    {
        // End to end through the shipped supplier's own class: the caller named no comparison,
        // one was there to check against, the check broke - and the file must NOT be written up
        // as NOT VALIDATED, which is a report that nobody had checked it. Nothing was checked and
        // nothing may be left behind.
        Path target = file("r.xml"); //$NON-NLS-1$
        MergeRulesTool tool = new MergeRulesTool(new EngineRuleAuthority(id -> {
            throw new IllegalStateException("the comparison tree could not be polled"); //$NON-NLS-1$
        }));

        String result = tool.execute(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, "the running comparison", //$NON-NLS-1$
            "the comparison tree could not be polled", "neither checked nor"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a check that failed must leave no file behind, least of all one stamped " //$NON-NLS-1$
            + "NOT VALIDATED", Files.exists(target)); //$NON-NLS-1$
    }

    @Test
    public void testAResolutionThatAnswersNothingStillWritesTheNotValidatedFile()
    {
        // The other side of the same split, proved through the same class: an ABSENT comparison
        // still degrades honestly. A fix that turned every empty answer into a refusal would have
        // taken the tool's whole no-comparison mode away.
        Path target = file("degraded.xml"); //$NON-NLS-1$
        MergeRulesTool tool = new MergeRulesTool(new EngineRuleAuthority(id -> Optional.empty()));

        String result = tool.execute(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("an absent comparison must still author the file: " + result, //$NON-NLS-1$
            result.contains("NOT VALIDATED")); //$NON-NLS-1$
        assertTrue("and the file must actually be there", Files.exists(target)); //$NON-NLS-1$
    }

    @Test
    public void testAFailedCheckDoesNotTellTheCallerToDropAParameterTheyNeverSent()
    {
        // The advice used to end "or omit comparisonId to author the file from names alone". For
        // the caller who never named one - the branch this refusal only started reaching once the
        // production supplier stopped swallowing its failures - that is a no-op dressed as a way
        // out. It is also wrong for the caller who did name one: dropping the id lands on the
        // same running comparison and fails identically.
        MergeRulesTool tool = new MergeRulesTool(new EngineRuleAuthority(id -> {
            throw new IllegalStateException("the comparison tree could not be polled"); //$NON-NLS-1$
        }));

        String result = tool.execute(params("mode", "write", "filePath", file("r.xml").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, "Retry once the comparison answers", "get_comparison_node"); //$NON-NLS-1$ //$NON-NLS-2$
        // The literal, not the constant: what is pinned is the sentence that reaches the caller.
        assertFalse("the refusal must not offer dropping the parameter as the way out: " + result, //$NON-NLS-1$
            result.contains("omit comparisonId")); //$NON-NLS-1$
    }

    // ======== NOT VALIDATED is TWO states, and no sentence may collapse it to one ========
    //
    // EngineRuleAuthority#resolve reads ComparisonSessionRegistry#activeComparisonId whenever the
    // caller named no id, so an omitted comparisonId does NOT mean "no comparison": it means
    // "whichever one is running". And the write reports NOT VALIDATED in two different states -
    // comparison.isEmpty(), and a comparison whose one reading is not RuleSnapshot#checked(). Every
    // sentence that named one of the two as the whole of it described a mode the tool lacks.
    //
    // One literal per @Test: JUnit abandons a method at its first failed assertion, so a single
    // method carrying them all would only ever hold the first one down.

    @Test
    public void testTheFailedCheckRefusalDoesNotSayNotValidatedNeedsNoComparisonAtAll()
    {
        MergeRulesTool tool = new MergeRulesTool(new EngineRuleAuthority(id -> {
            throw new IllegalStateException("the comparison tree could not be polled"); //$NON-NLS-1$
        }));

        String result = tool.execute(params("mode", "write", "filePath", file("r.xml").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse("an unreadable tree also reports NOT VALIDATED, so naming the absence of a " //$NON-NLS-1$
            + "comparison as the only state it covers is false: " + result, //$NON-NLS-1$
            result.contains("no comparison answers at all")); //$NON-NLS-1$
    }

    @Test
    public void testTheFailedCheckRefusalNamesBothNotValidatedStates()
    {
        // The positive half. Without it the pin above would pass on a refusal that had simply
        // stopped mentioning NOT VALIDATED, leaving the caller with no account of it at all.
        MergeRulesTool tool = new MergeRulesTool(new EngineRuleAuthority(id -> {
            throw new IllegalStateException("the comparison tree could not be polled"); //$NON-NLS-1$
        }));

        String result = tool.execute(params("mode", "write", "filePath", file("r.xml").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the count itself is the fact: " + result, //$NON-NLS-1$
            result.contains("written in two")); //$NON-NLS-1$
        assertTrue("state one - nothing answered: " + result, //$NON-NLS-1$
            result.contains("nothing answered at all")); //$NON-NLS-1$
        assertTrue("state two - answered, tree unreadable: " + result, //$NON-NLS-1$
            result.contains("a comparison answered while its tree could not be read")); //$NON-NLS-1$
    }

    @Test
    public void testTheFailedCheckRefusalSaysAnOmittedIdResolvesTheRunningComparison()
    {
        MergeRulesTool tool = new MergeRulesTool(new EngineRuleAuthority(id -> {
            throw new IllegalStateException("the comparison tree could not be polled"); //$NON-NLS-1$
        }));

        String result = tool.execute(params("mode", "write", "filePath", file("r.xml").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("resolve(null) asks the registry for the ACTIVE comparison, and the refusal " //$NON-NLS-1$
            + "has to say so: " + result, //$NON-NLS-1$
            result.contains("resolves whichever comparison is RUNNING")); //$NON-NLS-1$
    }

    @Test
    public void testTheUnreadableTreeRefusalDoesNotOfferAuthoringFromNamesAlone()
    {
        // Dropping the id here lands on the SAME comparison through activeComparisonId - the
        // write proceeds unvalidated, it does not switch to a names-only mode.
        MergeRulesTool tool = new MergeRulesTool(id -> Optional.of(addressOnly("cmp-7"))); //$NON-NLS-1$

        String result = tool.execute(params("mode", "write", "filePath", file("r.zip").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "comparisonId", "cmp-7", //$NON-NLS-1$ //$NON-NLS-2$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse("omitting the id does not author from names alone: " + result, //$NON-NLS-1$
            result.contains("to author the file from names alone")); //$NON-NLS-1$
    }

    @Test
    public void testTheUnreadableTreeRefusalSaysWhatOmittingTheIdActuallyDoes()
    {
        MergeRulesTool tool = new MergeRulesTool(id -> Optional.of(addressOnly("cmp-7"))); //$NON-NLS-1$

        String result = tool.execute(params("mode", "write", "filePath", file("r.zip").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "comparisonId", "cmp-7", //$NON-NLS-1$ //$NON-NLS-2$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("it writes without validation against the RUNNING comparison: " + result, //$NON-NLS-1$
            result.contains("resolves whichever comparison is RUNNING")); //$NON-NLS-1$
    }

    @Test
    public void testTheMissingIdRefusalDoesNotOfferAuthoringFromNamesAlone()
    {
        // Here the named id answered nothing - but another comparison may hold EDT's single slot,
        // and dropping the id resolves THAT one. "From names alone" is a mode the caller cannot
        // reach by removing a parameter.
        MergeRulesTool tool = new MergeRulesTool(id -> Optional.empty());

        String result = tool.execute(params("mode", "write", "filePath", file("r.xml").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "comparisonId", "cmp-gone", //$NON-NLS-1$ //$NON-NLS-2$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse("dropping the id is not a switch into a names-only mode: " + result, //$NON-NLS-1$
            result.contains("to author the file from names alone")); //$NON-NLS-1$
    }

    @Test
    public void testTheMissingIdRefusalSaysAnOmittedIdMayLandOnAnotherComparison()
    {
        MergeRulesTool tool = new MergeRulesTool(id -> Optional.empty());

        String result = tool.execute(params("mode", "write", "filePath", file("r.xml").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "comparisonId", "cmp-gone", //$NON-NLS-1$ //$NON-NLS-2$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the id the registry answers with need not be the one that just failed: " //$NON-NLS-1$
            + result, result.contains("may be a different one")); //$NON-NLS-1$
    }

    // ======== the zip entry is addressed by the ENTRY name, not by the file name ========

    @Test
    public void testTheZipRefusalDoesNotPutTheAddressOnTheFileName()
    {
        // EDT reads the ENTRY name; the archive's own file name never reaches the comparison. The
        // refusal said EDT "IGNORES an archive named anything else", which sends a caller to
        // rename the file - a change that cannot affect the outcome.
        String result = call(params("mode", "write", "filePath", file("r.zip").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse("the archive's own name is not what EDT matches: " + result, //$NON-NLS-1$
            result.contains("IGNORES an archive named anything else")); //$NON-NLS-1$
    }

    @Test
    public void testTheZipRefusalPutsTheAddressOnTheEntryAndFreesTheFileName()
    {
        String result = call(params("mode", "write", "filePath", file("r.zip").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("what is matched is the ENTRY: " + result, //$NON-NLS-1$
            result.contains("IGNORES an archive whose ENTRY is named anything else")); //$NON-NLS-1$
        assertTrue("and the file name is explicitly freed, so nobody renames it hoping: " //$NON-NLS-1$
            + result, result.contains("the name of the FILE itself is yours to choose")); //$NON-NLS-1$
    }

    @Test
    public void testTheZipRefusalDoesNotPromiseCheckedRulesWithoutAFinishedTree()
    {
        // "re-send this write (naming it with comparisonId if you want the rules checked too)"
        // promised a checked file for an act that is REFUSED while the tree is still building.
        String result = call(params("mode", "write", "filePath", file("r.zip").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse("naming the id is not on its own enough to get the rules checked: " + result, //$NON-NLS-1$
            result.contains("if you want the rules checked too")); //$NON-NLS-1$
        assertTrue("the condition the check actually has is named: " + result, //$NON-NLS-1$
            result.contains("needs its tree to have FINISHED")); //$NON-NLS-1$
    }

    // ======== the address-only report claims an address only where one exists ========

    @Test
    public void testTheAddressOnlyReportOfAnXmlDoesNotClaimItNamedTheFilesAddress()
    {
        // An '.xml' document carries no address - the Container line in the very same report says
        // so, and that is what lets any comparison read it. zipEntryId is null here, so the
        // sentence about a named address had nothing to name.
        Path target = file("degraded.xml"); //$NON-NLS-1$
        MergeRulesTool tool = new MergeRulesTool(id -> Optional.of(addressOnly("cmp-7"))); //$NON-NLS-1$

        String result = tool.execute(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the write still happens, unvalidated: " + result, //$NON-NLS-1$
            result.contains("NOT VALIDATED")); //$NON-NLS-1$
        assertFalse("an xml has no address for the comparison to have named: " + result, //$NON-NLS-1$
            result.contains("It named this file's")); //$NON-NLS-1$
    }

    @Test
    public void testTheAddressOnlyReportOfAnXmlDoesNotContradictItsOwnContainerLine()
    {
        Path target = file("degraded2.xml"); //$NON-NLS-1$
        MergeRulesTool tool = new MergeRulesTool(id -> Optional.of(addressOnly("cmp-7"))); //$NON-NLS-1$

        String result = tool.execute(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the container line says the document carries no address: " + result, //$NON-NLS-1$
            result.contains("the document carries no")); //$NON-NLS-1$
        assertTrue("so the validation line says what was really missing: " + result, //$NON-NLS-1$
            result.contains("Not one rule was checked against it")); //$NON-NLS-1$
    }

    @Test
    public void testTheAddressOnlyReportOfAZipStillSaysItGotAnEntry()
    {
        // The positive control that keeps the two pins above from passing on a report that had
        // simply dropped the sentence: a zip DOES get an address, and still says so.
        Path target = file("addressed.zip"); //$NON-NLS-1$
        MergeRulesTool tool = new MergeRulesTool(id -> Optional.of(addressOnly("cmp-7"))); //$NON-NLS-1$

        String result = tool.execute(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("a zip is addressed by the comparison that answered: " + result, //$NON-NLS-1$
            result.contains("It named this file's entry, and nothing else")); //$NON-NLS-1$
    }

    // ======== every promise of checked rules names the FINISHED tree it needs ========

    @Test
    public void testTheNamesOnlyReportDoesNotPromiseChecksFromAMerelyStartedComparison()
    {
        Path target = file("names.xml"); //$NON-NLS-1$
        MergeRulesTool tool = new MergeRulesTool(id -> Optional.empty());

        String result = tool.execute(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse("starting a comparison is not enough - its tree has to finish: " + result, //$NON-NLS-1$
            result.contains("Start one with compare_configurations and re-run this write")); //$NON-NLS-1$
        assertTrue("the wait is part of the instruction: " + result, //$NON-NLS-1$
            result.contains("wait until get_comparison_node reports its tree finished")); //$NON-NLS-1$
    }

    @Test
    public void testTheGuideExamplesDoNotOfferValidationWithoutAFinishedTree()
    {
        // The guide BODY already carries the three outcomes and the wait; its Examples section did
        // not, and an example is what a caller copies. The class javadoc's two-run recipe had the
        // same gap, and a source scan is the only instrument that reaches a javadoc.
        String guide = new MergeRulesTool().getGuide();

        assertFalse("the example must not offer validation as a bare add-the-id step: " + guide, //$NON-NLS-1$
            guide.contains("- Validate against a running comparison: add")); //$NON-NLS-1$
        assertTrue("it names the state validation needs: " + guide, //$NON-NLS-1$
            guide.contains("Validate against a running comparison whose tree has FINISHED")); //$NON-NLS-1$
    }

    @Test
    public void testTheClassJavadocTwoRunRecipeWaitsForAFinishedTree() throws IOException
    {
        String source = new String(Files.readAllBytes(sourceFile("tools/impl/MergeRulesTool.java")), //$NON-NLS-1$
            StandardCharsets.UTF_8);

        assertFalse("the recipe must not go from 'start a comparison' straight to its id", //$NON-NLS-1$
            source.contains("start a comparison, take its id")); //$NON-NLS-1$
        assertTrue("it has to name the wait that stands between them", //$NON-NLS-1$
            source.contains("WAIT until its tree")); //$NON-NLS-1$
    }

    // ==================== a key chain addresses the node the platform keys the same way ====================

    @Test
    public void testAKeyChainResolvesToTheNodeThePlatformKeysTheSameWay()
    {
        ComparisonNode module = topNode("CommonModule.Alpha", "CommonModule.Beta", //$NON-NLS-1$ //$NON-NLS-2$
            "CommonModule.Gamma"); //$NON-NLS-1$
        ComparisonNode collection = plainNode();
        withChildren(collection, module);
        ComparisonNode root = plainNode();
        withChildren(root, collection);

        List<String> chain = List.of("commonModules", "Alpha:Beta:Gamma"); //$NON-NLS-1$ //$NON-NLS-2$
        assertSame(module,
            MergeRulesTool.findNodes(root, List.of(chain), node -> "commonModules").get(chain)); //$NON-NLS-1$
    }

    @Test
    public void testATopObjectIsKeyedByTheNameOnEachSideWithNONEForAnAbsentOne()
    {
        // What TopNodePathGenerator writes: the LAST segment of each side's symlink, 'NONE' for a
        // side that has no such object. A key built from the whole symlink would match nothing.
        ComparisonNode added = topNode("Catalog.Added", null, "Catalog.Added"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("Added:NONE:Added", MergeRulesTool.serializedKey(added, node -> null)); //$NON-NLS-1$
    }

    @Test
    public void testACollectionElementIsKeyedByItsPositionNotByAFeatureName()
    {
        CollectionElementComparisonNode element = mock(CollectionElementComparisonNode.class);
        when(element.getPositionAfterMerge()).thenReturn(7);

        assertEquals("7", MergeRulesTool.serializedKey(element, node -> "commonModules")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAKeyNoChildCarriesResolvesToNothing()
    {
        ComparisonNode root = plainNode();
        withChildren(root, plainNode());

        assertNull(MergeRulesTool.findNodes(root, List.of(List.of("catalogs")), //$NON-NLS-1$
            node -> "commonModules").get(List.of("catalogs"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ============ a node with no choice on it is an ANSWER, not a missing node ============
    //
    // The live authority used to return "no answer" for BOTH a key chain that resolved to nothing
    // and a node that resolved fine but carried no MergeSettings. The caller renders the first as
    // "Node 'x' is not in comparison 'y'" - which, said of the second, denies a node the caller
    // can see in get_comparison_node, and sends them looking for a key that is already correct.
    // The tool has always had the right sentence for it ("offers no merge rule on node 'x'"); it
    // was unreachable from a live comparison because both facts arrived as the same empty answer.

    @Test
    public void testANodeThatOffersNoRuleIsAnAnswerRatherThanAMissingNode()
    {
        assertEquals("a node the tree HAS, offering nothing, is an empty ALLOWED SET - the fact " //$NON-NLS-1$
            + "the caller renders as 'offers no merge rule on node'", List.of(), //$NON-NLS-1$
            MergeRulesTool.allowedRulesOf(plainNode(), List.of()));
    }

    @Test
    public void testOnlyAKeyChainThatResolvesToNothingIsAMissingAnswer()
    {
        assertNull("no node is the ONE fact that renders as 'is not in comparison'", //$NON-NLS-1$
            MergeRulesTool.allowedRulesOf(null, List.of()));
    }

    @Test
    public void testTheRulesANodeOffersAreCarriedThroughAsPlatformLiterals()
    {
        assertEquals(List.of(MergeRule.GET_FROM_OTHER.getLiteral(),
            MergeRule.DO_NOT_MERGE.getLiteral()),
            MergeRulesTool.allowedRulesOf(plainNode(),
                List.of(MergeRule.GET_FROM_OTHER, MergeRule.DO_NOT_MERGE)));
    }

    // ==================== the collection key is the model feature name ====================

    @Test
    public void testACollectionAddressedByTheEnglishSingularLandsOnTheFeatureName() throws IOException
    {
        assertCollectionKeyWritten("Catalog", "catalogs", "en-singular.xml"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testACollectionAddressedByTheEnglishPluralLandsOnTheFeatureName() throws IOException
    {
        assertCollectionKeyWritten("Catalogs", "catalogs", "en-plural.xml"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testACollectionAddressedByTheRussianSingularLandsOnTheFeatureName() throws IOException
    {
        assertCollectionKeyWritten(CATALOG_RU, "catalogs", "ru-singular.xml"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testACollectionAddressedByTheRussianPluralLandsOnTheFeatureName() throws IOException
    {
        assertCollectionKeyWritten(CATALOGS_RU, "catalogs", "ru-plural.xml"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAFeatureNameIsWrittenExactlyAsSent() throws IOException
    {
        // The control: canonicalisation must not disturb the spelling the platform itself uses.
        assertCollectionKeyWritten("commonModules", "commonModules", "feature-name.xml"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testAKeyOutsideTheMetadataTypeTableIsWrittenAsSent() throws IOException
    {
        // Deliberate: the legal keys are the platform's whole feature catalogue, which includes
        // features that are not metadata types. Refusing what the type table cannot resolve would
        // reject correct input; whether the node exists is answered by a live comparison.
        assertCollectionKeyWritten("version", "version", "plain-feature.xml"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    // ==================== helpers ====================

    private void assertCollectionKeyWritten(String addressed, String expectedKey, String fileName)
        throws IOException
    {
        Path target = file(fileName);
        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[\"" + addressed + "\"],\"rule\":\"GetFromOther\"}]")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(result, result.startsWith("# Merge rules written:")); //$NON-NLS-1$
        String written = read(target);
        assertTrue("addressing the collection as '" + addressed + "' must be recorded under the " //$NON-NLS-1$ //$NON-NLS-2$
            + "model feature name '" + expectedKey + "' - the key EDT's reader matches: " + written, //$NON-NLS-1$ //$NON-NLS-2$
            written.contains("<Node Key=\"" + expectedKey + "\" MergeRule=\"GetFromOther\"/>")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static ComparisonNode plainNode()
    {
        return mock(ComparisonNode.class);
    }

    private static ComparisonNode topNode(String main, String other, String ancestor)
    {
        TopComparisonNode node = mock(TopComparisonNode.class);
        when(node.getMainSymlink()).thenReturn(main);
        when(node.getOtherSymlink()).thenReturn(other);
        when(node.getCommonAncestorSymlink()).thenReturn(ancestor);
        return node;
    }

    private static void withChildren(ComparisonNode parent, ComparisonNode... children)
    {
        EList<ComparisonNode> list = new BasicEList<>();
        list.addAll(List.of(children));
        when(parent.<ComparisonNode> getChildren()).thenReturn(list);
    }

    /**
     * An authority that records what the pass did with it: how many nodes it asked about, whether
     * any of them were asked AFTER the hold was given back, and how many times it was released.
     */
    private static final class RecordingAuthority
        implements MergeRuleAuthority
    {
        private final String id;
        private final List<String> allowed;
        /** Every address the pass handed over, so a test can see it was ONE batch and not many. */
        final List<List<String>> asked = new ArrayList<>();
        int reads;
        int readsAfterClose;
        int closes;
        boolean explode;

        RecordingAuthority(String id, List<String> allowed)
        {
            this.id = id;
            this.allowed = allowed;
        }

        @Override
        public String comparisonId()
        {
            return id;
        }

        @Override
        public String mergeRulesEntryId()
        {
            return ENTRY_ID;
        }

        @Override
        public RuleSnapshot rulesFor(Collection<List<String>> nodePaths)
        {
            reads++;
            asked.addAll(nodePaths);
            if (closes > 0)
            {
                readsAfterClose++;
            }
            if (explode)
            {
                throw new IllegalStateException("the comparison store is closed"); //$NON-NLS-1$
            }
            Map<List<String>, List<String>> answer = new LinkedHashMap<>();
            for (List<String> path : nodePaths)
            {
                answer.put(path, allowed);
            }
            return RuleSnapshot.of(answer);
        }

        @Override
        public void close()
        {
            closes++;
        }
    }

    /**
     * A comparison that names itself and cannot answer about rules - the shape the production
     * supplier hands back while the tree is still being built, or while EDT's comparison service
     * is momentarily away.
     *
     * @param id the comparison id
     * @return the authority
     */
    private static MergeRuleAuthority addressOnly(String id)
    {
        return new MergeRuleAuthority()
        {
            @Override
            public String comparisonId()
            {
                return id;
            }

            @Override
            public String mergeRulesEntryId()
            {
                return ENTRY_ID;
            }

            @Override
            public RuleSnapshot rulesFor(Collection<List<String>> nodePaths)
            {
                // The reading that carries no verdict: this comparison exists and its tree could
                // not be read, so nothing here refuses a decision and nothing here accepts one.
                return RuleSnapshot.unreadable();
            }
        };
    }

    private static MergeRuleAuthority authority(String id, List<String> allowed)
    {
        return new MergeRuleAuthority()
        {
            @Override
            public String comparisonId()
            {
                return id;
            }

            @Override
            public String mergeRulesEntryId()
            {
                return ENTRY_ID;
            }

            @Override
            public RuleSnapshot rulesFor(Collection<List<String>> nodePaths)
            {
                Map<List<String>, List<String>> answer = new LinkedHashMap<>();
                for (List<String> path : nodePaths)
                {
                    answer.put(path, allowed);
                }
                return RuleSnapshot.of(answer);
            }
        };
    }

    /**
     * Every entry name in an archive, in the order its directory lists them.
     *
     * @param zip the archive
     * @return the names
     * @throws IOException when the archive cannot be read
     */
    private static List<String> zipEntryNames(Path zip) throws IOException
    {
        List<String> names = new ArrayList<>();
        try (ZipFile file = new ZipFile(zip.toFile()))
        {
            Enumeration<? extends ZipEntry> entries = file.entries();
            while (entries.hasMoreElements())
            {
                names.add(entries.nextElement().getName());
            }
        }
        return names;
    }

    private void assertRefusedRule(String rule)
    {
        Path target = file("r.xml"); //$NON-NLS-1$
        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[],\"rule\":\"" + rule + "\"}]")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertErrorNaming(result, rule, "GetFromOther"); //$NON-NLS-1$
        assertFalse(Files.exists(target));
    }


    // ============ 'path' is required: the widest rule is never an accident ============

    @Test
    public void testADecisionWithNoPathIsRefusedInsteadOfRulingTheWholeConfiguration()
        throws IOException
    {
        Path target = file("rules.xml"); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        // An absent chain used to be the SAME chain an explicit [] produces, so a decision meant
        // for one object silently became a rule over everything - which the report then presented
        // as a root decision, as if it had been asked for.
        assertErrorNaming(result, "#1", "path"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a refused call must leave no file behind", Files.exists(target)); //$NON-NLS-1$
    }

    @Test
    public void testAMisspelledPathFieldIsRefusedRatherThanTreatedAsTheRoot() throws IOException
    {
        // The scenario the refusal exists for: the caller aimed at one object and mistyped the
        // field name. Nothing about 'paths' says "the whole configuration".
        Path target = file("rules.xml"); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", //$NON-NLS-1$
            "[{\"paths\":[\"commonModules\"],\"rule\":\"GetFromOther\"}]")); //$NON-NLS-1$

        assertErrorNaming(result, "#1", "path"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a refused call must leave no file behind", Files.exists(target)); //$NON-NLS-1$
    }

    @Test
    public void testANullPathIsRefusedToo() throws IOException
    {
        Path target = file("rules.xml"); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":null,\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, "#1", "path"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a refused call must leave no file behind", Files.exists(target)); //$NON-NLS-1$
    }

    @Test
    public void testTheRefusalForAMissingPathNamesTheExplicitEmptyArray() throws IOException
    {
        String result = call(params("mode", "write", "filePath", file("rules.xml").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "decisions", "[{\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        // The refusal has to hand back the call that WOULD have meant what the tool guessed
        // before, or a caller who really wanted the whole configuration has nothing to do next.
        assertErrorNaming(result, "\"path\": []"); //$NON-NLS-1$
    }

    @Test
    public void testTheSecondDecisionIsRefusedByItsOwnPosition() throws IOException
    {
        Path target = file("rules.xml"); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", //$NON-NLS-1$
            "[{\"path\":[\"commonModules\"],\"rule\":\"GetFromOther\"},{\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$

        assertErrorNaming(result, "#2"); //$NON-NLS-1$
        assertFalse("nothing is written until every decision has passed", Files.exists(target)); //$NON-NLS-1$
    }

    @Test
    public void testAnExplicitEmptyPathStillAddressesTheWholeConfiguration() throws IOException
    {
        // The control: [] is the ONE way to say "everything", and it must keep working, or the
        // refusal above would have removed the capability instead of making it deliberate.
        Path target = file("rules.xml"); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("an explicit [] is a decision the caller made: " + result, //$NON-NLS-1$
            result.startsWith("# Merge rules written:")); //$NON-NLS-1$
        assertTrue("and it lands on the root node", //$NON-NLS-1$
            read(target).contains("<Node Key=\"$$Root$$\" MergeRule=\"DoNotMerge\"/>")); //$NON-NLS-1$
    }

    // ============ A same-path rewrite may not detach two names of one file ============

    @Test
    public void testAHardLinkedTargetIsRefusedInsteadOfDetachingTheTwoNames() throws IOException
    {
        Path base = seedFixture();
        Path target = file("hard-link.xml"); //$NON-NLS-1$
        try
        {
            Files.createLink(target, base);
        }
        catch (IOException | UnsupportedOperationException e)
        {
            org.junit.Assume.assumeNoException("this filesystem has no hard links", e); //$NON-NLS-1$
        }

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "basedOn", base.toString(), //$NON-NLS-1$
            "decisions", "[{\"path\":[\"catalogs\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        // The identity check accepts them - they ARE one file - but the write replaces a directory
        // entry rather than the content behind it, so afterwards they would be two files while the
        // report called them one file rewritten.
        assertErrorNaming(result, "hard links", target.toRealPath().toString(), //$NON-NLS-1$
            base.toRealPath().toString());
        assertEquals("nothing may be written", FIXTURE, read(base)); //$NON-NLS-1$
        assertEquals("nothing may be written", FIXTURE, read(target)); //$NON-NLS-1$
        assertTrue("the two names must still be one file", Files.isSameFile(target, base)); //$NON-NLS-1$
    }

    @Test
    public void testTheHardLinkRefusalSaysWhatToSendInstead() throws IOException
    {
        Path base = seedFixture();
        Path target = file("hard-link.xml"); //$NON-NLS-1$
        try
        {
            Files.createLink(target, base);
        }
        catch (IOException | UnsupportedOperationException e)
        {
            org.junit.Assume.assumeNoException("this filesystem has no hard links", e); //$NON-NLS-1$
        }

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "basedOn", base.toString(), //$NON-NLS-1$
            "decisions", "[{\"path\":[\"catalogs\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, "Pass the SAME path"); //$NON-NLS-1$
    }

    @Test
    public void testRewritingOneFileAtItsOwnPathUnderOneNameStillWorks() throws IOException
    {
        // The control: the refusal above may not catch an ordinary same-path rewrite, which is
        // the only way this tool edits an existing file at all.
        Path file = seedFixture();

        String result = call(params("mode", "write", "filePath", file.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "basedOn", file.toString(), //$NON-NLS-1$
            "decisions", "[{\"path\":[\"catalogs\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("a same-path rewrite is the point of basedOn: " + result, //$NON-NLS-1$
            result.startsWith("# Merge rules written:")); //$NON-NLS-1$
        assertTrue("the decision carried in must still be there", //$NON-NLS-1$
            read(file).contains("Key=\"Alpha:Beta:Gamma\"")); //$NON-NLS-1$
        assertTrue("and the new one added", read(file).contains("Key=\"catalogs\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ============ A key is TEXT, and a scalar that is not text is not a key ============

    @Test
    public void testABooleanKeyIsRefusedInsteadOfBecomingTheKeyTrue() throws IOException
    {
        // Every JSON scalar has a string form, so accepting any primitive wrote Key="true" and
        // reported it as recorded - while EDT matches nodes by name and has none called that.
        Path target = file("rules.xml"); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[true],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, "#1", "key #1", "not a string"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse("a refused call must leave no file behind", Files.exists(target)); //$NON-NLS-1$
    }

    @Test
    public void testTheRefusalNamesTheOffendingKeyByItsPosition() throws IOException
    {
        // Which key it was, like every other malformed decision this tool refuses by position -
        // otherwise a caller with a long chain is told only that one of them is wrong.
        Path target = file("rules.xml"); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[\"commonModules\",false],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, "#1", "key #2", "not a string", "false"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertFalse("a refused call must leave no file behind", Files.exists(target)); //$NON-NLS-1$
    }

    @Test
    public void testANumericKeyIsRefusedAsANonStringAndNotQuietlyStringified() throws IOException
    {
        // A number reads as a computed POSITION once it has been turned into text, which is a
        // different complaint about a different thing: the caller never sent a key at all.
        Path target = file("rules.xml"); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[7],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, "key #1", "not a string"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a refused call must leave no file behind", Files.exists(target)); //$NON-NLS-1$
    }

    @Test
    public void testAQuotedKeyThatLooksLikeAScalarIsStillAccepted() throws IOException
    {
        // The control: the check is on the JSON TYPE, not on what the text looks like. A feature
        // whose name a caller quoted is a key like any other.
        Path target = file("rules.xml"); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[\"commonModules\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("a string key must still be written: " + result, //$NON-NLS-1$
            result.startsWith("# Merge rules written:")); //$NON-NLS-1$
        assertTrue(read(target).contains("<Node Key=\"commonModules\" MergeRule=\"DoNotMerge\"/>")); //$NON-NLS-1$
    }

    @Test
    public void testABlankKeyIsStillRefusedAndSaysWhichOne() throws IOException
    {
        Path target = file("rules.xml"); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[\"commonModules\",\"  \"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, "key #2", "blank"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a refused call must leave no file behind", Files.exists(target)); //$NON-NLS-1$
    }

    // ==== A key must hold characters XML can actually carry ====
    //
    // A control character is not blank, is a JSON string, is not a position key and is not an
    // object key, so a segment holding one passed every check on the way and was written into the
    // file as it stood. Nothing escapes it - XML 1.0 has no spelling for it at all - so what
    // landed on disk was a file EDT's reader refuses outright: the caller lost the whole rules
    // file, and this tool had reported it as written.

    @Test
    public void testAKeyHoldingACharacterXmlCannotCarryIsRefusedByPosition() throws IOException
    {
        Path target = file("rules.xml"); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[\"com\\u0001monModules\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, "#1", "key #1", "U+0001", "character 4"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertFalse("a refused call must leave no file behind", Files.exists(target)); //$NON-NLS-1$
    }

    @Test
    public void testTheRefusalNamesTheCharacterByCodeInsteadOfEchoingIt() throws IOException
    {
        // The refusal travels back as JSON through the same channel the offending character would
        // have broken, so echoing it would carry the problem into the answer about it.
        Path target = file("rules.xml"); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[\"com\\u0001monModules\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        // Both halves in one test on purpose: "does not contain the character" is satisfied by
        // any successful report too, so without the refusal beside it the assertion is vacuous.
        assertErrorNaming(result, "U+0001"); //$NON-NLS-1$
        assertFalse("the refusal must not carry the character it is complaining about", //$NON-NLS-1$
            result.contains(CONTROL_CHARACTER));
    }

    @Test
    public void testAKeyThatIsNothingButAControlCharacterIsRefusedRatherThanTrimmedAway()
        throws IOException
    {
        // It is not BLANK - Character.isWhitespace says no to U+0001 - and trim() deletes it all
        // the same, because trim cuts everything below U+0020. So the key that used to reach the
        // file was the EMPTY one: never sent by the caller, never matched by EDT.
        Path target = file("rules.xml"); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[\"\\u0001\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, "key #1", "U+0001"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a refused call must leave no file behind", Files.exists(target)); //$NON-NLS-1$
    }

    @Test
    public void testALoneSurrogateKeyIsRefused() throws IOException
    {
        // Half of a pair is not a character at all: XML's Char production excludes the surrogate
        // block, and a writer handed one produces bytes no reader accepts.
        Path target = file("rules.xml"); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[\"A\\ud83Db\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, "key #1", "U+D83D", "character 2"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse("a refused call must leave no file behind", Files.exists(target)); //$NON-NLS-1$
    }

    @Test
    public void testAWellFormedSurrogatePairIsStillAccepted() throws IOException
    {
        // The control that keeps the rule honest: it is the XML Char production, not "printable
        // ASCII". A pair is ONE code point above U+FFFF, which XML carries, so a name written
        // with one has to go through - refusing it would be a rule this tool invented.
        Path target = file("rules.xml"); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[\"catalogs\",\"A\\ud83d\\ude00:A\\ud83d\\ude00:A\\ud83d\\ude00\"]," //$NON-NLS-1$ //$NON-NLS-2$
                + "\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$

        assertTrue("a code point above U+FFFF is legal XML and must be written: " + result, //$NON-NLS-1$
            result.startsWith("# Merge rules written:")); //$NON-NLS-1$
        assertTrue("and it must reach the file as itself", //$NON-NLS-1$
            read(target).contains(ASTRAL_KEY));
    }

    @Test
    public void testATabInsideAKeyIsStillAcceptedBecauseXmlCarriesIt() throws IOException
    {
        // The second control: tab, newline and carriage return are the three control characters
        // XML 1.0 does allow, and the writer already has an escape for each. A check that refused
        // everything below U+0020 would reject them, which is a different rule from the one the
        // format actually has.
        Path target = file("rules.xml"); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[\"a\\tb\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("a tab is a legal XML character: " + result, //$NON-NLS-1$
            result.startsWith("# Merge rules written:")); //$NON-NLS-1$
        assertTrue("and the writer escapes it rather than dropping it", //$NON-NLS-1$
            read(target).contains("Key=\"a&#9;b\"")); //$NON-NLS-1$
    }

    // ============ Two decisions on one node are two answers to one question ============

    @Test
    public void testTwoDecisionsOnTheSamePathAreRefusedByPosition() throws IOException
    {
        // The tree is keyed by path, so the second decision simply overwrites the node the first
        // one set: ONE rule reaches the file while the report counts what the CALL carried and
        // says two were recorded. This tool's contract runs the other way round.
        Path target = file("rules.xml"); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[\"commonModules\"],\"rule\":\"GetFromOther\"}," //$NON-NLS-1$ //$NON-NLS-2$
                + "{\"path\":[\"commonModules\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$

        assertErrorNaming(result, "#1", "#2", "commonModules"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse("a refused call must leave no file behind", Files.exists(target)); //$NON-NLS-1$
    }

    @Test
    public void testTheDuplicateRefusalNamesTheNormalisedPathAndNotTheRawInput() throws IOException
    {
        // Two spellings of ONE collection - the metadata type token and the model feature name -
        // are the same node, and the refusal has to show the address that made them collide.
        Path target = file("rules.xml"); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[\"Catalog\"],\"rule\":\"GetFromOther\"}," //$NON-NLS-1$ //$NON-NLS-2$
                + "{\"path\":[\"catalogs\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$

        assertErrorNaming(result, "#1", "#2", "$$Root$$ / catalogs"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse("a refused call must leave no file behind", Files.exists(target)); //$NON-NLS-1$
    }

    @Test
    public void testTwoDecisionsOnDifferentNodesAreStillWritten() throws IOException
    {
        // The control: the refusal is about ONE node addressed twice, not about two decisions.
        Path target = file("rules.xml"); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[\"commonModules\"],\"rule\":\"GetFromOther\"}," //$NON-NLS-1$ //$NON-NLS-2$
                + "{\"path\":[\"catalogs\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$

        assertTrue(result, result.startsWith("# Merge rules written:")); //$NON-NLS-1$
        String written = read(target);
        assertTrue(written, written.contains("Key=\"commonModules\" MergeRule=\"GetFromOther\"")); //$NON-NLS-1$
        assertTrue(written, written.contains("Key=\"catalogs\" MergeRule=\"DoNotMerge\"")); //$NON-NLS-1$
    }

    // ============ Two colons are the SHAPE of an object key, not the proof ============

    @Test
    public void testAnObjectKeyWithAnEmptyMiddleSideIsRefused() throws IOException
    {
        // 'A::A' carries exactly two separators, so a count-only check passed it. The middle part
        // is not a name and not NONE - it is nothing, and EDT matches these keys by string
        // equality, so the decision would be recorded and could never be applied.
        Path target = file("rules.xml"); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", "[{\"path\":[\"commonModules\",\"A::A\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, "#1", "A::A", "the other side is empty", "NONE"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertFalse("a refused call must leave no file behind", Files.exists(target)); //$NON-NLS-1$
    }

    @Test
    public void testAnObjectKeyWithAnEmptyMainSideIsRefusedNamingThatSide()
    {
        String result = call(params("mode", "write", "filePath", file("r.xml").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "decisions", "[{\"path\":[\"commonModules\",\":A:A\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, "the main side is empty"); //$NON-NLS-1$
    }

    @Test
    public void testAnObjectKeyWithAnEmptyAncestorSideIsRefusedNamingThatSide()
    {
        String result = call(params("mode", "write", "filePath", file("r.xml").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "decisions", "[{\"path\":[\"commonModules\",\"A:A: \"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        // Whitespace is not a name either: EDT would look for a node called " " and find none.
        assertErrorNaming(result, "the ancestor side is empty"); //$NON-NLS-1$
    }

    @Test
    public void testAnObjectKeyWithTwoEmptySidesNamesBoth()
    {
        String result = call(params("mode", "write", "filePath", file("r.xml").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "decisions", "[{\"path\":[\"commonModules\",\"A::\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, "the other and ancestor sides are empty"); //$NON-NLS-1$
    }

    @Test
    public void testAMalformedObjectKeyIsRefusedAtTheCollectionLevelToo()
    {
        // Asked of EVERY key, not only the object one: at the collection level 'A::A' used to be
        // caught as "an object key where a collection name belongs", and a shape test that had
        // stopped recognising it would have let it through as a collection name instead.
        String result = call(params("mode", "write", "filePath", file("r.xml").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "decisions", "[{\"path\":[\"A::A\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, "A::A"); //$NON-NLS-1$
    }

    @Test
    public void testNoneIsStillALegalSideBecauseItNamesAnAbsentObject() throws IOException
    {
        // The control that keeps the rule honest: NONE is how the platform spells "the object
        // does not exist on this side", so it is a name and an empty part is not.
        Path target = file("rules.xml"); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", //$NON-NLS-1$
            "[{\"path\":[\"commonModules\",\"A:NONE:NONE\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$

        assertTrue(result, result.startsWith("# Merge rules written:")); //$NON-NLS-1$
        assertTrue(read(target).contains("Key=\"A:NONE:NONE\" MergeRule=\"DoNotMerge\"")); //$NON-NLS-1$
    }

    @Test
    public void testAnObjectKeyAbsentOnEverySideIsRefused() throws IOException
    {
        // 'NONE:NONE:NONE' clears every earlier gate: two separators, and each component names
        // something - NONE being the platform's own name for "absent here". What it describes is
        // an object that exists on NO side, and a comparison has no such node, because a node is
        // what one of the three sides contributed. Recorded, it would match nothing forever.
        Path target = file("rules.xml"); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", //$NON-NLS-1$
            "[{\"path\":[\"commonModules\",\"NONE:NONE:NONE\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$

        assertErrorNaming(result, "#1", "NONE:NONE:NONE", "all three sides", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "names no object at all"); //$NON-NLS-1$
        assertFalse("a refused call must leave no file behind", Files.exists(target)); //$NON-NLS-1$
    }

    @Test
    public void testAKeyAbsentOnEverySideIsRefusedAtTheCollectionLevelToo()
    {
        // Asked of EVERY level, like the empty-side check: at the collection level the key is
        // equally meaningless, and naming what is actually wrong with it beats refusing it as
        // "an object key where a collection name belongs".
        String result = call(params("mode", "write", "filePath", file("r.xml").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "decisions", "[{\"path\":[\"NONE:NONE:NONE\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, "NONE:NONE:NONE", "names no object at all"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAKeyWithOnePresentSideIsStillWritten() throws IOException
    {
        // The control that keeps the new rule from swallowing the legitimate case: ONE present
        // side is all it takes - this is how a deletion on the other two sides is addressed.
        Path target = file("rules.xml"); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", //$NON-NLS-1$
            "[{\"path\":[\"commonModules\",\"NONE:NONE:Gone\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$

        assertTrue(result, result.startsWith("# Merge rules written:")); //$NON-NLS-1$
        assertTrue(read(target).contains("Key=\"NONE:NONE:Gone\" MergeRule=\"DoNotMerge\"")); //$NON-NLS-1$
    }

    @Test
    public void testAnObjectGenuinelyNamedNoneInLowerCaseIsNotAnAbsence() throws IOException
    {
        // The literal is matched exactly, as TopObjectKey.parse matches it. A 1C object may be
        // called 'none', and refusing that key would refuse a real object over its spelling.
        Path target = file("rules.xml"); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", //$NON-NLS-1$
            "[{\"path\":[\"commonModules\",\"none:none:none\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$

        assertTrue(result, result.startsWith("# Merge rules written:")); //$NON-NLS-1$
        assertTrue(read(target).contains("Key=\"none:none:none\" MergeRule=\"DoNotMerge\"")); //$NON-NLS-1$
    }

    @Test
    public void testAWellFormedObjectKeyIsStillWritten() throws IOException
    {
        Path target = file("rules.xml"); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", //$NON-NLS-1$
            "[{\"path\":[\"commonModules\",\"A:B:C\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$

        assertTrue(result, result.startsWith("# Merge rules written:")); //$NON-NLS-1$
        assertTrue(read(target).contains("Key=\"A:B:C\" MergeRule=\"DoNotMerge\"")); //$NON-NLS-1$
    }

    // ============ Two same-path rewrites of one file do not lose each other ============

    /**
     * Two calls that rewrite the SAME existing file are a read-modify-replace each, and the
     * reservation cannot cover them: it refuses a target that must not exist, and here the file
     * exists legitimately. Unserialised, both read the same starting document and each writes only
     * its own additions, so one caller's decisions vanish while both reports claim success.
     * <p>
     * The interleaving is forced rather than hoped for. The injected authority is consulted INSIDE
     * the critical section - after the read, before the write - so the first call parks there and
     * waits for the second to finish. With the sequence serialised the second call cannot even
     * start, that wait expires, and both decisions survive; without it the second call runs to
     * completion inside the window and one of the two is lost whichever order the writes land in.
     *
     * @throws Exception when a worker cannot be joined
     */
    @Test
    public void testTwoConcurrentSamePathRewritesKeepBothSetsOfDecisions() throws Exception
    {
        Path target = seedFixture();
        CountDownLatch firstIsInside = new CountDownLatch(1);
        CountDownLatch secondHasFinished = new CountDownLatch(1);

        MergeRulesTool parking = new MergeRulesTool(id -> {
            firstIsInside.countDown();
            try
            {
                secondHasFinished.await(2, TimeUnit.SECONDS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        });

        AtomicReference<String> firstResult = new AtomicReference<>();
        Thread first = new Thread(() -> firstResult.set(parking.execute(params("mode", "write", //$NON-NLS-1$ //$NON-NLS-2$
            "filePath", target.toString(), "basedOn", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$
            "decisions", "[{\"path\":[\"catalogs\"],\"rule\":\"GetFromOther\"}]")))); //$NON-NLS-1$ //$NON-NLS-2$
        first.start();
        assertTrue("the first call never reached the critical section", //$NON-NLS-1$
            firstIsInside.await(10, TimeUnit.SECONDS));

        AtomicReference<String> secondResult = new AtomicReference<>();
        Thread second = new Thread(() -> {
            secondResult.set(call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "basedOn", target.toString(), //$NON-NLS-1$
                "decisions", "[{\"path\":[\"documents\"],\"rule\":\"DoNotMerge\"}]"))); //$NON-NLS-1$ //$NON-NLS-2$
            secondHasFinished.countDown();
        });
        second.start();

        first.join(30_000L);
        second.join(30_000L);
        assertNotNull("the first call did not finish", firstResult.get()); //$NON-NLS-1$
        assertNotNull("the second call did not finish", secondResult.get()); //$NON-NLS-1$

        String written = read(target);
        assertTrue("both calls reported success, so both decisions must be in the file - the " //$NON-NLS-1$
            + "first one is missing:\n" + written, //$NON-NLS-1$
            written.contains("Key=\"catalogs\" MergeRule=\"GetFromOther\"")); //$NON-NLS-1$
        assertTrue("the second call's decision is missing:\n" + written, //$NON-NLS-1$
            written.contains("Key=\"documents\" MergeRule=\"DoNotMerge\"")); //$NON-NLS-1$
        assertTrue("and neither may have discarded what the file already held:\n" + written, //$NON-NLS-1$
            written.contains("Key=\"Alpha:Beta:Gamma\" MergeRule=\"MergePrioritizingMain\"")); //$NON-NLS-1$
    }


    // ============ A same-path rewrite of an XML 1.1 file is refused, not performed ============
    //
    // The codec writes one declaration, version="1.0". A same-path rewrite therefore read a 1.1
    // source under 1.1's rules and would have written it back under 1.0's - and a character
    // reference legal only in 1.1 makes the result a file neither this tool nor EDT can read,
    // reported as a successful save. Refused at the read, so the file on the path never moves.

    /** A 1.1 rules file carrying a character only a 1.1 declaration lets a parser accept. */
    private static final String ONE_POINT_ONE_RULES = "<?xml version=\"1.1\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
        + "<Settings Format_version=\"2.0\"><MergeSettings>" //$NON-NLS-1$
        + "<Node Key=\"$$Root$$\">" //$NON-NLS-1$
        + "<Node Key=\"commonModules\" MergeRule=\"GetFromOther\" Note=\"a&#x1;b\"/>" //$NON-NLS-1$
        + "</Node></MergeSettings></Settings>"; //$NON-NLS-1$

    @Test
    public void testASamePathRewriteOfAnXmlOnePointOneFileIsRefused() throws IOException
    {
        Path file = seed("one-point-one.xml", ONE_POINT_ONE_RULES); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", file.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "basedOn", file.toString(), //$NON-NLS-1$
            "decisions", "[{\"path\":[\"catalogs\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, "1.1"); //$NON-NLS-1$
    }

    /**
     * The part that matters more than the wording: the file is still there, byte for byte. A
     * refusal that had already replaced or truncated it would print exactly the same message.
     *
     * @throws IOException when the fixture cannot be written or read back
     */
    @Test
    public void testTheRefusedSamePathRewriteLeavesTheFileExactlyAsItWas() throws IOException
    {
        Path file = seed("one-point-one.xml", ONE_POINT_ONE_RULES); //$NON-NLS-1$

        call(params("mode", "write", "filePath", file.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "basedOn", file.toString(), //$NON-NLS-1$
            "decisions", "[{\"path\":[\"catalogs\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("nothing may have been written over the file that was refused", //$NON-NLS-1$
            ONE_POINT_ONE_RULES, read(file));
    }

    /**
     * The control: the same call over an ordinary 1.0 file still writes. Without it the two above
     * would pass just as well against a tool that had stopped writing anything at all.
     *
     * @throws IOException when the fixture cannot be written or read back
     */
    @Test
    public void testTheSameSamePathRewriteOfAnOrdinaryFileStillWrites() throws IOException
    {
        Path file = seedFixture();

        call(params("mode", "write", "filePath", file.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "basedOn", file.toString(), //$NON-NLS-1$
            "decisions", "[{\"path\":[\"catalogs\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the ordinary same-path rewrite must still land:\n" + read(file), //$NON-NLS-1$
            read(file).contains("Key=\"catalogs\" MergeRule=\"DoNotMerge\"")); //$NON-NLS-1$
    }

    private String call(Map<String, String> params)
    {
        return new MergeRulesTool().execute(params);
    }

    private static Map<String, String> params(String... keyValues)
    {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2)
        {
            params.put(keyValues[i], keyValues[i + 1]);
        }
        return params;
    }

    private Path file(String name)
    {
        return workDir.resolve(name);
    }

    private Path seedFixture() throws IOException
    {
        Path file = file("seeded.xml"); //$NON-NLS-1$
        Files.write(file, FIXTURE.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /**
     * Writes a merge-rules document into the work directory.
     *
     * @param name the file name
     * @param xml the document text
     * @return the file
     * @throws IOException when the file cannot be written
     */
    private Path seed(String name, String xml) throws IOException
    {
        Path file = file(name);
        Files.write(file, xml.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String read(Path file) throws IOException
    {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static JsonObject schemaProperties()
    {
        return JsonParser.parseString(new MergeRulesTool().getInputSchema()).getAsJsonObject()
            .getAsJsonObject("properties"); //$NON-NLS-1$
    }

    private static void assertErrorNaming(String result, String... fragments)
    {
        // A refusal is the error JSON; a success is Markdown. Say which one arrived instead of
        // letting the JSON parser fail with a syntax error that hides the actual result.
        assertTrue("expected a refusal, got a successful report:\n" + result, //$NON-NLS-1$
            result.trim().startsWith("{")); //$NON-NLS-1$
        JsonObject json = JsonParser.parseString(result).getAsJsonObject();
        assertFalse("expected a refusal, got: " + result, json.get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        String message = json.get("error").getAsString(); //$NON-NLS-1$
        for (String fragment : fragments)
        {
            assertTrue("the refusal must name '" + fragment + "': " + message, //$NON-NLS-1$ //$NON-NLS-2$
                message.contains(fragment));
        }
    }

    /**
     * Locates a source file of this slice by walking up from the test working directory, the way
     * the other source-scanning ratchets in this suite do.
     *
     * @param relative path under the bundle's {@code src/com/ditrix/edt/mcp/server}
     * @return the file
     */
    private static Path sourceFile(String relative)
    {
        String base = "bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/"; //$NON-NLS-1$
        File dir = new File(System.getProperty("user.dir")); //$NON-NLS-1$
        for (int i = 0; i < 12 && dir != null; i++)
        {
            for (String prefix : List.of("", "mcp/")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                File candidate = new File(dir, prefix + base + relative);
                if (candidate.isFile())
                {
                    return candidate.toPath();
                }
            }
            dir = dir.getParentFile();
        }
        fail("could not locate " + relative + " by walking up from user.dir=" //$NON-NLS-1$ //$NON-NLS-2$
            + System.getProperty("user.dir")); //$NON-NLS-1$
        return null; // unreachable
    }
}
