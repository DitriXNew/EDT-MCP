/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.compare.core.ComparisonProcessHandle;
import com._1c.g5.v8.dt.compare.model.CollectionElementComparisonNode;
import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.compare.model.ComparisonNodeStatus;
import com._1c.g5.v8.dt.compare.model.MergeRule;
import com._1c.g5.v8.dt.compare.model.TopComparisonNode;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonEngine;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonFailures;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonSessionRegistry;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonView;
import com.ditrix.edt.mcp.server.utils.compare.MergeRulesCodec;
import com.ditrix.edt.mcp.server.utils.compare.MergeRulesCodec.MergeRulesFormatException;
import com.ditrix.edt.mcp.server.utils.compare.MergeRulesDocument;
import com.ditrix.edt.mcp.server.utils.compare.MergeRulesDocument.Decision;
import com.ditrix.edt.mcp.server.utils.compare.MergeRulesDocument.TopObjectKey;
import com.ditrix.edt.mcp.server.utils.compare.PaddedNames;
import com.ditrix.edt.mcp.server.utils.compare.PathMutex;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Reads and authors EDT's merge-rules file - the sparse document of per-node merge decisions a
 * configuration comparison saves, and re-applies when a comparison is launched with it.
 * <p>
 * <b>THREE outcomes, and the answer always says which one ran.</b> Decisions are addressed by
 * NAMES, not by internal node ids, so the DOCUMENT can be authored with no comparison running at
 * all - which is the scenario this exists for: prepare the decisions, then open the comparison
 * window once, already carrying them. That freedom belongs to the document, not to every
 * container it can be put in: an {@code .xml} needs nothing, a {@code .zip} needs a live
 * comparison to name its entry (see below). And a file written without a comparison has NOT been
 * checked against what each node actually allows, and reporting it as if it had is exactly the
 * class of lie this plugin keeps removing. A comparison answers TWO separable things - its
 * ADDRESS, known as soon as the session is found, and a RULE VERDICT, which needs a FINISHED tree
 * - so there are three outcomes and not two:
 * <ul>
 * <li><b>no comparison answered</b> - the file is authored from names and the report says
 * NOT VALIDATED, naming {@code compare_configurations} as the way to get validation; a
 * {@code .zip} target is refused outright, because its entry name is unknowable;</li>
 * <li><b>a comparison answered, its tree could not be read</b> - the address is known, so a
 * {@code .zip} still gets its own entry name, but no rule was checked. With no
 * {@code comparisonId} the write proceeds and the report says NOT VALIDATED, naming the
 * comparison that supplied the address; with a {@code comparisonId} it is REFUSED, because a
 * checked file is what the caller asked for;</li>
 * <li><b>a comparison answered with a FINISHED tree</b> - every rule is checked against the rules
 * its node allows before anything is written, and an illegal rule is refused naming the node, the
 * rule and the allowed set.</li>
 * </ul>
 * The three are never mixed silently, and nothing is written until EVERY decision has passed:
 * a half-applied set would be a file whose contents nobody chose.
 * <p>
 * <b>Two containers, and which one the platform reads depends on its version.</b> EDT 2026.1
 * reads a merge-settings file from {@code .xml} or {@code .zip}; EDT 2026.2 reads a {@code .zip}
 * ALONE and refuses an {@code .xml} with its own assertion (see {@link MergeRulesCodec}). A zip
 * is addressed, and by exactly one thing: the STRING
 * {@code <mainProject>_<otherProject>_<ancestorProject>}, case for case, as
 * {@code ComparisonEngine#mergeRulesEntryId} spells it. EDT restores the entry under that name
 * and silently ignores an archive whose entry is named anything else. Two consequences, and both
 * are stated to callers rather than left to be discovered. It is NOT tied to one comparison run -
 * a later comparison of the same three projects, over different revisions, reads the same entry -
 * so the risk a zip carries is stale decisions applied again, not decisions quietly dropped. And
 * the name is a bare concatenation over a separator project names may themselves contain, so it
 * is NOT injective: the triples {@code (A_B, C, D)} and {@code (A, B_C, D)} both spell
 * {@code A_B_C_D}. "No other comparison can find this file" is therefore not something this tool
 * says; what holds is the one direction - a comparison whose own three names spell a DIFFERENT
 * string finds nothing here. The name is only knowable from a live comparison, so a zip target
 * with none is REFUSED rather than filled with a guess; the alternative that refusal names is
 * {@code .xml}, whose one limitation is stated in the report instead of being hidden: it is not
 * read by EDT 2026.2.
 * <p>
 * <b>Which container is chosen by {@code filePath}, and by nothing else.</b> Passing a
 * {@code comparisonId} does not turn an {@code .xml} target into a zip; it asks for validation.
 * On EDT 2026.2 that makes the useful path a TWO-RUN one: start a comparison, WAIT until its tree
 * has FINISHED - a named comparison whose tree cannot be read is refused, and an unnamed one is
 * written unvalidated - take its id, write the {@code .zip} (which is now addressed and
 * validated), let that comparison go, and start the next comparison over the same projects with
 * {@code mergeRulesFile} pointing at it.
 * <p>
 * <b>This tool cannot merge.</b> It reads and writes a settings file; running the merge stays a
 * human action in the comparison window. It never receives the comparison manager, only the
 * narrow authority below, which answers exactly one question - which rules a node allows.
 * <p>
 * <b>Rule literals are the platform's wire literals</b> ({@code GetFromOther}, {@code DoNotMerge},
 * ...) and are parsed with {@code MergeRule.get(literal)}. The Java constant spelling
 * ({@code GET_FROM_OTHER}) is NOT a literal and is rejected with the correct spelling named.
 */
public class MergeRulesTool implements IMcpTool
{
    public static final String NAME = "merge_rules"; //$NON-NLS-1$

    /** Parameter: which half of the tool to run. */
    private static final String KEY_MODE = "mode"; //$NON-NLS-1$

    /** Parameter: the merge-rules file to read, or to write. */
    private static final String KEY_FILE_PATH = "filePath"; //$NON-NLS-1$

    /** Parameter: an existing rules file whose content the write starts from. */
    private static final String KEY_BASED_ON = "basedOn"; //$NON-NLS-1$

    /** Parameter: the decisions to record. */
    private static final String KEY_DECISIONS = "decisions"; //$NON-NLS-1$

    /** Parameter: the live comparison to validate against. */
    private static final String KEY_COMPARISON_ID = "comparisonId"; //$NON-NLS-1$

    /** Value of {@link #KEY_MODE}: parse a rules file and report its decisions. */
    private static final String MODE_READ = "read"; //$NON-NLS-1$

    /** Value of {@link #KEY_MODE}: record decisions into a rules file. */
    private static final String MODE_WRITE = "write"; //$NON-NLS-1$

    /** Field of one {@link #KEY_DECISIONS} element: the key chain below the root. */
    private static final String FIELD_PATH = "path"; //$NON-NLS-1$

    /** Field of one {@link #KEY_DECISIONS} element: the merge-rule literal. */
    private static final String FIELD_RULE = "rule"; //$NON-NLS-1$

    /** Default cap on reported decision rows. */
    private static final int DEFAULT_LIMIT = 200;

    /**
     * Rule literals this tool will author. The four that are a plain choice between the sides;
     * the platform's other two are refused, see {@link #REFUSED_RULES}.
     */
    private static final List<String> AUTHORABLE_RULES = List.of("GetFromOther", "DoNotMerge", //$NON-NLS-1$ //$NON-NLS-2$
        "MergePrioritizingMain", "MergePrioritizingOther"); //$NON-NLS-1$ //$NON-NLS-2$

    /**
     * Rule literals refused unconditionally, in either mode. Both name a merge that is CONFIGURED
     * elsewhere and not by the rule alone - a custom merge carries its own nested settings, and an
     * external-tool merge names a tool and hands it the content - so writing the bare literal
     * would record a decision whose actual behaviour nobody chose here.
     */
    private static final List<String> REFUSED_RULES = List.of("CustomMerge", "MergeUsingExternalTool"); //$NON-NLS-1$ //$NON-NLS-2$

    /**
     * The parameters {@link #MODE_WRITE} takes and {@link #MODE_READ} does not, in the order
     * {@link #getInputSchema()} declares them - so a call carrying several of them is told about
     * them in the order it could read them off the schema. Named in one place because the refusal
     * and the presence test have to agree about which parameters the promise covers.
     */
    private static final List<String> WRITE_ONLY_PARAMETERS =
        List.of(KEY_BASED_ON, KEY_DECISIONS, KEY_COMPARISON_ID);

    private final MergeRuleAuthoritySupplier authoritySupplier;

    /**
     * Creates the tool with the production authority - the one that asks a live comparison, over
     * {@link ComparisonEngine}, which rules each node allows.
     * <p>
     * The authority answers RULES only for a comparison whose tree has FINISHED. It still answers
     * the comparison's ADDRESS - the id and the zip entry name - while the tree is still building
     * or while EDT's comparison service is away, because that is a fact about the handle and needs
     * no tree. With no comparison at all it answers nothing. Either way the write is authored from
     * names and reported as NOT VALIDATED, which is the honest degradation, never a
     * validated-looking answer.
     */
    public MergeRulesTool()
    {
        this(new EngineRuleAuthority());
    }

    /**
     * Creates the tool with the authority that answers which rules a node allows. This is the
     * single wiring point for the comparison facade: the tool never sees the comparison manager
     * itself, only this one question.
     *
     * @param authoritySupplier resolves the authority for a comparison id (or for whatever
     *            comparison is live, when the id is {@code null}), never {@code null}
     */
    public MergeRulesTool(MergeRuleAuthoritySupplier authoritySupplier)
    {
        this.authoritySupplier = authoritySupplier;
    }

    /**
     * The supplier this instance will consult. Exists so a test can pin WHICH supplier the
     * shipped, no-argument constructor installs: a tool that advertises validation while holding
     * a supplier that can never answer would advertise a mode nothing can enter, and no
     * behavioural test run without EDT can tell the two suppliers apart.
     *
     * @return the supplier, never {@code null}
     */
    MergeRuleAuthoritySupplier authoritySupplier()
    {
        return authoritySupplier;
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Read or author EDT's merge-rules file - the per-node decisions a configuration " //$NON-NLS-1$
            + "comparison saves and re-applies when it is launched. Which container to write is " //$NON-NLS-1$
            + "chosen by filePath: '.xml' needs NO running comparison but is read by EDT 2026.1 " //$NON-NLS-1$
            + "only, while the '.zip' both versions read carries an entry named after the " //$NON-NLS-1$
            + "comparison's three project names, so it needs a live comparison and is refused " //$NON-NLS-1$
            + "without one. Validation has THREE outcomes: a comparison whose tree is FINISHED " //$NON-NLS-1$
            + "checks every rule against what its node allows and refuses an illegal one; a " //$NON-NLS-1$
            + "comparison that answers but whose tree cannot be read yet names a zip's entry " //$NON-NLS-1$
            + "without checking anything, reported NOT VALIDATED - or refused outright when you " //$NON-NLS-1$
            + "passed comparisonId; with no comparison at all the file is authored from names, " //$NON-NLS-1$
            + "also NOT VALIDATED. Never merges anything - running the merge stays a human " //$NON-NLS-1$
            + "action in the comparison window. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('merge_rules')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .enumProperty(KEY_MODE,
                "'read' parses a rules file and reports its decisions; 'write' records decisions " //$NON-NLS-1$
                    + "into one (required).", //$NON-NLS-1$
                true, MODE_READ, MODE_WRITE)
            .stringProperty(KEY_FILE_PATH,
                "Absolute path of the merge-rules file (required). read: the file to parse, " //$NON-NLS-1$
                    + "'.xml' or the '.zip' a comparison saves - the extension's CASE does not " //$NON-NLS-1$
                    + "matter, '.ZIP' is read. write: this is what picks the container, and here " //$NON-NLS-1$
                    + "the extension must be spelled in LOWER CASE, because EDT's own reader " //$NON-NLS-1$
                    + "compares it exactly and would refuse the file inside the launch. '.zip' is " //$NON-NLS-1$
                    + "read by every supported EDT and holds an entry named after the " //$NON-NLS-1$
                    + "comparison's three PROJECT names, so it needs a live comparison and is " //$NON-NLS-1$
                    + "refused without one; '.xml' needs none and is read by EDT 2026.1 only. An " //$NON-NLS-1$
                    + "existing file is OVERWRITTEN only when 'basedOn' names that SAME file, " //$NON-NLS-1$
                    + "whose decisions are carried into the file that REPLACES it; any other " //$NON-NLS-1$
                    + "write over an existing file is refused so decisions are never silently " //$NON-NLS-1$
                    + "discarded.", //$NON-NLS-1$
                true)
            .stringProperty(KEY_BASED_ON,
                "write: an existing rules file to start from, so its decisions and payload are " //$NON-NLS-1$
                    + "kept and yours are merged in (optional; '.xml' or '.zip', case-" //$NON-NLS-1$
                    + "insensitive - it is only read, never handed to EDT).") //$NON-NLS-1$
            .objectArrayProperty(KEY_DECISIONS,
                "write: the decisions to record, as [{path, rule}]. 'path' is the key chain below " //$NON-NLS-1$
                    + "the root - [] = the whole configuration, ['commonModules'] = a whole " //$NON-NLS-1$
                    + "collection (the EMF feature name; a metadata type token in either " //$NON-NLS-1$
                    + "language, 'Catalog' or 'Catalogs' or the Russian form, is translated to " //$NON-NLS-1$
                    + "it), ['commonModules','Main:Main:Main'] = one " //$NON-NLS-1$
                    + "object, keyed by its name on the main, other and ancestor sides joined by " //$NON-NLS-1$
                    + "':' with 'NONE' for a side that has no such object. 'rule' is one of " //$NON-NLS-1$
                    + "GetFromOther, DoNotMerge, MergePrioritizingMain, MergePrioritizingOther.") //$NON-NLS-1$
            .stringProperty(KEY_COMPARISON_ID,
                "write: validate every rule against this live comparison before writing " //$NON-NLS-1$
                    + "(optional). Validation needs a FINISHED tree: naming a comparison whose " //$NON-NLS-1$
                    + "tree cannot be read yet is REFUSED, never quietly downgraded, because you " //$NON-NLS-1$
                    + "asked for a checked file. Omitted = validate against the running " //$NON-NLS-1$
                    + "comparison when its tree is finished, otherwise author unvalidated and " //$NON-NLS-1$
                    + "say so.") //$NON-NLS-1$
            .integerProperty(McpKeys.LIMIT, "Max decision rows to report; default 200, max 1000 (optional)") //$NON-NLS-1$
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String missing = JsonUtils.requireArguments(params, KEY_MODE, KEY_FILE_PATH);
        if (missing != null)
        {
            return missing;
        }
        String mode = JsonUtils.extractStringArgument(params, KEY_MODE);
        String filePath = JsonUtils.extractStringArgument(params, KEY_FILE_PATH);
        String basedOn = JsonUtils.extractStringArgument(params, KEY_BASED_ON);
        String comparisonId = JsonUtils.extractStringArgument(params, KEY_COMPARISON_ID);
        List<JsonObject> decisions = JsonUtils.extractObjectArray(params, KEY_DECISIONS);
        int limit = Pagination.clampLimit(JsonUtils.extractIntArgument(params, McpKeys.LIMIT, DEFAULT_LIMIT),
            Pagination.MAX_LIMIT);

        if (MODE_READ.equals(mode))
        {
            // Judged by what the caller SUPPLIED, and by nothing about the value.
            //
            // The refusal below promises that a write-only parameter is refused rather than
            // ignored, and that promise is about SUPPLY - "you sent a parameter this mode does not
            // take". Every test of the VALUE that has stood here was narrower than the promise,
            // and each one let some payload through as "absent": judging 'decisions' by the
            // extracted list read '"decisions":[null]' as absent, because the shared extractor
            // keeps only JSON objects and drops the rest without a word; widening that to write
            // mode's malformed-element rule still read '[]', an object and a scalar as absent; and
            // isSet still read an empty 'basedOn' or 'comparisonId' as absent. In every one of
            // those a caller who meant to write and picked the wrong mode got a successful-looking
            // report and was never told that nothing had been recorded.
            //
            // Asking about presence is not a second judgement about content - it is the simpler
            // question the promise is actually about, and it has one answer for all three
            // parameters. That is also why it does not compete with write mode's judgement: write
            // mode still refuses a malformed 'decisions' with its own sentence naming the
            // offending element, and this branch never reaches that code.
            //
            // The one shape this cannot see: the protocol layer skips a JSON null argument while
            // building the map, so '"decisions": null' on the wire arrives as no key at all and
            // reads. That is the transport's rule for every tool, not a boundary drawn here.
            String supplied = suppliedWriteOnlyParameters(params);
            if (supplied != null)
            {
                return ToolResult.error("Apart from " + KEY_MODE + " itself, mode '" + MODE_READ //$NON-NLS-1$ //$NON-NLS-2$
                    + "' takes only " + KEY_FILE_PATH + " and " + McpKeys.LIMIT //$NON-NLS-1$ //$NON-NLS-2$
                    + ". This call also sent " + supplied //$NON-NLS-1$
                    + " - write-only, and nothing was read. A write-only parameter is refused for " //$NON-NLS-1$
                    + "having been SENT, not for what it holds: an empty array, an empty string, " //$NON-NLS-1$
                    + "or a value this tool cannot use is still a parameter you supplied, and " //$NON-NLS-1$
                    + "reading past it would answer a call you did not make. Re-send with mode '" //$NON-NLS-1$
                    + MODE_WRITE + "' - carrying the payload you meant to record - or drop them " //$NON-NLS-1$
                    + "to read the file.").toJson(); //$NON-NLS-1$
            }
            return read(filePath, limit);
        }
        if (MODE_WRITE.equals(mode))
        {
            // The shared extractor keeps only JSON objects and discards the rest without a word, so
            // a malformed element would be written off silently and the report would state a
            // decision count lower than what the caller sent - this tool's whole contract is that
            // the report never overstates, and understating without saying so breaks it just as
            // badly. Every other malformed decision here is refused BY POSITION; this one now is
            // too. The shared helper is left alone: other callers depend on its lenient shape.
            String malformed = nonObjectDecisionRefusal(params);
            if (malformed != null)
            {
                return malformed;
            }
            return write(filePath, basedOn, comparisonId, decisions, limit);
        }
        return ToolResult.error("Unknown " + KEY_MODE + " '" + mode + "'. Use '" + MODE_READ //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "' to parse a merge-rules file, or '" + MODE_WRITE + "' to record decisions into one.") //$NON-NLS-1$ //$NON-NLS-2$
            .toJson();
    }

    // ==================== read ====================

    /**
     * Parses a rules file and reports its decisions.
     *
     * <h2>No extension check, and that is deliberate</h2>
     * {@code hasReadableExtension} is a rule about what the PLATFORM will accept, and reading is
     * the one path that never hands the file to the platform. So this path asks nothing about the
     * name: {@code MergeRulesCodec.read} decides how to open the file with
     * {@code MergeRulesCodec#isZip}, which is case-INSENSITIVE, and anything else is parsed as the
     * XML document it is. {@code RULES.ZIP} therefore reads, and so does a rules file somebody
     * renamed. The lower-case rule applies to the two places where EDT itself opens the file -
     * a {@code write} target and {@code compare_configurations}'s {@code mergeRulesFile} - and
     * refusing it here as well would refuse a file that this call reads perfectly well.
     *
     * @param filePath the caller's path
     * @param limit the cap on reported rows
     * @return the report, or the refusal
     */
    private String read(String filePath, int limit)
    {
        Path given;
        try
        {
            given = Paths.get(filePath);
        }
        catch (InvalidPathException e)
        {
            return invalidPath(KEY_FILE_PATH, filePath, e);
        }
        String relative = relativePathRefusal(KEY_FILE_PATH, filePath, given);
        if (relative != null)
        {
            return relative;
        }
        Path file = given.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file))
        {
            return ToolResult.error("Merge-rules file not found: " + file //$NON-NLS-1$
                + ". Point " + KEY_FILE_PATH + " at the '.xml' or '.zip' a comparison saved, or " //$NON-NLS-1$ //$NON-NLS-2$
                + "author one with mode 'write'.").toJson(); //$NON-NLS-1$
        }
        MergeRulesDocument document;
        try
        {
            document = MergeRulesCodec.read(file);
        }
        catch (MergeRulesFormatException e)
        {
            return ToolResult.error(e.getMessage()).toJson();
        }
        catch (IOException e)
        {
            return ToolResult.error("Could not read the merge-rules file " + file + ": " //$NON-NLS-1$ //$NON-NLS-2$
                + describe(e)).toJson();
        }
        return renderRead(document, limit);
    }

    private String renderRead(MergeRulesDocument document, int limit)
    {
        List<Decision> decisions = document.decisions();
        int unreachable = document.unreachableRuleCount();
        // The heading is a CONSTANT, and the source is a field below it. It used to be
        // "# Merge rules: " + sourceLabel, and that label is not this server's text: for a zip it
        // ends in the ENTRY name, and a zip entry name is an arbitrary string that may legally
        // hold a line break followed by anything - a heading, a table, an instruction - where a
        // filesystem path could not. Concatenated into a heading, that is a second document
        // growing inside this one, indistinguishable from the real report. So the label goes
        // through MarkdownUtils.inlineCode, which cannot be read as markup at all, and it goes
        // into a field, where a break would at worst end a line rather than open a section.
        StringBuilder out = new StringBuilder("# Merge rules\n\n"); //$NON-NLS-1$
        out.append("- Source: ").append(MarkdownUtils.inlineCode(document.sourceLabel())).append('\n'); //$NON-NLS-1$
        out.append("- Format version: ").append(document.formatVersion()).append('\n'); //$NON-NLS-1$
        out.append("- Decisions: ").append(decisions.size()).append('\n'); //$NON-NLS-1$
        out.append("- Preserved sections this tool does not interpret: ") //$NON-NLS-1$
            .append(document.preservedSectionCount())
            .append(" (kept verbatim by a rewrite)\n\n"); //$NON-NLS-1$
        if (decisions.isEmpty())
        {
            if (unreachable > 0)
            {
                // Saying "no merge rule" here would claim an absence the file
                // contradicts: the rules ARE in it, they just sit where no address
                // reaches them.
                out.append(unreachableRuleClause(unreachable));
                return out.toString();
            }
            out.append("The file records no merge rule. A merge-rules file is SPARSE - it holds only " //$NON-NLS-1$
                + "the decisions somebody made, so an empty one leaves every node on EDT's own " //$NON-NLS-1$
                + "default.\n"); //$NON-NLS-1$
            return out.toString();
        }
        out.append(MarkdownUtils.tableHeader("#", "Node", "Level", "Main", "Other", "Ancestor", "Rule", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            "Order side")); //$NON-NLS-1$
        int shown = Math.min(limit, decisions.size());
        for (int i = 0; i < shown; i++)
        {
            Decision decision = decisions.get(i);
            Optional<TopObjectKey> key = decision.topObjectKey();
            out.append(MarkdownUtils.tableRow(String.valueOf(i + 1), String.join(" / ", decision.path()), //$NON-NLS-1$
                level(decision), side(key, TopObjectKey::main), side(key, TopObjectKey::other),
                side(key, TopObjectKey::ancestor), decision.rule(),
                decision.orderSide() == null ? "-" : decision.orderSide())); //$NON-NLS-1$
        }
        if (shown < decisions.size())
        {
            out.append('\n').append(Pagination.truncationNotice(shown, decisions.size())).append('\n');
        }
        if (unreachable > 0)
        {
            out.append('\n').append(unreachableRuleClause(unreachable));
        }
        out.append("\n> Levels: `root` = the whole configuration, `collection` = every object of one " //$NON-NLS-1$
            + "kind, `object` = one object keyed by its name on the three sides, `member` = below " //$NON-NLS-1$
            + "the object, where the platform keys nodes by a computed POSITION that shifts when " //$NON-NLS-1$
            + "other rules change - reported here, never authored.\n" //$NON-NLS-1$
            // Said once, here, rather than decided per cell: the side columns print the key
            // exactly as the file spells it, and this is what the one ambiguous spelling means.
            + "> A side column reading '" + MergeRulesDocument.SIDE_ABSENT //$NON-NLS-1$
            + "' is the platform's marker for 'the object does not exist on that side' - and is " //$NON-NLS-1$
            + "also what a side holding an object NAMED '" + MergeRulesDocument.SIDE_ABSENT //$NON-NLS-1$
            + "' looks like, that being a legal 1C name. The key cannot tell the two apart; a " //$NON-NLS-1$
            + "running comparison resolves it.\n"); //$NON-NLS-1$
        return out.toString();
    }

    /**
     * What the report says about the merge rules a file carries where no address reaches them.
     * <p>
     * Emitted ONLY when there are such rules, so an ordinary file reads exactly as before.
     * The sentence it replaces - "the file records no merge rule" - would be a false claim of
     * absence here: {@code decisions()} has no address to report such a rule under and the
     * preserved-section count does not cover it either, so without this the file would be
     * reported as holding nothing while holding a rule somebody wrote.
     * <p>
     * <b>Shared by BOTH reports, and it has to be.</b> The read report is not the only place the
     * fact is due: a write with {@code basedOn} carries such a rule into the file it writes, so
     * the write report's own counts understate the file for exactly the same reason. One clause
     * against one counter ({@code MergeRulesDocument.unreachableRuleCount()}) keeps the two
     * halves from wording - or numbering - the same fact differently.
     * <p>
     * <b>The clause states the PARTITION and gives its places as examples, never as a list.</b>
     * Its counter is a subtraction - every {@code MergeRule} the file spells, minus the ones the
     * table above addresses - so a shape nobody has named yet is already inside this number, and
     * a sentence that read as an exhaustive inventory would be the one part of the report that
     * could still go stale.
     *
     * @param count how many such rules the file carries, always positive
     * @return the clause, ending in a newline
     */
    private static String unreachableRuleClause(int count)
    {
        boolean one = count == 1;
        return "The file carries " + count + " merge rule" + (one ? "" : "s") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " at no address: every 'MergeRule' this file spells is either one of the decisions " //$NON-NLS-1$
            + "counted above or one of these " + count + ". They sit where a key chain from the '" //$NON-NLS-1$ //$NON-NLS-2$
            + MergeRulesDocument.ROOT_KEY + "' marker never arrives - among such places: outside " //$NON-NLS-1$
            + "that marker's subtree (a second '<" + MergeRulesDocument.TAG_MERGE_SETTINGS //$NON-NLS-1$
            + ">' element included, because only the first one is read), below a node that " //$NON-NLS-1$
            + "carries no 'Key', and on an element that is not a '<" + MergeRulesDocument.TAG_NODE //$NON-NLS-1$
            + ">' at all. EDT resolves a node by its key chain from that marker, so " //$NON-NLS-1$
            + (one ? "that rule applies" : "those rules apply") //$NON-NLS-1$ //$NON-NLS-2$
            + " to nothing and no request here can address " //$NON-NLS-1$
            + (one ? "it" : "them") + ", which is why " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + (one ? "it is not counted as a decision" : "they are not counted as decisions") //$NON-NLS-1$ //$NON-NLS-2$
            + " above. A rewrite still carries " + (one ? "it" : "them") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " through verbatim, as it carries any other payload this tool does not " //$NON-NLS-1$
            + "interpret.\n"; //$NON-NLS-1$
    }

    private static String level(Decision decision)
    {
        if (decision.depth() == 0)
        {
            return "root"; //$NON-NLS-1$
        }
        if (decision.depth() == 1)
        {
            return "collection"; //$NON-NLS-1$
        }
        if (decision.depth() == MergeRulesDocument.MAX_AUTHORABLE_DEPTH)
        {
            return "object"; //$NON-NLS-1$
        }
        return "member"; //$NON-NLS-1$
    }

    /**
     * One side's cell of the decisions table: the component EXACTLY as the file spells it.
     * <p>
     * It used to print "(absent)" wherever the component read {@link MergeRulesDocument#SIDE_ABSENT},
     * which is a statement about the configuration that the key does not support - {@code NONE} is
     * the platform's absence marker AND a legal 1C name, so the cell claimed an absence for a side
     * that may hold an object called {@code NONE} (see {@code MergeRulesDocument.TopObjectKey}).
     * Printing the spelling states only what the file says; what the two readings are is in the
     * legend under the table, once, instead of being decided per cell.
     *
     * @param key the parsed key, or empty when the decision does not address a top object
     * @param reader which of the three components to render
     * @return the cell
     */
    private static String side(Optional<TopObjectKey> key, Function<TopObjectKey, String> reader)
    {
        if (key.isEmpty())
        {
            return "-"; //$NON-NLS-1$
        }
        return reader.apply(key.get());
    }

    // ==================== write ====================

    private String write(String filePath, String basedOn, String comparisonId, List<JsonObject> rawDecisions,
        int limit)
    {
        Path given;
        try
        {
            given = Paths.get(filePath);
        }
        catch (InvalidPathException e)
        {
            return invalidPath(KEY_FILE_PATH, filePath, e);
        }
        String relative = relativePathRefusal(KEY_FILE_PATH, filePath, given);
        if (relative != null)
        {
            return relative;
        }
        Path file = given.toAbsolutePath().normalize();
        Path fileName = file.getFileName();
        if (fileName == null)
        {
            // A root path ("C:\" / "/") names no file at all.
            return ToolResult.error(KEY_FILE_PATH + " must name a file, not a directory root: '" //$NON-NLS-1$
                + filePath + "'.").toJson(); //$NON-NLS-1$
        }
        if (!MergeRulesCodec.hasReadableExtension(file))
        {
            String wrongCase = wrongCaseExtensionRefusal(fileName.toString());
            if (wrongCase != null)
            {
                return wrongCase;
            }
            return ToolResult.error("mode 'write' produces '" + MergeRulesCodec.ZIP_EXTENSION //$NON-NLS-1$
                + "' or '" + MergeRulesCodec.XML_EXTENSION + "', but " + KEY_FILE_PATH + " is '" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + fileName + "'. EDT's merge-settings reader takes no other name: 2026.2 reads a " //$NON-NLS-1$
                + "zip alone, 2026.1 reads either, so anything else would be refused inside the " //$NON-NLS-1$
                + "launch that was meant to apply it. Name the file '" //$NON-NLS-1$
                + MergeRulesCodec.ZIP_EXTENSION + "' and pass " + KEY_COMPARISON_ID //$NON-NLS-1$ //$NON-NLS-2$
                + " (a zip entry is named after the comparison's three projects), or '" //$NON-NLS-1$
                + MergeRulesCodec.XML_EXTENSION + "' to author it without one.").toJson(); //$NON-NLS-1$
        }
        if (rawDecisions.isEmpty())
        {
            return ToolResult.error("mode 'write' needs " + KEY_DECISIONS + ": [{path, rule}]. " //$NON-NLS-1$ //$NON-NLS-2$
                + "'path' is the key chain below the root ([] = the whole configuration), 'rule' is " //$NON-NLS-1$
                + "one of " + String.join(", ", AUTHORABLE_RULES) + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        Path base = null;
        if (isSet(basedOn))
        {
            Path givenBase;
            try
            {
                givenBase = Paths.get(basedOn);
            }
            catch (InvalidPathException e)
            {
                return invalidPath(KEY_BASED_ON, basedOn, e);
            }
            String relativeBase = relativePathRefusal(KEY_BASED_ON, basedOn, givenBase);
            if (relativeBase != null)
            {
                return relativeBase;
            }
            base = givenBase.toAbsolutePath().normalize();
            if (!Files.isRegularFile(base))
            {
                return ToolResult.error(KEY_BASED_ON + " file not found: " + base //$NON-NLS-1$
                    + ". Omit it to author a fresh rules file.").toJson(); //$NON-NLS-1$
            }
        }

        PathMutex mutex = PathMutex.take(file);
        try
        {
            return writeUnderMutex(file, base, basedOn, comparisonId, rawDecisions, limit);
        }
        finally
        {
            mutex.release();
        }
    }

    /**
     * Everything from "is a file already there" to the write itself, with this process holding the
     * target path against every other call in it.
     *
     * <h2>What the mutex is FOR</h2>
     * A same-path rewrite is a READ-MODIFY-REPLACE: the document is read from {@code basedOn},
     * this call's decisions are applied to it in memory, and the result REPLACES the target - what
     * sits on the path afterwards is a NEW file object, not the one that was read. Two calls
     * that name the same existing file as both {@code filePath} and {@code basedOn} are a case the
     * reservation cannot cover - the reservation refuses a target that must NOT exist, and here
     * the file exists legitimately. Unserialised, both read the SAME old document, both pass the
     * guard, and each writes its own additions over the other's: the first call's decisions
     * disappear and the second call's report says everything was recorded. That is the report
     * claiming more than happened, which this tool refuses everywhere else.
     * <p>
     * <b>Held across the whole sequence, including the validation.</b> Releasing it after the read
     * would restore the race exactly: what has to be indivisible is not the read and not the
     * write, but the interval between them.
     *
     * <h2>What it does NOT guarantee</h2>
     * <ul>
     * <li><b>Nothing across processes.</b> It is a lock in this JVM only. Another EDT, an editor,
     * or a person with a text editor can still write the file between this read and this write.
     * The single filesystem step the codec performs keeps the file from being seen half-written;
     * it cannot keep a foreign write from being lost.</li>
     * <li><b>Nothing across spellings.</b> The key is the absolute, normalised path, so the same
     * file reached through a symbolic link, a junction, or - on a case-insensitive filesystem - a
     * different case is a DIFFERENT key and is not serialised against this one. Widening the key
     * to the file's real identity is a filesystem question ({@code Files.isSameFile}) that cannot
     * be asked of a path that does not exist yet, which is the ordinary case for a fresh write.</li>
     * <li><b>It is not a file lock.</b> Nothing here prevents anyone reading the file, and no
     * lock survives this call.</li>
     * </ul>
     *
     * @param file the absolute, normalised target
     * @param base the absolute, normalised starting point, or {@code null} for a fresh document
     * @param basedOn the {@code basedOn} argument exactly as it arrived, for the report
     * @param comparisonId the live comparison to validate against, or {@code null}
     * @param rawDecisions the decisions as sent
     * @param limit the cap on reported rows
     * @return the report, or the refusal
     */
    private String writeUnderMutex(Path file, Path base, String basedOn, String comparisonId,
        List<JsonObject> rawDecisions, int limit)
    {
        // The guard is on WHICH file is about to be replaced, not on whether a starting point was
        // named: 'basedOn' one file and writing over ANOTHER discards the target's decisions just
        // as completely as writing over it with a fresh document, and the report would then name
        // only the decisions that were carried in.
        // Decided HERE, where the guard is, and carried to the write as the write's own
        // instruction rather than re-derived there. The guard answers WHETHER a replacement is
        // allowed; the codec is what makes that answer take effect in ONE filesystem step. Split
        // the two - an exists() check here, an unconditional replacing move there - and two
        // concurrent writes to a path that started out free both pass this guard and both perform
        // that move, so the second silently destroys the decisions the first had just recorded.
        // Asked of the codec, not of the string, so the one place that knows what the platform
        // reads decides what this write produces.
        boolean zipped = MergeRulesCodec.isZip(file);
        MergeRulesCodec.Target targetPolicy = MergeRulesCodec.Target.MUST_NOT_EXIST;
        if (Files.exists(file))
        {
            if (!isSameFile(file, base))
            {
                String startedElsewhere = base == null ? "" //$NON-NLS-1$
                    : " (" + KEY_BASED_ON + " names a DIFFERENT file, " + base //$NON-NLS-1$ //$NON-NLS-2$
                        + ", whose decisions would be written over the ones already there)"; //$NON-NLS-1$
                return ToolResult.error("A file already exists at " + file + startedElsewhere //$NON-NLS-1$
                    + " and would be replaced, discarding the decisions it holds. Either pass " //$NON-NLS-1$
                    + KEY_BASED_ON + " with the SAME path, " + file //$NON-NLS-1$
                    + ", to carry its decisions into the replacement, or choose another " //$NON-NLS-1$
                    + KEY_FILE_PATH + ".").toJson(); //$NON-NLS-1$
            }
            String detached = separateNameRefusal(file, base);
            if (detached != null)
            {
                return detached;
            }
            targetPolicy = MergeRulesCodec.Target.MAY_BE_REPLACED;
        }

        MergeRulesDocument document;
        if (base != null)
        {
            try
            {
                document = MergeRulesCodec.read(base);
            }
            catch (MergeRulesFormatException e)
            {
                return ToolResult.error(e.getMessage()).toJson();
            }
            catch (IOException e)
            {
                return ToolResult.error("Could not read " + KEY_BASED_ON + " " + base + ": " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + describe(e)).toJson();
            }
        }
        else
        {
            document = MergeRulesDocument.empty();
        }
        int existingDecisions = document.decisions().size();
        // Asked the moment the starting document is in hand, and BEFORE a single decision is
        // parsed: nothing below this line may run for a write that is not going to happen.
        String sidecars = sidecarEntriesRefusal(file, targetPolicy, document);
        if (sidecars != null)
        {
            return sidecars;
        }

        List<RequestedDecision> requested = new ArrayList<>();
        // The normalised path, not the raw one, and that is the point: two spellings the platform
        // reads as the same node - a type token against a feature name, a leading root marker,
        // padding around a key - arrive here as ONE path. See the refusal for what a duplicate
        // costs.
        Map<List<String>, Integer> firstSeenAt = new HashMap<>();
        for (int i = 0; i < rawDecisions.size(); i++)
        {
            ParsedDecision parsed = parseDecision(rawDecisions.get(i), i + 1);
            if (parsed.refusal != null)
            {
                return parsed.refusal;
            }
            Integer earlier = firstSeenAt.putIfAbsent(parsed.decision.path, i + 1);
            if (earlier != null)
            {
                return duplicatePathRefusal(earlier.intValue(), i + 1, parsed.decision.path);
            }
            requested.add(parsed.decision);
        }

        // Applied to the IN-MEMORY document first, so that what gets checked below is the
        // document that would be written - not just the decisions this call happens to carry.
        // Nothing reaches the disk until every check passes; the write is the last statement of
        // this method.
        int replaced = 0;
        Set<List<String>> requestedPaths = new HashSet<>();
        for (RequestedDecision decision : requested)
        {
            if (document.mergeRuleAt(decision.path).isPresent())
            {
                replaced++;
            }
            document.setMergeRule(decision.path, decision.rule);
            requestedPaths.add(fullPathOf(decision.path));
        }

        boolean idGiven = isSet(comparisonId);
        Optional<MergeRuleAuthority> comparison = Optional.empty();
        // The two halves of what a live comparison gives this write, kept apart on purpose. The
        // ADDRESS is a fact about which projects the comparison runs over, and it is known the
        // moment the session answers at all; VALIDATION additionally needs the tree to be
        // readable. They used to be one: the entry name was computed only on the path that had
        // already proved the tree finished, so an unfinished comparison lost the address with it
        // and a zip write was refused as if no comparison existed - while the very comparison the
        // refusal told the caller to start was the one holding EDT's single slot.
        //
        // ONE snapshot for the WHOLE document, and that is what makes the report true. The tree's
        // readiness and the rules of each decision used to be read in SEPARATE boundaries - the
        // readiness once, then one boundary per decision - so a file carrying several decisions
        // could be accepted against a mixture of an old tree, a half-rebuilt one and a new one
        // while the report said every decision had been checked against the comparison. A rule
        // accepted against a stale node is one the finished comparison can refuse.
        boolean validated = false;
        String refusal;
        try
        {
            comparison = authoritySupplier.authority(idGiven ? comparisonId : null);
            RuleSnapshot snapshot = comparison.isPresent()
                ? comparison.get().rulesFor(decisionPaths(document)) : RuleSnapshot.unreadable();
            validated = snapshot.checked();
            refusal = validated ? firstRefusedDecision(comparison.get().comparisonId(), snapshot,
                document, requestedPaths) : null;
        }
        catch (RuntimeException e)
        {
            // The comparison answered with a FAILURE rather than with an answer. That is neither
            // "this rule is illegal" nor "the rules were checked", so nothing is written and the
            // failure is named: writing anyway would report an unchecked file as a checked one,
            // and refusing the rule would attribute a verdict to a comparison that gave none.
            Activator.logError("Could not validate merge rules against a live comparison", e); //$NON-NLS-1$
            // The way out is stated WITHOUT sending the caller to drop comparisonId. That advice
            // used to end this sentence, and it is a no-op for the caller who never named one -
            // the branch this refusal now also reaches, since the production supplier stopped
            // swallowing its own failures. Worse, it is misleading for the caller who did: while a
            // comparison is running and failing, dropping the id lands on the SAME comparison and
            // fails the same way. Authoring from names is what happens when nothing answers at
            // all, and that is not a mode a parameter can be dropped to enter.
            //
            // NOT VALIDATED is named as the TWO states it actually covers. The sentence used to
            // end by naming only the state where nothing answers: the other one is a comparison
            // that answers while its tree cannot be read, and that is the state this very branch
            // sits next to - so the refusal was denying the existence of its own neighbour.
            return ToolResult.error("Could not check the decisions against " //$NON-NLS-1$
                + (idGiven ? "comparison '" + comparisonId + "'" : "the running comparison") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + ": " + describe(e) + ". Nothing was written - the rules were neither checked nor " //$NON-NLS-1$ //$NON-NLS-2$
                + "found illegal, so nothing about the file has to be undone. Retry once the " //$NON-NLS-1$
                + "comparison answers; get_comparison_node says whether its tree can be read. " //$NON-NLS-1$
                + "Dropping " + KEY_COMPARISON_ID + " does not author the file from names " //$NON-NLS-1$ //$NON-NLS-2$
                + "instead: with no id this tool resolves whichever comparison is RUNNING and " //$NON-NLS-1$
                + "is checked against that one. A NOT VALIDATED report is written in two " //$NON-NLS-1$
                + "states - when nothing answered at all, and when a comparison answered while " //$NON-NLS-1$
                + "its tree could not be read.").toJson(); //$NON-NLS-1$
        }
        finally
        {
            // The authority is held for the WHOLE pass and released the moment the pass is over -
            // on the refusal path, on the failure path and on the way to the write alike. What it
            // holds is a lease on the comparison session (see MergeRuleAuthority#close), and a
            // lease outliving its read would keep a finished comparison out of the idle sweep's
            // reach for as long as the server runs. Everything the report still needs from it -
            // the comparison id - is a value it already carries.
            comparison.ifPresent(MergeRuleAuthority::close);
        }
        // Kept as a plain value, exactly as the comparison id is: the write happens after the pass
        // has been given back, and a zip's entry name has to survive that. The getter is reached
        // AFTER close(), which is safe by construction rather than by luck - it answers a final
        // String captured while the lease was held, and close() releases the lease, not the value.
        // Asked only when the target IS a zip: an xml file carries no address, so there is nothing
        // about a comparison it could need. Taken from the comparison that ANSWERED, not from the
        // one that validated - an unfinished tree still names its own projects.
        String zipEntryId =
            zipped ? comparison.map(MergeRuleAuthority::mergeRulesEntryId).orElse(null) : null;
        if (idGiven && comparison.isPresent() && !validated)
        {
            // The caller asked for validation against a comparison that IS there and cannot give a
            // verdict. Said as itself rather than as "nothing answered for that id": the id is
            // right, the comparison is registered, and the only thing missing is a readable tree -
            // a state that ends by itself, so the action is to wait rather than to hunt for
            // another id. Nothing is written, because a validated write is what was asked for.
            return ToolResult.error("Cannot validate against comparison '" + comparisonId //$NON-NLS-1$
                + "': it is registered, but its tree could not be read - either it is still being " //$NON-NLS-1$
                + "built, or EDT's comparison service did not answer for it. An unfinished tree " //$NON-NLS-1$
                + "cannot tell 'not compared yet' from 'not in this comparison', so it is never " //$NON-NLS-1$
                + "used to refuse a rule. get_comparison_node shows whether the tree is still " //$NON-NLS-1$
                + "building; re-send this write once it is finished. Or omit " + KEY_COMPARISON_ID //$NON-NLS-1$
                + " to write without validation - which is NOT authoring from names alone: with " //$NON-NLS-1$
                + "no id this tool resolves whichever comparison is RUNNING, so the report names " //$NON-NLS-1$
                + "the comparison that answered and says its rules were NOT validated, and a '" //$NON-NLS-1$
                + MergeRulesCodec.ZIP_EXTENSION
                + "' target still gets that comparison's own entry name.").toJson(); //$NON-NLS-1$
        }
        if (idGiven && comparison.isEmpty())
        {
            // Names what was observed - that nothing answered for this id - without picking a
            // cause. In particular it does NOT say "no comparison is running" and does NOT send
            // the caller to start one: EDT runs a single comparison per instance, so if another
            // comparison holds the slot, starting one is refused. The branch above covers the
            // comparison that IS registered and merely unreadable, so this one no longer has to
            // hedge between the two.
            return ToolResult.error("Cannot validate against comparison '" + comparisonId //$NON-NLS-1$
                + "': nothing answered for it, so nothing is registered under that id any more. " //$NON-NLS-1$
                + "The id is the one compare_configurations returned; get_comparison_node says " //$NON-NLS-1$
                + "whether a comparison under that id can still be read. Or omit " //$NON-NLS-1$
                + KEY_COMPARISON_ID
                + " to write without validation - which is not necessarily authoring from names " //$NON-NLS-1$
                + "alone: with no id this tool resolves whichever comparison is RUNNING, which " //$NON-NLS-1$
                + "may be a different one. Either way the report says the rules were NOT " //$NON-NLS-1$
                + "validated, and it names the comparison when one answered.").toJson(); //$NON-NLS-1$
        }
        if (zipped && !isSet(zipEntryId))
        {
            // The one case this tool will not author from names. Every other unvalidated write
            // still produces a file the platform reads; a zip whose entry is named anything but
            // the launching comparison's own triple is SKIPPED by EDT with a log warning and the
            // launch proceeds with no decisions at all - so a guessed name would hand back a file
            // reported as written that can never apply. There is nothing to say in a caveat
            // either, because the failure is silent on the platform's side.
            return ToolResult.error("Nothing was written: a '" + MergeRulesCodec.ZIP_EXTENSION //$NON-NLS-1$
                + "' merge-rules file carries an entry named after the comparison's three " //$NON-NLS-1$
                + "PROJECT names - EDT restores that entry and IGNORES an archive whose ENTRY is " //$NON-NLS-1$
                + "named anything else, applying nothing and reporting nothing; the name of the " //$NON-NLS-1$
                + "FILE itself is yours to choose - and no comparison answered here, so the " //$NON-NLS-1$
                + "entry name cannot be known. Start the comparison with compare_configurations " //$NON-NLS-1$
                + "and re-send this write; naming it with " + KEY_COMPARISON_ID //$NON-NLS-1$
                + " also asks for the rules to be checked, which needs its tree to have " //$NON-NLS-1$
                + "FINISHED. Or write '" + MergeRulesCodec.XML_EXTENSION //$NON-NLS-1$
                + "' instead - which needs no comparison, and which EDT 2026.1 reads directly " //$NON-NLS-1$
                + "while EDT 2026.2 does not read it at all.") //$NON-NLS-1$
                .toJson();
        }
        if (!validated)
        {
            // Asked here and not at the parse, because until this point it was not known whether
            // anything would be in a position to answer it. A validated write has had every key
            // looked up in the comparison, which settles the one spelling a key cannot settle by
            // itself; unvalidated, nothing can, and the refusal says so rather than choosing.
            String unresolved = unresolvedAbsentSpellingRefusal(requested, comparison.isPresent());
            if (unresolved != null)
            {
                return unresolved;
            }
        }
        if (refusal != null)
        {
            return refusal;
        }

        try
        {
            if (zipEntryId == null)
            {
                MergeRulesCodec.write(file, document, targetPolicy);
            }
            else
            {
                MergeRulesCodec.writeZip(file, document, targetPolicy, zipEntryId);
            }
        }
        catch (FileAlreadyExistsException e)
        {
            if (targetPolicy != MergeRulesCodec.Target.MUST_NOT_EXIST)
            {
                // Not the reservation, then, but some other name the write collided with - the
                // temporary, say. Only the reservation entitles this call to say a rules file
                // got there first, and describing an unrelated collision that way would name a
                // cause nobody observed.
                return ToolResult.error("Could not write the merge-rules file " + file + ": " //$NON-NLS-1$ //$NON-NLS-2$
                    + describe(e)).toJson();
            }
            // The path was free when this call checked it and is not free any more: another write
            // claimed it in between. Reported as its own refusal rather than as a generic I/O
            // failure, because it means the same thing the guard above means - a file with
            // decisions in it would be replaced - and the way out is the same one.
            return ToolResult.error("Nothing was written: a file appeared at " + file //$NON-NLS-1$
                + " while this call was preparing its decisions, so writing now would discard " //$NON-NLS-1$
                + "the decisions it holds. Read it with mode '" + MODE_READ //$NON-NLS-1$
                + "' and, if you still want to update it, re-send this write with " + KEY_BASED_ON //$NON-NLS-1$
                + "='" + file + "'.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (IOException e)
        {
            return ToolResult.error("Could not write the merge-rules file " + file + ": " //$NON-NLS-1$ //$NON-NLS-2$
                + describe(e)).toJson();
        }
        return renderWrite(file, basedOn, existingDecisions, requested, replaced, comparison, validated,
            document, limit, zipEntryId);
    }

    /**
     * Largest number of foreign entry names a refusal or a report spells out before it counts the
     * rest. The same shape the codec uses for its own entry listings: a huge archive costs a
     * sentence rather than a page.
     */
    private static final int MAX_LISTED_OTHER_ENTRIES = 10;

    /**
     * Refuses a same-path rewrite of an archive that holds entries this tool did not read.
     *
     * <h2>The loss this exists to prevent</h2>
     * A zipped merge-settings file is a CONTAINER. {@code MergeRulesCodec.read} takes the one
     * merge-settings entry out of it and is content to do so while other entries sit beside it -
     * a {@code notes.txt}, a file somebody dropped in, anything - because one candidate is still
     * unambiguous. The write then produces a NEW single-entry archive and moves it over the path.
     * Every other entry is gone, and the report says only how many merge rules were recorded: our
     * write destroying data that was never ours, reported as a success. That is the same shape as
     * the reservation defect this feature already fixed, and a declared boundary did not shield
     * data loss there either.
     *
     * <h2>Why refusing, rather than copying the other entries through</h2>
     * Copying looks kinder and is the worse answer, for three reasons that were weighed rather
     * than assumed.
     * <ul>
     * <li><b>It re-writes bytes nothing here validated.</b> {@code MAX_DOCUMENT_BYTES} bounds the
     * entry this codec reads; a foreign entry is bounded by nothing, and this very codec DELETED a
     * whole-archive copy for exactly that - see {@code MergeRulesCodec#copyAddressedEntry}, whose
     * javadoc names the multi-gigabyte neighbour it used to duplicate byte for byte. Re-adding one
     * at the write door would put back the defect the read door removed.</li>
     * <li><b>The result could not be described honestly.</b> The JDK offers no raw entry copy, so
     * a carried-through entry is inflated and re-deflated: a different compression, different
     * metadata, and no claim of "unchanged" this tool could support. A report that said the
     * entries were kept would be making the promise this class exists not to make.</li>
     * <li><b>Refusing costs one step, and that step loses nothing.</b> Point {@code filePath} at a
     * path of its own: the rules land in a clean single-entry archive, the original keeps
     * everything it had, and both are readable afterwards.</li>
     * </ul>
     *
     * <h2>The answer is the same at every door, which is why only one door asks</h2>
     * A write to a path that is NOT the starting file is already refused when something is there
     * (see the guard at the top of {@code writeUnderMutex}), and a write to a free path creates a
     * new archive and destroys nothing. So the one door through which an existing archive gets
     * replaced is the same-path rewrite, and this is it. What the other door still owes the caller
     * is a description rather than a refusal - the new file holds the merge-settings entry alone,
     * while the archive it started from was only read - and {@link #otherEntriesClause} says it.
     * One rule stated at two doors, because the outcomes differ: this tool never destroys an entry
     * it did not read, and it never lets a caller believe a copy was made that was not.
     *
     * @param file the write target
     * @param targetPolicy what was established about the path; only
     *     {@link MergeRulesCodec.Target#MAY_BE_REPLACED} is a rewrite of the very file the
     *     document came out of
     * @param document the starting document, carrying what its container held besides it
     * @return the rendered refusal, or {@code null} when nothing would be destroyed
     */
    private static String sidecarEntriesRefusal(Path file, MergeRulesCodec.Target targetPolicy,
        MergeRulesDocument document)
    {
        String held = describeWhatElseTheContainerHeld(document);
        if (targetPolicy != MergeRulesCodec.Target.MAY_BE_REPLACED || held == null)
        {
            return null;
        }
        return ToolResult.error("Nothing was written: " + file + " is an archive that also holds " //$NON-NLS-1$ //$NON-NLS-2$
            + held
            + ", and this write replaces the WHOLE archive with one holding the merge-settings " //$NON-NLS-1$
            + "entry alone - so that would be destroyed, while the report named only the merge " //$NON-NLS-1$
            + "rules. It is not copied through either: this tool did not read it, cannot bound " //$NON-NLS-1$
            + "its size, and re-compressing an entry is not the same as keeping it. Write to a " //$NON-NLS-1$
            + "path of its own instead - keep " + KEY_BASED_ON + "='" + file //$NON-NLS-1$ //$NON-NLS-2$
            + "' and give " + KEY_FILE_PATH //$NON-NLS-1$
            + " a different path: the decisions this archive holds are carried into a clean " //$NON-NLS-1$
            + "single-entry file while this one keeps everything it has. Or extract the " //$NON-NLS-1$
            + "merge-settings entry yourself and manage the archive outside this tool.").toJson();
    }

    /**
     * Names everything the container held besides the merge-settings entry that was read - the
     * entries, the archive COMMENT, or both - or {@code null} when it held nothing else.
     * <p>
     * The comment is named separately and never counted as an entry: a caller told "1 other
     * entry" who then finds a comment gone was told something false. It is destroyed by a rewrite
     * for the same reason an entry is - the replacement is a fresh archive that carries none.
     *
     * @param document the document, carrying what its container held besides it
     * @return the phrase, or {@code null} when there is nothing else
     */
    private static String describeWhatElseTheContainerHeld(MergeRulesDocument document)
    {
        List<String> others = document.unreadContainerEntries();
        boolean comment = document.containerCarriedComment();
        if (others.isEmpty() && !comment)
        {
            return null;
        }
        if (others.isEmpty())
        {
            return "an archive comment"; //$NON-NLS-1$
        }
        return describeOtherEntries(others) + (comment ? " and an archive comment" : ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Names the entries a container held besides the one that was read, keeping at most
     * {@link #MAX_LISTED_OTHER_ENTRIES} of them and COUNTING the rest.
     *
     * @param others the other entry names, never empty
     * @return the phrase, e.g. {@code 1 other entry (notes.txt)}
     */
    private static String describeOtherEntries(List<String> others)
    {
        int shown = Math.min(others.size(), MAX_LISTED_OTHER_ENTRIES);
        // "and N more" rather than the report's "(showing X of Y)" notice: this is one clause
        // inside a sentence, and a parenthesis nested in a parenthesis is harder to read than the
        // count it carries. The wording is the codec's own, which lists entries the same way.
        return others.size() + (others.size() == 1 ? " other entry (" : " other entries (") //$NON-NLS-1$ //$NON-NLS-2$
            + String.join(", ", others.subList(0, shown)) //$NON-NLS-1$
            + (shown < others.size() ? " and " + (others.size() - shown) + " more" : "") + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /**
     * The line that says what became of the entries the STARTING archive held beside its
     * merge-settings document, when the write went to a different path.
     * <p>
     * Nothing was destroyed here - the starting archive was only read - but the file produced is
     * not a copy of it, and a caller who believed it was would carry a container forward that has
     * quietly lost everything except the rules. The same fact at the other door is a refusal
     * rather than a line; {@link #sidecarEntriesRefusal} says why the two differ.
     *
     * @param document the starting document, carrying what its container held besides it
     * @param basedOn the starting path exactly as it arrived, for the sentence
     * @return the line ending in a newline, or an empty string when there is nothing to say
     */
    private static String otherEntriesClause(MergeRulesDocument document, String basedOn)
    {
        List<String> others = document.unreadContainerEntries();
        boolean comment = document.containerCarriedComment();
        if (others.isEmpty() && !comment)
        {
            return ""; //$NON-NLS-1$
        }
        // The entry names are the ARCHIVE's text, not this server's, and they land in a report
        // that HAS structure - the same reason the source label and the target path go through
        // this helper rather than into the line raw.
        StringBuilder line = new StringBuilder("- Other entries: the archive at "); //$NON-NLS-1$
        line.append(MarkdownUtils.inlineCode(basedOn)).append(" also held "); //$NON-NLS-1$
        int shown = Math.min(others.size(), MAX_LISTED_OTHER_ENTRIES);
        for (int i = 0; i < shown; i++)
        {
            line.append(i == 0 ? "" : ", ").append(MarkdownUtils.inlineCode(others.get(i))); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (shown < others.size())
        {
            line.append(" and ").append(others.size() - shown).append(" more"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (comment)
        {
            line.append(others.isEmpty() ? "an archive comment" : " and an archive comment"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // What is NOT said here: that the file written is an archive. It is whatever filePath
        // picked, and pointing an archive's decisions at an '.xml' target is a legitimate write -
        // a sentence calling the result "a new archive" would be false for exactly that call.
        // The claim the caller needs is about CONTENT, and it is true of both containers.
        return line.append(". **That is NOT in the file written here**, which carries the " //$NON-NLS-1$
            + "merge-settings document and nothing else. Nothing was removed from the archive " //$NON-NLS-1$
            + "above; it was only read.\n") //$NON-NLS-1$
            .toString();
    }

    /**
     * The line that says what was lost from the merge-settings ENTRY itself - a zip entry comment
     * or an extra field.
     * <p>
     * A line rather than a refusal, and the difference is not a compromise: a sidecar entry is
     * data this tool was never asked to touch, while THIS entry is the one the caller asked to
     * replace. The replacement is a new entry by construction - named after the comparison EDT
     * looks for, holding the document just authored - so refusing over an attribute of it would
     * refuse the operation. What the caller is owed is not a veto but the fact, and it is stated
     * here rather than discovered later.
     *
     * @param document the starting document
     * @return the line ending in a newline, or an empty string when the entry carried nothing
     */
    private static String replacedEntryMetadataClause(MergeRulesDocument document)
    {
        if (!document.readEntryCarriedMetadata())
        {
            return ""; //$NON-NLS-1$
        }
        return "- Entry metadata: the merge-settings entry this write started from carried a zip " //$NON-NLS-1$
            + "entry comment or an extra field, and **that did not come across**. The entry " //$NON-NLS-1$
            + "written here is a new one - it is named after the comparison EDT looks for and " //$NON-NLS-1$
            + "holds the document authored by this call - so nothing of the old entry's own " //$NON-NLS-1$
            + "attributes is carried. Nothing EDT reads is affected: it matches the entry NAME " //$NON-NLS-1$
            + "and reads the content.\n"; //$NON-NLS-1$
    }

    /**
     * States which container was written and which EDT reads it.
     * <p>
     * The two containers are not interchangeable and the difference is invisible in the file: a
     * {@code .zip} is read by every supported EDT but only by a comparison whose three PROJECT
     * names spell its entry, while an {@code .xml} is read by any comparison but only on EDT
     * 2026.1 - 2026.2 refuses it outright ({@code Can read merge settings from a zip file}). A
     * report that named neither would leave the caller to discover the version limit inside the
     * launch it was preparing.
     * <p>
     * The zip line says PROJECT NAMES rather than "this comparison", because that is what the
     * name is made of and the difference is the whole risk: another comparison over the same three
     * projects - a later run, other revisions - restores the same entry and applies decisions
     * taken about something else.
     * <p>
     * What it deliberately does NOT say is that no other comparison can reach the file. The name
     * is a bare concatenation with {@code _}, a character project names may contain, so the
     * triples {@code (A_B, C, D)} and {@code (A, B_C, D)} spell the same entry - the mapping is
     * not injective, and a claim of exclusivity would be one this tool cannot support. The line
     * states the direction that does hold and names the separator, so a caller whose project
     * names carry underscores can see the risk instead of being told it does not exist.
     *
     * @param zipEntryId the entry the zip was written under, or {@code null} for a bare xml file
     * @return the line, ending in a newline
     */
    private static String containerClause(String zipEntryId)
    {
        if (zipEntryId == null)
        {
            return "- Container: '" + MergeRulesCodec.XML_EXTENSION //$NON-NLS-1$
                + "' - read by EDT 2026.1 and by ANY comparison, because the document carries no " //$NON-NLS-1$
                + "address. **EDT 2026.2 does not read it**: its merge-settings reader takes a '" //$NON-NLS-1$
                + MergeRulesCodec.ZIP_EXTENSION + "' alone and fails the launch instead. Write '" //$NON-NLS-1$ //$NON-NLS-2$
                + MergeRulesCodec.ZIP_EXTENSION + "' (which needs a live comparison to name its " //$NON-NLS-1$
                + "entry) for a file that works on both.\n"; //$NON-NLS-1$
        }
        // The entry name is the PLATFORM's text - three project names joined by an underscore -
        // and it reaches this line unvalidated, so it goes through the same helper the source
        // label does rather than into a code span spelled out here: a backtick in a project name
        // closes a hand-written span and hands the rest of the sentence to the reader as markup.
        return "- Container: '" + MergeRulesCodec.ZIP_EXTENSION + "', entry " //$NON-NLS-1$ //$NON-NLS-2$
            + MarkdownUtils.inlineCode(zipEntryId + MergeRulesCodec.XML_EXTENSION)
            + " - read by EDT 2026.1 and 2026.2 alike, and ADDRESSED BY THAT EXACT STRING: " //$NON-NLS-1$
            + "EDT restores this entry for ANY comparison whose main, other and ancestor project " //$NON-NLS-1$
            + "names, joined by '_' in that order, spell it - this run or a later one over other " //$NON-NLS-1$
            + "revisions - so re-using the file re-applies these decisions. It is a string, not a " //$NON-NLS-1$
            + "set: swapping main and other spells a different entry, and because '_' is also a " //$NON-NLS-1$
            + "legal character in a project name the mapping is not one-to-one (main 'A_B' with " //$NON-NLS-1$
            + "other 'C' spells what main 'A' with other 'B_C' spells). What holds in one " //$NON-NLS-1$
            + "direction: a comparison whose own three names spell a DIFFERENT string finds no " //$NON-NLS-1$
            + "entry of its own here, applies nothing and says nothing. The name of the FILE is " //$NON-NLS-1$
            + "yours to choose; only the entry name and the lower-case '" //$NON-NLS-1$
            + MergeRulesCodec.ZIP_EXTENSION + "' matter.\n"; //$NON-NLS-1$
    }

    private String renderWrite(Path file, String basedOn, int existingDecisions, List<RequestedDecision> requested,
        int replaced, Optional<MergeRuleAuthority> comparison, boolean validated,
        MergeRulesDocument document, int limit, String zipEntryId)
    {
        // The target path through the SAME helper as the read report's source label, and for the
        // same reason: the path is the caller's text, not this server's. A file name may hold a
        // line break on the filesystems this server runs on, and concatenated into a heading that
        // break ends the heading and lets what follows be read as a new block - a second document
        // growing inside the report, indistinguishable from the real one. inlineCode cannot be
        // read as markup at all, so the heading stays one heading whatever the path is spelled
        // with.
        StringBuilder out = new StringBuilder("# Merge rules written: ") //$NON-NLS-1$
            .append(MarkdownUtils.inlineCode(file.toString())).append("\n\n"); //$NON-NLS-1$
        if (validated)
        {
            // "ONE reading" is claimed because it is now true, and it was the missing half of this
            // sentence: the decisions used to be checked one boundary each, against a tree the
            // engine may rebuild in between, so "checked against the comparison" named a state of
            // the comparison that never existed as a whole at any instant.
            out.append("**Validated against comparison `").append(comparison.get().comparisonId()) //$NON-NLS-1$
                .append("`.** Every decision IN THE FILE was checked against the rules its own " //$NON-NLS-1$
                    + "node allows before anything was written - the ones written now and the " //$NON-NLS-1$
                    + "ones carried in from " + KEY_BASED_ON + " alike - and all of them against " //$NON-NLS-1$ //$NON-NLS-2$
                    + "ONE reading of a tree that reported itself FINISHED, so no two of them " //$NON-NLS-1$
                    + "were judged by different states of it. The table below lists " //$NON-NLS-1$
                    + "only what this call requested.\n\n"); //$NON-NLS-1$
        }
        else if (comparison.isPresent())
        {
            // A comparison answered - which is why a zip written here IS addressed - but it could
            // not give a verdict on a single rule. Both facts are stated, because reporting only
            // the first would read as a checked file and reporting only the second would hide
            // where the entry name came from.
            //
            // The address half is claimed only where an address exists. An '.xml' document
            // carries none - which is what the Container line two lines below says about it, and
            // what makes it readable by any comparison - so on that target this branch used to
            // contradict its own report a paragraph later. zipEntryId is exactly the fact
            // "this file got an address", so it is what the sentence branches on.
            out.append("**NOT VALIDATED - comparison `").append(comparison.get().comparisonId()) //$NON-NLS-1$
                .append("` answered, but its tree could not be read.**") //$NON-NLS-1$
                .append(zipEntryId == null
                    ? " Not one rule was checked against it: the literals were checked" //$NON-NLS-1$
                    : " It named this file's entry, and nothing else: the literals were checked") //$NON-NLS-1$
                .append(" against the platform's rule vocabulary, while whether each rule is " //$NON-NLS-1$
                    + "legal for its own node needs a FINISHED tree, which an unfinished one " //$NON-NLS-1$
                    + "cannot substitute for - there a node that has not been compared yet is " //$NON-NLS-1$
                    + "indistinguishable from a node the comparison does not have. Re-run this " //$NON-NLS-1$
                    + "write once get_comparison_node reports the tree finished to have every " //$NON-NLS-1$
                    + "rule checked.\n\n"); //$NON-NLS-1$
        }
        else
        {
            // "without a comparison to check them against" and not "with no comparison running":
            // this branch is also reached when a comparison IS running but none answered for these
            // nodes, and the report may not name a cause it did not observe.
            out.append("**NOT VALIDATED - authored from names, without a comparison to check them " //$NON-NLS-1$
                + "against.** The literals were checked against the platform's rule vocabulary, but " //$NON-NLS-1$
                + "whether each rule is legal for its own node is knowable only from a live " //$NON-NLS-1$
                + "comparison whose tree has FINISHED. Start one with compare_configurations, wait " //$NON-NLS-1$
                + "until get_comparison_node reports its tree finished, and re-run this write to " //$NON-NLS-1$
                + "have every rule checked.\n\n"); //$NON-NLS-1$
        }
        out.append(containerClause(zipEntryId));
        out.append("- Decisions recorded: ").append(requested.size()).append(" (") //$NON-NLS-1$ //$NON-NLS-2$
            .append(requested.size() - replaced).append(" new, ").append(replaced).append(" replaced)\n"); //$NON-NLS-1$ //$NON-NLS-2$
        if (isSet(basedOn))
        {
            // The same label, through the same helper, for the same reason: 'basedOn' may name a
            // zip, and the label then ends in that archive's ENTRY name. See renderRead.
            out.append("- Based on: ").append(MarkdownUtils.inlineCode(document.sourceLabel())) //$NON-NLS-1$
                .append(carriedOverClause(existingDecisions, replaced));
            // Beside it, and only when there is something to say: what the starting archive held
            // that this file does NOT. The same-path rewrite that would have DESTROYED those
            // entries never gets here - it is refused - so reaching this line means the starting
            // archive still has them and the caller is being told the new file is not a copy.
            out.append(otherEntriesClause(document, basedOn));
            out.append(replacedEntryMetadataClause(document));
        }
        out.append("- Decisions in the file now: ").append(document.decisions().size()).append('\n'); //$NON-NLS-1$
        out.append("- Preserved sections this tool does not interpret: ") //$NON-NLS-1$
            .append(document.preservedSectionCount()).append("\n\n"); //$NON-NLS-1$
        // The SAME disclosure the read report makes, for the same reason and against the same
        // counter. A rule at an unreachable node arrives through basedOn and the rewrite carries it
        // through verbatim - so the file this call just wrote holds it - while neither number above
        // covers it: decisions() has no address to return it under, and the preserved-section count
        // measures BLOCKS rather than rules. Reported only by the read half, the write report
        // presented the file as holding exactly the rules it had listed, and the one rule nobody
        // can address stayed invisible to the caller who had just copied it forward.
        int unreachable = document.unreachableRuleCount();
        if (unreachable > 0)
        {
            out.append(unreachableRuleClause(unreachable)).append('\n');
        }
        out.append(MarkdownUtils.tableHeader("#", "Node", "Rule")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        int shown = Math.min(limit, requested.size());
        for (int i = 0; i < shown; i++)
        {
            RequestedDecision decision = requested.get(i);
            out.append(MarkdownUtils.tableRow(String.valueOf(i + 1), renderPath(decision.path), decision.rule));
        }
        if (shown < requested.size())
        {
            out.append('\n').append(Pagination.truncationNotice(shown, requested.size())).append('\n');
        }
        out.append("\n> Launch a comparison with this file to apply the decisions; the merge itself " //$NON-NLS-1$
            + "stays a human action in the comparison window.\n"); //$NON-NLS-1$
        return out.toString();
    }

    /**
     * What became of the decisions the {@code basedOn} document already held.
     *
     * <h2>Why the count is split rather than printed whole</h2>
     * A decision this call writes at a path the starting document already carried OVERWRITES the
     * rule that was there - that is what the {@code replaced} counter one line above counts - so
     * the rule that arrived in the document is not the rule that leaves it. Printing the whole
     * count as "kept" reported a file of ten carried-in decisions, one of them just overwritten,
     * as "1 replaced" and "10 decisions it already held were kept" in the same breath: an audit of
     * what was really carried over cannot be taken from that, because the two numbers describe
     * overlapping sets and the report never says which.
     * <p>
     * <b>Kept means the RULE, not the address.</b> The number below counts the decisions that
     * leave this write carrying the rule they arrived with. A replaced one keeps its address - it
     * is still a decision on that node, and it is still counted in "Decisions in the file now" -
     * but the rule at it is this call's, which is exactly what an audit of the starting document
     * must not be told was preserved. The replaced ones are named on the same line so neither
     * number has to be read as the other's complement.
     *
     * @param existingDecisions how many addressable decisions the starting document held
     * @param replaced how many of them this call wrote over
     * @return the parenthesised clause, newline-terminated
     */
    private static String carriedOverClause(int existingDecisions, int replaced)
    {
        if (replaced == 0)
        {
            return " (" + existingDecisions + " decisions it already held were kept)\n"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return " (of the " + existingDecisions + " decisions it already held, " //$NON-NLS-1$ //$NON-NLS-2$
            + (existingDecisions - replaced) + " kept the rule they arrived with; this call " //$NON-NLS-1$
            + "replaced " + replaced + ")\n"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String renderPath(List<String> path)
    {
        return renderFullPath(fullPathOf(path));
    }

    /**
     * Refuses two decisions in one call that address the SAME node.
     *
     * <h2>Why this is a refusal and not "the last one wins"</h2>
     * The document is a tree keyed by path, so a second decision on a path simply overwrites the
     * node the first one set. Only one rule reaches the file - and the report then counts what the
     * CALL carried, so it says "2 decisions recorded (2 new)" for a file holding one. This tool's
     * contract runs the other way: the report never claims more than happened. A silent overwrite
     * is also not a thing the caller can have meant, because there is no way to tell which of the
     * two they wanted; the array simply says two different things about one node.
     * <p>
     * <b>Both positions, and the path as this tool READ it.</b> The positions are what the caller
     * edits, and the normalised path is what makes a non-obvious collision legible - the two
     * spellings may look nothing alike in the request ({@code Catalog} and {@code catalogs} are
     * one collection) and rendering the raw input would leave the caller unable to see why they
     * collided. The rendered path is this tool's own text, built from keys that have already
     * passed every character check above.
     *
     * @param first the 1-based position of the decision that claimed the path
     * @param second the 1-based position of the decision that collided with it
     * @param path the normalised key chain below the root
     * @return the refusal
     */
    private static String duplicatePathRefusal(int first, int second, List<String> path)
    {
        return ToolResult.error("Nothing was written: decisions #" + first + " and #" + second //$NON-NLS-1$ //$NON-NLS-2$
            + " in '" + KEY_DECISIONS + "' both address " + renderPath(path) //$NON-NLS-1$ //$NON-NLS-2$
            + ". Only one rule can be recorded on a node, so the later one would silently " //$NON-NLS-1$
            + "overwrite the earlier and the report would count both as written. Send one " //$NON-NLS-1$
            + "decision per node - keep the rule you want and drop the other. Two keys that " //$NON-NLS-1$
            + "look different can still be one node: a collection is normalised to its model " //$NON-NLS-1$
            + "feature name, so 'Catalog' and 'catalogs' are the same address.").toJson(); //$NON-NLS-1$
    }

    /**
     * @param path the keys below the root
     * @return the full key chain, starting at {@link MergeRulesDocument#ROOT_KEY}
     */
    private static List<String> fullPathOf(List<String> path)
    {
        List<String> full = new ArrayList<>();
        full.add(MergeRulesDocument.ROOT_KEY);
        full.addAll(path);
        return full;
    }

    /**
     * @param fullPath a key chain that already starts at {@link MergeRulesDocument#ROOT_KEY}
     * @return the chain as one readable address
     */
    private static String renderFullPath(List<String> fullPath)
    {
        return String.join(" / ", fullPath); //$NON-NLS-1$
    }

    /**
     * Refuses a {@code decisions} array that carries an element which is not a JSON object.
     *
     * <h2>Write mode alone asks this, and that is the division of labour</h2>
     * This sentence names the offending ELEMENT, so it is a statement about content and belongs
     * where content is being recorded. Read mode does not ask it: there the only question is
     * whether the parameter was supplied at all, which {@link #suppliedWriteOnlyParameters}
     * answers for all three write-only parameters at once. Read mode did route through here once,
     * and the effect was to narrow its question back to "an array holding a non-object", which
     * read an empty array, an object and a scalar as an absent parameter.
     *
     * @param params the call arguments
     * @return the rendered refusal, or {@code null} when every element is an object (or the value is
     *         not a parsable array at all, which the existing checks already report)
     */
    private static String nonObjectDecisionRefusal(Map<String, String> params)
    {
        String raw = params == null ? null : params.get(KEY_DECISIONS);
        if (raw == null || !raw.trim().startsWith("[")) //$NON-NLS-1$
        {
            return null;
        }
        JsonArray array;
        try
        {
            JsonElement element = JsonParser.parseString(raw.trim());
            if (!element.isJsonArray())
            {
                return null;
            }
            array = element.getAsJsonArray();
        }
        catch (RuntimeException e)
        {
            return null;
        }
        for (int i = 0; i < array.size(); i++)
        {
            if (array.get(i).isJsonObject())
            {
                continue;
            }
            return ToolResult.error("Nothing was written: decision #" + (i + 1) + " in '" //$NON-NLS-1$ //$NON-NLS-2$
                + KEY_DECISIONS + "' is not an object. Each element is {path, rule} - for example " //$NON-NLS-1$
                + "{\"path\": [\"commonModules\", \"Main:Main:Main\"], \"rule\": \"GetFromOther\"}.") //$NON-NLS-1$
                .toJson();
        }
        return null;
    }

    /**
     * Parses and validates one requested decision.
     *
     * @param raw the decision object as sent
     * @param position its 1-based position, for the error message
     * @return the parsed decision, or the refusal explaining why it is not usable
     */
    private ParsedDecision parseDecision(JsonObject raw, int position)
    {
        JsonElement ruleElement = raw.get(FIELD_RULE);
        if (ruleElement == null || !ruleElement.isJsonPrimitive())
        {
            return ParsedDecision.refused(ToolResult.error("Decision #" + position + " has no '" //$NON-NLS-1$ //$NON-NLS-2$
                + FIELD_RULE + "'. Each " + KEY_DECISIONS //$NON-NLS-1$
                + " element is {path, rule}, where rule is one of " //$NON-NLS-1$
                + String.join(", ", AUTHORABLE_RULES) + ".").toJson()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String rule = ruleElement.getAsString();
        String ruleRefusal = validateRuleLiteral(rule, position);
        if (ruleRefusal != null)
        {
            return ParsedDecision.refused(ruleRefusal);
        }

        List<String> path = new ArrayList<>();
        JsonElement pathElement = raw.get(FIELD_PATH);
        if (pathElement == null || pathElement.isJsonNull())
        {
            // The widest rule this tool can write is the one it must never write by accident. An
            // absent (or misspelled, or null) 'path' used to leave the key chain empty, which is
            // the SAME chain an explicit [] produces - so a typo in the field name silently turned
            // a decision meant for one object into a rule over the whole configuration, and the
            // report called it a root decision as if that had been asked for. It is refused by
            // position, like every other malformed decision, and [] stays the one way to say
            // "everything".
            return ParsedDecision.refused(ToolResult.error("Decision #" + position + " has no '" //$NON-NLS-1$ //$NON-NLS-2$
                + FIELD_PATH + "'. Nothing was written. The whole configuration is addressed by an " //$NON-NLS-1$
                + "EXPLICIT empty array - {\"" + FIELD_PATH + "\": [], \"" + FIELD_RULE //$NON-NLS-1$ //$NON-NLS-2$
                + "\": \"" + rule + "\"} - so that a rule over everything is never the result of a " //$NON-NLS-1$ //$NON-NLS-2$
                + "missing field. For one collection send [\"commonModules\"], for one object " //$NON-NLS-1$
                + "[\"commonModules\", \"Main:Main:Main\"].").toJson()); //$NON-NLS-1$
        }
        if (pathElement.isJsonArray())
        {
            JsonArray array = pathElement.getAsJsonArray();
            for (int index = 0; index < array.size(); index++)
            {
                JsonElement segment = array.get(index);
                // A key is TEXT the platform matches in the file. Every JSON scalar has a string
                // form, so accepting any primitive silently turned a number or a boolean into one
                // - [true] was recorded as Key="true" and reported as written, while EDT's reader
                // has no node called that and never would. The type is checked before the
                // conversion, and the refusal says which key it was, like every other malformed
                // decision this tool refuses by position.
                if (!segment.isJsonPrimitive() || !segment.getAsJsonPrimitive().isString())
                {
                    return ParsedDecision.refused(ToolResult.error("Decision #" + position //$NON-NLS-1$
                        + ": key #" + (index + 1) + " in '" + FIELD_PATH + "' is " + segment //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + ", which is not a string. A key is the text EDT matches against the " //$NON-NLS-1$
                        + "file - a collection's model feature name, or an object's three names - " //$NON-NLS-1$
                        + "so send it quoted: [] addresses the whole configuration, " //$NON-NLS-1$
                        + "['commonModules'] a collection, ['commonModules','A:A:A'] one object.") //$NON-NLS-1$
                        .toJson());
                }
                String key = segment.getAsString();
                if (key.isBlank())
                {
                    return ParsedDecision.refused(ToolResult.error("Decision #" + position //$NON-NLS-1$
                        + ": key #" + (index + 1) + " in '" + FIELD_PATH //$NON-NLS-1$ //$NON-NLS-2$
                        + "' is blank. Every key must name something: [] addresses the whole " //$NON-NLS-1$
                        + "configuration, ['commonModules'] a collection, " //$NON-NLS-1$
                        + "['commonModules','A:A:A'] one object.").toJson()); //$NON-NLS-1$
                }
                String unwritable = unwritableKeyRefusal(key, position, index + 1);
                if (unwritable != null)
                {
                    return ParsedDecision.refused(unwritable);
                }
                String padded = paddedKeyRefusal(key, position, index + 1);
                if (padded != null)
                {
                    return ParsedDecision.refused(padded);
                }
                // Added exactly as accepted. There is no trim here any more: it removed only what
                // is at or below U+0020, so it cleaned an ASCII-padded key while leaving a
                // U+2003-padded one to be recorded and never applied - and either way the key
                // written was not the key sent. Both halves of that are now refusals.
                path.add(key);
            }
        }
        else
        {
            return ParsedDecision.refused(ToolResult.error("Decision #" + position + " has a '" //$NON-NLS-1$ //$NON-NLS-2$
                + FIELD_PATH
                + "' that is not an array of keys. Send [] for the whole configuration, or the key " //$NON-NLS-1$
                + "chain below the root, e.g. ['commonModules','A:A:A'].").toJson()); //$NON-NLS-1$
        }
        if (!path.isEmpty() && MergeRulesDocument.ROOT_KEY.equals(path.get(0)))
        {
            // The chain is relative to the root; a caller who spelled the root marker out anyway
            // means the same node, so accept it rather than address a phantom child of the root.
            path.remove(0);
        }
        canonicalizeCollectionKey(path);
        String pathRefusal = validatePath(path, position);
        if (pathRefusal != null)
        {
            return ParsedDecision.refused(pathRefusal);
        }
        return ParsedDecision.parsed(new RequestedDecision(path, rule));
    }

    /**
     * Refuses a key holding a character XML itself cannot carry, naming WHERE it is and WHAT it is
     * by code point.
     * <p>
     * <b>Why this is not caught by any of the checks around it.</b> A control character is not
     * blank ({@code Character.isWhitespace} says no to {@code U+0001}), it is a JSON string, it is
     * not a position key and not an object key, so a segment holding one passes every check on the
     * way and is written into the file as the attribute value it is. Escaping does not save it
     * either: {@code &}, {@code <}, {@code >} and the quote are markup, and tab, newline and
     * carriage return are the only control characters XML has an escape FOR - the rest are not
     * legal characters in an XML 1.0 document at all, in any spelling. What lands on disk is then
     * a file EDT's reader cannot parse, so the caller loses not the one key but the whole rules
     * file, and this tool reported it as written.
     * <p>
     * <b>The set is the XML {@code Char} production, not "whatever prints".</b> XML 1.0 allows
     * tab, newline and carriage return, everything from {@code U+0020} to {@code U+D7FF},
     * {@code U+E000} to {@code U+FFFD}, and every code point above {@code U+FFFF}. A lone
     * surrogate is therefore refused while a well-formed surrogate PAIR is accepted, because the
     * pair is one code point in the last range - an emoji in an object name is legal XML and would
     * be wrong to refuse.
     * <p>
     * <b>Asked of the value as SENT, and the value as sent is what gets written.</b> There is
     * no normalisation anywhere on this path to be asked before: a {@code String.trim} used to
     * stand between this check and the file, and it deleted exactly this - a key that is nothing
     * but U+0001 became the EMPTY key, and one that merely starts with U+0001 was quietly
     * rewritten into a key the caller never sent, both the "reported as written, silently
     * something else" shape this tool refuses everywhere else. What that trim was there to tidy
     * is refused instead, by {@link #paddedKeyRefusal}.
     * <p>
     * <b>The character is named by CODE, never echoed.</b> The refusal travels back through the
     * same JSON the offending byte would have broken, so putting it in the message would carry the
     * problem into the answer about it.
     *
     * @param key the key exactly as the caller sent it
     * @param position the decision's position, for the message
     * @param keyNumber the key's position within the chain, 1-based
     * @return the refusal, or {@code null} when every character can be written
     */
    private static String unwritableKeyRefusal(String key, int position, int keyNumber)
    {
        int offset = firstUnwritableCharacter(key);
        if (offset < 0)
        {
            return null;
        }
        return ToolResult.error("Decision #" + position + ": key #" + keyNumber + " in '" //$NON-NLS-1$ //$NON-NLS-2$
            + FIELD_PATH + "' holds " + codePointName(key.charAt(offset)) //$NON-NLS-1$
            + " at character " + (offset + 1) //$NON-NLS-1$
            + ", which XML 1.0 cannot carry in any spelling - not even as an escape. Nothing was " //$NON-NLS-1$
            + "written: a rules file holding it is one EDT's reader rejects outright, so the " //$NON-NLS-1$
            + "whole file would be lost and not just this key. Only tab, newline and carriage " //$NON-NLS-1$
            + "return are legal below U+0020; a character like this usually arrives from a " //$NON-NLS-1$
            + "mis-decoded copy-paste. Re-send the key without it.").toJson(); //$NON-NLS-1$
    }

    /**
     * @param key the key to scan
     * @return the index of the first character XML 1.0 cannot carry, or {@code -1} when there is
     *         none
     */
    private static int firstUnwritableCharacter(String key)
    {
        for (int i = 0; i < key.length(); i++)
        {
            char current = key.charAt(i);
            if (Character.isHighSurrogate(current) && i + 1 < key.length()
                && Character.isLowSurrogate(key.charAt(i + 1)))
            {
                // A well-formed pair is one code point above U+FFFF, and XML carries all of those.
                i++;
                continue;
            }
            if (!isXmlCharacter(current))
            {
                return i;
            }
        }
        return -1;
    }

    /**
     * @param character one UTF-16 unit, already known not to be part of a well-formed surrogate
     *            pair
     * @return whether XML 1.0's {@code Char} production allows it
     */
    private static boolean isXmlCharacter(char character)
    {
        // Written as numbers, not as character escapes: a backslash-u escape in a source
        // file is translated by the Java lexer before the code is parsed, so the bounds of a
        // range would stop being readable as bounds.
        return character == '\t' || character == '\n' || character == '\r'
            || (character >= 0x20 && character <= 0xD7FF)
            || (character >= 0xE000 && character <= 0xFFFD);
    }

    /**
     * @param character the character to name
     * @return its code point in the {@code U+XXXX} spelling, so a refusal can point at it without
     *         carrying it - {@link PaddedNames#codePointName(char)}'s spelling, because the same
     *         whitespace character is named for a scope entry by the same words
     */
    private static String codePointName(char character)
    {
        return PaddedNames.codePointName(character);
    }


    /**
     * Refuses a key whose NAME is padded with whitespace, naming the character by code point and
     * saying where it sits.
     * <p>
     * <b>Why a refusal and not a silent clean-up.</b> Normalising is the same defect in a better
     * coat: the caller asked for one address and the file would carry another, and this tool's
     * whole contract is that what it reports recorded is what it wrote. It is also the answer that
     * stays true at the OTHER doors - a key carried in from {@code basedOn} is copied through
     * verbatim, because rewriting another document's keys is not this tool's job, and a write path
     * that stripped its own input would address a different node from the one the caller had just
     * read out of that file, silently adding a decision where they meant to replace one. Refusing
     * leaves exactly one rule for the whole tool: it never invents a key.
     * <p>
     * <b>The position is the UTF-16 offset, the unit {@link #unwritableKeyRefusal} already
     * reports.</b> A name holding a character above {@code U+FFFF} before the padding therefore
     * counts it as two, where a person counting on screen counts one. Reporting a code-point
     * ordinal here would make the same word mean two different things in two refusals about the
     * same key, and the sibling refusal cannot use code points at all: it points at a LONE
     * surrogate, which is not a code point. One unit for both, named rather than assumed.
     * <p>
     * <b>Named by code, not echoed.</b> Every other refusal here quotes the offending key back,
     * and this is the one where the echo would carry the offending character instead of naming
     * it: what is wrong is a whitespace character, and the echo does not say which of them it is.
     * So this refusal states the code point and its offset. A key is not trimmed on its way in,
     * so an ordinary {@code U+0020} against a name's end is refused here exactly as
     * {@code U+2003}, {@code U+00A0} and their kin are.
     * <p>
     * What counts as whitespace, why {@code isBlank} and {@code trim} disagreed about it, and
     * which characters are deliberately NOT covered are all settled by
     * {@link MergeRulesDocument#firstPaddedNameCharacter(String)}, which owns what a key is made
     * of; this method only phrases the answer.
     *
     * @param key the key exactly as the caller sent it
     * @param position the decision's position, for the message
     * @param keyNumber the key's position within the chain, 1-based
     * @return the refusal, or {@code null} when no name in the key is padded
     */
    private static String paddedKeyRefusal(String key, int position, int keyNumber)
    {
        int offset = MergeRulesDocument.firstPaddedNameCharacter(key);
        if (offset < 0)
        {
            return null;
        }
        return ToolResult.error("Decision #" + position + ": key #" + keyNumber + " in '" //$NON-NLS-1$ //$NON-NLS-2$
            + FIELD_PATH + "' has " + codePointName(key.charAt(offset)) //$NON-NLS-1$
            + ", a whitespace character, at character " + (offset + 1) //$NON-NLS-1$
            + ", where a name begins or ends. Nothing was written: EDT matches node keys by exact " //$NON-NLS-1$
            + "string equality and no key it writes holds whitespace, so a padded name matches no " //$NON-NLS-1$
            + "node in any comparison - the decision would be recorded and never applied. It is " //$NON-NLS-1$
            + "not trimmed for you, because a key this tool rewrote is no longer the key you " //$NON-NLS-1$
            + "asked for. Re-send it without the padding: ['commonModules'] for a collection, " //$NON-NLS-1$
            + "['commonModules','A:A:A'] for one object.").toJson(); //$NON-NLS-1$
    }

    /**
     * Rewrites the collection key into the model FEATURE name the platform keys that node by.
     * <p>
     * The platform writes {@code commonModules} / {@code catalogs} - the EMF feature name - and
     * matches the file by string equality, so a caller who addressed the collection by its
     * metadata TYPE token ({@code Catalog}, {@code Catalogs}, and the Russian forms of both) would
     * otherwise get a decision recorded under a key EDT's reader never matches: reported as
     * written, silently never applied. Every other address in this feature already goes through
     * the shared bilingual resolvers, and this is the same question asked of the same table.
     * <p>
     * A key the table does not recognise is left EXACTLY as sent. The legal keys are the
     * platform's whole feature catalogue - which includes features that are not metadata types at
     * all ({@code version}, {@code defaultLanguage}) - so refusing what this table cannot resolve
     * would reject correct input. Whether such a key exists is a question only a live comparison
     * can answer, and with one it IS answered, by the node lookup.
     *
     * @param path the key chain below the root, modified in place
     */
    private static void canonicalizeCollectionKey(List<String> path)
    {
        if (path.isEmpty())
        {
            return;
        }
        String key = path.get(0);
        // The SHAPE, not the well-formedness: a key spelled like a top-object key is one the
        // caller meant as a top-object key, however badly, and running it through the type-token
        // table would rewrite it into something they never sent. Whether its three components name
        // anything is validatePath's question.
        if (MergeRulesDocument.hasTopObjectKeyShape(key) || MergeRulesDocument.isPositionKey(key))
        {
            return;
        }
        String featureName = MetadataTypeUtils.getConfigReferenceName(key);
        if (featureName != null)
        {
            path.set(0, featureName);
        }
    }

    private String validateRuleLiteral(String rule, int position)
    {
        if (MergeRule.get(rule) == null)
        {
            return ToolResult.error("Decision #" + position + ": '" + rule + "' is not a merge rule. " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "The file spells rules the way the platform does, in camel case - " //$NON-NLS-1$
                + String.join(", ", AUTHORABLE_RULES) //$NON-NLS-1$
                + " - not as a Java constant (GET_FROM_OTHER is not a rule literal).").toJson(); //$NON-NLS-1$
        }
        if (REFUSED_RULES.contains(rule))
        {
            return ToolResult.error("Decision #" + position + ": '" + rule + "' is refused. It names a " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "merge whose behaviour is configured elsewhere - a custom merge carries its own " //$NON-NLS-1$
                + "nested settings, an external-tool merge names the tool - so the bare literal " //$NON-NLS-1$
                + "would record a decision nobody made here. Set it in the comparison window; this " //$NON-NLS-1$
                + "tool authors " + String.join(", ", AUTHORABLE_RULES) + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (!AUTHORABLE_RULES.contains(rule))
        {
            return ToolResult.error("Decision #" + position + ": '" + rule + "' is a merge rule this " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "tool does not author. It writes " + String.join(", ", AUTHORABLE_RULES) + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return null;
    }

    private String validatePath(List<String> path, int position)
    {
        if (path.size() > MergeRulesDocument.MAX_AUTHORABLE_DEPTH)
        {
            return ToolResult.error("Decision #" + position + " addresses a node " + path.size() //$NON-NLS-1$ //$NON-NLS-2$
                + " levels below the root; this tool authors at most " //$NON-NLS-1$
                + MergeRulesDocument.MAX_AUTHORABLE_DEPTH
                + " (the whole configuration, a collection, or one object). Below the object the " //$NON-NLS-1$
                + "platform keys nodes by a computed POSITION that shifts as soon as another rule " //$NON-NLS-1$
                + "changes, so a rule written there would land on whatever ends up at that " //$NON-NLS-1$
                + "position. Set the rule on the object and refine it in the comparison window.") //$NON-NLS-1$
                .toJson();
        }
        for (int i = 0; i < path.size(); i++)
        {
            String key = path.get(i);
            if (MergeRulesDocument.isPositionKey(key))
            {
                return ToolResult.error("Decision #" + position + " uses the key '" + key //$NON-NLS-1$ //$NON-NLS-2$
                    + "', which is a computed POSITION, not a name. Positions shift when other " //$NON-NLS-1$
                    + "rules change, so they are reported but never authored - address the " //$NON-NLS-1$
                    + "collection or the object by name instead.").toJson(); //$NON-NLS-1$
            }
            String emptySide = emptyTopObjectSideRefusal(key, position);
            if (emptySide != null)
            {
                return emptySide;
            }
            boolean topObjectLevel = i == MergeRulesDocument.MAX_AUTHORABLE_DEPTH - 1;
            if (topObjectLevel && !MergeRulesDocument.isTopObjectKey(key))
            {
                return ToolResult.error("Decision #" + position + ": '" + key + "' addresses an object, " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + "so it needs the object's name on all three sides - '" + key + ":" + key + ":" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + key + "' when the name is the same everywhere, '" + key //$NON-NLS-1$
                    + ":NONE:NONE' when only the main side has it, and three different names when " //$NON-NLS-1$
                    + "it was renamed. Read an existing rules file with mode 'read' to see the " //$NON-NLS-1$
                    + "exact keys.").toJson(); //$NON-NLS-1$
            }
            if (!topObjectLevel && MergeRulesDocument.hasTopObjectKeyShape(key))
            {
                return ToolResult.error("Decision #" + position + ": '" + key + "' is an object key " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + "(three names) at the collection level. A collection is keyed by the model " //$NON-NLS-1$
                    + "feature name alone, e.g. ['commonModules','" + key + "'].").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return null;
    }

    /**
     * Refuses a key that is SHAPED like a top-object key but leaves one of its three sides empty.
     * <p>
     * <b>Two colons are not proof of the shape.</b> {@code A::A} carries exactly two separators, so
     * the shape test passed it, and the middle component is not a name and not the platform's
     * {@code NONE} either - it is nothing. EDT matches these keys by string equality, so a decision
     * recorded under one matches no node in any comparison: reported as written, never applicable.
     * That is the failure mode this tool refuses everywhere else, and without a live comparison to
     * consult it is the only place the key can be caught at all.
     * <p>
     * Asked of EVERY level, not just the object level, so that the collection level keeps refusing
     * a three-part key rather than quietly recording one as a collection name.
     *
     * @param key one key of the chain, exactly as the caller sent it
     * @param position the decision's position, for the message
     * @return the refusal, or {@code null} when the key is not a malformed top-object key
     */
    private static String emptyTopObjectSideRefusal(String key, int position)
    {
        List<String> empty = MergeRulesDocument.emptyTopObjectKeySides(key);
        if (empty.isEmpty())
        {
            return null;
        }
        String sides = empty.size() == 1 ? "the " + empty.get(0) + " side is" //$NON-NLS-1$ //$NON-NLS-2$
            : "the " + String.join(" and ", empty) + " sides are"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return ToolResult.error("Decision #" + position + ": '" + key + "' has the three parts of " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "an object key, but " + sides + " empty. Nothing was written. Every part must NAME " //$NON-NLS-1$ //$NON-NLS-2$
            + "something: the object's name on that side, or the literal " //$NON-NLS-1$
            + MergeRulesDocument.SIDE_ABSENT + " when the object does not exist there. An empty " //$NON-NLS-1$
            + "part matches no node in any comparison, so the decision would be recorded and " //$NON-NLS-1$
            + "never applied. Send 'A:A:A' when the name is the same everywhere, 'A:" //$NON-NLS-1$
            + MergeRulesDocument.SIDE_ABSENT + ":" + MergeRulesDocument.SIDE_ABSENT //$NON-NLS-1$ //$NON-NLS-2$
            + "' when only the main side has it.").toJson(); //$NON-NLS-1$
    }

    /**
     * Refuses a key that spells the platform's {@code NONE} on all three sides, and ONLY when
     * nothing was in a position to resolve what it spells.
     *
     * <h2>Why this is not decided next to {@link #emptyTopObjectSideRefusal(String, int)}</h2>
     * That refusal is certain: a component that is empty is not a name and not the absence marker
     * either, so no comparison anywhere has a node for it. This one is NOT. {@code NONE} is how
     * the platform spells "this side has no such object" AND a legal 1C name - the platform's own
     * identifier predicate has no keyword list - so {@code NONE:NONE:NONE} reads two ways: an
     * object present on no side, which no comparison has a node for, or an object called
     * {@code NONE} on all three, which is an ordinary node that a comparison resolves by the same
     * string equality as any other. It used to be refused unconditionally, next to the empty-side
     * check, stating the first reading as fact - so a decision about a real node was rejected for
     * naming no object, while the running comparison would have found it.
     * <p>
     * <b>So the key is put to the comparison, and this is what happens when there is none.</b>
     * A validated write has already asked: {@code firstRefusedDecision} looks every decision up in
     * the snapshot and refuses the ones the comparison does not have, this key included and with a
     * message that says which node was missing. Unvalidated, nothing can tell the readings apart,
     * and the refusal says exactly that instead of picking one.
     *
     * <p>
     * <b>It is only ever reached with no comparison NAMED - which is not the same as none having
     * ANSWERED.</b> A call that names one and cannot be validated has already been refused above,
     * so {@code idGiven} is false by the time this runs; but with no id this tool still resolves
     * whichever comparison is RUNNING, and that one may have answered with a tree it could not
     * read. The two states get different sentences, because the way out differs: start a
     * comparison, against wait for the one already there. Told to "pass comparisonId" in the
     * second state, the caller lands on the unreadable-tree refusal above - advice that resolves
     * nothing.
     *
     * @param key one key of the chain, exactly as the caller sent it
     * @param position the decision's position, for the message
     * @param comparisonAnswered whether a comparison answered at all, though without a readable
     *            tree - the difference between "start one" and "wait for this one"
     * @return the refusal, or {@code null} when the key is not spelled that way
     */
    private static String unresolvedAbsentSpellingRefusal(String key, int position,
        boolean comparisonAnswered)
    {
        if (!MergeRulesDocument.spellsSideAbsentOnEveryTopObjectKeySide(key))
        {
            return null;
        }
        return ToolResult.error("Decision #" + position + ": '" + key + "' spells " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + MergeRulesDocument.SIDE_ABSENT + " on every side, and nothing here can tell what " //$NON-NLS-1$
            + "that means. Nothing was written. '" + MergeRulesDocument.SIDE_ABSENT //$NON-NLS-1$
            + "' is the platform's marker for 'the object does not exist on this side', and it " //$NON-NLS-1$
            + "is also a legal 1C name, so EACH of the three parts reads both ways on its own: " //$NON-NLS-1$
            + "this key addresses an ordinary node as soon as ANY side holds an object NAMED '" //$NON-NLS-1$
            + MergeRulesDocument.SIDE_ABSENT + "', and addresses nothing only if every side is " //$NON-NLS-1$
            + "truly absent - which no comparison has a node for, so the decision would be " //$NON-NLS-1$
            + "recorded and never applied. Only a comparison can say which: " //$NON-NLS-1$
            + (comparisonAnswered
                ? "the running comparison answered here, but its tree could not be read, so " //$NON-NLS-1$
                    + "nothing could look the key up. get_comparison_node says whether that " //$NON-NLS-1$
                    + "tree is still building; re-send this write once it has finished" //$NON-NLS-1$
                : "no comparison answered at all - start one with compare_configurations and " //$NON-NLS-1$
                    + "re-send this write, and the key is looked up instead of guessed at here") //$NON-NLS-1$
            + ". If you meant an object with a name, spell the name: 'A:A:A' when it is the same " //$NON-NLS-1$
            + "everywhere, 'A:" + MergeRulesDocument.SIDE_ABSENT + ":" //$NON-NLS-1$ //$NON-NLS-2$
            + MergeRulesDocument.SIDE_ABSENT + "' when only the main side has it. Read an existing " //$NON-NLS-1$
            + "rules file with mode '" + MODE_READ + "' to see the exact keys.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The first decision THIS CALL sent whose key spells {@code NONE} on every side, when no
     * comparison was in a position to resolve it.
     * <p>
     * Asked of every key of the chain, though in practice only the object level reaches it: a
     * three-part key at the COLLECTION level is refused during the parse by the shape rule, and
     * that refusal is certain where this one is not. The loop stays general so the question does
     * not depend on which level the shape rule happens to police.
     * <p>
     * Only the keys this call sent, matching where the check used to live (the parse of the
     * request). A key inherited through {@code basedOn} is already in a file somebody wrote, and
     * this tool does not refuse to rewrite what it can read.
     *
     * @param requested the decisions this call sent, in the order they arrived
     * @param comparisonAnswered whether a comparison answered without a readable tree
     * @return the refusal, or {@code null} when no requested key is spelled that way
     */
    private static String unresolvedAbsentSpellingRefusal(List<RequestedDecision> requested,
        boolean comparisonAnswered)
    {
        for (int i = 0; i < requested.size(); i++)
        {
            for (String key : requested.get(i).path)
            {
                String refusal = unresolvedAbsentSpellingRefusal(key, i + 1, comparisonAnswered);
                if (refusal != null)
                {
                    return refusal;
                }
            }
        }
        return null;
    }

    /**
     * Checks every decision THE FILE WOULD CARRY against the comparison, stopping at the first one
     * it refuses.
     * <p>
     * The document and not the request, because the report says the rules were checked and the
     * file is what the platform will read. A write started from {@code basedOn} carries decisions
     * this call never sent; validating only the new ones stamped "checked" on a file whose
     * inherited half nobody had looked at, and an inherited rule the comparison does not allow is
     * exactly as inapplicable as a fresh one.
     *
     * <p>
     * It reads ONE snapshot and never the live tree: the snapshot was taken in a single boundary,
     * so the whole document is judged by one state of the comparison. Asking the tree per decision
     * - which is what this used to do - let successive decisions be judged by successive states of
     * a tree the engine rebuilds as it goes, while the report claimed one comparison had checked
     * them all.
     *
     * @param comparisonId the comparison the snapshot was taken from, as refusals name it
     * @param snapshot the one reading every decision is judged by
     * @param document the document as it would be written
     * @param requestedPaths the full key chains this call set, so a refusal can say where the
     *            offending decision came from
     * @return the refusal for the first decision the comparison does not allow, or {@code null}
     *         when the whole document passed
     */
    private String firstRefusedDecision(String comparisonId, RuleSnapshot snapshot,
        MergeRulesDocument document, Set<List<String>> requestedPaths)
    {
        for (Decision decision : document.decisions())
        {
            String refusal = validateAgainstNode(comparisonId, snapshot, decision.path(),
                decision.rule(), !requestedPaths.contains(decision.path()));
            if (refusal != null)
            {
                return refusal;
            }
        }
        return null;
    }

    /**
     * @param comparisonId the comparison the snapshot was taken from
     * @param snapshot the one reading this decision is judged by
     * @param fullPath the key chain, starting at {@link MergeRulesDocument#ROOT_KEY}
     * @param rule the rule literal recorded at that node
     * @param inherited whether the decision came from {@code basedOn} rather than from this call
     * @return the refusal, or {@code null} when the comparison allows the rule
     */
    private String validateAgainstNode(String comparisonId, RuleSnapshot snapshot,
        List<String> fullPath, String rule, boolean inherited)
    {
        String node = renderFullPath(fullPath);
        String origin = inherited ? originNote(fullPath) : ""; //$NON-NLS-1$
        Optional<List<String>> allowed = snapshot.rulesAt(fullPath);
        if (allowed.isEmpty())
        {
            return ToolResult.error("Node '" + node + "' is not in comparison '" //$NON-NLS-1$ //$NON-NLS-2$
                + comparisonId + "'. A rule on a node the comparison does not have " //$NON-NLS-1$
                + "would never be applied - check the keys with get_comparison_node, or omit " //$NON-NLS-1$
                + KEY_COMPARISON_ID + " to author the file unvalidated." + origin).toJson(); //$NON-NLS-1$
        }
        if (allowed.get().isEmpty())
        {
            // An allowed set that is EMPTY is an answer, not a missing one: the comparison has the
            // node and offers no rule on it. Falling through would print "That node allows: " with
            // nothing after it, which reads as a broken message rather than as the platform's
            // verdict.
            return ToolResult.error("Comparison '" + comparisonId //$NON-NLS-1$
                + "' offers no merge rule on node '" + node //$NON-NLS-1$
                + "': the platform offers a choice only where a node may be merged. Nothing was " //$NON-NLS-1$
                + "written - set the rule on a node that does carry a choice (get_comparison_node " //$NON-NLS-1$
                + "shows the tree), or omit " + KEY_COMPARISON_ID //$NON-NLS-1$
                + " to author the file unvalidated." + origin).toJson(); //$NON-NLS-1$
        }
        if (!allowed.get().contains(rule))
        {
            return ToolResult.error("Rule '" + rule + "' is not allowed for node '" //$NON-NLS-1$ //$NON-NLS-2$
                + node + "' in comparison '" + comparisonId //$NON-NLS-1$
                + "'. That node allows: " + String.join(", ", allowed.get()) //$NON-NLS-1$ //$NON-NLS-2$
                + ". Nothing was written - the whole set is applied only once every decision " //$NON-NLS-1$
                + "passes." + origin).toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Says that the offending decision is one this call never sent, so the caller looks in the
     * right file for it.
     *
     * @param fullPath the key chain of the decision
     * @return the sentence to append to the refusal
     */
    private static String originNote(List<String> fullPath)
    {
        return " This decision was NOT sent by this call: it came in from " + KEY_BASED_ON //$NON-NLS-1$
            + " and would have been written to the new file, so it is checked too. Fix it in " //$NON-NLS-1$
            + "the " + KEY_BASED_ON + " file, or send a decision for '" + renderFullPath(fullPath) //$NON-NLS-1$ //$NON-NLS-2$
            + "' that this comparison allows."; //$NON-NLS-1$
    }

    // ==================== helpers ====================

    /**
     * Every address the file WOULD carry, deduplicated and in document order.
     * <p>
     * The whole set travels to the comparison at once, because the point of the batch is that ONE
     * boundary answers for all of them. Duplicates are dropped here rather than in the walk: two
     * decisions at one address are one question, and asking it twice would only widen the window
     * the batch exists to close.
     *
     * @param document the document as it would be written
     * @return the full key chains, each starting at {@link MergeRulesDocument#ROOT_KEY}
     */
    private static Set<List<String>> decisionPaths(MergeRulesDocument document)
    {
        Set<List<String>> paths = new LinkedHashSet<>();
        for (Decision decision : document.decisions())
        {
            paths.add(decision.path());
        }
        return paths;
    }

    private static boolean isSet(String value)
    {
        return value != null && !value.isBlank();
    }

    /**
     * Names the write-only parameters this call SUPPLIED, in schema order, whatever they hold.
     * <p>
     * <b>Presence, and nothing about content.</b> {@link #MODE_READ} promises that a write-only
     * parameter is refused rather than ignored, and that promise is about supply. Asking what a
     * value HOLDS answers a different and narrower question, and every narrower answer this branch
     * has carried let some payload through as "absent": the extracted decision list read
     * {@code "decisions":[null]} as absent, write mode's malformed-element rule read an empty
     * array, an object and a scalar as absent, and {@link #isSet} reads {@code ""} as absent. A
     * caller who meant to write and picked the wrong mode then got a report that looked like a
     * result.
     * <p>
     * <b>Write mode is deliberately not routed through this.</b> Write mode has to say WHICH
     * element is unusable, and {@link #nonObjectDecisionRefusal} still says it; one presence test
     * answering for both modes would replace that sentence with this one.
     * <p>
     * <b>The one shape this cannot see.</b> The protocol layer builds the argument map from the
     * request and skips a JSON {@code null} value outright, so {@code "decisions": null} on the
     * wire arrives here as no key at all and is read as absent. That is the transport's rule for
     * every tool, not a boundary this tool draws. A key that IS present while holding a
     * {@code null} value - which only a direct caller can construct - is supply, and is named.
     *
     * @param params the call arguments, may be {@code null}
     * @return the supplied names joined by {@code ", "}, or {@code null} when none was supplied
     */
    private static String suppliedWriteOnlyParameters(Map<String, String> params)
    {
        if (params == null)
        {
            return null;
        }
        List<String> supplied = new ArrayList<>();
        for (String name : WRITE_ONLY_PARAMETERS)
        {
            if (params.containsKey(name))
            {
                supplied.add(name);
            }
        }
        return supplied.isEmpty() ? null : String.join(", ", supplied); //$NON-NLS-1$
    }

    /**
     * Refuses a same-path update whose two paths are SEPARATE names for one file - hard links.
     * <p>
     * {@link #isSameFile} accepts them, and rightly so: they are one file, and the identity check
     * is asking whether the write replaces something the caller did not mean to lose. What the
     * identity check cannot see is that the write replaces a directory ENTRY rather than the
     * content behind it, so afterwards the two names are two different files - {@code filePath}
     * carrying the new rules and {@code basedOn} still carrying the old ones - while the report
     * says the ONE file they name was rewritten. A symbolic link is not this case and is not
     * refused: the codec follows it, so the file it names is the file that gets written and the
     * link survives.
     * <p>
     * The two are told apart WITHOUT any platform-specific attribute: one file reached through a
     * link resolves to one real path, whereas two hard links are two real paths of equal identity.
     * A filesystem that cannot answer is not evidence of anything and is left alone - the identity
     * was already established, and inventing a refusal from a failed question would refuse correct
     * input.
     *
     * @param file the target, known to exist and to be the same file as {@code base}
     * @param base the {@code basedOn} file, never {@code null} here
     * @return the refusal, or {@code null} when the two paths are one name for one file
     */
    private static String separateNameRefusal(Path file, Path base)
    {
        Path realFile;
        Path realBase;
        try
        {
            realFile = file.toRealPath();
            realBase = base.toRealPath();
        }
        catch (IOException e)
        {
            return null;
        }
        if (realFile.equals(realBase))
        {
            return null;
        }
        return ToolResult.error(KEY_FILE_PATH + " " + realFile + " and " + KEY_BASED_ON + " " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + realBase + " are two names for ONE file (hard links). Nothing was written. A write " //$NON-NLS-1$
            + "replaces a directory entry, not the content behind it, so it would leave " + realFile //$NON-NLS-1$
            + " holding the new rules and " + realBase //$NON-NLS-1$
            + " still holding the old ones - the two names would stop being the same file, and " //$NON-NLS-1$
            + "this report would have called that ONE file rewritten. Pass the SAME path as " //$NON-NLS-1$
            + KEY_FILE_PATH + " and " + KEY_BASED_ON + " to update that one file, or write to a " //$NON-NLS-1$ //$NON-NLS-2$
            + "path that is not a second name for it.").toJson(); //$NON-NLS-1$
    }

    /**
     * Whether two paths name the same file on disk. Asked of the filesystem rather than of the
     * strings, because a case difference or a link makes two spellings of ONE file compare
     * unequal - and treating a rewrite of one file as a write over another file's decisions
     * would refuse correct input.
     *
     * @param left an existing file
     * @param right the file to compare it with, or {@code null}
     * @return {@code true} when both name one file
     */
    private static boolean isSameFile(Path left, Path right)
    {
        if (right == null)
        {
            return false;
        }
        if (left.equals(right))
        {
            return true;
        }
        try
        {
            return Files.isSameFile(left, right);
        }
        catch (IOException e)
        {
            // The filesystem could not answer. "Different" is the safe reading: it refuses the
            // write rather than replacing a file whose identity was never established.
            return false;
        }
    }

    /**
     * Refuses a path that is not absolute, or {@code null} when it is. The schema has always said
     * "absolute path"; this is what makes that a contract rather than a hope.
     * <p>
     * The refusal itself is {@link ComparisonFailures#relativePath}, shared with the other tool
     * that takes a merge-rules path: the reasoning and the wording belong to the situation, not
     * to whichever tool observed it.
     *
     * @param parameter the parameter name, for the message
     * @param value the value exactly as the caller passed it
     * @param path that value parsed
     * @return the refusal, or {@code null} when the path is absolute
     */
    private static String relativePathRefusal(String parameter, String value, Path path)
    {
        ToolResult refusal = ComparisonFailures.relativePath(parameter, value, path);
        return refusal == null ? null : refusal.toJson();
    }

    /**
     * Refuses a target whose extension is right but SPELLED in the wrong case.
     * <p>
     * Its own sentence, because the generic "name it .zip or .xml" refusal reads as nonsense over
     * a file already called {@code rules.ZIP}: the caller did name it that, and the part that
     * failed is invisible unless it is pointed at. What is being enforced here is the platform's
     * own test - EDT 2026.2 asserts {@code "zip".equals(FileUtil.getExtension(path))} and 2026.1
     * branches the same way - so this refusal is the whole cost of a rename, against a write that
     * would otherwise produce a perfectly valid archive the launch then refuses.
     *
     * @param name the target's file name, exactly as spelled
     * @return the refusal, or {@code null} when the name does not differ from an accepted one by
     *         case alone
     */
    private static String wrongCaseExtensionRefusal(String name)
    {
        String lower = name.toLowerCase(Locale.ROOT);
        String extension;
        if (lower.endsWith(MergeRulesCodec.ZIP_EXTENSION))
        {
            extension = MergeRulesCodec.ZIP_EXTENSION;
        }
        else if (lower.endsWith(MergeRulesCodec.XML_EXTENSION))
        {
            extension = MergeRulesCodec.XML_EXTENSION;
        }
        else
        {
            return null;
        }
        String spelled = name.substring(name.length() - extension.length());
        return ToolResult.error("Nothing was written: " + KEY_FILE_PATH + " is '" + name //$NON-NLS-1$ //$NON-NLS-2$
            + "', and EDT's merge-settings reader compares the extension EXACTLY - '" + spelled //$NON-NLS-1$
            + "' is not '" + extension + "'. The file would be written and then refused inside " //$NON-NLS-1$
            + "the launch meant to apply it, with the platform's own assertion. Re-send with the " //$NON-NLS-1$
            + "extension in lower case.").toJson(); //$NON-NLS-1$
    }

    private static String invalidPath(String parameter, String value, InvalidPathException e)
    {
        return ToolResult.error(parameter + " is not a usable file path: '" + value + "' (" //$NON-NLS-1$ //$NON-NLS-2$
            + describe(e) + ").").toJson(); //$NON-NLS-1$
    }

    private static String describe(Exception e)
    {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /** One validated decision on its way into the document. */
    private static final class RequestedDecision
    {
        private final List<String> path;

        private final String rule;

        RequestedDecision(List<String> path, String rule)
        {
            this.path = path;
            this.rule = rule;
        }
    }

    /** Either a parsed decision or the refusal that explains why there is none. */
    private static final class ParsedDecision
    {
        private final RequestedDecision decision;

        private final String refusal;

        private ParsedDecision(RequestedDecision decision, String refusal)
        {
            this.decision = decision;
            this.refusal = refusal;
        }

        static ParsedDecision parsed(RequestedDecision decision)
        {
            return new ParsedDecision(decision, null);
        }

        static ParsedDecision refused(String refusal)
        {
            return new ParsedDecision(null, refusal);
        }
    }

    /**
     * ONE reading of a comparison tree: whether it reported itself FINISHED, and what every asked
     * for node allows - both taken inside a single read boundary.
     *
     * <h2>Why the answer is a snapshot and not a getter</h2>
     * The comparison tree is rebuilt as the engine works, and its readiness is a property of the
     * whole tree rather than of a node. Reading the readiness in one boundary and then each node in
     * a boundary of its own means the decisions of one file can be judged by an old tree, a
     * half-rebuilt one and a new one in turn - and the report would still say every decision had
     * been checked against the comparison, which would name a state the comparison was never in.
     * Carrying both halves out of ONE boundary is the only shape in which that sentence is true,
     * and it is also the cheaper one: the walk visits each node of the requested chains once
     * instead of descending from the root once per decision.
     *
     * <h2>Three answers, and the middle one is not the missing one</h2>
     * {@link #checked()} {@code false} means the tree could not be read as a whole - absent, not
     * finished, or no view at all - and NOTHING in it may be read as a verdict about a node. When
     * it is {@code true}, an address that is ABSENT from the snapshot is the comparison saying it
     * has no such node, while an address present with an EMPTY list is the comparison saying it
     * has the node and offers no choice on it. Those two are different refusals, and folding them
     * together is a defect this tool has already had once.
     */
    public static final class RuleSnapshot
    {
        /** The one reading that answers nothing: no verdict may be drawn from it. */
        private static final RuleSnapshot UNREADABLE = new RuleSnapshot(false, Map.of());

        private final boolean checked;

        private final Map<List<String>, List<String>> allowed;

        private RuleSnapshot(boolean checked, Map<List<String>, List<String>> allowed)
        {
            this.checked = checked;
            this.allowed = allowed;
        }

        /**
         * The reading that carries no verdict: the tree was not readable as a whole, so no
         * decision may be refused - or accepted - on the strength of it.
         *
         * @return the snapshot, never {@code null}
         */
        public static RuleSnapshot unreadable()
        {
            return UNREADABLE;
        }

        /**
         * A reading taken from a FINISHED tree.
         *
         * @param allowed what each asked-for address offers; an address the tree does not have is
         *            ABSENT from the map, and one it has but offers nothing on maps to an EMPTY
         *            list
         * @return the snapshot, never {@code null}
         */
        public static RuleSnapshot of(Map<List<String>, List<String>> allowed)
        {
            Map<List<String>, List<String>> copy = new HashMap<>();
            if (allowed != null)
            {
                for (Map.Entry<List<String>, List<String>> entry : allowed.entrySet())
                {
                    if (entry.getKey() != null && entry.getValue() != null)
                    {
                        copy.put(Collections.unmodifiableList(new ArrayList<>(entry.getKey())),
                            Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
                    }
                }
            }
            return new RuleSnapshot(true, copy);
        }

        /**
         * @return {@code true} when this reading came from a tree that reported itself FINISHED,
         *         and its addresses may therefore be read as verdicts
         */
        public boolean checked()
        {
            return checked;
        }

        /**
         * @param nodePath the full key chain, starting at {@link MergeRulesDocument#ROOT_KEY}
         * @return the allowed literals, or empty when this reading has no such node
         */
        public Optional<List<String>> rulesAt(List<String> nodePath)
        {
            return nodePath == null ? Optional.empty() : Optional.ofNullable(allowed.get(nodePath));
        }
    }

    /**
     * The one question this tool asks a live comparison: which merge rules does a node allow?
     * <p>
     * Deliberately this narrow. The tool never holds the comparison manager, so it has no way to
     * start a merge even reflectively; and an authority that cannot answer for a node returns
     * empty rather than an empty ALLOWED SET, because "I cannot see that node" and "that node
     * allows nothing" are different facts and only one of them is a refusal.
     */
    public interface MergeRuleAuthority
        extends AutoCloseable
    {
        /**
         * The comparison this authority speaks for, as the report should name it.
         *
         * @return the comparison id, never {@code null}
         */
        String comparisonId();

        /**
         * The name EDT will look for INSIDE a zipped merge-rules file when it restores this
         * comparison's decisions - {@code <mainProject>_<otherProject>_<ancestorProject>}, without
         * an extension.
         * <p>
         * It is on this interface for the same reason {@link #comparisonId()} is: it is a fact
         * about the live comparison that the write needs and cannot derive. The tool must not
         * derive it either - the names are what the platform put in the comparison's descriptors,
         * not what the request spelled - and an entry named anything else is skipped by EDT with a
         * log warning while the launch proceeds with no decisions. That silence is why this is a
         * required answer rather than a defaulted one: an authority that could not name it would
         * let a zip be written that looks correct and does nothing.
         * <p>
         * It is an ADDRESS and not an identity: the three names are joined by {@code _}, which is
         * itself legal in a project name, so different triples can spell the same id - see
         * {@code ComparisonEngine#mergeRulesEntryId}. Nothing here may be worded as "no other
         * comparison can restore this file".
         *
         * @return the entry id, never {@code null}
         */
        String mergeRulesEntryId();

        /**
         * What every address the file would carry allows, read in ONE go.
         * <p>
         * A BATCH and not a getter, and that is the contract rather than an optimisation: the
         * returned {@link RuleSnapshot} carries the tree's own readiness beside the per-node
         * answers, so the caller judges the whole document by one state of the comparison. Asked
         * one address at a time - which is what this used to be - the readiness was established in
         * a boundary of its own and each address in another, and a file with several decisions
         * could be accepted against a tree that was rebuilt between two of them while the report
         * said one comparison had checked them all.
         * <p>
         * A comparison answers two separable things, and only one of them needs its tree. The
         * ADDRESS ({@link #comparisonId()}, {@link #mergeRulesEntryId()}) is a fact about which
         * projects the run covers and is known as soon as the session is found; a RULE VERDICT
         * needs a FINISHED tree, because the tree is lazy and a node that has not been compared yet
         * is indistinguishable from a node the comparison does not have. Folding the two together
         * is what made an unfinished comparison lose its address as well, so a zip write was
         * refused for want of a name the live handle was holding all along. An authority that
         * cannot read the tree therefore still answers here - with
         * {@link RuleSnapshot#unreadable()}, which refuses nothing.
         * <p>
         * Whether the WRITE then proceeds depends on what the caller asked for, and the two
         * answers are deliberately different: with no {@code comparisonId} the write degrades to
         * the NOT VALIDATED report, which still says which comparison named the file; with a
         * {@code comparisonId} it is REFUSED, because a validated write is what was asked for and
         * quietly downgrading it would report an unchecked file to a caller who requested a
         * checked one. Both branches are in {@code writeUnderMutex}.
         *
         * @param nodePaths the full key chains to answer for, each starting at
         *            {@link MergeRulesDocument#ROOT_KEY}
         * @return the one reading, never {@code null}
         */
        RuleSnapshot rulesFor(Collection<List<String>> nodePaths);

        /**
         * Ends whatever the authority held open for the length of the validation pass.
         * <p>
         * It exists because the pass outlives the lookup that found the session: the walk that
         * answers for every decision in the FILE is one read on the comparison's BM store, and a
         * file built from {@code basedOn} can carry hundreds of addresses for it to resolve. While
         * that read runs the idle sweep can reclaim the very session being walked - it fires from
         * any comparison-tool call in another thread, and its TTL is counted from the last touch,
         * not from the start of this pass - which would stop the comparison under an active
         * validation and fail the write half way through. The production binding therefore holds a
         * {@code ComparisonSessionRegistry.Lease} from the first lookup to this call.
         * <p>
         * Declared with no checked exception, and empty by default: an authority that holds nothing
         * has nothing to end, and a caller must be able to close one in a {@code finally} without
         * an exception path that could mask the refusal it is carrying.
         */
        @Override
        default void close()
        {
            // an authority that holds nothing open has nothing to release
        }
    }

    /**
     * Resolves the {@link MergeRuleAuthority} for a call. The production binding is supplied by
     * the comparison facade at registration; with none, every write is authored from names and
     * reported as NOT VALIDATED, which is the honest answer rather than a missing feature
     * dressed up as a checked one.
     */
    @FunctionalInterface
    public interface MergeRuleAuthoritySupplier
    {
        /**
         * Finds the live comparison this write can lean on.
         * <p>
         * Empty means "no comparison answered at all" and deliberately does not say WHY - the
         * comparison may not be running, or this deployment may have no comparison facade wired at
         * all. Callers must therefore report the absence of validation, never a cause they did not
         * observe.
         * <p>
         * A PRESENT authority is not by itself a promise of validation: what it answers with is
         * a {@link RuleSnapshot}, and one that is not {@link RuleSnapshot#checked()} carries no
         * verdict at all. An authority that names the comparison but cannot read its tree is a
         * third state, and it exists because the address a zip needs is knowable exactly when the
         * session is - not when its tree happens to be finished.
         * <p>
         * <b>A failed attempt is THROWN, never collapsed into empty.</b> "Nothing answered" and
         * "something was asked and broke" are different facts, and only the first entitles a
         * caller to write the file and report it NOT VALIDATED. An implementation that swallowed
         * its own failure would hand the caller a file described as unchecked when the check had
         * in fact started and failed - which is a report of work that did not happen. Any
         * unchecked exception out of this method therefore means the check could not be carried
         * out, and the caller reports THAT.
         *
         * @param comparisonId the comparison the caller named, or {@code null} to use whichever
         *            comparison is running
         * @return the authority, or empty when there is no comparison to validate against
         */
        Optional<MergeRuleAuthority> authority(String comparisonId);
    }

    // ==================== the facade adapter ====================

    /**
     * The one place in this file that touches the comparison facade - the production binding of
     * {@link MergeRuleAuthoritySupplier}.
     *
     * <p>It never receives an {@code IComparisonManager} or an {@code IComparisonSession}: it
     * holds {@link ComparisonEngine} and the read-only {@link ComparisonView} it hands out, which
     * is one of the three independent layers that make a merge unreachable from a tool.</p>
     *
     * <p><b>RULES are answered only from a FINISHED tree, and the readiness is read in the SAME
     * boundary as the rules.</b> The comparison tree is lazy, so a node that has not been compared
     * yet is simply absent from its parent's children - indistinguishable from a node the
     * comparison does not have. Answering from a half-built tree would turn "not compared yet"
     * into the refusal "that node is not in this comparison", which is a statement the tool would
     * not have observed. This supplier no longer decides that on its own: it used to prove the
     * tree finished in a boundary of its own and then answer each node in another, so the proof
     * and the answers could describe different trees. It hands back an authority that can read the
     * tree at all, and that authority's ONE read reports both - see
     * {@link MergeRuleAuthority#rulesFor(java.util.Collection)}.</p>
     *
     * <p><b>The ADDRESS is answered whenever the session is found.</b> The zip entry name is
     * {@code <main>_<other>_<ancestor>}, read straight off the handle's three descriptors, so it
     * needs neither the tree nor the comparison service. It used to be computed only after both
     * had answered, which threw a known name away and refused a zip write for want of it.</p>
     *
     * <p><b>Answering nothing is reserved for what it OBSERVED.</b> Every empty answer below is a
     * reading: no comparison named, no session under that id, no handle. A failure to obtain any
     * of those readings is not one of them, and is not caught - see {@link #authority(String)}.</p>
     */
    static final class EngineRuleAuthority
        implements MergeRuleAuthoritySupplier
    {
        /**
         * The resolution step, named so a test can drive both of its outcomes headlessly.
         * <p>
         * A seam and not a fake of the platform: what has to be exercised is which outcomes this
         * class collapses into "no validation" and which it lets through, and that is a property
         * of this class alone. Without EDT installed the real resolution answers empty on its
         * first line, so no behavioural test could otherwise tell "answered nothing" from
         * "could not be asked".
         */
        @FunctionalInterface
        interface Resolution
        {
            /**
             * @param comparisonId the comparison the caller named, or {@code null} for whichever
             *            comparison is running
             * @return the authority, or empty when there is no comparison to validate against
             */
            Optional<MergeRuleAuthority> resolve(String comparisonId);
        }

        private final Resolution resolution;

        /** Creates the shipped supplier, which resolves against the installed comparison facade. */
        EngineRuleAuthority()
        {
            this(EngineRuleAuthority::resolve);
        }

        /**
         * @param resolution the resolution step, never {@code null}
         */
        EngineRuleAuthority(Resolution resolution)
        {
            this.resolution = resolution;
        }

        /**
         * {@inheritDoc}
         * <p>
         * <b>A failure is NOT caught here, and that is the fix for a report that lied.</b> This
         * method used to wrap the resolution in {@code catch (RuntimeException)} and answer empty,
         * which put two different facts through one door: "there is no comparison to check these
         * rules against" and "there is one, the check was attempted, and the attempt failed". The
         * caller can only read empty as the first, so a write made while the tree-readiness read
         * threw was written anyway and stamped NOT VALIDATED - a file the caller was told nobody
         * had checked, when in truth the check had started and broken. The caller asked to write
         * under conditions where validation was possible, and it is entitled to hear that it was
         * not carried out.
         * <p>
         * Naming an id already behaved this way: with {@code comparisonId} given, an empty answer
         * is refused rather than degraded, so the same failure reached the caller through that
         * branch and vanished through this one. The two branches now agree.
         * <p>
         * The ABSENCE of a comparison still answers empty and still degrades to NOT VALIDATED -
         * that is the honest report, not a refusal - so the split is between "nothing answered"
         * and "the attempt failed", not between "validated" and "refused".
         */
        @Override
        public Optional<MergeRuleAuthority> authority(String comparisonId)
        {
            return resolution.resolve(comparisonId);
        }

        private static Optional<MergeRuleAuthority> resolve(String comparisonId)
        {
            // Through the registry's own entry point, not the engine's: a live session must stay
            // findable while EDT's service is momentarily unregistered. Asked BEFORE the engine,
            // and that order is the fix: the entry name is a property of the handle alone, so a
            // deployment whose comparison service is momentarily away still knows which projects
            // the running comparison covers.
            ComparisonSessionRegistry registry = ComparisonSessionRegistry.shared();
            String id = isSet(comparisonId) ? comparisonId : registry.activeComparisonId();
            if (id == null)
            {
                return Optional.empty();
            }
            // LEASED, not merely looked up: the validation pass reads the tree once per decision in
            // the file, and the session must survive from here to the last of those reads. The
            // lease also carries the handle it leased - asking the registry for it separately would
            // be a second liveness question, and the two can disagree.
            ComparisonSessionRegistry.Lease lease = registry.lease(id);
            boolean handedOver = false;
            try
            {
                ComparisonProcessHandle handle = lease.handle();
                if (handle == null)
                {
                    return Optional.empty();
                }
                // Computed HERE, from the handle the lease is holding, and carried as a value: the
                // write happens after the lease has been given back, and the descriptors the names
                // come from are reachable only through it. It is a pure function of the handle -
                // three project names - so it is known whether or not the tree can be read.
                String entryId = ComparisonEngine.mergeRulesEntryId(handle);
                ComparisonEngine engine = ComparisonEngine.get().orElse(null);
                // Both "the service could not be asked" and "EDT no longer knows the handle" land
                // the same way HERE and only here: this path degrades to no validation, which the
                // report states, so it draws no conclusion from either and needs to tell them apart
                // nowhere.
                ComparisonView view = engine == null ? null : engine.view(handle).orElse(null);
                if (view == null)
                {
                    // The comparison is REGISTERED and names its own projects; only the verdict is
                    // missing. Answering empty here - which is what this used to do - threw the
                    // address away with the verdict and left a zip write refused for want of a
                    // name that was in hand. The lease is given back on the way out: nothing below
                    // this line reads the tree.
                    //
                    // Whether the tree has FINISHED is deliberately NOT asked here any more. It
                    // used to be, in a read of its own, and the rules were then read in further
                    // reads - so "the tree was finished" and "these are its rules" were statements
                    // about different instants. The readiness now comes back from the same
                    // boundary as the rules, which is the only place it can be true of the same
                    // tree.
                    return Optional.of(new AddressOnlyAuthority(id, entryId));
                }
                handedOver = true;
                return Optional.of(new LiveComparisonAuthority(engine, view, id, entryId, lease));
            }
            finally
            {
                if (!handedOver)
                {
                    // Every path that does not hand out a TREE-READING authority gives the lease
                    // back here; only the authority that reads the tree owns one, and it closes it
                    // itself.
                    lease.close();
                }
            }
        }

    }

    /**
     * A comparison that named itself and can say nothing else.
     * <p>
     * It exists because "which projects is this comparison over" and "is this rule legal on that
     * node" are answered by different parts of the platform: the first by the handle's three
     * descriptors, the second by a tree that has to be FINISHED. When the second is unavailable
     * the first still is, and a write that needs only the address - a zip, which is addressed by
     * the project triple - has no reason to fail. Holding nothing, it closes to nothing.
     */
    private static final class AddressOnlyAuthority
        implements MergeRuleAuthority
    {
        private final String comparisonId;

        private final String mergeRulesEntryId;

        AddressOnlyAuthority(String comparisonId, String mergeRulesEntryId)
        {
            this.comparisonId = comparisonId;
            this.mergeRulesEntryId = mergeRulesEntryId;
        }

        @Override
        public String comparisonId()
        {
            return comparisonId;
        }

        @Override
        public String mergeRulesEntryId()
        {
            return mergeRulesEntryId;
        }

        /**
         * {@inheritDoc}
         * <p>
         * The reading that carries no verdict, and it is an ANSWER rather than a silence: this
         * comparison exists and its tree could not be read, so nothing here refuses a decision and
         * nothing here accepts one. The caller reports NOT VALIDATED, or refuses the write when a
         * checked one was asked for - never an illegal-rule refusal drawn from a tree nobody read.
         */
        @Override
        public RuleSnapshot rulesFor(Collection<List<String>> nodePaths)
        {
            return RuleSnapshot.unreadable();
        }
    }

    /**
     * Answers which rules a node allows, from one live comparison, through the read-only view.
     *
     * <p>Every lookup runs inside {@link ComparisonEngine#read} - the comparison tree lives in the
     * comparison's OWN BM store, so a project transaction is the wrong boundary (CLAUDE.md don't
     * #1) - and nothing from that store escapes: only rule literals come back.</p>
     */
    private static final class LiveComparisonAuthority
        implements MergeRuleAuthority
    {
        private final ComparisonEngine engine;

        private final ComparisonView view;

        private final String comparisonId;

        private final String mergeRulesEntryId;

        /** Held for the whole validation pass, so the idle sweep cannot reclaim it mid-read. */
        private final ComparisonSessionRegistry.Lease lease;

        LiveComparisonAuthority(ComparisonEngine engine, ComparisonView view, String comparisonId,
            String mergeRulesEntryId, ComparisonSessionRegistry.Lease lease)
        {
            this.engine = engine;
            this.view = view;
            this.comparisonId = comparisonId;
            this.mergeRulesEntryId = mergeRulesEntryId;
            this.lease = lease;
        }

        @Override
        public String comparisonId()
        {
            return comparisonId;
        }

        @Override
        public String mergeRulesEntryId()
        {
            return mergeRulesEntryId;
        }

        @Override
        public void close()
        {
            // Lease.close() is idempotent, so a caller that closes in a finally AND in a
            // try-with-resources cannot decrement the count twice and expose a live read.
            lease.close();
        }

        /**
         * {@inheritDoc}
         * <p>
         * ONE {@code engine.read}, and everything the verdict rests on comes out of it: the root's
         * own status, and the rules at every address that resolved. Inside it the tree is walked
         * ONCE along the requested chains rather than descended from the root once per address -
         * a file built from {@code basedOn} can carry hundreds of them, and repeating the descent
         * inside a single boundary would hold that boundary open for a multiple of the work it
         * needs. Nothing from the comparison's store leaves: only rule literals come back.
         */
        @Override
        public RuleSnapshot rulesFor(Collection<List<String>> nodePaths)
        {
            Map<List<String>, List<String>> byFull = addressable(nodePaths);
            RuleSnapshot snapshot = engine.read(view, "Read the rules a comparison allows", //$NON-NLS-1$
                (transaction, monitor) -> readSnapshot(byFull));
            // The facade answers null only if the platform handed the task back without running
            // it; that is not a reading either, so it degrades to the one that refuses nothing.
            return snapshot == null ? RuleSnapshot.unreadable() : snapshot;
        }

        /**
         * The addresses this pass will answer for, each mapped to the chain BELOW the root that
         * the walk matches. An address that is not rooted at {@link MergeRulesDocument#ROOT_KEY}
         * is dropped here: it addresses nothing the walk could reach, so it comes back absent,
         * which the caller renders as "the comparison has no such node".
         *
         * @param nodePaths the full key chains
         * @return full chain to relative chain, in the order they were asked
         */
        private static Map<List<String>, List<String>> addressable(Collection<List<String>> nodePaths)
        {
            Map<List<String>, List<String>> byFull = new LinkedHashMap<>();
            if (nodePaths == null)
            {
                return byFull;
            }
            for (List<String> path : nodePaths)
            {
                if (path != null && !path.isEmpty()
                    && MergeRulesDocument.ROOT_KEY.equals(path.get(0)))
                {
                    byFull.put(new ArrayList<>(path),
                        new ArrayList<>(path.subList(1, path.size())));
                }
            }
            return byFull;
        }

        /** The one reading: the tree's own status and the rules at every address that resolved. */
        private RuleSnapshot readSnapshot(Map<List<String>, List<String>> byFull)
        {
            ComparisonNode root = view.rootNode();
            if (root == null || view.topNodeStatus(root.bmGetId()) != ComparisonNodeStatus.FINISHED)
            {
                // Read HERE and not before: the status and the rules below it are one observation
                // of one tree, which is the whole point of the batch.
                return RuleSnapshot.unreadable();
            }
            Map<List<String>, ComparisonNode> found =
                findNodes(root, byFull.values(), this::featureNameOf);
            Map<List<String>, List<String>> allowed = new LinkedHashMap<>();
            for (Map.Entry<List<String>, List<String>> asked : byFull.entrySet())
            {
                ComparisonNode node = found.get(asked.getValue());
                if (node != null)
                {
                    // Absent from the map is "no such node"; an EMPTY list is "the node is here and
                    // offers no choice". allowedRulesOf keeps the two apart.
                    allowed.put(asked.getKey(),
                        allowedRulesOf(node, view.availableMergeRules(node)));
                }
            }
            return RuleSnapshot.of(allowed);
        }

        private String featureNameOf(ComparisonNode node)
        {
            EStructuralFeature feature = view.relatedFeature(node);
            return feature == null ? null : feature.getName();
        }
    }

    /**
     * Turns what the tree answered about ONE node into the answer the authority contract is
     * written in: {@code null} means "the comparison has no such node", and a list - EMPTY
     * included - means "the comparison has this node, and this is what it offers".
     * <p>
     * <b>The two used to be one.</b> A node that was FOUND but carried no {@code MergeSettings}
     * returned {@code null} as well, which the caller renders as "Node 'x' is not in comparison
     * 'y'" - a statement about the tree that is simply untrue, and one that sends the caller to
     * {@code get_comparison_node} to look for a node it will find sitting right there. The tool
     * already has the right sentence for the observed fact ("offers no merge rule on node 'x':
     * the platform offers a choice only where a node may be merged"), and it was unreachable
     * from the live comparison because both facts arrived as the same missing answer.
     * <p>
     * No settings and settings with an empty rule list are deliberately NOT split further: both
     * are the platform saying this node carries no choice, and
     * {@code ComparisonView#availableMergeRules} already answers an empty list for either.
     *
     * @param node the node the key chain resolved to, or {@code null} when it resolved to none
     * @param available what the platform offers on that node (empty when it offers nothing)
     * @return the literals, or {@code null} only when there is no such node
     */
    static List<String> allowedRulesOf(ComparisonNode node, List<MergeRule> available)
    {
        if (node == null)
        {
            return null;
        }
        List<String> literals = new ArrayList<>();
        for (MergeRule rule : available)
        {
            if (rule != null)
            {
                literals.add(rule.getLiteral());
            }
        }
        return literals;
    }

    // ==================== key chain -> node ====================

    /**
     * Resolves MANY key chains in ONE walk down from the root, matching each key against the key
     * the PLATFORM would serialize that child under. The two must agree, or a rule validated
     * against one node would be written under a key addressing another.
     *
     * <h2>One walk, and why it is not an optimisation</h2>
     * This used to resolve ONE chain, and the pass called it once per decision - so every decision
     * re-scanned the children of every node on its way down, and each of those descents ran in a
     * read boundary of its own. The boundaries are the defect the batch exists to close; the
     * repeated descents are what would have made keeping them in ONE boundary expensive. Folding
     * the chains into a prefix tree makes the walk visit each node of the requested chains exactly
     * once, so the single boundary is held for less work than the old sequence of boundaries did
     * in total.
     *
     * <h2>Which child a key resolves to</h2>
     * The FIRST child carrying that key, exactly as the one-chain walk did: siblings are scanned in
     * the platform's own order and the first match wins. A key already taken at this level is not
     * matched again by a later sibling, so two children keyed alike cannot both claim one branch.
     * <p>
     * A level is left as soon as every key IT was asked for has been matched. That follows from
     * first-wins and changes no answer - the siblings behind the last match could only carry keys
     * nobody asked for, or keys already taken - it only stops a two-key request from reading a
     * ten-thousand-element collection to the end.
     *
     * @param root the comparison tree's root node
     * @param relativePaths the chains of keys below the root; an EMPTY chain addresses the root
     *            itself
     * @param featureNameOf resolves a node's model feature name, which only the session knows
     * @return the node each chain resolved to; a chain no child carries is ABSENT from the map
     */
    static Map<List<String>, ComparisonNode> findNodes(ComparisonNode root,
        Collection<List<String>> relativePaths, Function<ComparisonNode, String> featureNameOf)
    {
        Map<List<String>, ComparisonNode> found = new HashMap<>();
        if (root == null || relativePaths == null || relativePaths.isEmpty())
        {
            return found;
        }
        PathNode wanted = new PathNode();
        for (List<String> path : relativePaths)
        {
            if (path != null)
            {
                wanted.want(path);
            }
        }
        walk(root, wanted, featureNameOf, found);
        return found;
    }

    /**
     * Descends into the children this level still wants, and records the nodes the chains ending
     * here resolved to.
     *
     * @param node the node reached so far
     * @param wanted the chains still to satisfy below it
     * @param featureNameOf resolves a node's model feature name
     * @param found collects chain to node
     */
    private static void walk(ComparisonNode node, PathNode wanted,
        Function<ComparisonNode, String> featureNameOf, Map<List<String>, ComparisonNode> found)
    {
        if (wanted.chain != null)
        {
            found.put(wanted.chain, node);
        }
        if (wanted.children.isEmpty())
        {
            return;
        }
        Set<String> taken = new HashSet<>();
        for (ComparisonNode child : childrenOf(node))
        {
            if (child == null)
            {
                // The list is the PLATFORM's now, and its elements may be null. The filtering the
                // copy used to do has to happen HERE instead, and it is not cosmetic: this loop
                // does not test its element with instanceof and walk away, it keys it - and
                // serializedKey hands anything it does not recognise to featureNameOf, which asks
                // the live comparison view for the model feature of the node. There is no such
                // question about nothing.
                continue;
            }
            String key = serializedKey(child, featureNameOf);
            PathNode next = key == null ? null : wanted.children.get(key);
            if (next == null || !taken.add(key))
            {
                continue;
            }
            walk(child, next, featureNameOf, found);
            if (taken.size() == wanted.children.size())
            {
                // Every key this level was asked for has now been matched, and 'taken' only ever
                // receives keys that are in 'wanted', so the two sizes meeting means exactly that.
                // Matching is first-wins per key, so a later sibling can carry only a key nobody
                // asked for or one already taken - neither of which can add an answer. WHICH nodes
                // resolve is therefore unchanged; only how many siblings are read to find them.
                // Without this, addressing one common module read every common module in the
                // configuration, and each of those reads goes to the BM store.
                break;
            }
        }
    }

    /**
     * The requested chains folded into a prefix tree, so one walk answers all of them.
     * <p>
     * Depth is bounded by the LONGEST requested chain and not by the comparison, because the walk
     * only descends where a chain still wants a child - a document addresses a handful of levels,
     * whatever the size of the tree beside it.
     */
    private static final class PathNode
    {
        private final Map<String, PathNode> children = new HashMap<>();

        /** The chain that ends here, or {@code null} when nothing was asked for this node. */
        private List<String> chain;

        /**
         * @param path the keys below this node
         */
        void want(List<String> path)
        {
            PathNode current = this;
            for (String key : path)
            {
                current = current.children.computeIfAbsent(key, k -> new PathNode());
            }
            // The chain as the CALLER spelled it, so the answer is keyed by what was asked for.
            current.chain = Collections.unmodifiableList(new ArrayList<>(path));
        }
    }

    /**
     * The key the platform's own path generators write for a node: the three side NAMES for a top
     * object, the position for a collection element, the model feature name otherwise. Measured
     * against {@code TopNodePathGenerator} / {@code ContainmentNodePathGenerator}, not guessed.
     *
     * @param node the node to key
     * @param featureNameOf resolves a node's model feature name
     * @return the key, or {@code null} when the node has none
     */
    static String serializedKey(ComparisonNode node, Function<ComparisonNode, String> featureNameOf)
    {
        if (node instanceof TopComparisonNode)
        {
            TopComparisonNode top = (TopComparisonNode)node;
            return MergeRulesDocument.TopObjectKey.format(nameFromSymlink(top.getMainSymlink()),
                nameFromSymlink(top.getOtherSymlink()),
                nameFromSymlink(top.getCommonAncestorSymlink()));
        }
        if (node instanceof CollectionElementComparisonNode)
        {
            return Integer.toString(((CollectionElementComparisonNode)node).getPositionAfterMerge());
        }
        return featureNameOf.apply(node);
    }

    /**
     * The last segment of a symlink, which is what the platform puts in a top-object key. Computed
     * here rather than taken from a platform label helper: the label helpers branch on
     * {@code Locale.getDefault()}, so their output depends on the machine the server runs on.
     *
     * @param symlink a qualified name, or {@code null} when the side has no such object
     * @return the name, or {@code null}
     */
    private static String nameFromSymlink(String symlink)
    {
        if (symlink == null)
        {
            return null;
        }
        int dot = symlink.lastIndexOf('.');
        return dot < 0 ? symlink : symlink.substring(dot + 1);
    }

    /**
     * Direct children of a node, tolerating a null node and a null child list.
     * <p>
     * <b>The platform's own list, not a copy of it</b> - the third site of the same fix, after
     * {@code ComparisonNodeRenderer.childrenOf} and {@code SupportStateReader.children}. The copy
     * charged the FULL width of every level to a walk that descends only where a requested chain
     * still wants a child and now stops as soon as the level has nothing left to give: addressing
     * one common module used to duplicate every common module in the configuration first, and
     * these are BM-backed nodes, so each element is a store read rather than an array slot.
     * <p>
     * What the copy also did was drop nulls, and {@link #walk} does that itself now - it must,
     * because unlike the other two sites its loop KEYS every element instead of testing it with
     * {@code instanceof}.
     *
     * @param node the node to read
     * @return the live child list, or an empty one; elements may be {@code null}
     */
    private static List<ComparisonNode> childrenOf(ComparisonNode node)
    {
        if (node == null)
        {
            return Collections.emptyList();
        }
        List<ComparisonNode> children = node.<ComparisonNode> getChildren();
        return children == null ? Collections.emptyList() : children;
    }
}
