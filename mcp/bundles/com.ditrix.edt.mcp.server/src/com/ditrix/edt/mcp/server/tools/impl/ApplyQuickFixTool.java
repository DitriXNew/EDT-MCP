/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool;
import com.ditrix.edt.mcp.server.tools.base.WriteScope;
import com.ditrix.edt.mcp.server.tools.impl.GetProjectErrorsTool.ErrorInfo;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.e1c.g5.v8.dt.check.qfix.FixProcessHandle;
import com.e1c.g5.v8.dt.check.qfix.FixVariantDescriptor;
import com.e1c.g5.v8.dt.check.qfix.IFixManager;
import com.e1c.g5.v8.dt.check.qfix.IFixVariant;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.google.gson.JsonObject;

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
        return "Apply an EDT quick fix to a validation problem. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('apply_quick_fix')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(KEY_PROJECT, "EDT project name the marker belongs to (required).", true) //$NON-NLS-1$
            .stringProperty(KEY_CHECK_ID,
                "Check id of the marker to fix (required): the 'Check code' from get_project_errors " //$NON-NLS-1$
                + "(symbolic id like 'doc-comment-parameter-section', or the short UID). Matched by " //$NON-NLS-1$
                + "EXACT case-insensitive equality against either - not a substring, since this " //$NON-NLS-1$
                + "mutates the model and a loose match could silently pick the wrong check.", true) //$NON-NLS-1$
            .stringProperty(KEY_MODULE_PATH,
                "Narrow to a BSL module: the 'Module path' from get_project_errors " //$NON-NLS-1$
                + "(e.g. 'CommonModules/MyModule/Module.bsl'). Optional but recommended when the same " //$NON-NLS-1$
                + "check fires in several modules.") //$NON-NLS-1$
            .integerProperty(KEY_LINE,
                "Narrow to the 1-based 'Line' from get_project_errors. Optional; if supplied it must " //$NON-NLS-1$
                + "be >= 1 - 0 or negative is rejected rather than treated as 'omitted'.") //$NON-NLS-1$
            .integerProperty(KEY_INDEX,
                "1-based selector when the locator still matches several markers (the error lists " //$NON-NLS-1$
                + "them). Omit for a single match. If supplied, it must be >= 1 (0 or negative is " //$NON-NLS-1$
                + "rejected, never silently treated as 'omitted') and is validated strictly against " //$NON-NLS-1$
                + "the CURRENT match count and rejected when out of range - even against a single " //$NON-NLS-1$
                + "match - so a stale index from an earlier response is never silently applied to the " //$NON-NLS-1$
                + "wrong marker.") //$NON-NLS-1$
            .integerProperty(KEY_VARIANT,
                "1-based fix-variant index, required only when the chosen marker's fix exposes more " //$NON-NLS-1$
                + "than one variant (the error then lists them). If supplied, it must be >= 1 (0 or " //$NON-NLS-1$
                + "negative is rejected, never silently treated as 'omitted') and is validated " //$NON-NLS-1$
                + "strictly against the CURRENT variant count and rejected when out of range - even " //$NON-NLS-1$
                + "against a single variant - so a stale selector is never silently applied to the " //$NON-NLS-1$
                + "wrong fix.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "True when the fix was applied.", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(KEY_CHECK_ID, "The marker's check id.") //$NON-NLS-1$
            .stringProperty("location", "Where the fix was applied (module:line or object).") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(KEY_MODULE_PATH, "The module the fixed MARKER was positioned in; empty " //$NON-NLS-1$
                + "when the marker carried no module position (typically an object-level marker " //$NON-NLS-1$
                + "raised on the model, but also any marker whose locator did not resolve). This " //$NON-NLS-1$
                + "is where the problem WAS, not a statement about everything the fix touched.") //$NON-NLS-1$
            .stringProperty("appliedVariant", "Description of the fix variant that was applied.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("message", "Human-readable summary.") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    /**
     * What to await for a fix whose reach the platform will not report.
     * <p>
     * Package-visible so both branches can be pinned directly: the classification is the whole of
     * this tool's answer to "where did I write", and a test that could only reach it through a live
     * EDT fix run would not pin it at all.
     *
     * @param modulePath the module position of the marker that was fixed, empty/{@code null} when it
     *     had none
     * @param projectName the project the call named
     * @return the projects to await
     */
    static Collection<String> undeterminableFallback(String modulePath, String projectName)
    {
        if (modulePath != null && !modulePath.isEmpty())
        {
            return Collections.emptyList();
        }
        return projectName == null || projectName.isEmpty() ? Collections.<String> emptyList()
            : Collections.singletonList(projectName);
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

        SelectorArgument lineArg = extractSelectorArgument(params, KEY_LINE);
        if (lineArg.isRejected())
        {
            return lineArg.rejection;
        }
        SelectorArgument indexArg = extractSelectorArgument(params, KEY_INDEX);
        if (indexArg.isRejected())
        {
            return indexArg.rejection;
        }
        SelectorArgument variantArg = extractSelectorArgument(params, KEY_VARIANT);
        if (variantArg.isRejected())
        {
            return variantArg.rejection;
        }
        int line = lineArg.value;
        int index = indexArg.value;
        int variant = variantArg.value;

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
        if (markerManager == null || fixManager == null || dtProjectManager == null || checkRepository == null)
        {
            return ToolResult.error("Quick-fix services are not available " //$NON-NLS-1$
                + "(IMarkerManager / IFixManager / IDtProjectManager / ICheckRepository).").toJson(); //$NON-NLS-1$
        }

        IDtProject dtProject = dtProjectManager.getDtProject(project);
        if (dtProject == null)
        {
            return ToolResult.error("Project '" + projectName //$NON-NLS-1$
                + "' is not an EDT project (no IDtProject); quick-fixes apply to configuration/extension projects.").toJson(); //$NON-NLS-1$
        }

        List<MarkerMatch> matches = findMatches(markerManager, checkRepository, project,
            checkId, modulePath, line);
        // Resolve each candidate's object presentation (inside a read boundary - see the method).
        // Needed BEFORE the ambiguity listing below: an object/metadata-level marker carries no
        // modulePath/line, so without it several candidates would print as an identical
        // "<checkId> - <message>" and an index would pick a target the caller cannot identify.
        resolveObjectPresentations(project, matches);
        if (matches.isEmpty())
        {
            return ToolResult.error("No marker matches check '" + checkId + "'" //$NON-NLS-1$ //$NON-NLS-2$
                + locatorSuffix(modulePath, line) + " in project '" + projectName //$NON-NLS-1$
                + "'. Run get_project_errors (responseFormat=detailed) and pick a row whose " //$NON-NLS-1$
                + "'Fix registered' column is 'yes'.").toJson(); //$NON-NLS-1$
        }

        // BEFORE any selection, including one the caller already made: when two candidates cannot be
        // told apart AND cannot be stably ordered, no index over this batch means anything - neither
        // the one this call would print nor the one a previous call handed out. Checking only on the
        // listing path would leave the MUTATING path (index supplied) unguarded, which is the one
        // that actually applies the fix to the wrong object.
        if (hasUnresolvableAmbiguity(matches))
        {
            return ToolResult.error(matches.size() + " markers match check '" + checkId //$NON-NLS-1$ //$NON-NLS-2$
                + "', and at least two of them are object-level markers whose target object could " //$NON-NLS-1$
                + "not be resolved right now (the model was unavailable or busy). They are therefore " //$NON-NLS-1$
                + "indistinguishable and their order is not stable, so no index can be trusted to " //$NON-NLS-1$
                + "select the one you mean - and this tool would MUTATE whatever it selected. These " //$NON-NLS-1$
                + "markers carry no module path, so narrowing the locator cannot separate them " //$NON-NLS-1$
                + "either. Retry in a moment (the targets usually resolve once the model settles); " //$NON-NLS-1$
                + "if it persists, run get_project_errors (responseFormat=detailed) to see which " //$NON-NLS-1$
                + "objects are affected and fix them via modify_metadata.").toJson(); //$NON-NLS-1$
        }

        int chosenIdx = chooseIndex(matches.size(), index);
        if (chosenIdx < 0)
        {
            return multipleMarkersError(checkId, matches);
        }
        MarkerMatch chosen = matches.get(chosenIdx);

        return applyFix(fixManager, dtProject, chosen, variant, projectName);
    }

    /**
     * Streams the project's markers and returns those whose check id EXACTLY matches
     * {@code checkId} (symbolic or short UID, case-insensitive - not a substring; see
     * {@link GetProjectErrorsTool#checkIdMatchesExact}) and, when given, the {@code modulePath}
     * and 1-based {@code line}. No BM read transaction is needed: only the marker's own check id
     * and extra-info (module URI + line) are read, never the lazily-resolved object presentation.
     * <p>
     * The result is sorted into a DETERMINISTIC order (module path, then line, then check id, then
     * message) before being returned: {@link IMarkerManager#markers()} makes no ordering promise,
     * so an {@code index} chosen from one call's error listing (see {@link #multipleMarkersError})
     * must still resolve to the SAME candidate on a follow-up call with the same locator + selector,
     * even though the underlying stream may enumerate the same marker set in a different order each
     * time. Content-derived sorting removes stream order as a source of that instability.
     */
    static List<MarkerMatch> findMatches(IMarkerManager markerManager,
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
            if (!GetProjectErrorsTool.checkIdMatchesExact(shortUid, symbolic, checkId))
            {
                return;
            }
            ErrorInfo loc = new ErrorInfo();
            GetProjectErrorsTool.populateModuleLocation(marker, loc);
            // Case-insensitive, like the checkId half of the same locator (checkIdMatchesExact) -
            // a caller reconstructing the locator rather than copying it verbatim should not be
            // rejected by a module-path case mismatch the checkId half would have tolerated.
            if (modulePath != null && !modulePath.isEmpty()
                && (loc.modulePath == null || !modulePath.equalsIgnoreCase(loc.modulePath)))
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
        matches.sort(MarkerMatch.DETERMINISTIC_ORDER);
        return matches;
    }

    /**
     * Fills in each match's {@link MarkerMatch#objectPresentation} - the marker's target object as
     * {@code get_project_errors} prints it in its {@code Location} column (e.g.
     * {@code Catalog.Products}).
     * <p>
     * Kept SEPARATE from {@link #findMatches} because it is the one part that needs a BM read
     * boundary: {@link Marker#getObjectPresentation()} resolves the target lazily out of the model
     * (CLAUDE.md don't #1 - the same reason {@code GetProjectErrorsTool.collectErrors} wraps its own
     * marker walk). The presentation is what makes an OBJECT-level candidate identifiable: such a
     * marker has no {@code modulePath}/{@code line}, so without it {@link #multipleMarkersError}
     * would list several candidates as an identical "{@code <checkId> - <message>}" and an
     * {@code index} would select a target the caller cannot tell apart.
     * <p>
     * Best-effort and never fatal HERE: a project with no BM model, or a marker whose target cannot
     * be resolved, simply leaves that presentation {@code null} and the listing falls back to the
     * module/check locator - a marker is never dropped just because its presentation failed. The
     * CALLER decides what an unresolved batch means, which is not the same question: for a single
     * match the labels are pure cosmetics, but for an AMBIGUOUS object-level batch they are the only
     * thing that distinguishes the choices and orders them, so proceeding there would hand out an
     * {@code index} that a later, successful resolution would renumber. See
     * {@link #ambiguityNeedsTargets}.
     *
     * @param project the project the markers belong to
     * @param matches the matches to enrich, modified in place (may be empty)
     */
    private static void resolveObjectPresentations(IProject project, List<MarkerMatch> matches)
    {
        if (matches.isEmpty())
        {
            return;
        }
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        IBmModel bmModel = bmModelManager != null ? bmModelManager.getModel(project) : null;
        if (bmModel == null)
        {
            return;
        }
        // The read boundary itself is guarded too, not just the per-marker resolution inside it:
        // these presentations are cosmetic listing labels, so a transaction that cannot be opened
        // or is rejected outright (model mid-rebuild, a busy BM) must not turn a perfectly
        // applicable quick-fix into a failed tool call - which is what an escaping exception here
        // would do, since AbstractMetadataWriteTool's catch reports it as the tool's own error.
        try
        {
            BmTransactions.<Void>read(bmModel, "ApplyQuickFixResolveTargets", (tx, pm) -> { //$NON-NLS-1$
                for (MarkerMatch match : matches)
                {
                    try
                    {
                        String presentation = match.marker.getObjectPresentation();
                        if (presentation != null && !presentation.isEmpty())
                        {
                            match.objectPresentation = presentation;
                        }
                    }
                    catch (Exception e)
                    {
                        // Unresolvable target: keep the match, just without a presentation.
                        Activator.logWarning("Could not resolve the target of a quick-fix candidate for check '" //$NON-NLS-1$
                            + match.checkId + "': " + e.getMessage()); //$NON-NLS-1$
                    }
                }
                return null;
            });
        }
        catch (Exception e)
        {
            Activator.logWarning("Could not open a read transaction to resolve quick-fix candidate " //$NON-NLS-1$
                + "targets: " + e.getMessage()); //$NON-NLS-1$
        }
        // Re-sort now that the presentations are known: it is a tiebreaker in
        // DETERMINISTIC_ORDER, and findMatches sorted while every value was still null - so
        // object-level candidates would otherwise keep the marker stream's arbitrary order and
        // an index=N could point at a different object on the next call.
        matches.sort(MarkerMatch.DETERMINISTIC_ORDER);
    }

    /**
     * Whether any two candidates are BOTH indistinguishable to the caller AND not stably ordered —
     * in which case no {@code index} over this batch can be trusted and the call must be refused
     * rather than allowed to mutate.
     * <p>
     * Asks {@link MarkerMatch#DETERMINISTIC_ORDER} directly instead of re-deriving a proxy rule: the
     * comparator IS this class's definition of "told apart", so a tie between two adjacent entries
     * of the sorted list means the sort had nothing left to separate them and their printed order is
     * whatever {@code IMarkerManager.markers()} happened to enumerate. Asking the comparator also
     * keeps this correct if its key list ever changes.
     * <p>
     * A tie alone is not enough. Two candidates identical across every field INCLUDING a resolved
     * target are genuinely the same choice presented twice, and picking either is equivalent — the
     * stance {@code DETERMINISTIC_ORDER} already documents. The hazard is a tie caused by MISSING
     * information: object-level candidates ({@code modulePath} null — no module position to separate
     * them) whose {@code objectPresentation} never resolved. Those print identically, sort
     * arbitrarily, and may well be DIFFERENT objects, so a retry that does resolve the targets
     * renumbers them and the caller's {@code index} lands somewhere else. Since they tie, both
     * carry the same (absent) values, so testing one of the pair is testing both.
     * <p>
     * Package-visible (not {@code private}) for the same reason as
     * {@link #sortVariantsDeterministically} and {@link #describeForListing}: a pure decision worth
     * pinning by unit test, without a live {@link IFixManager} session or a BM model.
     *
     * @param matches the candidate matches, ALREADY sorted with {@link MarkerMatch#DETERMINISTIC_ORDER}
     * @return {@code true} when the batch must be refused instead of indexed
     */
    static boolean hasUnresolvableAmbiguity(List<MarkerMatch> matches)
    {
        for (int i = 1; i < matches.size(); i++)
        {
            MarkerMatch previous = matches.get(i - 1);
            MarkerMatch current = matches.get(i);
            if (MarkerMatch.DETERMINISTIC_ORDER.compare(previous, current) != 0)
            {
                continue;
            }
            boolean noModulePosition = previous.modulePath == null || previous.modulePath.isEmpty();
            if (noModulePosition && previous.objectPresentation == null)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Drives EDT's quick-fix lifecycle for the chosen marker: prepare → list applicable variants →
     * select → execute → finish. {@code finishFix} always runs (cleanup), including on the early
     * returns where no change was applied.
     */
    private static String applyFix(IFixManager fixManager, IDtProject dtProject, MarkerMatch chosen,
        int variant, String projectName)
    {
        FixProcessHandle handle = fixManager.prepareFix(chosen.marker, dtProject);
        if (handle == null)
        {
            // No fix process could be prepared for this marker's check at all (e.g. the check has no
            // registered quick-fix) - report the same "no fix available" outcome as the empty-variants
            // case below, WITHOUT feeding a null handle into getApplicableFixVariants/finishFix (both
            // require a real handle per the EDT quick-fix lifecycle).
            return noFixAvailableError(chosen);
        }
        try
        {
            // Null-tolerant for the same reason the handle == null case above is: the engine is
            // permissive about a fix session that never reached a variant context, and a raw
            // new ArrayList<>(null) would surface as an NPE with no message - a JSON error
            // carrying no text at all - instead of the actionable "no quick-fix available" that
            // the empty case answers with.
            Collection<FixVariantDescriptor> applicable = fixManager.getApplicableFixVariants(handle);
            if (applicable == null || applicable.isEmpty())
            {
                return noFixAvailableError(chosen);
            }
            List<FixVariantDescriptor> variants = new ArrayList<>(applicable);
            sortVariantsDeterministically(variants);

            int chosenIdx = chooseIndex(variants.size(), variant);
            if (chosenIdx < 0)
            {
                return multipleVariantsError(chosen, variants);
            }
            FixVariantDescriptor chosenVariant = variants.get(chosenIdx);

            fixManager.selectFixVariant(chosenVariant, handle);
            // The engine's selectFixVariant/executeFix are BOTH silent no-ops when the fix session is
            // not in the state they expect (selectFixVariant needs CONTEXTS_INITIALIZED, executeFix
            // needs VARIANT_SELECTED) - they return void and swallow the mismatch. Without this guard
            // a session that refused the selection would fall straight through to a do-nothing
            // executeFix and still be reported as a successful fix. getSelectedFixVariant is the
            // engine's own read-back of that state, so a null here means "nothing was selected,
            // executeFix would change nothing" - report it instead of a false success.
            IFixVariant<?> selected = fixManager.getSelectedFixVariant(handle);
            if (selected == null)
            {
                return ToolResult.error("The quick-fix engine did not accept the selected variant '" //$NON-NLS-1$
                    + describeForListing(chosenVariant) + "' for check '" + chosen.checkId + "'" //$NON-NLS-1$ //$NON-NLS-2$
                    + " - nothing was applied. Re-run get_project_errors (the marker set may have " //$NON-NLS-1$
                    + "changed since it was listed) and try again, or fix it manually via " //$NON-NLS-1$
                    + "write_module_source / modify_metadata.").toJson(); //$NON-NLS-1$
            }
            String uiBundle = interactiveFixBundle(selected);
            if (uiBundle != null)
            {
                return ToolResult.error("The registered quick-fix for check '" + chosen.checkId //$NON-NLS-1$
                    + "' (" + describeForListing(chosenVariant) + ") is an INTERACTIVE IDE action from '" + uiBundle //$NON-NLS-1$ //$NON-NLS-2$
                    + "', not an automated edit - it opens an editor/view for a human and changes " //$NON-NLS-1$
                    + "nothing on its own, so it cannot be applied headlessly. Fix this one manually " //$NON-NLS-1$
                    + "via write_module_source / modify_metadata.").toJson(); //$NON-NLS-1$
            }
            fixManager.executeFix(handle, new NullProgressMonitor());

            // The one write in this plugin whose scope cannot be stated: EDT's fix extension point
            // reports NOTHING about what the variant touched, and the point does not forbid a fix on
            // a module-positioned diagnostic from changing the model as well. So this stays a
            // classification - but it is recorded here, at the call whose opacity is the reason,
            // rather than re-derived afterwards from the response, and it publishes no write scope
            // at all, because "I could not tell" must never reach the caller as "I wrote nowhere".
            //
            // The classification itself is unchanged and deliberately asymmetric. A MODULE marker is
            // fixed by editing that module, which typically queues no .mdo export; waiting anyway
            // would let unrelated metadata work in the same project refuse a successful edit. A
            // marker with NO module position is usually an object-level one raised on the model, and
            // its fix is overwhelmingly likely to queue the same export every metadata write queues -
            // and it can also be a marker whose locator simply did not resolve, which is why that
            // branch is the conservative one: waiting costs a pause, answering early costs a
            // half-written tree. One answer for both was this defect's first shape, and its mirror.
            WriteScope.recordUndeterminable(
                "EDT's quick-fix extension point does not report what the fix touched", //$NON-NLS-1$
                undeterminableFallback(chosen.modulePath, projectName));

            return ToolResult.success()
                .put("success", true) //$NON-NLS-1$
                .put(KEY_CHECK_ID, chosen.checkId)
                .put("location", chosen.location()) //$NON-NLS-1$
                // Reported, not inferred: `location` collapses a module position and an object
                // presentation into one string, so reading it back could only guess which kind of
                // marker was fixed. The barrier's scope decision below turns on exactly that.
                .put(KEY_MODULE_PATH, chosen.modulePath == null ? "" : chosen.modulePath) //$NON-NLS-1$
                .put("appliedVariant", describeForListing(chosenVariant)) //$NON-NLS-1$
                .put("message", "Applied quick-fix '" + describeForListing(chosenVariant) + "' at " + chosen.location()) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .toJson();
        }
        finally
        {
            try
            {
                fixManager.finishFix(handle);
            }
            catch (Exception e)
            {
                // Cleanup-only: standard try/finally semantics would let a finishFix failure
                // DISCARD the try block's real outcome (a genuinely applied fix reported as a
                // failure - risking a caller double-applying it - or a more diagnostic exception
                // replaced by this cleanup-time one). Swallow it instead; a session-cleanup
                // hiccup is not worth masking the actual result.
                Activator.logWarning("finishFix failed for check '" + chosen.checkId + "': " //$NON-NLS-1$ //$NON-NLS-2$
                    + e.getMessage());
            }
        }
    }

    /**
     * Detects a quick-fix that is an INTERACTIVE IDE action rather than an automated edit.
     * <p>
     * Not every registered fix edits anything by itself. EDT's doc-comment checks, for instance,
     * register {@code OpenBslDocCommentViewFix} - which lives in {@code com.e1c.v8codestyle.bsl.ui}
     * and, as its name says, OPENS the doc-comment view for a human to fill in. Run headlessly it
     * completes without error and changes nothing: no file, no model, and the marker stays. The
     * engine reports nothing about this ({@code executeFix} returns {@code void}), so without this
     * guard the tool would answer "Applied quick-fix ..." for a fix that demonstrably did nothing -
     * the false success this tool must never produce.
     * <p>
     * The signal is the OSGi bundle the fix implementation ships in: an interactive fix lives in a
     * {@code *.ui} bundle (it needs the workbench), an automated one in the core check bundle (e.g.
     * {@code ConsecutiveEmptyLinesFix} / {@code RemoveExportFix} in {@code com.e1c.v8codestyle.bsl}).
     * A conservative test - only a {@code .ui} bundle is refused, anything else proceeds - so a new
     * automated fix is never wrongly rejected.
     *
     * @param fixVariant the variant the engine actually selected
     * @return the UI bundle's symbolic name when the fix is interactive, else {@code null}
     */
    private static String interactiveFixBundle(IFixVariant<?> fixVariant)
    {
        Bundle bundle = FrameworkUtil.getBundle(fixVariant.getClass());
        String name = bundle != null ? bundle.getSymbolicName() : null;
        if (name == null)
        {
            return null;
        }
        return name.endsWith(".ui") || name.contains(".ui.") ? name : null; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Pure selection decision shared by the marker-{@code index} and fix-{@code variant} pickers (no
     * live service needed - unit-testable in isolation): returns the 0-based index into the candidate
     * list to use, or {@code -1} when the caller must see the "several match, pick one" error.
     * <p>
     * A supplied selector ({@code selector >= 1}) is honored STRICTLY: it must be in range
     * {@code [1, count]} or this returns -1, even when {@code count == 1} - a stale selector carried
     * over from an earlier multi-candidate response (the candidate set can change between calls, e.g.
     * after an edit/revalidation, or between listing fix variants and applying one) must be rejected,
     * not silently resolved to whatever happens to be the sole current candidate. Only when NO selector
     * was supplied ({@code selector < 1}, the "not given" sentinel) does a single candidate auto-select.
     *
     * @param count the number of current candidates (markers, or applicable fix variants)
     * @param selector the caller-supplied 1-based selector, or &lt; 1 when none was given
     * @return the 0-based index to use, or -1 for "ambiguous / out of range"
     */
    static int chooseIndex(int count, int selector)
    {
        if (selector >= 1)
        {
            return selector <= count ? selector - 1 : -1;
        }
        return count == 1 ? 0 : -1;
    }

    /** The "no selector was supplied" sentinel fed to {@link #chooseIndex} and the {@code line} gate. */
    private static final int SELECTOR_NOT_GIVEN = -1;

    /**
     * Extracts a 1-based selector argument ({@link #KEY_LINE}, {@link #KEY_INDEX} or
     * {@link #KEY_VARIANT}), distinguishing "the caller omitted it" from "the caller explicitly
     * supplied a value at or below 0" - a distinction {@link JsonUtils#extractIntArgument} cannot make
     * on its own, since both resolve to the same {@code defaultValue}. Presence is checked directly
     * against the raw {@code params} map FIRST:
     * <ul>
     * <li>absent / blank -&gt; {@link #SELECTOR_NOT_GIVEN} (unchanged behaviour: {@link #chooseIndex}
     * auto-selects a sole candidate, or the {@code line} gate in {@link #findMatches} is skipped);</li>
     * <li>present but its parsed value is &lt; 1 (including non-numeric garbage, which
     * {@code extractIntArgument} would otherwise also silently default) -&gt; a rejection, so an
     * explicit {@code index=0} / {@code variant=0} / {@code line=0} is never treated as if it had been
     * omitted;</li>
     * <li>present and &gt;= 1 -&gt; that value, used as-is.</li>
     * </ul>
     *
     * @param params the raw string-valued params map (may be {@code null})
     * @param argumentName the 1-based selector argument's name
     * @return the resolved {@link SelectorArgument}: either a usable value or a ready-to-return error
     */
    static SelectorArgument extractSelectorArgument(Map<String, String> params, String argumentName)
    {
        String raw = params == null ? null : params.get(argumentName);
        if (raw == null || raw.trim().isEmpty())
        {
            return SelectorArgument.of(SELECTOR_NOT_GIVEN);
        }
        int parsed = JsonUtils.extractIntArgument(params, argumentName, SELECTOR_NOT_GIVEN);
        if (parsed < 1)
        {
            return SelectorArgument.rejected(ToolResult.error(argumentName + " must be >= 1 (it is 1-based), got '" //$NON-NLS-1$
                + raw.trim() + "'. Omit " + argumentName + " entirely to use its default instead of " //$NON-NLS-1$ //$NON-NLS-2$
                + "an explicit value.").toJson()); //$NON-NLS-1$
        }
        return SelectorArgument.of(parsed);
    }

    /**
     * Outcome of {@link #extractSelectorArgument}: either a resolved value ({@link #SELECTOR_NOT_GIVEN}
     * when the caller omitted the argument, or the caller's validated 1-based value), or a rejection
     * error ready to return verbatim from {@code executeOnUiThread} when the caller supplied an
     * explicit out-of-range value.
     */
    static final class SelectorArgument
    {
        final int value;
        final String rejection;

        private SelectorArgument(int value, String rejection)
        {
            this.value = value;
            this.rejection = rejection;
        }

        static SelectorArgument of(int value)
        {
            return new SelectorArgument(value, null);
        }

        static SelectorArgument rejected(String rejection)
        {
            return new SelectorArgument(SELECTOR_NOT_GIVEN, rejection);
        }

        boolean isRejected()
        {
            return rejection != null;
        }
    }

    /** Actionable "no quick-fix for this marker" error - either no fix process, or no variants. */
    private static String noFixAvailableError(MarkerMatch chosen)
    {
        // Via location() so an OBJECT-level marker (no modulePath/line) still names its target
        // object rather than reporting the check alone; when location() has nothing better than the
        // check id it returns exactly that, and repeating it as "at <checkId>" would be noise.
        String where = chosen.location();
        String suffix = where.equals(chosen.checkId) ? "" : " at " + where; //$NON-NLS-1$ //$NON-NLS-2$
        return ToolResult.error("No quick-fix is available for check '" + chosen.checkId //$NON-NLS-1$
            + "'" + suffix //$NON-NLS-1$
            + ". Not every validation check has an auto-fix (and a check whose type HAS one can " //$NON-NLS-1$
            + "still have no variant applicable to this particular marker); fix it manually via " //$NON-NLS-1$
            + "write_module_source / modify_metadata.").toJson(); //$NON-NLS-1$
    }

    /**
     * Builds an actionable "several candidates - pick one" error: the lead-in, the selector range
     * the caller must answer with, then the choices numbered from 1.
     * <p>
     * ONE builder for BOTH ambiguity prompts (several MARKERS matched the locator; several
     * VARIANTS registered for the chosen marker's fix) deliberately. They are the same decision
     * asked twice, and while they were two hand-rolled loops they kept drifting: an improvement
     * landed in one and was found missing from the other a review round later - the marker listing
     * learned to print its target object so two object-level markers could be told apart, while the
     * variant listing went on printing a bare description that two variants can share. Building
     * both here makes that drift structurally impossible: how a choice is presented is now decided
     * in a single place, and the ordering that backs the numbering is likewise shared discipline
     * ({@link MarkerMatch#DETERMINISTIC_ORDER} / {@link #sortVariantsDeterministically}).
     *
     * @param lead the sentence before the selector clause, e.g. {@code "3 markers match check 'x'"}
     * @param selector the parameter the caller re-calls with ({@code index} / {@code variant})
     * @param hint extra guidance for the selector clause (e.g. a narrowing tip), or {@code ""}
     * @param labels the choices in the order they are numbered; each must identify its own choice
     *            unambiguously - two identical labels make the selector a coin flip, and this tool
     *            MUTATES what it selects
     * @return the ready error JSON
     */
    private static String ambiguousChoiceError(String lead, String selector, String hint,
        List<String> labels)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(lead).append("; re-call with ").append(selector).append("=<1..") //$NON-NLS-1$ //$NON-NLS-2$
          .append(labels.size()).append(">").append(hint).append(": "); //$NON-NLS-1$ //$NON-NLS-2$
        for (int i = 0; i < labels.size(); i++)
        {
            if (i > 0)
            {
                sb.append("; "); //$NON-NLS-1$
            }
            sb.append(i + 1).append(") ").append(labels.get(i)); //$NON-NLS-1$
        }
        return ToolResult.error(sb.toString()).toJson();
    }

    /** Actionable "several markers match — pick one" error, listing each with its 1-based index. */
    private static String multipleMarkersError(String checkId, List<MarkerMatch> matches)
    {
        List<String> labels = new ArrayList<>(matches.size());
        for (MarkerMatch m : matches)
        {
            labels.add(m.location() + " — " + m.message); //$NON-NLS-1$
        }
        return ambiguousChoiceError(matches.size() + " markers match check '" + checkId + "'", //$NON-NLS-1$ //$NON-NLS-2$
            "index", " (or narrow with modulePath/line)", labels); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Actionable "this fix has several variants — pick one" error. */
    private static String multipleVariantsError(MarkerMatch chosen, List<FixVariantDescriptor> variants)
    {
        List<String> labels = new ArrayList<>(variants.size());
        for (FixVariantDescriptor variant : variants)
        {
            labels.add(describeForListing(variant));
        }
        return ambiguousChoiceError("The fix for check '" + chosen.checkId + "' at " //$NON-NLS-1$ //$NON-NLS-2$
            + chosen.location() + " has " + variants.size() + " variants", //$NON-NLS-1$ //$NON-NLS-2$
            "variant", "", labels); //$NON-NLS-1$ //$NON-NLS-2$
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

    /**
     * Sorts {@code variants} into a DETERMINISTIC order by their displayed {@link #describe}
     * text, in place.
     * <p>
     * {@code getApplicableFixVariants()} returns a {@code Collection} with no ordering
     * guarantee (the same class of hazard as {@code IMarkerManager.markers()} - see
     * {@link MarkerMatch#DETERMINISTIC_ORDER}) - and each call to {@code applyFix} prepares a
     * FRESH {@link FixProcessHandle}, so a {@code variant=N} selector chosen from ONE call's
     * {@link #multipleVariantsError} listing must still resolve to the SAME variant on a
     * follow-up call, even though the underlying Collection could enumerate the same variant
     * set in a different order each time.
     * <p>
     * Sorted by ({@code description}, {@code details}) - both fields, not the collapsed
     * {@link #describe} text {@code multipleVariantsError} shows the caller: two variants can
     * share the same non-empty description while differing only in details, in which case
     * {@code describe} alone would tie them and leave their relative order exactly as unstable
     * as the input Collection it exists to neutralize. Two variants tied on BOTH fields are
     * genuinely indistinguishable from the data this class exposes (the same conclusion
     * {@link MarkerMatch#DETERMINISTIC_ORDER} reaches for two markers identical across every
     * field) - there is no further signal to break that tie on.
     * <p>
     * Package-visible (not {@code private}) so this pure ordering decision is unit-testable
     * without a live {@link IFixManager} session.
     *
     * @param variants the candidate fix variants for one marker; sorted in place, never
     *            {@code null} or empty when called from {@link #applyFix}
     */
    static void sortVariantsDeterministically(List<FixVariantDescriptor> variants)
    {
        variants.sort(Comparator
            .comparing(ApplyQuickFixTool::descriptionSortKey, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(ApplyQuickFixTool::detailsSortKey, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    private static String descriptionSortKey(FixVariantDescriptor variant)
    {
        String desc = variant.getDescription();
        return desc != null && !desc.isEmpty() ? desc : null;
    }

    private static String detailsSortKey(FixVariantDescriptor variant)
    {
        String details = variant.getDetails();
        return details != null && !details.isEmpty() ? details : null;
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

    /**
     * A fix variant's label for the {@link #multipleVariantsError} listing: {@link #describe} plus
     * the variant's {@code details} whenever they say something the description does not.
     * <p>
     * {@code describe} alone can print two DIFFERENT variants identically - a check may register
     * several fixes sharing one description and differing only in details (e.g. "Rename variable"
     * with details "to 'a'" / "to 'b'"). The caller answers with a bare number and this tool then
     * MUTATES the source, so an ambiguous listing asks them to choose blind. The same collision is
     * why {@link #sortVariantsDeterministically} orders by ({@code description}, {@code details})
     * rather than by {@code describe}: the listing must SHOW whatever the ordering relies on, or
     * the numbering is stable but unreadable.
     * <p>
     * Nothing is appended when {@code details} is empty, or when {@code describe} already returned
     * the details themselves (its empty-description fallback) - that would just print them twice.
     * <p>
     * Package-visible (not {@code private}) for the same reason as
     * {@link #sortVariantsDeterministically}: it is a pure labelling decision, unit-testable
     * without a live {@link IFixManager} session.
     *
     * @param variant the variant to label
     * @return the listing label, never {@code null}
     */
    static String describeForListing(FixVariantDescriptor variant)
    {
        String label = describe(variant);
        String details = variant.getDetails();
        if (details != null && !details.isEmpty() && !details.equals(label))
        {
            return label + " (" + details + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return label;
    }

    /**
     * One marker matched by the locator, with the bits needed for selection + reporting.
     * Package-visible (not {@code private}) so {@link #findMatches}'s ordering is unit-testable.
     */
    static final class MarkerMatch
    {
        /**
         * Content-derived total order (module path, line, target object, check id, message), all
         * null-safe: markers with no module path sort first, followed by unattributed lines. Two
         * markers identical across every field are genuinely indistinguishable to the caller, so
         * their relative order does not matter.
         * <p>
         * {@code objectPresentation} participates because it is the ONLY thing separating two
         * object-level markers that share a check and a message (neither has a module position): if
         * it were left out they would tie here, and their printed order - hence what {@code index=N}
         * selects - would fall back to the marker stream's own unspecified order. Since it is filled
         * only after the read boundary, {@link ApplyQuickFixTool#resolveObjectPresentations} re-sorts
         * once the values are known.
         */
        static final Comparator<MarkerMatch> DETERMINISTIC_ORDER = Comparator
            .comparing((MarkerMatch m) -> m.modulePath, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(m -> m.line, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(m -> m.objectPresentation, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(m -> m.checkId, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(m -> m.message, Comparator.nullsFirst(Comparator.naturalOrder()));

        final Marker marker;
        final String checkId;
        final String modulePath;
        final Integer line;
        final String message;

        /**
         * The marker's target object as {@code get_project_errors} prints it in its {@code Location}
         * column (e.g. {@code Catalog.Products}), or {@code null} when it has none / could not be
         * resolved. NOT final and NOT set by the constructor: resolving it needs a BM read boundary,
         * so it is filled afterwards by {@link ApplyQuickFixTool#resolveObjectPresentations} rather
         * than dragging a transaction into the marker walk itself.
         */
        String objectPresentation;

        MarkerMatch(Marker marker, String checkId, String modulePath, Integer line, String message)
        {
            this.marker = marker;
            this.checkId = checkId;
            this.modulePath = modulePath;
            this.line = line;
            this.message = message;
        }

        /**
         * The most specific locator available: "module:line" for a BSL-positioned marker, else the
         * target object's presentation (an object/metadata-level marker has no module position), and
         * only if neither resolves, the check id - which alone identifies nothing when several
         * markers share the check, hence the presentation fallback in between.
         */
        String location()
        {
            if (modulePath != null && !modulePath.isEmpty())
            {
                return line != null ? modulePath + ":" + line : modulePath; //$NON-NLS-1$
            }
            if (objectPresentation != null && !objectPresentation.isEmpty())
            {
                return objectPresentation;
            }
            return checkId;
        }
    }
}
