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
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

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

    @Test
    public void testWriteRefusesAZipTargetBecauseEdtWouldIgnoreIt()
    {
        String result = call(params("mode", "write", "filePath", file("r.zip").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$
        assertErrorNaming(result, ".zip", ".xml"); //$NON-NLS-1$ //$NON-NLS-2$
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
            public Optional<List<String>> availableRules(List<String> nodePath)
            {
                return Optional.empty();
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

        assertEquals("both decisions must be checked", 2, authority.reads); //$NON-NLS-1$
        assertEquals("no rule may be checked once the pass has released its hold", 0, //$NON-NLS-1$
            authority.readsAfterClose);
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
        // "Nothing answered for this id" has two causes and the refusal may not pick one: the
        // comparison may be gone, or its tree may still be building - the authority stays silent
        // on an unfinished tree on purpose. Telling the caller to START one is the reading that
        // cannot be acted on: EDT runs a single comparison per instance, so a second launch is
        // refused while the first is still building.
        String result = call(params("mode", "write", "filePath", file("r.xml").toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "comparisonId", "cmp-4", //$NON-NLS-1$ //$NON-NLS-2$
            "decisions", "[{\"path\":[],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorNaming(result, "cmp-4", "not finished"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the refusal must not send the caller to start a comparison that may already " //$NON-NLS-1$
            + "be running: " + result, result.contains("Start one")); //$NON-NLS-1$ //$NON-NLS-2$
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
            public Optional<List<String>> availableRules(List<String> nodePath)
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

        assertSame(module, MergeRulesTool.findNode(root,
            List.of("commonModules", "Alpha:Beta:Gamma"), node -> "commonModules")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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

        assertNull(MergeRulesTool.findNode(root, List.of("catalogs"), node -> "commonModules")); //$NON-NLS-1$ //$NON-NLS-2$
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
        public Optional<List<String>> availableRules(List<String> nodePath)
        {
            reads++;
            if (closes > 0)
            {
                readsAfterClose++;
            }
            if (explode)
            {
                throw new IllegalStateException("the comparison store is closed"); //$NON-NLS-1$
            }
            return Optional.of(allowed);
        }

        @Override
        public void close()
        {
            closes++;
        }
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
            public Optional<List<String>> availableRules(List<String> nodePath)
            {
                return Optional.of(allowed);
            }
        };
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

    // ============ An in-place update may not detach two names of one file ============

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
        // report called it an update in place.
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
    public void testUpdatingOneFileInPlaceUnderOneNameStillWorks() throws IOException
    {
        // The control: the refusal above may not catch an ordinary in-place update, which is the
        // only way this tool edits an existing file at all.
        Path file = seedFixture();

        String result = call(params("mode", "write", "filePath", file.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "basedOn", file.toString(), //$NON-NLS-1$
            "decisions", "[{\"path\":[\"catalogs\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("an in-place update is the point of basedOn: " + result, //$NON-NLS-1$
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
    public void testAWellFormedObjectKeyIsStillWritten() throws IOException
    {
        Path target = file("rules.xml"); //$NON-NLS-1$

        String result = call(params("mode", "write", "filePath", target.toString(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "decisions", //$NON-NLS-1$
            "[{\"path\":[\"commonModules\",\"A:B:C\"],\"rule\":\"DoNotMerge\"}]")); //$NON-NLS-1$

        assertTrue(result, result.startsWith("# Merge rules written:")); //$NON-NLS-1$
        assertTrue(read(target).contains("Key=\"A:B:C\" MergeRule=\"DoNotMerge\"")); //$NON-NLS-1$
    }

    // ============ Two in-place updates of one file do not lose each other ============

    /**
     * Two calls that update the SAME existing file are a read-modify-write each, and the
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
    public void testTwoConcurrentInPlaceUpdatesKeepBothSetsOfDecisions() throws Exception
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
