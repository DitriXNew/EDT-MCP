/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools;

import static org.junit.Assert.assertEquals;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.impl.ValidateFormModelTool;
import com.ditrix.edt.mcp.server.utils.FormModelValidator;

/**
 * The finding-code contract of {@code validate_form_model}, pinned against what the engine
 * ACTUALLY emits rather than against a list somebody remembered to update.
 *
 * <p>A finding code is advertised as stable for a caller to branch on, so three things have to
 * agree: the emission sites, the tool guide, and the generated page under {@code docs/tools}. Three
 * review rounds found them disagreeing, each time by reading. A ratchet keyed on a hand-maintained
 * registry would only have moved the problem - forget the registry entry and the check goes quiet -
 * so the codes and their severities are read out of the validator's own SOURCE, the way
 * {@code NoMergeStarterRatchetTest} reads the bundle it guards.</p>
 */
public class FormFindingCodeContractRatchetTest
{
    private static final String VALIDATOR_SOURCE =
        "mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/utils/FormModelValidator.java"; //$NON-NLS-1$

    private static final String GENERATED_PAGE = "docs/tools/validate_form_model.md"; //$NON-NLS-1$

    /** One emission, as written: {@code new Finding(SEVERITY_X, CODE_Y, ...}. */
    private static final Pattern EMISSION =
        Pattern.compile("new\\s+Finding\\(\\s*([A-Za-z_$][\\w$]*)\\s*,\\s*([^,]+?)\\s*,"); //$NON-NLS-1$

    /** A finding code as the guide writes it: backticked, kebab-case. */
    private static final Pattern GUIDE_CODE = Pattern.compile("`([a-z]+(?:-[a-z]+)+)`"); //$NON-NLS-1$

    /**
     * Every code the engine emits is a declared constant listed in {@link FormModelValidator#CODES}.
     *
     * <p>This is the join that makes the other checks mean anything: without it a new check could
     * emit a code - or a bare literal - that no list knows about, and every guide ratchet would pass
     * while the caller met an outcome nobody documented.</p>
     */
    @Test
    public void testEveryEmittedCodeIsADeclaredCode()
    {
        Map<String, String> emitted = emittedCodes();
        assertTrue("the source scan found no emissions at all - it is not reading the validator", //$NON-NLS-1$
            emitted.size() >= 20);
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> entry : emitted.entrySet())
        {
            if (!FormModelValidator.CODES.contains(entry.getKey()))
            {
                problems.add(entry.getKey() + " is emitted but missing from FormModelValidator.CODES"); //$NON-NLS-1$
            }
        }
        assertTrue(String.valueOf(problems), problems.isEmpty());
    }

    /**
     * The guide documents every emitted code WITH the severity it is emitted at.
     *
     * <p>The severity is half the contract: it decides whether {@code valid} comes back false, so a
     * row that says {@code warning} for a code the engine raises as an error misleads exactly the
     * caller who reads the table to decide what to do.</p>
     */
    @Test
    public void testTheGuideDocumentsEveryCodeAtItsRealSeverity()
    {
        Map<String, String> documented = documentedCodes(guide());
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> entry : emittedCodes().entrySet())
        {
            String severity = documented.get(entry.getKey());
            if (severity == null)
            {
                problems.add(entry.getKey() + ": emitted, but the guide table has no row for it"); //$NON-NLS-1$
            }
            else if (!severity.equals(entry.getValue()))
            {
                problems.add(entry.getKey() + ": emitted as " + entry.getValue() //$NON-NLS-1$
                    + ", documented as " + severity); //$NON-NLS-1$
            }
        }
        assertTrue(String.valueOf(problems), problems.isEmpty());
    }

    /**
     * And the guide advertises nothing the engine cannot produce: a row left behind by a check that
     * was removed promises an outcome that never arrives, which is the same broken contract read
     * from the other end.
     */
    @Test
    public void testTheGuideAdvertisesNoCodeTheEngineCannotEmit()
    {
        List<String> phantom = new ArrayList<>();
        for (String code : documentedCodes(guide()).keySet())
        {
            if (!FormModelValidator.CODES.contains(code))
            {
                phantom.add(code);
            }
        }
        assertTrue("the guide advertises a code the engine cannot emit: " + phantom, //$NON-NLS-1$
            phantom.isEmpty());
    }

    /**
     * The checked-in page under {@code docs/tools} is generated FROM the guide, and a reader of the
     * repository sees only that copy. Regenerating it is a separate step a change can skip, so the
     * two tables are compared here instead of trusted to be in step.
     */
    @Test
    public void testTheGeneratedPageCarriesTheSameTable()
    {
        File page = locate(GENERATED_PAGE);
        assertNotNull("could not locate " + GENERATED_PAGE + " from user.dir=" //$NON-NLS-1$ //$NON-NLS-2$
            + System.getProperty("user.dir"), page); //$NON-NLS-1$
        assertEquals("docs/tools/validate_form_model.md is stale - regenerate it with " //$NON-NLS-1$
            + "docs/generate_tool_docs.py against a server running this build", //$NON-NLS-1$
            documentedCodes(guide()), documentedCodes(read(page.toPath())));
    }

    // --- reading ---------------------------------------------------------------------------------

    /** The bundled guide, as the tool actually serves it. */
    private static String guide()
    {
        String guide = new ValidateFormModelTool().getGuide();
        assertNotNull("the bundled guide must load, or these ratchets prove nothing", guide); //$NON-NLS-1$
        return guide;
    }

    /**
     * Code -&gt; severity, read from the validator's emission sites.
     *
     * @return the codes the engine can produce, each with the severity it is produced at
     */
    private static Map<String, String> emittedCodes()
    {
        File source = locate(VALIDATOR_SOURCE);
        if (source == null)
        {
            fail("could not locate " + VALIDATOR_SOURCE + " from user.dir=" //$NON-NLS-1$ //$NON-NLS-2$
                + System.getProperty("user.dir")); //$NON-NLS-1$
        }
        Map<String, String> emitted = new LinkedHashMap<>();
        Matcher sites = EMISSION.matcher(read(source.toPath()));
        while (sites.find())
        {
            String severity = constantValue(sites.group(1));
            String code = constantValue(sites.group(2).trim());
            if (severity == null || code == null)
            {
                fail("an emission does not use the declared constants, so no ratchet can see it: " //$NON-NLS-1$
                    + sites.group());
            }
            String seen = emitted.put(code, severity);
            if (seen != null && !seen.equals(severity))
            {
                fail(code + " is emitted at two severities (" + seen + ", " + severity //$NON-NLS-1$ //$NON-NLS-2$
                    + "), so it cannot be documented as either"); //$NON-NLS-1$
            }
        }
        return emitted;
    }

    /** The value of a {@code FormModelValidator} constant by NAME, or {@code null} if there is none. */
    private static String constantValue(String name)
    {
        try
        {
            Object value = FormModelValidator.class.getField(name).get(null);
            return value instanceof String ? (String)value : null;
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            return null;
        }
    }

    /**
     * Code -&gt; severity, read from a Markdown code table. The leading pipe of a row is OPTIONAL in
     * Markdown, so a row is split into cells first and only the FIRST cell is searched for codes -
     * the prose column names other things in backticks.
     *
     * @param markdown the guide body, or a generated page carrying it
     * @return every documented code with the severity its row states
     */
    private static Map<String, String> documentedCodes(String markdown)
    {
        Map<String, String> documented = new LinkedHashMap<>();
        for (String raw : markdown.split("\n")) //$NON-NLS-1$
        {
            String line = raw.trim();
            if (!line.contains("|") || !line.contains("`")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                continue;
            }
            String[] cells = line.replaceAll("^\\|", "").split("\\|"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (cells.length < 2)
            {
                continue;
            }
            String severity = cells[1].trim();
            if (!FormModelValidator.SEVERITY_ERROR.equals(severity)
                && !FormModelValidator.SEVERITY_WARNING.equals(severity))
            {
                continue;
            }
            Matcher codes = GUIDE_CODE.matcher(cells[0]);
            while (codes.find())
            {
                documented.put(codes.group(1), severity);
            }
        }
        return documented;
    }

    private static String read(Path path)
    {
        try
        {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
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
