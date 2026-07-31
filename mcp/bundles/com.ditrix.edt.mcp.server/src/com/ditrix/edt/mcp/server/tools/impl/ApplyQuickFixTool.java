/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com.e1c.g5.v8.dt.check.qfix.FixProcessHandle;
import com.e1c.g5.v8.dt.check.qfix.FixVariantDescriptor;
import com.e1c.g5.v8.dt.check.qfix.IFixManager;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool;
import com.ditrix.edt.mcp.server.tools.impl.GetProjectErrorsTool.ErrorInfo;

/**
 * Applies EDT's official quick-fix (auto-fix) to one validation marker — the MCP
 * counterpart of the "Quick Fix" action in the EDT problems view.
 * <p>
 * The marker is addressed by the SAME locator get_project_errors prints — its
 * {@code Check code} (+ optional {@code Module path} / {@code Line}) — because EDT
 * markers carry no stable per-marker id ({@code getMarkerId()} returns the shared
 * source-module URI). The matching marker is found by streaming the marker manager;
 * when the locator still matches several markers (e.g. two parameter-doc problems on
 * one line) an explicit 1-based {@code index} disambiguates them. The fix then runs
 * through {@link IFixManager}: prepare -&gt; list variants -&gt; select -&gt; execute
 * -&gt; finish; a check with several fix variants needs an explicit {@code variant}.
 * <p>
 * Mutates the model, so it extends {@link AbstractMetadataWriteTool} (runs on the UI
 * thread). {@link IFixManager} manages its own change application.
 */
public class ApplyQuickFixTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "apply_quick_fix"; //$NON-NLS-1$

    private static final String KEY_PROJECT = "projectName"; //$NON-NLS-1$
    private static final String KEY_CHECK_ID = "checkId"; //$NON-NLS-1$
    private static final String KEY_MODULE_PATH = "modulePath"; //$NON-NLS-1$
    private static final String KEY_LINE = "line"; //$NON-NLS-1$
    private static final String KEY_INDEX = "index"; //$NON-NLS-1$
    private static final String KEY_VARIANT = "variant"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Apply EDT's official quick-fix (auto-fix) to one validation marker — the headless " //$NON-NLS-1$
            + "counterpart of the 'Quick Fix' action in the problems view. Address the marker by the " //$NON-NLS-1$
            + "locator get_project_errors prints: its Check code (+ Module path + Line to narrow); the " //$NON-NLS-1$
            + "'Fix' column there flags which rows are fixable. When the locator matches several markers " //$NON-NLS-1$
            + "(or the fix has several variants) the error lists them and you re-call with index / variant. " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('apply_quick_fix')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(KEY_PROJECT, "EDT project name the marker belongs to (required).", true) //$NON-NLS-1$
            .stringProperty(KEY_CHECK_ID,
                "Check id of the marker to fix (required): the 'Check code' from get_project_errors " //$NON-NLS-1$
                + "(symbolic id like 'doc-comment-parameter-section', or the short UID). Matched " //$NON-NLS-1$
                + "case-insensitively against both.", true) //$NON-NLS-1$
            .stringProperty(KEY_MODULE_PATH,
                "Narrow to a BSL module: the 'Module path' from get_project_errors " //$NON-NLS-1$
                + "(e.g. 'CommonModules/MyModule/Module.bsl'). Optional but recommended when the same " //$NON-NLS-1$
                + "check fires in several modules.") //$NON-NLS-1$
            .integerProperty(KEY_LINE,
                "Narrow to the 1-based 'Line' from get_project_errors. Optional.") //$NON-NLS-1$
            .integerProperty(KEY_INDEX,
                "1-based selector when the locator still matches several markers (the error lists " //$NON-NLS-1$
                + "them). Omit for a single match.") //$NON-NLS-1$
            .integerProperty(KEY_VARIANT,
                "1-based fix-variant index, required only when the chosen marker's fix exposes more " //$NON-NLS-1$
                + "than one variant (the error then lists them).") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "True when the fix was applied.", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(KEY_CHECK_ID, "The marker's check id.") //$NON-NLS-1$
            .stringProperty("location", "Where the fix was applied (module:line or object).") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("appliedVariant", "Description of the fix variant that was applied.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("message", "Human-readable summary.") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String required = JsonUtils.requireArguments(params, KEY_PROJECT, KEY_CHECK_ID);
        if (required != null)
        {
            return required;
        }
        String projectName = JsonUtils.extractStringArgument(params, KEY_PROJECT);
        String checkId = JsonUtils.extractStringArgument(params, KEY_CHECK_ID);
        String modulePath = JsonUtils.extractStringArgument(params, KEY_MODULE_PATH);
        int line = JsonUtils.extractIntArgument(params, KEY_LINE, -1);
        int index = JsonUtils.extractIntArgument(params, KEY_INDEX, -1);
        int variant = JsonUtils.extractIntArgument(params, KEY_VARIANT, -1);

        ProjectContext ctx = resolveProjectAndConfig(projectName);
        if (ctx.hasError())
        {
            return ctx.error;
        }
        IProject project = ctx.project;

        IMarkerManager markerManager = Activator.getDefault().getMarkerManager();
        IFixManager fixManager = Activator.getDefault().getFixManager();
        IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
        ICheckRepository checkRepository = Activator.getDefault().getCheckRepository();
        if (markerManager == null || fixManager == null || dtProjectManager == null)
        {
            return ToolResult.error("Quick-fix services are not available " //$NON-NLS-1$
                + "(IMarkerManager / IFixManager / IDtProjectManager).").toJson(); //$NON-NLS-1$
        }

        IDtProject dtProject = dtProjectManager.getDtProject(project);
        if (dtProject == null)
        {
            return ToolResult.error("Project '" + projectName //$NON-NLS-1$
                + "' is not an EDT project (no IDtProject); quick-fixes apply to configuration/extension projects.").toJson(); //$NON-NLS-1$
        }

        List<MarkerMatch> matches = findMatches(markerManager, checkRepository, project,
            checkId, modulePath, line);
        if (matches.isEmpty())
        {
            return ToolResult.error("No marker matches check '" + checkId + "'" //$NON-NLS-1$ //$NON-NLS-2$
                + locatorSuffix(modulePath, line) + " in project '" + projectName //$NON-NLS-1$
                + "'. Run get_project_errors (responseFormat=detailed) and pick a row whose 'Fix' column is 'yes'.").toJson(); //$NON-NLS-1$
        }

        MarkerMatch chosen;
        if (matches.size() == 1)
        {
            chosen = matches.get(0);
        }
        else if (index >= 1 && index <= matches.size())
        {
            chosen = matches.get(index - 1);
        }
        else
        {
            return multipleMarkersError(checkId, matches);
        }

        return applyFix(fixManager, dtProject, chosen, variant);
    }

    /**
     * Streams the project's markers and returns those whose check id matches {@code checkId}
     * (symbolic or short UID, case-insensitive substring) and, when given, the {@code modulePath}
     * and 1-based {@code line}. No BM read transaction is needed: only the marker's own check id
     * and extra-info (module URI + line) are read, never the lazily-resolved object presentation.
     */
    private static List<MarkerMatch> findMatches(IMarkerManager markerManager,
        ICheckRepository checkRepository, IProject project, String checkId, String modulePath, int line)
    {
        List<MarkerMatch> matches = new ArrayList<>();
        markerManager.markers().forEach(marker -> {
            IProject markerProject = marker.getProject();
            if (markerProject == null || !markerProject.equals(project))
            {
                return;
            }
            String shortUid = marker.getCheckId() != null ? marker.getCheckId() : ""; //$NON-NLS-1$
            String symbolic = GetProjectErrorsTool.resolveSymbolicCheckId(marker, shortUid, checkRepository);
            if (!GetProjectErrorsTool.checkIdMatches(shortUid, symbolic, checkId))
            {
                return;
            }
            ErrorInfo loc = new ErrorInfo();
            GetProjectErrorsTool.populateModuleLocation(marker, loc);
            if (modulePath != null && !modulePath.isEmpty() && !modulePath.equals(loc.modulePath))
            {
                return;
            }
            if (line >= 1 && (loc.line == null || loc.line.intValue() != line))
            {
                return;
            }
            matches.add(new MarkerMatch(marker, symbolic != null ? symbolic : shortUid,
                loc.modulePath, loc.line, marker.getMessage() != null ? marker.getMessage() : "")); //$NON-NLS-1$
        });
        return matches;
    }

    /**
     * Drives EDT's quick-fix lifecycle for the chosen marker: prepare → list applicable variants →
     * select → execute → finish. {@code finishFix} always runs (cleanup), including on the early
     * returns where no change was applied.
     */
    private static String applyFix(IFixManager fixManager, IDtProject dtProject, MarkerMatch chosen,
        int variant)
    {
        FixProcessHandle handle = fixManager.prepareFix(chosen.marker, dtProject);
        try
        {
            List<FixVariantDescriptor> variants = new ArrayList<>(fixManager.getApplicableFixVariants(handle));
            if (variants.isEmpty())
            {
                return ToolResult.error("No quick-fix is available for check '" + chosen.checkId //$NON-NLS-1$
                    + "'" + locatorSuffix(chosen.modulePath, chosen.line == null ? -1 : chosen.line) //$NON-NLS-1$
                    + ". Not every validation check has an auto-fix; fix it manually via " //$NON-NLS-1$
                    + "write_module_source / modify_metadata.").toJson(); //$NON-NLS-1$
            }

            FixVariantDescriptor chosenVariant;
            if (variants.size() == 1)
            {
                chosenVariant = variants.get(0);
            }
            else if (variant >= 1 && variant <= variants.size())
            {
                chosenVariant = variants.get(variant - 1);
            }
            else
            {
                return multipleVariantsError(chosen, variants);
            }

            fixManager.selectFixVariant(chosenVariant, handle);
            fixManager.executeFix(handle, new NullProgressMonitor());

            return ToolResult.success()
                .put("success", true) //$NON-NLS-1$
                .put(KEY_CHECK_ID, chosen.checkId)
                .put("location", chosen.location()) //$NON-NLS-1$
                .put("appliedVariant", describe(chosenVariant)) //$NON-NLS-1$
                .put("message", "Applied quick-fix '" + describe(chosenVariant) + "' at " + chosen.location()) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .toJson();
        }
        finally
        {
            fixManager.finishFix(handle);
        }
    }

    /** Actionable "several markers match — pick one" error, listing each with its 1-based index. */
    private static String multipleMarkersError(String checkId, List<MarkerMatch> matches)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(matches.size()).append(" markers match check '").append(checkId) //$NON-NLS-1$
          .append("'; re-call with index=<1..").append(matches.size()) //$NON-NLS-1$
          .append("> (or narrow with modulePath/line): "); //$NON-NLS-1$
        for (int i = 0; i < matches.size(); i++)
        {
            if (i > 0)
            {
                sb.append("; "); //$NON-NLS-1$
            }
            MarkerMatch m = matches.get(i);
            sb.append(i + 1).append(") ").append(m.location()).append(" — ").append(m.message); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return ToolResult.error(sb.toString()).toJson();
    }

    /** Actionable "this fix has several variants — pick one" error. */
    private static String multipleVariantsError(MarkerMatch chosen, List<FixVariantDescriptor> variants)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("The fix for check '").append(chosen.checkId).append("' at ").append(chosen.location()) //$NON-NLS-1$ //$NON-NLS-2$
          .append(" has ").append(variants.size()).append(" variants; re-call with variant=<1..") //$NON-NLS-1$ //$NON-NLS-2$
          .append(variants.size()).append(">: "); //$NON-NLS-1$
        for (int i = 0; i < variants.size(); i++)
        {
            if (i > 0)
            {
                sb.append("; "); //$NON-NLS-1$
            }
            sb.append(i + 1).append(") ").append(describe(variants.get(i))); //$NON-NLS-1$
        }
        return ToolResult.error(sb.toString()).toJson();
    }

    /** Trailing " at <module>:<line>" / " at <module>" locator clause for an error message, or "". */
    private static String locatorSuffix(String modulePath, int line)
    {
        if (modulePath == null || modulePath.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        return line >= 1 ? " at " + modulePath + ":" + line : " at " + modulePath; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** A fix variant's human description, falling back to its details / a placeholder. */
    private static String describe(FixVariantDescriptor variant)
    {
        String desc = variant.getDescription();
        if (desc != null && !desc.isEmpty())
        {
            return desc;
        }
        String details = variant.getDetails();
        return details != null && !details.isEmpty() ? details : "(unnamed fix)"; //$NON-NLS-1$
    }

    /** One marker matched by the locator, with the bits needed for selection + reporting. */
    private static final class MarkerMatch
    {
        final Marker marker;
        final String checkId;
        final String modulePath;
        final Integer line;
        final String message;

        MarkerMatch(Marker marker, String checkId, String modulePath, Integer line, String message)
        {
            this.marker = marker;
            this.checkId = checkId;
            this.modulePath = modulePath;
            this.line = line;
            this.message = message;
        }

        /** "module:line" when the marker resolves to a BSL position, else the check id. */
        String location()
        {
            if (modulePath != null && !modulePath.isEmpty())
            {
                return line != null ? modulePath + ":" + line : modulePath; //$NON-NLS-1$
            }
            return checkId;
        }
    }
}
