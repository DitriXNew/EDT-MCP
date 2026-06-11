/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.swt.widgets.Display;

import com._1c.g5.v8.bm.core.BmUriUtil;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.MetadataPathResolver;

/**
 * Bulk re-synchronizes the in-memory BM model to the on-disk {@code src/}
 * {@code .mdo} files and reports any pre-existing BM&harr;disk desync.
 * <p>
 * <b>Why this exists.</b> A metadata write commits into the in-memory BM model
 * and {@link BmTransactions#forceExportToDisk} flushes only the <em>single</em>
 * changed top object to disk. Objects created in earlier sessions (before that
 * per-object flush existed) live in the BM model and the
 * {@code Configuration.mdo} object list, yet have no {@code .mdo} on disk. The
 * desync is invisible until {@code update_database} / XML import fails with
 * "object file does not exist - /Subsystems/X.mdo; /Roles/Y.mdo; ...".
 * {@code clean_project} re-imports disk&rarr;BM (the wrong direction) and cannot
 * recreate the missing files.
 * <p>
 * This tool walks every top object of the project's BM model
 * ({@link IBmTransaction#getTopObjectIterator()}, which catches all kinds, not
 * just the collections an enumeration would special-case) and keeps only the
 * real metadata objects ({@link MdObject} - the ones that map to a {@code .mdo}
 * file), then computes which of them have no {@code .mdo} on disk and calls
 * {@link IBmModelManager#forceExport(com._1c.g5.v8.dt.core.platform.IDtProject, List)}
 * (via {@link BmTransactions#forceExportToDisk}) for that MISSING SUBSET only, so
 * the absent {@code .mdo} files are (re)written under {@code src/}. The export
 * runs on the SWT UI thread, so re-serializing every object of a large
 * configuration there just to restore a handful of files would freeze the
 * workbench for no benefit; {@code fullExport=true} opts in to the
 * export-everything refresh when a full rewrite is actually wanted. Internal BM
 * top objects that are not {@link MdObject} and therefore have no {@code .mdo} -
 * content forms ({@code com._1c.g5.v8.dt.form.model.Form}, persisted as
 * {@code .form}) and BSL module reference/context index objects
 * ({@code Module.bsl.mRIdx} / {@code Module.bsl.mCtxIdx}) - are excluded so they
 * are not mis-reported as a missing-{@code .mdo} desync.
 * <p>
 * <b>Integrity report.</b> Before exporting, the tool computes the expected
 * {@code .mdo} path for each top object ({@code src/<TypeDir>/<Name>/<Name>.mdo},
 * via {@link MetadataPathResolver#resolveTopObjectMdoPath(String)}) and records
 * the ones that are missing on disk - that set is the actual desync. After the
 * export it re-checks and reports anything still missing (normally none). With
 * default parameters the run is idempotent and writes nothing on an in-sync
 * project (the missing set is empty, so there is nothing to export and no model
 * change); a second run reports {@code 0} missing again.
 * <p>
 * <b>Dangling-reference cleanup.</b> Independently of the missing-{@code .mdo}
 * desync above, a {@code Configuration.mdo} can still <em>register</em> objects
 * that have neither a {@code .mdo} file nor a BM body - "dangling" / "orphaned"
 * entries left behind when an object's body was lost but its registration in a
 * Configuration reference collection ({@code webServices}, {@code commonForms},
 * {@code subsystems}, &hellip;) was not. EDT surfaces each as a
 * {@code md-reference-intergrity} warning ("a lost reference is set in field X
 * at position N"), and {@code update_database} / XML import fail because the
 * Configuration points at non-existent object bodies. {@code delete_metadata}
 * cannot remove them (no BM object to delete). These entries are
 * <b>unresolved EMF proxies</b> in the Configuration's many-valued
 * {@link MdObject} references. This tool detects them with the same check the
 * codebase already uses for BM references - {@link InternalEObject#eIsProxy()}
 * combined, for a BM proxy URI, with
 * {@link BmUriUtil#extractTopObjectFqn(URI)} +
 * {@link IBmTransaction#getTopObjectByFqn(String)} returning {@code null} - so
 * only genuinely unresolvable entries are touched, never a valid reference. By
 * default the scan is REPORT-ONLY ({@code danglingFound} + {@code danglingDetails},
 * no model change). Removal is an explicit, destructive opt-in: when
 * {@code cleanDanglingReferences} is {@code true} the proxy elements are removed
 * from their collections inside a BM transaction and the {@code Configuration}
 * top object is re-exported, REWRITING {@code Configuration.mdo} so it no longer
 * registers them; the project then validates clean and {@code update_database}
 * unblocks. The removal is reported as {@code danglingRemovedCount} /
 * {@code danglingRemoved} ONLY after the transaction has committed - a failed
 * write task surfaces as {@code danglingWarning} with no removal claim. The scan
 * is idempotent (a clean project reports {@code danglingFound 0}).
 */
public class ResyncToDiskTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "resync_to_disk"; //$NON-NLS-1$

    /** Cap on how many FQNs are listed back in the JSON to keep responses bounded. */
    private static final int MAX_LISTED_FQNS = 500;

    /**
     * Total time budget (ms) to wait for the post-export {@code .mdo} flush to be
     * visible on the filesystem before counting a file as still missing.
     * {@link BmTransactions#forceExportToDisk} runs the platform serializer
     * synchronously, but a just-restored file can still lag a separate on-disk
     * existence check by a beat (OS/filesystem write-visibility, plus any
     * UI-scheduled tail of the export), so the re-check polls within this budget
     * rather than judging on the first probe.
     */
    private static final long MDO_FLUSH_WAIT_MS = 2500L;

    /** Poll interval (ms) between on-disk re-checks within {@link #MDO_FLUSH_WAIT_MS}. */
    private static final long MDO_FLUSH_POLL_MS = 100L;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Bulk re-synchronize the in-memory BM model to the on-disk src/ .mdo files " //$NON-NLS-1$
            + "and report BM-to-disk desync. Walks EVERY top metadata object of the " //$NON-NLS-1$
            + "configuration (all kinds), reports the objects whose .mdo is missing on disk " //$NON-NLS-1$
            + "(missingBefore), and force-exports that missing subset so the files are " //$NON-NLS-1$
            + "restored (fullExport=true re-exports every object instead). Fixes 'object " //$NON-NLS-1$
            + "file does not exist' failures from update_database / XML import caused by an " //$NON-NLS-1$
            + "accumulated desync. Dangling/orphaned references in Configuration.mdo " //$NON-NLS-1$
            + "(unresolved proxies shown by get_project_errors as md-reference-intergrity " //$NON-NLS-1$
            + "'lost reference' warnings that block update_database / XML import) are " //$NON-NLS-1$
            + "REPORTED by default (danglingFound + danglingDetails); set " //$NON-NLS-1$
            + "cleanDanglingReferences=true to REMOVE them - destructive: rewrites " //$NON-NLS-1$
            + "Configuration.mdo. " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('resync_to_disk')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required).", true) //$NON-NLS-1$
            .booleanProperty("cleanDanglingReferences", //$NON-NLS-1$
                "When true, REMOVE dangling/orphaned references from Configuration.mdo - entries " //$NON-NLS-1$
                    + "that register an object with no .mdo and no BM body (unresolved proxies), " //$NON-NLS-1$
                    + "the source of md-reference-intergrity 'lost reference' warnings that block " //$NON-NLS-1$
                    + "update_database / XML import. Destructive: rewrites Configuration.mdo. " //$NON-NLS-1$
                    + "Default: false - only report danglingFound + danglingDetails without " //$NON-NLS-1$
                    + "changing anything.") //$NON-NLS-1$
            .booleanProperty("fullExport", //$NON-NLS-1$
                "When true, force-export EVERY metadata top object's .mdo (a full disk refresh; " //$NON-NLS-1$
                    + "slow on a large configuration - the export runs on the UI thread). " //$NON-NLS-1$
                    + "Default: false - export only the objects whose .mdo is missing on disk.") //$NON-NLS-1$
            .booleanProperty("revalidate", //$NON-NLS-1$
                "When true, schedule a full project revalidation (clean build) after the export so " //$NON-NLS-1$
                    + "stale markers refresh. Default: false (export only).") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the export succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("projectName", "The project that was re-synchronized") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("objectsExported", //$NON-NLS-1$
                "Number of top objects whose .mdo was (re)written - the missing subset by default, " //$NON-NLS-1$
                    + "every object when fullExport=true") //$NON-NLS-1$
            .integerProperty("totalTopObjects", "Total metadata top objects walked in the BM model") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("fullExport", "Whether a full export of every top object was requested") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("missingBeforeCount", "Top objects that had no .mdo on disk before the export") //$NON-NLS-1$ //$NON-NLS-2$
            .stringArrayProperty("missingBefore", "FQNs that were missing on disk before the export") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("stillMissingCount", "Top objects still missing on disk after the export") //$NON-NLS-1$ //$NON-NLS-2$
            .stringArrayProperty("stillMissing", "FQNs still missing on disk after the export") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("cleanDanglingReferences", "Whether dangling-reference removal was requested") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("danglingFound", "Dangling (unresolved-proxy) references detected in Configuration.mdo") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("danglingRemovedCount", "Dangling references actually removed (0 in report-only mode)") //$NON-NLS-1$ //$NON-NLS-2$
            .objectArrayProperty("danglingRemoved", "Removed dangling entries: [{field, lostFqn, position}]") //$NON-NLS-1$ //$NON-NLS-2$
            .objectArrayProperty("danglingDetails", "All dangling entries found: [{field, lostFqn, position}]") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("danglingWarning", "Set when the dangling scan/cleanup could not complete cleanly") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("revalidate", "Whether a post-export revalidation was requested") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("revalidateWarning", "Set when the optional post-export revalidation failed") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("message", "Human-readable summary of the outcome") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        boolean revalidate = JsonUtils.extractBooleanArgument(params, "revalidate", false); //$NON-NLS-1$
        // Default FALSE (report-only): removing the dangling entries rewrites
        // Configuration.mdo, so the destructive step must be an explicit opt-in, never
        // a side effect of a diagnostic run.
        boolean cleanDangling = JsonUtils.extractBooleanArgument(params, "cleanDanglingReferences", false); //$NON-NLS-1$
        // Default FALSE: only the objects whose .mdo is actually missing are exported;
        // true re-exports everything (a full disk refresh, slow on the UI thread).
        boolean fullExport = JsonUtils.extractBooleanArgument(params, "fullExport", false); //$NON-NLS-1$

        ProjectContext ctx = resolveProjectAndConfig(projectName);
        if (ctx.hasError())
        {
            return ctx.error;
        }

        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            return ToolResult.error("IBmModelManager not available").toJson(); //$NON-NLS-1$
        }
        IBmModel bmModel = bmModelManager.getModel(ctx.project);
        if (bmModel == null)
        {
            return ToolResult.error("BM model not available for project: " + projectName).toJson(); //$NON-NLS-1$
        }

        // Step 1: enumerate the real metadata top objects of the configuration via
        // the BM model. getTopObjectIterator() yields every IBmObject top object
        // regardless of kind (so nothing is missed the way a per-collection walk
        // could), and the collector keeps only MdObject instances - the ones that
        // map to a .mdo file - filtering out internal non-metadata top objects
        // (content forms, BSL module index objects) that have no .mdo by design.
        List<String> allFqns = collectMetadataTopObjectFqns(bmModel);

        // Step 2: integrity check BEFORE the export - which registered objects have
        // no .mdo on disk. This pre-export set is the real desync we are catching up.
        List<String> missingBefore = findMissingMdoFiles(ctx.project, allFqns);

        // Step 3: restore the missing .mdo files (same path the per-object persist
        // uses). By default ONLY the missingBefore subset is force-exported: the export
        // runs on the SWT UI thread, so re-serializing EVERY object of a large
        // configuration there just to restore a handful of files would freeze the
        // workbench while rewriting identical bytes. fullExport=true opts in to the
        // export-everything refresh. When the dangling cleanup (step 5) removes
        // entries it re-exports the Configuration top object itself, so that case
        // does not need a full export either.
        List<String> exportFqns = selectExportFqns(fullExport, allFqns, missingBefore);
        // An empty export list (already in sync, no full export requested) is a genuine
        // no-op: nothing to write, trivially successful.
        boolean exported = exportFqns.isEmpty() || BmTransactions.forceExportToDisk(ctx.project, exportFqns);

        // Step 4: re-check on disk. After a successful export the missing-before set
        // should be empty; anything still missing is surfaced so the caller knows
        // the desync was not fully resolved (e.g. a type with no src/ layout).
        // forceExportToDisk runs the platform serializer synchronously, but the just-
        // written .mdo can still lag this separate on-disk existence check by a beat
        // (OS/filesystem write-visibility, plus any UI-scheduled tail of the export),
        // so an immediate re-check could observe a file that is about to land as still
        // missing and mis-report stillMissingCount. Re-check with a short bounded wait:
        // poll each previously-missing .mdo for existence, and only the files still
        // absent after the budget elapses count as stillMissing.
        List<String> stillMissing =
            findMissingMdoFilesWithWait(ctx.project, missingBefore, MDO_FLUSH_WAIT_MS, MDO_FLUSH_POLL_MS);

        // Step 5: clean dangling/orphaned references in Configuration.mdo. These are
        // unresolved proxies registered in the Configuration's MdObject collections
        // with no .mdo and no BM body - the source of md-reference-intergrity "lost
        // reference" warnings that block update_database / XML import. Detection and
        // (optional) removal run in a single BM task; when entries are removed the
        // Configuration top object is re-exported so Configuration.mdo no longer
        // registers them.
        DanglingResult dangling =
            cleanDanglingReferences(ctx.config, bmModel, ctx.project, cleanDangling);

        // Step 6 (optional, best-effort): refresh stale validation markers. Only run
        // when the export actually succeeded - a failed export must not then trigger a
        // full clean build.
        String revalidateWarning = null;
        if (revalidate && exported)
        {
            try
            {
                CleanProjectTool.cleanProject(projectName);
            }
            catch (Exception e)
            {
                Activator.logError("Error revalidating after resync_to_disk: " + projectName, e); //$NON-NLS-1$
                revalidateWarning = unwrapCauseMessage(e);
            }
        }

        // The force-export swallows failures and returns false (unresolved
        // services/project, or the export threw). `exported` is true when the export
        // list was empty (a genuine in-sync no-op), so false here always means an
        // export was expected but did not run/succeed - report success:false so the
        // caller is not misled by a success envelope.
        boolean exportFailed = !exported;
        ToolResult result = exportFailed
            ? ToolResult.error("Force-export to disk did not run or failed (services/project/FQN " //$NON-NLS-1$
                + "unresolved or export threw); see message/objectsExported") //$NON-NLS-1$
            : ToolResult.success();
        result.put("projectName", projectName) //$NON-NLS-1$
            .put("objectsExported", exported ? exportFqns.size() : 0) //$NON-NLS-1$
            .put("totalTopObjects", allFqns.size()) //$NON-NLS-1$
            .put("fullExport", fullExport) //$NON-NLS-1$
            .put("revalidate", revalidate) //$NON-NLS-1$
            .put("missingBeforeCount", missingBefore.size()) //$NON-NLS-1$
            .put("missingBefore", limit(missingBefore)) //$NON-NLS-1$
            .put("stillMissingCount", stillMissing.size()) //$NON-NLS-1$
            .put("stillMissing", limit(stillMissing)) //$NON-NLS-1$
            .put("cleanDanglingReferences", cleanDangling) //$NON-NLS-1$
            .put("danglingFound", dangling.found) //$NON-NLS-1$
            .put("danglingRemovedCount", dangling.removedCount()) //$NON-NLS-1$
            // danglingRemoved: the entries actually removed (empty in report-only mode).
            // danglingDetails: every dangling entry found, shown even when nothing was
            // removed so a report-only run still surfaces what is dangling.
            .put("danglingRemoved", limitObjects(dangling.removedFromModel ? dangling.details //$NON-NLS-1$
                : Collections.emptyList()))
            .put("danglingDetails", limitObjects(dangling.details)); //$NON-NLS-1$
        if (dangling.warning != null)
        {
            result.put("danglingWarning", dangling.warning); //$NON-NLS-1$
        }
        if (revalidateWarning != null)
        {
            result.put("revalidateWarning", revalidateWarning); //$NON-NLS-1$
        }
        result.put("message", buildMessage(exported, exported ? exportFqns.size() : 0, fullExport, //$NON-NLS-1$
            missingBefore.size(), stillMissing.size(), dangling, cleanDangling));
        return result.toJson();
    }

    /**
     * Decides WHICH top objects step 3 force-exports. By default only the
     * {@code missingBefore} subset - the objects whose {@code .mdo} is actually absent
     * on disk - is exported: the export runs on the SWT UI thread, and re-serializing
     * every object of a large configuration there just to restore a handful of missing
     * files would freeze the workbench while rewriting identical bytes.
     * {@code fullExport=true} opts in to the export-everything refresh.
     * <p>
     * The {@code Configuration} top object is NOT part of this decision: when dangling
     * references are removed, {@link #cleanDanglingReferences} re-exports the
     * {@code Configuration} itself.
     *
     * @param fullExport {@code true} to export every top object
     * @param allFqns every metadata top-object FQN of the project
     * @param missingBefore the FQNs whose {@code .mdo} is missing on disk
     * @return the FQNs to force-export (never {@code null})
     */
    static List<String> selectExportFqns(boolean fullExport, List<String> allFqns,
        List<String> missingBefore)
    {
        return fullExport ? allFqns : missingBefore;
    }

    /**
     * Collects the FQNs of the real metadata top objects managed by the project's
     * BM model. Runs inside a read-only BM transaction and iterates
     * {@link IBmTransaction#getTopObjectIterator()}, keeping only {@link MdObject}
     * instances (the objects that map to a {@code .mdo} file). Internal BM top
     * objects that are not metadata and have no {@code .mdo} - content forms
     * ({@code com._1c.g5.v8.dt.form.model.Form}) and BSL module reference/context
     * index objects ({@code Module.bsl.mRIdx} / {@code Module.bsl.mCtxIdx}) - are
     * skipped so they are not flagged as a missing-{@code .mdo} desync.
     *
     * @param bmModel the project BM model
     * @return the metadata top-object FQNs (never {@code null}; may be empty)
     */
    private static List<String> collectMetadataTopObjectFqns(IBmModel bmModel)
    {
        List<String> fqns = new ArrayList<>();
        BmTransactions.<Void>read(bmModel, "CollectTopObjectsForResync", (tx, pm) -> //$NON-NLS-1$
        {
            Iterator<IBmObject> it = tx.getTopObjectIterator();
            while (it.hasNext())
            {
                IBmObject obj = it.next();
                if (obj == null)
                {
                    continue;
                }
                // getTopObjectIterator() returns EVERY BM top object, including
                // internal ones that are not metadata and have no .mdo file:
                // content forms (com._1c.g5.v8.dt.form.model.Form, persisted as
                // .form) and BSL module reference/context index objects
                // (Module.bsl.mRIdx / Module.bsl.mCtxIdx, internal derived data).
                // Only real metadata objects implement MdObject and map to a .mdo
                // file (Catalog/Document/CommonModule/Subsystem/Role/StyleItem/...),
                // so filter out everything else to avoid false-positive "missing
                // .mdo" reports for these non-MdObject top objects.
                if (!(obj instanceof MdObject))
                {
                    continue;
                }
                String fqn = obj.bmGetFqn();
                if (fqn != null && !fqn.isEmpty())
                {
                    fqns.add(fqn);
                }
            }
            return null;
        });
        return fqns;
    }

    /**
     * Outcome of the dangling-reference scan/cleanup: how many dangling entries
     * were found, the per-entry detail of the ones removed, and an optional
     * best-effort warning when the BM task or Configuration re-export failed.
     * Package-private so the commit-honest reporting can be unit-tested.
     */
    static final class DanglingResult
    {
        /** Total dangling (unresolved-proxy) entries detected across all collections. */
        int found;
        /**
         * {@code true} when the detected entries were removed from the model AND the
         * BM write transaction committed. Set only by {@link #runRemovalWriteTask}
         * AFTER {@link BmTransactions#write} returned - never inside the task body,
         * where the commit has not happened yet.
         */
        boolean removedFromModel;
        /** Detail of each dangling entry: {@code field}, {@code lostFqn}, {@code position}. */
        final List<Map<String, Object>> details = new ArrayList<>();
        /** Non-{@code null} when detection/removal could not be performed cleanly. */
        String warning;

        /** Number of entries actually removed (0 in report-only mode). */
        int removedCount()
        {
            return removedFromModel ? found : 0;
        }
    }

    /**
     * Detects, and (when {@code remove} is {@code true}) removes, dangling /
     * orphaned references registered in the project's {@link Configuration}.
     * <p>
     * A dangling entry is an element of one of the Configuration's many-valued
     * {@link MdObject} reference collections ({@code catalogs}, {@code subsystems},
     * {@code webServices}, {@code commonForms}, {@code commonAttributes},
     * {@code commandGroups}, {@code sessionParameters}, {@code businessProcesses},
     * &hellip;) that is an <b>unresolved EMF proxy</b>: the referenced object has no
     * {@code .mdo} and no BM body, so EDT reports it as a
     * {@code md-reference-intergrity} "lost reference" warning and
     * {@code update_database} / XML import fail.
     * <p>
     * <b>Detection.</b> Every collection is read without proxy resolution
     * ({@code eGet(ref, false)}), and each element is tested with the same check
     * the codebase already uses for BM references:
     * {@link InternalEObject#eIsProxy()} is {@code true}, and - for a BM proxy URI
     * - {@link BmUriUtil#extractTopObjectFqn(URI)} +
     * {@link IBmTransaction#getTopObjectByFqn(String)} returning {@code null}
     * confirm the target genuinely does not exist. Only the EClass reference
     * features whose type is a subtype of {@link MdObject} are scanned, and a
     * non-proxy (resolvable) element is never touched, so a valid reference is
     * never removed.
     * <p>
     * <b>Removal.</b> When {@code remove} is {@code true} the proxy elements are
     * removed from their {@link EList}s inside the same BM write transaction; the
     * change is then flushed by re-exporting the {@code Configuration} top object
     * so {@code Configuration.mdo} no longer registers them. The removal is claimed
     * ({@code removedFromModel}) ONLY after the write task has returned, i.e. after
     * the transaction committed; a throwing task reports {@code danglingWarning}
     * and no removal. When {@code remove} is {@code false} the method only reports
     * what is dangling (no model change, no re-export). The operation is
     * idempotent: a clean Configuration yields {@code found == 0} and makes no
     * change.
     *
     * @param config the project configuration (a {@link IBmObject})
     * @param bmModel the project BM model
     * @param project the workspace project (for the Configuration re-export)
     * @param remove {@code true} to remove the dangling entries, {@code false} to
     *            only report them
     * @return the {@link DanglingResult} (never {@code null})
     */
    private static DanglingResult cleanDanglingReferences(Configuration config, IBmModel bmModel,
        IProject project, boolean remove)
    {
        DanglingResult result = new DanglingResult();
        if (!(config instanceof IBmObject))
        {
            result.warning = "Configuration is not a BM object; dangling-reference scan skipped."; //$NON-NLS-1$
            return result;
        }
        final long configBmId = ((IBmObject)config).bmGetId();

        // Detection (and, when remove=true, mutation) run inside one BM write task so
        // the same transaction that observes the proxies also removes them atomically.
        // The task body only reports whether it removed anything IN the transaction;
        // the removedFromModel claim is made by runRemovalWriteTask AFTER
        // BmTransactions.write returned, i.e. only once the transaction actually
        // committed - a failed commit must not be reported as "Removed N".
        boolean committed = runRemovalWriteTask(result,
            () -> BmTransactions.<Boolean>write(bmModel, "CleanDanglingReferences", (tx, pm) -> //$NON-NLS-1$
            {
                Configuration cfg = (Configuration)tx.getObjectById(configBmId);
                if (cfg == null)
                {
                    result.warning = "Configuration not found in transaction; dangling-reference scan skipped."; //$NON-NLS-1$
                    return Boolean.FALSE;
                }
                return Boolean.valueOf(scanAndRemove(cfg, tx, remove, result));
            }));
        if (!committed)
        {
            return result;
        }

        // Flush the cleaned Configuration to disk so Configuration.mdo no longer
        // registers the removed proxies. Only needed when something was removed.
        if (result.removedFromModel && result.found > 0)
        {
            String configFqn = ((IBmObject)config).bmGetFqn();
            if (configFqn != null && !configFqn.isEmpty())
            {
                boolean reExported = BmTransactions.forceExportToDisk(project, configFqn);
                if (!reExported)
                {
                    result.warning = "Dangling entries removed in the model but Configuration.mdo " //$NON-NLS-1$
                        + "re-export failed; run resync_to_disk again or clean_project."; //$NON-NLS-1$
                }
            }
        }
        return result;
    }

    /**
     * Runs the dangling-removal BM write task and applies its outcome to
     * {@code result} with commit-honest reporting: {@code removedFromModel} is set
     * ONLY after the task has returned successfully - i.e. after
     * {@link BmTransactions#write} committed the transaction. Setting it inside the
     * task body would claim "Removed N" even when the commit subsequently fails and
     * the removal is rolled back.
     * <p>
     * When the task throws, no removal is claimed ({@code removedFromModel} stays
     * {@code false}, so {@link DanglingResult#removedCount()} is {@code 0} and
     * {@code danglingRemoved} stays empty); the failure is logged and surfaced as
     * {@code result.warning} instead - the existing warning plumbing.
     *
     * @param result the result the outcome is applied to
     * @param writeTask the BM write task; returns whether entries were removed
     *            inside the transaction
     * @return {@code true} when the task completed (the transaction committed),
     *         {@code false} when it threw
     */
    static boolean runRemovalWriteTask(DanglingResult result, Callable<Boolean> writeTask)
    {
        try
        {
            result.removedFromModel = Boolean.TRUE.equals(writeTask.call());
            return true;
        }
        catch (Exception e)
        {
            Activator.logError("Error cleaning dangling references in Configuration", e); //$NON-NLS-1$
            result.warning = unwrapCauseMessage(e);
            return false;
        }
    }

    /**
     * Scans every many-valued {@link MdObject} reference of the Configuration for
     * unresolved-proxy (dangling) elements, records them in {@code result}, and -
     * when {@code remove} is {@code true} - removes them from their {@link EList}.
     * <p>
     * Runs inside the supplied BM transaction so {@link #isDanglingReference} can
     * confirm a BM proxy's target is truly absent via
     * {@link IBmTransaction#getTopObjectByFqn(String)}.
     *
     * @return whether any dangling entry was removed INSIDE the transaction. The
     *         caller ({@link #runRemovalWriteTask}) turns this into the
     *         {@code removedFromModel} claim only after the transaction commits.
     */
    @SuppressWarnings("unchecked")
    private static boolean scanAndRemove(Configuration cfg, IBmTransaction tx, boolean remove,
        DanglingResult result)
    {
        boolean removedAny = false;
        for (EReference ref : cfg.eClass().getEAllReferences())
        {
            if (!ref.isMany())
            {
                // Single-valued references are not the "lost reference at position N"
                // collections the md-reference-intergrity check reports; skip them.
                continue;
            }
            if (ref.isDerived() || ref.isTransient() || ref.isVolatile() || !ref.isChangeable())
            {
                // Derived/computed collections are not the persisted Configuration.mdo
                // registrations and may be unmodifiable - never the dangling source.
                continue;
            }
            if (!isMdObjectReference(ref))
            {
                continue;
            }
            // Read WITHOUT resolving proxies: a dangling target must stay a proxy so
            // eIsProxy() can detect it; resolving a valid ref returns the real object.
            Object value = cfg.eGet(ref, false);
            if (!(value instanceof EList))
            {
                continue;
            }
            EList<EObject> list = (EList<EObject>)value;
            // Walk a snapshot of positions so the reported position matches the
            // original .mdo layout even as earlier entries are removed.
            List<EObject> dangling = new ArrayList<>();
            int position = 0;
            for (EObject element : list)
            {
                if (element != null && isDanglingReference(element, tx))
                {
                    result.found++;
                    String lostFqn = proxyFqnOf(element);
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("field", ref.getName()); //$NON-NLS-1$
                    entry.put("lostFqn", lostFqn != null ? lostFqn : "(unknown)"); //$NON-NLS-1$ //$NON-NLS-2$
                    entry.put("position", Integer.valueOf(position)); //$NON-NLS-1$
                    result.details.add(entry);
                    dangling.add(element);
                }
                position++;
            }
            if (remove && !dangling.isEmpty())
            {
                list.removeAll(dangling);
                removedAny = true;
            }
        }
        return removedAny;
    }

    /**
     * Returns {@code true} when {@code ref} points at metadata objects, i.e. its
     * reference type is a subtype of {@link MdObject}. The Configuration also holds
     * non-{@code MdObject} references (e.g. the default-language/object pointers)
     * that are not part of the "lost reference" collections, so they are excluded.
     */
    private static boolean isMdObjectReference(EReference ref)
    {
        return ref.getEReferenceType() != null
            && MdClassPackage.Literals.MD_OBJECT.isSuperTypeOf(ref.getEReferenceType());
    }

    /**
     * Tests whether a Configuration reference element is a dangling (unresolvable)
     * entry, using the same approach the codebase applies to BM references.
     * <p>
     * The element is dangling when it is an EMF proxy that cannot be resolved: it is
     * an {@link InternalEObject} with {@link InternalEObject#eIsProxy()} set, and -
     * when the proxy URI is a BM URI - its target top object is absent from the
     * transaction ({@link IBmTransaction#getTopObjectByFqn(String)} is {@code null}).
     * A non-proxy element (a real, resolvable object) is never dangling, so a valid
     * reference is never removed.
     */
    private static boolean isDanglingReference(EObject element, IBmTransaction tx)
    {
        if (!(element instanceof InternalEObject))
        {
            return false;
        }
        InternalEObject internal = (InternalEObject)element;
        if (!internal.eIsProxy())
        {
            // Resolvable / already-resolved object: a genuine reference, never removed.
            return false;
        }
        URI proxyUri = internal.eProxyURI();
        if (proxyUri == null)
        {
            // A proxy with no URI cannot be resolved at all: dangling by definition.
            return true;
        }
        if (BmUriUtil.isBmUri(proxyUri))
        {
            // BM proxy: confirm the target top object genuinely does not exist before
            // treating it as dangling, so a not-yet-loaded-but-present object is kept.
            String fqn = BmUriUtil.extractTopObjectFqn(proxyUri);
            if (fqn == null || fqn.isEmpty())
            {
                return true;
            }
            return tx.getTopObjectByFqn(fqn) == null;
        }
        // Non-BM proxy on an MdObject reference: try one last resolution against the
        // element's own resource set; only if it stays an unresolved proxy is it
        // dangling. EcoreUtil.resolve returns the proxy unchanged when it cannot be
        // resolved, so an equal-and-still-proxy result means genuinely unresolvable.
        EObject resolved = EcoreUtil.resolve(element, element);
        return resolved == element && ((InternalEObject)resolved).eIsProxy();
    }

    /**
     * Extracts the lost FQN reported by a dangling proxy for the
     * {@code danglingRemoved} report. For a BM proxy URI this is the referenced top
     * object FQN (e.g. {@code "WebService.TestService"}); otherwise the raw proxy
     * URI string, or {@code null} when none is available.
     */
    private static String proxyFqnOf(EObject element)
    {
        if (!(element instanceof InternalEObject))
        {
            return null;
        }
        URI proxyUri = ((InternalEObject)element).eProxyURI();
        if (proxyUri == null)
        {
            return null;
        }
        if (BmUriUtil.isBmUri(proxyUri))
        {
            String fqn = BmUriUtil.extractTopObjectFqn(proxyUri);
            if (fqn != null && !fqn.isEmpty())
            {
                return fqn;
            }
        }
        return proxyUri.toString();
    }

    /**
     * Returns the subset of the given FQNs whose expected {@code .mdo} file does
     * not exist on disk under {@code src/}.
     * <p>
     * The expected path is {@code src/<TypeDir>/<Name>/<Name>.mdo}, resolved via
     * {@link MetadataPathResolver#resolveTopObjectMdoPath(String)}. FQNs whose type
     * has no {@code src/} directory layout (e.g. {@code Language}, {@code Style},
     * or the {@code Configuration} root) are skipped rather than reported as
     * missing, since they are not stored as an own {@code .mdo} under a type
     * directory. The on-disk filesystem is checked directly (not the possibly
     * stale workspace resource tree) so the result reflects reality immediately.
     *
     * @param project the workspace project
     * @param fqns the FQNs to check
     * @return the FQNs with a missing {@code .mdo} (never {@code null})
     */
    private static List<String> findMissingMdoFiles(IProject project, List<String> fqns)
    {
        File projectRoot = projectRootOf(project);
        if (projectRoot == null)
        {
            return new ArrayList<>();
        }
        return findMissingMdoFiles(projectRoot, fqns);
    }

    /**
     * Re-checks the given FQNs against disk with a short bounded wait, so a
     * {@code .mdo} that the post-export flush is about to write is counted as
     * present rather than mis-reported as still missing.
     * <p>
     * The check runs immediately and then re-polls only the FQNs still missing,
     * sleeping {@code pollMs} between rounds, until either nothing is missing or the
     * cumulative {@code waitMs} budget is exhausted. The first round is free (no
     * sleep), so an already-flushed configuration returns at once; the wait is only
     * paid while files are genuinely still landing. Returns the FQNs whose
     * {@code .mdo} is still absent after the budget elapses.
     *
     * @param project the workspace project
     * @param fqns the FQNs to re-check (typically the pre-export missing set)
     * @param waitMs total time budget in milliseconds for the file to appear
     * @param pollMs sleep between re-checks in milliseconds
     * @return the FQNs still missing on disk after the bounded wait (never {@code null})
     */
    private static List<String> findMissingMdoFilesWithWait(IProject project, List<String> fqns, long waitMs,
        long pollMs)
    {
        File projectRoot = projectRootOf(project);
        if (projectRoot == null)
        {
            return new ArrayList<>();
        }
        return findMissingMdoFilesWithWait(projectRoot, fqns, waitMs, pollMs);
    }

    /**
     * Filesystem core of {@link #findMissingMdoFilesWithWait(IProject, List, long, long)}:
     * polls {@code projectRoot} for each FQN's expected {@code .mdo} until none are
     * missing or the {@code waitMs} budget is spent. Decoupled from
     * {@link IProject} so the bounded-wait behaviour can be unit-tested against a
     * plain temp directory.
     * <p>
     * This runs on the SWT UI thread (the tool's {@code executeOnUiThread} is
     * invoked inside {@link Display#syncExec}). A bare {@link Thread#sleep} poll
     * would freeze the workbench for the whole budget AND - if any part of the
     * post-export {@code .mdo} flush tail is posted back to the UI thread
     * (asyncExec / a UI job) - starve that flush so the file never lands during
     * the wait, defeating the purpose. So when on the UI thread we PUMP the event
     * loop between existence re-checks (mirroring
     * {@code GetContentAssistTool.waitForResourceReadiness}/{@code pumpUi}): drain
     * {@link Display#readAndDispatch()}, which keeps the workbench responsive and
     * lets any UI-thread-scheduled flush run, and only sleep briefly when there
     * are no pending events. When {@link Display#getCurrent()} is {@code null}
     * (a non-UI / headless caller, including the unit tests) we fall back to the
     * plain sleep loop, since that path is not on the UI thread.
     */
    static List<String> findMissingMdoFilesWithWait(File projectRoot, List<String> fqns, long waitMs,
        long pollMs)
    {
        long deadline = System.currentTimeMillis() + Math.max(0L, waitMs);
        List<String> missing = findMissingMdoFiles(projectRoot, fqns);
        // Display.getCurrent() is null off the UI thread (and in headless tests, where
        // no SWT display thread exists); only then is the plain sleep path safe.
        Display display = Display.getCurrent();
        while (!missing.isEmpty() && System.currentTimeMillis() < deadline)
        {
            if (display != null)
            {
                // On the UI thread: drain queued events so the workbench stays
                // responsive and any UI-scheduled flush tail can run. Only sleep
                // briefly when there is nothing to dispatch, to keep the poll bounded.
                if (!display.readAndDispatch())
                {
                    sleepQuietly(Math.max(1L, pollMs));
                }
            }
            else
            {
                // Non-UI / headless caller: plain bounded sleep between re-checks.
                if (!sleepQuietly(Math.max(1L, pollMs)))
                {
                    break;
                }
            }
            // Re-check only the still-missing subset: a file that already appeared
            // stays present, so it never returns to the missing set.
            missing = findMissingMdoFiles(projectRoot, missing);
        }
        return missing;
    }

    /**
     * Sleeps for {@code millis}, restoring the interrupt flag on interruption.
     *
     * @return {@code true} if it slept the full duration, {@code false} if it was
     *         interrupted (the caller should stop waiting)
     */
    private static boolean sleepQuietly(long millis)
    {
        try
        {
            Thread.sleep(millis);
            return true;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Filesystem core of {@link #findMissingMdoFiles(IProject, List)}: returns the
     * FQNs whose {@code .mdo} is absent under {@code projectRoot} right now (the
     * expected path - {@code src/<TypeDir>/<Name>/<Name>.mdo} relative to the
     * project root - comes from
     * {@link MetadataPathResolver#resolveTopObjectMdoPath(String)}). Decoupled
     * from {@link IProject} so it (and the bounded-wait variant) can be unit-tested.
     */
    static List<String> findMissingMdoFiles(File projectRoot, List<String> fqns)
    {
        List<String> missing = new ArrayList<>();
        for (String fqn : fqns)
        {
            String relative = MetadataPathResolver.resolveTopObjectMdoPath(fqn);
            if (relative == null)
            {
                // Type has no own .mdo file under a type directory: not a desync candidate.
                continue;
            }
            File mdoFile = new File(projectRoot, relative);
            if (!mdoFile.isFile())
            {
                missing.add(fqn);
            }
        }
        return missing;
    }

    /**
     * Resolves the project's root directory as a {@link File}, or {@code null}
     * when the project has no on-disk location.
     */
    private static File projectRootOf(IProject project)
    {
        IPath location = project.getLocation();
        if (location == null)
        {
            return null;
        }
        return location.toFile();
    }

    /** Caps a list to {@link #MAX_LISTED_FQNS} entries for a bounded JSON response. */
    private static List<String> limit(List<String> values)
    {
        if (values.size() <= MAX_LISTED_FQNS)
        {
            return values;
        }
        return new ArrayList<>(values.subList(0, MAX_LISTED_FQNS));
    }

    /** Caps a list of detail maps to {@link #MAX_LISTED_FQNS} entries. */
    private static List<Map<String, Object>> limitObjects(List<Map<String, Object>> values)
    {
        if (values.size() <= MAX_LISTED_FQNS)
        {
            return values;
        }
        return new ArrayList<>(values.subList(0, MAX_LISTED_FQNS));
    }

    /** Builds a concise human-readable summary of the outcome. */
    private static String buildMessage(boolean exported, int objectsExported, boolean fullExport,
        int missingBefore, int stillMissing, DanglingResult dangling, boolean cleanDangling)
    {
        StringBuilder sb = new StringBuilder();
        if (!exported)
        {
            sb.append("Export to disk did not run (services/project unavailable). ").append(missingBefore) //$NON-NLS-1$
                .append(" object(s) were missing on disk before the attempt."); //$NON-NLS-1$
        }
        else if (fullExport)
        {
            sb.append("Re-exported all ").append(objectsExported).append(" top object(s) to src/. "); //$NON-NLS-1$ //$NON-NLS-2$
            if (missingBefore == 0)
            {
                sb.append("Already in sync: no .mdo files were missing."); //$NON-NLS-1$
            }
            else
            {
                appendMissingOutcome(sb, missingBefore, stillMissing);
            }
        }
        else if (missingBefore == 0)
        {
            sb.append("Already in sync: no .mdo files were missing, nothing to export."); //$NON-NLS-1$
        }
        else
        {
            appendMissingOutcome(sb, missingBefore, stillMissing);
        }
        // Dangling-reference summary.
        sb.append(' ');
        if (dangling.found == 0)
        {
            sb.append("No dangling references in Configuration.mdo."); //$NON-NLS-1$
        }
        else if (dangling.removedFromModel)
        {
            sb.append("Removed ").append(dangling.found) //$NON-NLS-1$
                .append(" dangling reference(s) from Configuration.mdo."); //$NON-NLS-1$
        }
        else
        {
            sb.append("Found ").append(dangling.found) //$NON-NLS-1$
                .append(" dangling reference(s) in Configuration.mdo"); //$NON-NLS-1$
            sb.append(cleanDangling
                ? " (not removed - see danglingWarning)." //$NON-NLS-1$
                : " (report-only; pass cleanDanglingReferences=true to remove them)."); //$NON-NLS-1$
        }
        return sb.toString();
    }

    /** Appends the missing-before/still-missing outcome shared by both export modes. */
    private static void appendMissingOutcome(StringBuilder sb, int missingBefore, int stillMissing)
    {
        sb.append(missingBefore)
            .append(" object(s) had no .mdo on disk before and were written out"); //$NON-NLS-1$
        if (stillMissing == 0)
        {
            sb.append("; all are present now."); //$NON-NLS-1$
        }
        else
        {
            sb.append("; ").append(stillMissing).append(" still missing after export."); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
