/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/**
 * Issue #922: a write admitted into a project this server just switched lands in the previous branch's model. The
 * checkout-reload refusal lives in the shared gates ({@code AbstractMetadataWriteTool.execute}, the cascade
 * pre-flight, {@code ProjectStateChecker.strictWriteErrorOrNull}); a tool that writes the model, or writes FROM it into
 * the work tree or the infobase, outside those gates bypasses it. This ratchet finds every such tool by what it does
 * and fails when one asks no reload-aware gate - the way three tools slipped past the first three rounds of #928.
 */
public class CheckoutReloadGateRatchetTest
{
    private static final String IMPL_DIR =
        "mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/tools/impl"; //$NON-NLS-1$

    /** What writing the model looks like in a tool's source. */
    private static final List<String> MODEL_WRITE_MARKERS = List.of(
        "BmTransactions.write(", //$NON-NLS-1$
        "forceExportToDisk(", //$NON-NLS-1$
        ".ensureExtended("); //$NON-NLS-1$

    /**
     * Tools that write the model, or write from it into the work tree or the infobase, through an API this ratchet
     * cannot see by a marker (LanguageTool, the external-object builder, the DCS writer, the infobase update).
     */
    private static final Set<String> MODEL_DERIVED_WRITERS = Set.of(
        "TranslateConfigurationTool", //$NON-NLS-1$
        "GenerateTranslationStringsTool", //$NON-NLS-1$
        "BuildExternalObjectsTool", //$NON-NLS-1$
        "DcsTool", //$NON-NLS-1$
        "WriteModuleSourceTool", //$NON-NLS-1$
        "UpdateDatabaseTool"); //$NON-NLS-1$

    /** Gates that ask the checkout-reload question. */
    private static final List<String> RELOAD_AWARE_GATES = List.of(
        "extends AbstractMetadataWriteTool", //$NON-NLS-1$
        "checkoutReloadErrorOrNull(", //$NON-NLS-1$
        "strictWriteErrorOrNull(", //$NON-NLS-1$
        "settleBeforeCascadeOrError(", //$NON-NLS-1$
        "modelBuildingErrorOrNull("); //$NON-NLS-1$

    /** {@code create_project} writes only the project it creates: no previous-branch model can be in it. */
    private static final Set<String> EXEMPT = Set.of("CreateProjectTool"); //$NON-NLS-1$

    @Test
    public void everyModelWriterAsksAReloadAwareGate() throws IOException
    {
        File dir = locate(IMPL_DIR);
        assertNotNull("could not locate " + IMPL_DIR + " from user.dir=" + System.getProperty("user.dir"), dir); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        File[] sources = dir.listFiles((d, name) -> name.endsWith(".java")); //$NON-NLS-1$
        assertNotNull(sources);

        List<String> writers = new ArrayList<>();
        List<String> ungated = new ArrayList<>();
        for (File source : sources)
        {
            String name = source.getName().substring(0, source.getName().length() - ".java".length()); //$NON-NLS-1$
            String text = Files.readString(source.toPath(), StandardCharsets.UTF_8);
            boolean writes = MODEL_DERIVED_WRITERS.contains(name)
                || MODEL_WRITE_MARKERS.stream().anyMatch(text::contains);
            if (!writes || EXEMPT.contains(name))
            {
                continue;
            }
            writers.add(name);
            if (RELOAD_AWARE_GATES.stream().noneMatch(text::contains))
            {
                ungated.add(name);
            }
        }
        for (String expected : MODEL_DERIVED_WRITERS)
        {
            assertTrue("listed writer not found (renamed?): " + expected, writers.contains(expected)); //$NON-NLS-1$
        }
        assertTrue("the marker scan found too few writers to mean anything: " + writers, writers.size() >= 12); //$NON-NLS-1$
        assertTrue("tools that write the model but ask no checkout-reload gate (#922): " + ungated, //$NON-NLS-1$
            ungated.isEmpty());
    }

    /**
     * The shared write gate asks about {@code projectName}; {@code adopt_metadata_object} takes the BASE there and
     * writes into the EXTENSION, so it must ask about the extension itself.
     */
    @Test
    public void adoptionAsksAboutTheExtensionItWritesInto() throws IOException
    {
        File source = locate(IMPL_DIR + "/AdoptMetadataObjectTool.java"); //$NON-NLS-1$
        assertNotNull(source);
        String text = Files.readString(source.toPath(), StandardCharsets.UTF_8);
        assertTrue(text.contains("ProjectStateChecker.checkoutReloadErrorOrNull(target.getProject())")); //$NON-NLS-1$
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
