/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.BmBatchSessionException;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.md.extension.IMdAdoptedPropertyAccess;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com._1c.g5.v8.dt.metadata.mdclass.extension.type.MdPropertyState;
import com._1c.g5.wiring.ServiceAccess;
import com.ditrix.edt.mcp.server.Activator;

/**
 * Gives the module of an object ADOPTED into a configuration extension the state EDT's UI gives it when
 * code is added to it, {@code Extended}, and records it in the owner's {@code .mdo} at once (issue #711).
 *
 * <h2>The state, and where EDT records it</h2>
 * An adopted object's {@code .mdo} carries one state per module in its extension block -
 * {@code <module>Extended</module>} for a common module, {@code <objectModule>}, {@code <managerModule>},
 * {@code <commandModule>}, {@code <sessionModule>} and so on for the others. When code is added to an
 * adopted module in EDT's UI, EDT records {@code Extended} there (its module adopter sets the state with
 * the platform call this step makes). EDT also switches the state from {@code None} to
 * {@code Extended} from a resource-synchronization listener ({@code BslModuleExtensionBuildDispatcherRule})
 * that runs a few seconds after a {@code .bsl} of an adopted object is synchronized - but only in the
 * MODEL: the listener commits through {@code IBmModel#execute}, which never saves, so the {@code .mdo}
 * keeps no line. Until the listener has run, the model is {@code None} as well.
 * <p>
 * Nothing here claims what the missing line costs the extension EDT exports or loads into an infobase:
 * on EDT 2026.2.0 the XML dump carried the module of an adopted object also when its state was
 * {@code Checked} and when the object had no extension block, and the infobase load was not tested. The
 * step brings the {@code .mdo} in line with what EDT's UI records, and its texts say no more than that.
 *
 * <h2>What this does</h2>
 * After a module file is written, {@link #ensureExtended} repeats the listener's sequence with the
 * listener's own calls: the owner is found from the file's path, the feature from the file's stem, and
 * {@link IMdAdoptedPropertyAccess#hasPropertyState} with {@code NONE} gates
 * {@link IMdAdoptedPropertyAccess#setPropertyState} with {@code EXTENDED}, made on the transaction's copy
 * of the owner inside a write task. Then the owner's TOP object is force-exported and the export is
 * awaited within the metadata writes' budget - also when the state was already {@code Extended}, which
 * is how the listener leaves it, so the {@code .mdo} is brought in line with the model.
 * <p>
 * It never switches a state back (EDT keeps {@code Extended} when a module is emptied), never touches
 * {@code Checked} or {@code Notify}, and never creates a missing extension block - the platform's set
 * would create one, and throws for an abstract block class; the {@code NONE} gate keeps the listener
 * away from that too, because the platform reports a missing block as "not None". Leaving
 * {@code Checked} and {@code Notify} as they are, the step follows EDT's listener, which asks the same
 * {@code NONE} question first - not EDT's module adopter ({@code BslModuleAdopterParticipant}, which
 * EDT's UI runs when it adds a method to an extension): that sets {@code Extended} without asking
 * whenever it adopts a module other than a form's (2026.2.0 bytecode). A {@code Checked} or {@code Notify} state and a
 * missing block are reported with the written text; they do not fail the write.
 * <p>
 * EDT does not list the root's ordinary-application module among the properties an extension can
 * extend: none of its property-type delegates, 8.3.8 to 8.5.1, names it, while those of 8.3.9 to 8.5.1
 * list the root's session, managed-application and external-connection modules (8.3.8's lists no module
 * at all). It is switched like the others, but a step that cannot finish on it does not fail the write.
 *
 * <h2>Boundaries</h2>
 * The owner is read off the STRICT reading of the module path
 * ({@link VendorSupportGuard#moduleOwnerFqn(String, boolean)}): {@code <Dir>/<Name>/<Stem>.bsl},
 * {@code <Dir>/<Name>/Commands/<Command>/CommandModule.bsl} and {@code Configuration/<Stem>.bsl}. So it
 * does not cover the modules of an external data source's tables, cubes and dimension tables (deeper
 * paths), nor {@code Bot}, {@code IntegrationService} and {@code WebSocketClient} modules (no source
 * directory is mapped for those types): their state has to be set in EDT. A recalculation's module has
 * no extension state at all, and a form's module needs none: the form's own state
 * ({@code <form>Extended</form>}) is set when the form is adopted.
 * <p>
 * Until EDT has registered the project's BM model, an adopted object cannot be told from one of the
 * extension's own: a write into any module of the strict reading then fails as a transient error.
 * <p>
 * A dry-run mode of {@code write_module_source} (issue #465) must skip this step: it changes the model
 * and exports the owner's {@code .mdo}.
 */
public final class AdoptedModuleState
{
    /** The frontmatter key a write reports this step's outcome under. */
    public static final String FRONT_MATTER_KEY = "moduleExtensionState"; //$NON-NLS-1$

    /**
     * How long to wait for the owner's {@code .mdo} export: the budget the metadata write tools give
     * their own export (AbstractMetadataWriteTool's EXPORT_DEADLINE_MS, whose javadoc carries the
     * measurement - 0.02-0.15s on a healthy workspace).
     */
    static final long EXPORT_DEADLINE_MS = 60_000L;

    private static final String CONFIGURATION = "Configuration"; //$NON-NLS-1$
    private static final String BSL = ".bsl"; //$NON-NLS-1$

    /** The root's {@code .mdo}: the one top object outside a type directory, as DeleteMetadataTool names it. */
    private static final String CONFIGURATION_MDO = "src/Configuration/Configuration.mdo"; //$NON-NLS-1$

    /**
     * What EDT itself does with the state - the verified facts the step rests on, and no more: its UI
     * records the state in the {@code .mdo}, its listener only in the model.
     */
    private static final String PARITY = "EDT's UI records this state in the .mdo when code is added to an " //$NON-NLS-1$
        + "adopted module; EDT's own listener sets it only in the model, a few seconds after the file is " //$NON-NLS-1$
        + "written, and never saves the .mdo."; //$NON-NLS-1$

    /**
     * The call that re-runs this step alone. An empty append rewrites the module's current text: its line
     * breaks are normalized (one trailing break, the module's own separator), and the whole module goes
     * through the syntax check again - so a module written with the check skipped needs it skipped again.
     */
    private static final String RERUN_CALL = "write_module_source on the same module with mode=append and an " //$NON-NLS-1$
        + "empty source - it rewrites the module's current text, line ends normalized (add " //$NON-NLS-1$
        + "skipSyntaxCheck=true if the module was written with it)"; //$NON-NLS-1$

    /** The step's own rerun, as an instruction. */
    private static final String RERUN = "run this step again: call " + RERUN_CALL; //$NON-NLS-1$

    /** The step's own rerun, as the operation BmModelResolver's transient-model error tells to retry. */
    private static final String RETRY_OPERATION = "this step: call " + RERUN_CALL; //$NON-NLS-1$

    /** Said by every failure: the text IS written, so repeating the edit would apply it twice. */
    private static final String STANDS = "The write stands: do not re-send the original edit."; //$NON-NLS-1$

    /**
     * Why a step that cannot finish on the ordinary-application module does not fail the write: a fact of
     * EDT's own property types - no delegate, 8.3.8 to 8.5.1, lists that module (see the class comment).
     * It compares that module with no other: which modules a delegate lists depends on the platform
     * version.
     */
    private static final String ORDINARY_NOTE = "EDT does not list the ordinary-application module among the " //$NON-NLS-1$
        + "properties an extension can extend, so this does not fail the write."; //$NON-NLS-1$

    /** How the step ended. */
    public enum Outcome
    {
        /** Not the module of an adopted object in an extension: nothing was done. */
        NOT_APPLICABLE,
        /** The state was None and is now Extended; the owner's {@code .mdo} export was requested. */
        SWITCHED,
        /** The state already was Extended; the owner's {@code .mdo} export was requested again. */
        ALREADY_EXTENDED,
        /** The adopted owner has no extension block, so there is no state to record: a success, with a note. */
        NO_EXTENSION_BLOCK,
        /**
         * The state is Checked or Notify: like EDT's own listener, this step switches only None, so the
         * state is left as it is - a success with a note.
         */
        OTHER_STATE,
        /** The step could not be carried out. */
        FAILED,
        /** The state was switched, but the owner's {@code .mdo} export was not confirmed. */
        EXPORT_NOT_CONFIRMED
    }

    /** How the owner's {@code .mdo} export ended. */
    enum Export
    {
        /** The export queue drained within the budget. */
        DRAINED,
        /** The export was requested, but its completion cannot be observed. */
        UNOBSERVABLE,
        /** The export was requested and not confirmed within the budget. */
        PENDING,
        /** The platform accepted no export request. */
        NOT_SCHEDULED
    }

    /**
     * The platform calls the step makes, behind one seam so a test can run the whole decision against
     * an in-memory model and observe what is exported, and in which order. Package-visible: only the
     * tests, in this package, supply another one.
     */
    interface Environment
    {
        /**
         * @param project the project the module was written in
         * @return whether it is a configuration extension
         */
        boolean isExtensionProject(IProject project);

        /**
         * @param project the project the module was written in
         * @return its BM model, or the unavailable resolution - with its actionable error - while EDT
         *     has not registered one; never {@code null}
         */
        BmModelResolver.Resolution model(IProject project);

        /** @return EDT's adopted-property service, or {@code null} when it is not available */
        IMdAdoptedPropertyAccess access();

        /**
         * @param owner the module's owner, read inside a model read
         * @return the FQN of the top object whose {@code .mdo} holds the owner's state
         */
        String topObjectFqn(EObject owner);

        /**
         * @param project the project
         * @param topObjectFqn the top object to export
         * @return whether the platform accepted an export request
         */
        boolean forceExport(IProject project, String topObjectFqn);

        /**
         * @param project the project
         * @param timeoutMs the budget
         * @return how the export wait ended
         */
        BuildUtils.DiskExportState awaitExport(IProject project, long timeoutMs);
    }

    /** The production platform. */
    static final Environment PLATFORM = new Environment()
    {
        @Override
        public boolean isExtensionProject(IProject project)
        {
            return ExtensionOriginUtils.isExtensionProject(project);
        }

        @Override
        public BmModelResolver.Resolution model(IProject project)
        {
            return BmModelResolver.resolve(project);
        }

        @Override
        public IMdAdoptedPropertyAccess access()
        {
            try
            {
                return ServiceAccess.get(IMdAdoptedPropertyAccess.class);
            }
            catch (RuntimeException e) // NOSONAR a missing service is an answer (the step fails), not a crash
            {
                return null;
            }
        }

        @Override
        public String topObjectFqn(EObject owner)
        {
            // The model's own identity of the top object, as AdoptMetadataObjectTool takes it - never
            // the path's spelling, which the platform would not find (an export of a name it does not
            // know is silently not scheduled).
            if (!(owner instanceof IBmObject))
            {
                return null;
            }
            IBmObject top = ((IBmObject)owner).bmGetTopObject();
            return top == null ? null : top.bmGetFqn();
        }

        @Override
        public boolean forceExport(IProject project, String topObjectFqn)
        {
            return BmTransactions.forceExportToDisk(project, topObjectFqn);
        }

        @Override
        public BuildUtils.DiskExportState awaitExport(IProject project, long timeoutMs)
        {
            return BuildUtils.waitForDiskExport(project, timeoutMs);
        }
    };

    private AdoptedModuleState()
    {
        // Utility class
    }

    /**
     * Where a module file sits in the metadata: the object that owns its state and the file's stem.
     * Package-visible for tests.
     */
    static final class ModuleAddress
    {
        /** {@code Type.Name}, {@code Type.Name.Command.Name}, or {@code Configuration}. */
        final String ownerFqn;
        /** The file name without {@code .bsl}, e.g. {@code ObjectModule}. */
        final String stem;

        ModuleAddress(String ownerFqn, String stem)
        {
            this.ownerFqn = ownerFqn;
            this.stem = stem;
        }

        /** @return whether the owner is the configuration root itself */
        boolean isConfiguration()
        {
            return CONFIGURATION.equals(ownerFqn);
        }

        /**
         * @return whether this is the root's ordinary-application module, which EDT does not list among the
         *     properties an extension can extend - known from the path alone, so also when the model could
         *     not be read
         */
        boolean isOrdinaryApplicationModule()
        {
            return isConfiguration() && moduleFeature(MdClassPackage.Literals.CONFIGURATION,
                stem) == MdClassPackage.Literals.CONFIGURATION__ORDINARY_APPLICATION_MODULE;
        }
    }

    /**
     * Reads the owner of a module file off its project-relative path through the one reading of a module
     * path, {@link VendorSupportGuard#moduleOwnerFqn(String, boolean)}, STRICTLY: only a module its owner
     * holds itself - {@code Configuration/<Stem>.bsl}, {@code <Dir>/<Name>/<Stem>.bsl} and
     * {@code <Dir>/<Name>/Commands/<Command>/CommandModule.bsl}. Anything deeper names a NESTED object (a
     * form, a recalculation, an external data source's table), and mapping it to the object above it -
     * as the lenient reading does for the vendor guard - would switch the wrong object's module: a
     * recalculation's {@code RecordSetModule.bsl} would switch its register's. Package-visible for tests.
     *
     * @param projectRelativePath the module file's project-relative path, may be {@code null}
     * @return the address, or {@code null} when the path is not one of the three shapes
     */
    static ModuleAddress moduleAddress(String projectRelativePath)
    {
        String ownerFqn = VendorSupportGuard.moduleOwnerFqn(projectRelativePath, true);
        if (ownerFqn == null)
        {
            return null;
        }
        // The strict reading admitted the path only with a <Stem>.bsl file name as its last segment.
        String path = projectRelativePath.replace('\\', '/');
        String file = path.substring(path.lastIndexOf('/') + 1);
        return new ModuleAddress(ownerFqn, file.substring(0, file.length() - BSL.length()));
    }

    /**
     * The owner's feature a module file stands for: EDT's own map - the first structural feature whose
     * name equals the stem, ignoring case - restricted to references to a BSL {@code Module}. EDT's
     * listener does not restrict it, so a file named after an attribute ({@code Comment.bsl}) would be
     * taken for a module there; here it is no module. A form has no such feature ({@code BasicForm}
     * holds a {@code form}, not a {@code module}), so form modules fall out on their own.
     * Package-visible for tests.
     *
     * @param ownerClass the owner's class, may be {@code null}
     * @param stem the file name without {@code .bsl}
     * @return the module feature, or {@code null}
     */
    static EStructuralFeature moduleFeature(EClass ownerClass, String stem)
    {
        if (ownerClass == null || stem == null)
        {
            return null;
        }
        for (EStructuralFeature feature : ownerClass.getEAllStructuralFeatures())
        {
            if (feature.getName().equalsIgnoreCase(stem))
            {
                return isModuleReference(feature) ? feature : null;
            }
        }
        return null;
    }

    private static boolean isModuleReference(EStructuralFeature feature)
    {
        if (!(feature instanceof EReference))
        {
            return false;
        }
        EClass type = ((EReference)feature).getEReferenceType();
        return type != null && BslPackage.Literals.MODULE.isSuperTypeOf(type);
    }

    /**
     * The project-relative {@code .mdo} of a top object, named by the model's own FQN of it - the object
     * the export writes: the root's special case, then {@link MetadataPathResolver#resolveTopObjectMdoPath},
     * as DeleteMetadataTool names a registering container's file.
     *
     * @param topObjectFqn the model's FQN of the top object, may be {@code null}
     * @return the path, or {@code null} when there is no FQN or it maps to no {@code .mdo}
     */
    static String mdoPathOf(String topObjectFqn)
    {
        if (topObjectFqn == null || topObjectFqn.isEmpty())
        {
            return null;
        }
        return CONFIGURATION.equals(topObjectFqn) ? CONFIGURATION_MDO
            : MetadataPathResolver.resolveTopObjectMdoPath(topObjectFqn);
    }

    /** What the state decision found. Package-visible for tests. */
    static final class Verdict
    {
        /** The kinds of finding. */
        enum Kind
        {
            /** Not an adopted object's module. */
            NOT_APPLICABLE,
            /** The state is None: switch it (or, after a write, it was switched). */
            SWITCH,
            /** The state is Extended. */
            ALREADY_EXTENDED,
            /** The adopted object has no extension block. */
            NO_EXTENSION_BLOCK,
            /** The state is Checked or Notify ({@link Verdict#detail}): left as it is. */
            OTHER_STATE,
            /** The decision itself failed ({@link Verdict#failure}, or {@link Verdict#detail} without one). */
            FAILED
        }

        final Kind kind;
        /** The state's name for OTHER_STATE ({@code Checked} or {@code Notify}); the reason for a FAILED with no exception. */
        final String detail;
        /** The platform's refusal for FAILED, when there was one. */
        final RuntimeException failure;

        private Verdict(Kind kind, String detail, RuntimeException failure)
        {
            this.kind = kind;
            this.detail = detail;
            this.failure = failure;
        }

        static Verdict of(Kind kind)
        {
            return new Verdict(kind, null, null);
        }

        /** @return whether the finding names the owner's {@code .mdo}: a state to export or to report */
        boolean namesTheMdo()
        {
            return kind == Kind.SWITCH || kind == Kind.ALREADY_EXTENDED || kind == Kind.NO_EXTENSION_BLOCK
                || kind == Kind.OTHER_STATE;
        }
    }

    /**
     * Decides, without writing anything, what the state of {@code feature} on {@code owner} calls for.
     * Not an adopted object's module (not an {@link MdObject}, not {@code ADOPTED}, or no module
     * feature) answers NOT_APPLICABLE without asking the service at all. Otherwise the service is asked
     * the listener's own question first ({@code NONE}?), then whether a block exists at all, because the
     * service answers "not None" for a missing block.
     * <p>
     * Every module is decided alike, the ordinary-application module included: {@code None} is to be
     * switched, as EDT's listener switches it; {@code Checked} or {@code Notify} is named, to be left as
     * it is. The state has only those four values, so a service that answers none of them contradicts
     * itself: a failure, never a state to name. The mapper's "no extension property / class" refusals
     * cannot occur for an owner {@link #moduleAddress} admits - each has an extension class carrying the
     * module state - so one is platform drift or a corrupted block, and answered FAILED, never silently
     * skipped. Package-visible for tests.
     *
     * @param owner the module's owner
     * @param feature its module feature, may be {@code null}
     * @param access EDT's adopted-property service, may be {@code null}
     * @return the verdict, never {@code null}
     */
    static Verdict decide(EObject owner, EStructuralFeature feature, IMdAdoptedPropertyAccess access)
    {
        if (!(owner instanceof MdObject) || feature == null
            || ((MdObject)owner).getObjectBelonging() != ObjectBelonging.ADOPTED)
        {
            return Verdict.of(Verdict.Kind.NOT_APPLICABLE);
        }
        if (access == null)
        {
            return new Verdict(Verdict.Kind.FAILED, "EDT's adopted-property service is not available " //$NON-NLS-1$
                + "(the md.extension bundle may not be active)", null); //$NON-NLS-1$
        }
        try
        {
            boolean none = access.hasPropertyState(owner, feature, MdPropertyState.NONE);
            if (((MdObject)owner).getExtension() == null)
            {
                return Verdict.of(Verdict.Kind.NO_EXTENSION_BLOCK);
            }
            if (none)
            {
                return Verdict.of(Verdict.Kind.SWITCH);
            }
            if (access.hasPropertyState(owner, feature, MdPropertyState.EXTENDED))
            {
                return Verdict.of(Verdict.Kind.ALREADY_EXTENDED);
            }
            MdPropertyState kept = keptState(owner, feature, access);
            if (kept == null)
            {
                return new Verdict(Verdict.Kind.FAILED, "EDT's adopted-property service names none of the four " //$NON-NLS-1$
                    + "module states (None, Extended, Checked, Notify) for it", null); //$NON-NLS-1$
            }
            return new Verdict(Verdict.Kind.OTHER_STATE, kept.getName(), null);
        }
        catch (IllegalStateException | IllegalArgumentException e)
        {
            return new Verdict(Verdict.Kind.FAILED, null, e);
        }
    }

    /** The state the step leaves as it is - Checked or Notify - or {@code null} when the service names neither. */
    private static MdPropertyState keptState(EObject owner, EStructuralFeature feature, IMdAdoptedPropertyAccess access)
    {
        for (MdPropertyState state : new MdPropertyState[] { MdPropertyState.CHECKED, MdPropertyState.NOTIFY })
        {
            if (access.hasPropertyState(owner, feature, state))
            {
                return state;
            }
        }
        return null;
    }

    /**
     * After a module file was written, gives the module of an object adopted into the extension the
     * state {@code Extended}, the way EDT does when code is added to an adopted module, and brings the
     * owner's {@code .mdo} in line. See the class comment for the sequence and its boundaries.
     * <p>
     * Never throws: anything the platform throws becomes a FAILED result. Only unexpected platform
     * exceptions are logged at ERROR; a transient model state is logged at INFO, a finding not at all.
     *
     * @param project the project the module was written in
     * @param scope the project's metadata root, resolved before the write
     * @param projectRelativePath the written file's project-relative path
     * @return the step's result, never {@code null}
     */
    public static Result ensureExtended(IProject project, MetadataScope scope, String projectRelativePath)
    {
        return ensureExtended(project, scope, projectRelativePath, PLATFORM);
    }

    /**
     * {@link #ensureExtended(IProject, MetadataScope, String)} against an explicit platform - the seam the
     * tests drive.
     *
     * @param project the project the module was written in
     * @param scope the project's metadata root
     * @param projectRelativePath the written file's project-relative path
     * @param env the platform
     * @return the step's result, never {@code null}
     */
    static Result ensureExtended(IProject project, MetadataScope scope, String projectRelativePath,
        Environment env)
    {
        // Pure, and first: a path that is no module of the strict reading is answered before the
        // platform is asked anything, and every later failure has an owner to name.
        ModuleAddress address = moduleAddress(projectRelativePath);
        if (address == null)
        {
            return Result.notApplicable();
        }
        Probe probe = null;
        try
        {
            if (!env.isExtensionProject(project) || scope == null || scope.configuration() == null)
            {
                return Result.notApplicable();
            }
            Result.Context context = new Result.Context(address, projectRelativePath, projectName(project));
            BmModelResolver.Resolution resolution = env.model(project);
            if (!resolution.isAvailable())
            {
                Activator.logInfo("Module state of '" + address.ownerFqn + "' not checked: no BM model for " //$NON-NLS-1$ //$NON-NLS-2$
                    + context.projectName + " yet"); //$NON-NLS-1$
                return Result.modelUnavailable(context, resolution.actionableError(RETRY_OPERATION, STANDS));
            }
            IBmModel model = resolution.getModel();
            IMdAdoptedPropertyAccess access = env.access();
            probe = BmTransactions.read(model, "AdoptedModuleState.check", //$NON-NLS-1$
                (tx, monitor) -> probe(scope, address, access, env));
            Verdict verdict = probe.verdict;
            if (verdict.kind == Verdict.Kind.SWITCH)
            {
                Probe found = probe;
                verdict = BmTransactions.write(model, "AdoptedModuleState.extend", //$NON-NLS-1$
                    (tx, monitor) -> extend(tx, found, address.stem, access));
            }
            context = context.withProbe(probe.feature, mdoPathOf(probe.topFqn));
            switch (verdict.kind)
            {
            case SWITCH:
                return Result.exported(Outcome.SWITCHED, context, export(project, probe.topFqn, env));
            case ALREADY_EXTENDED:
                return Result.exported(Outcome.ALREADY_EXTENDED, context, export(project, probe.topFqn, env));
            case NO_EXTENSION_BLOCK:
                return Result.finding(Outcome.NO_EXTENSION_BLOCK, context, null);
            case OTHER_STATE:
                return Result.finding(Outcome.OTHER_STATE, context, verdict.detail);
            case FAILED:
                return failure(context, verdict);
            case NOT_APPLICABLE:
            default:
                return Result.notApplicable();
            }
        }
        catch (BmBatchSessionException e)
        {
            Activator.logInfo("Module state of '" + address.ownerFqn + "' not set: EDT is running a batch " //$NON-NLS-1$ //$NON-NLS-2$
                + "operation on " + projectName(project) + " (" + PlatformFailures.describe(e) + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return Result.batchSession(contextOf(address, projectRelativePath, project, probe),
                PlatformFailures.describe(e));
        }
        catch (RuntimeException e)
        {
            Activator.logError("Could not set the module state of '" + address.ownerFqn //$NON-NLS-1$
                + "' to Extended after writing " + projectRelativePath, e); //$NON-NLS-1$
            return Result.unexpected(contextOf(address, projectRelativePath, project, probe),
                PlatformFailures.describe(e));
        }
    }

    /** The context of a step that stopped on an exception: what the read had found by then, if anything. */
    private static Result.Context contextOf(ModuleAddress address, String projectRelativePath, IProject project,
        Probe probe)
    {
        Result.Context context = new Result.Context(address, projectRelativePath, projectName(project));
        return probe == null ? context : context.withProbe(probe.feature, mdoPathOf(probe.topFqn));
    }

    /** What the read found: the owner (as the read resolved it), its module feature and its verdict. */
    private static final class Probe
    {
        final EObject owner;
        final EStructuralFeature feature;
        final Verdict verdict;
        /** The model's FQN of the owner's top object; taken only when the finding names its {@code .mdo}. */
        final String topFqn;

        Probe(EObject owner, EStructuralFeature feature, Verdict verdict, String topFqn)
        {
            this.owner = owner;
            this.feature = feature;
            this.verdict = verdict;
            this.topFqn = topFqn;
        }
    }

    /** The read: resolve the owner exactly, map the feature, decide - all inside one model read. */
    private static Probe probe(MetadataScope scope, ModuleAddress address, IMdAdoptedPropertyAccess access,
        Environment env)
    {
        // Resolved EXACTLY: the directory IS the programmatic Name, so there is no yo retry.
        EObject owner = address.isConfiguration() ? scope.configuration()
            : MetadataNodeResolver.resolveAddress(scope, address.ownerFqn, false);
        if (owner == null)
        {
            return new Probe(null, null, Verdict.of(Verdict.Kind.NOT_APPLICABLE), null);
        }
        EStructuralFeature feature = moduleFeature(owner.eClass(), address.stem);
        Verdict verdict = decide(owner, feature, access);
        return new Probe(owner, feature, verdict, verdict.namesTheMdo() ? env.topObjectFqn(owner) : null);
    }

    /**
     * The write: EDT's listener's own sequence on the transaction's copy of the owner - decide again
     * there, and set {@code EXTENDED} only on that verdict, so a listener that won the race in between
     * leaves an ALREADY_EXTENDED here, not a second set.
     */
    private static Verdict extend(IBmTransaction tx, Probe probe, String stem, IMdAdoptedPropertyAccess access)
    {
        EObject owner = tx.toTransactionObject(probe.owner);
        if (owner == null)
        {
            return Verdict.of(Verdict.Kind.NOT_APPLICABLE);
        }
        EStructuralFeature feature = moduleFeature(owner.eClass(), stem);
        Verdict verdict = decide(owner, feature, access);
        if (verdict.kind == Verdict.Kind.SWITCH)
        {
            access.setPropertyState(owner, feature, MdPropertyState.EXTENDED);
        }
        return verdict;
    }

    /**
     * Requests the export of the owner's top object and waits for it. A refused request is reported as
     * such and never waited for: the queue would be empty, the wait would "drain", and the answer would
     * claim an export that was never queued. Never throws: the state is committed by now, so a failure
     * here is an unconfirmed export, not a failed step.
     */
    private static Export export(IProject project, String topFqn, Environment env)
    {
        try
        {
            if (topFqn == null || topFqn.isEmpty() || !env.forceExport(project, topFqn))
            {
                return Export.NOT_SCHEDULED;
            }
            BuildUtils.DiskExportState state = env.awaitExport(project, EXPORT_DEADLINE_MS);
            if (state == BuildUtils.DiskExportState.DRAINED)
            {
                return Export.DRAINED;
            }
            return state == BuildUtils.DiskExportState.PENDING ? Export.PENDING : Export.UNOBSERVABLE;
        }
        catch (RuntimeException e)
        {
            Activator.logError("Could not export the .mdo of " + topFqn + " after setting a module state", e); //$NON-NLS-1$ //$NON-NLS-2$
            return Export.NOT_SCHEDULED;
        }
    }

    /** A FAILED verdict: the platform's refusal (logged at ERROR - it is drift), or a missing service. */
    private static Result failure(Result.Context context, Verdict verdict)
    {
        if (verdict.failure != null)
        {
            Activator.logError("EDT refused the module state question for '" + context.address.ownerFqn + "'", //$NON-NLS-1$ //$NON-NLS-2$
                verdict.failure);
            return Result.unexpected(context, PlatformFailures.describe(verdict.failure));
        }
        Activator.logWarning("Module state of '" + context.address.ownerFqn + "' not checked: " + verdict.detail); //$NON-NLS-1$ //$NON-NLS-2$
        return Result.serviceUnavailable(context, verdict.detail);
    }

    private static String projectName(IProject project)
    {
        return project == null ? "?" : project.getName(); //$NON-NLS-1$
    }

    /** The step's result: its outcome, and what the write's response says about it. */
    public static final class Result
    {
        private static final Result NOT_APPLICABLE = new Result(Outcome.NOT_APPLICABLE, null, null, null, Cause.NONE);

        /** Why a FAILED failed; decides the advice. */
        private enum Cause
        {
            NONE, MODEL_UNAVAILABLE, BATCH_SESSION, SERVICE, UNEXPECTED
        }

        /** Where the step ran: the module, its owner and the project, and what the read found. */
        static final class Context
        {
            final ModuleAddress address;
            final String modulePath;
            final String projectName;
            /** The owner's module feature; its name is the {@code .mdo} element. May be {@code null}. */
            final EStructuralFeature feature;
            /** The {@code .mdo} of the owner's top object, as the model names it. May be {@code null}. */
            final String mdoPath;

            Context(ModuleAddress address, String modulePath, String projectName)
            {
                this(address, modulePath, projectName, null, null);
            }

            private Context(ModuleAddress address, String modulePath, String projectName, EStructuralFeature feature,
                String mdoPath)
            {
                this.address = address;
                this.modulePath = modulePath;
                this.projectName = projectName;
                this.feature = feature;
                this.mdoPath = mdoPath;
            }

            Context withProbe(EStructuralFeature moduleFeature, String topObjectMdoPath)
            {
                return new Context(address, modulePath, projectName, moduleFeature, topObjectMdoPath);
            }
        }

        private final Outcome outcome;
        private final Context context;
        private final Export export;
        private final String detail;
        private final Cause cause;

        private Result(Outcome outcome, Context context, Export export, String detail, Cause cause)
        {
            this.outcome = outcome;
            this.context = context;
            this.export = export;
            this.detail = detail;
            this.cause = cause;
        }

        /** @return the result of a write that is not into an adopted object's module */
        public static Result notApplicable()
        {
            return NOT_APPLICABLE;
        }

        static Result exported(Outcome state, Context context, Export export)
        {
            boolean confirmedEnough = export == Export.DRAINED || export == Export.UNOBSERVABLE;
            // Only an export this call CAUSED may refuse it: after a switch an unconfirmed export is a
            // failure, while for a state that was already Extended it is a warning on a success - and for
            // the ordinary-application module, which EDT does not list among the properties an extension
            // can extend, a note.
            Outcome outcome = state == Outcome.SWITCHED && !confirmedEnough
                && !context.address.isOrdinaryApplicationModule() ? Outcome.EXPORT_NOT_CONFIRMED : state;
            return new Result(outcome, context, export, null, Cause.NONE);
        }

        static Result finding(Outcome outcome, Context context, String state)
        {
            return new Result(outcome, context, null, state, Cause.NONE);
        }

        /**
         * @param context where the step ran
         * @param actionable BmModelResolver's actionable error for the unavailable model
         * @return the failure of a step that could not read the model
         */
        static Result modelUnavailable(Context context, String actionable)
        {
            return new Result(Outcome.FAILED, context, null, actionable, Cause.MODEL_UNAVAILABLE);
        }

        static Result batchSession(Context context, String why)
        {
            return new Result(Outcome.FAILED, context, null, why, Cause.BATCH_SESSION);
        }

        static Result serviceUnavailable(Context context, String why)
        {
            return new Result(Outcome.FAILED, context, null, why, Cause.SERVICE);
        }

        static Result unexpected(Context context, String why)
        {
            return new Result(Outcome.FAILED, context, null, why, Cause.UNEXPECTED);
        }

        /** @return the outcome */
        public Outcome outcome()
        {
            return outcome;
        }

        /**
         * @return whether the step failed, so the write must answer with an error that says the text
         *     is written: a failure, or a switch whose {@code .mdo} export was not confirmed - never for
         *     the ordinary-application module, which EDT does not list among the properties an extension
         *     can extend. A Checked/Notify state or a missing extension block is no failure: the write
         *     succeeds, and its key and paragraph say so.
         */
        public boolean failed()
        {
            switch (outcome)
            {
            case EXPORT_NOT_CONFIRMED:
                return true;
            case FAILED:
                return !ordinaryApplicationModule();
            default:
                return false;
            }
        }

        /**
         * @return the value of the {@link AdoptedModuleState#FRONT_MATTER_KEY} key - {@code switchedToExtended},
         *     {@code alreadyExtended}, {@code keptChecked}, {@code keptNotify} or {@code noExtensionBlock} -
         *     or {@code null} when the write reports no key
         */
        public String frontMatterValue()
        {
            switch (outcome)
            {
            case SWITCHED:
                return "switchedToExtended"; //$NON-NLS-1$
            case ALREADY_EXTENDED:
                return "alreadyExtended"; //$NON-NLS-1$
            case OTHER_STATE:
                return "kept" + detail; //$NON-NLS-1$
            case NO_EXTENSION_BLOCK:
                return "noExtensionBlock"; //$NON-NLS-1$
            default:
                return null;
            }
        }

        /**
         * The paragraph a successful write adds under its first line: a switch, a state left as it is, a
         * missing extension block, an export that is not known to have finished, or a step that could not
         * run on the ordinary-application module. A paragraph on every write into an already-Extended
         * module would be noise: the key says that much.
         *
         * @return the paragraph, or {@code null} for none
         */
        public String bodySentence()
        {
            switch (outcome)
            {
            case SWITCHED:
                return switchedParagraph();
            case OTHER_STATE:
                return keptParagraph();
            case NO_EXTENSION_BLOCK:
                return noBlockParagraph();
            case ALREADY_EXTENDED:
                return export == Export.DRAINED ? null : alreadyExtendedParagraph();
            case FAILED:
                return ordinaryApplicationModule() ? "The state of the ordinary-application module of " //$NON-NLS-1$
                    + ownerName() + " was left as it is: " + failureReason() + ". " + ORDINARY_NOTE : null; //$NON-NLS-1$ //$NON-NLS-2$
            default:
                return null;
            }
        }

        private String switchedParagraph()
        {
            String switched = "The module belongs to " + ownerWithRole() + ": its module state was switched from " //$NON-NLS-1$ //$NON-NLS-2$
                + "None to Extended (" + stateLine("Extended") + ")" + exportClause() + " " + PARITY; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            return ordinaryApplicationModule() && !confirmed() ? switched + " " + ORDINARY_NOTE : switched; //$NON-NLS-1$
        }

        /**
         * Checked or Notify: named, and left exactly as the author set it. The note compares the step with
         * EDT's own listener, which asks for None first as the step does - not with EDT as a whole: EDT's
         * module adopter, which its UI runs to add a method to an extension, sets Extended without asking.
         */
        private String keptParagraph()
        {
            return "The module state of " + ownerName() + " is " + detail + " (" + stateLine(detail) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "), not Extended, and was left as it is: like EDT's own listener, this tool only switches a " //$NON-NLS-1$
                + "module's state from None to Extended. If the module should be Extended, set its state in EDT, " //$NON-NLS-1$
                + "in the properties of the adopted object."; //$NON-NLS-1$
        }

        /** No extension block: there is no state to record, and nothing was created to hold one. */
        private String noBlockParagraph()
        {
            String missing = "This module belongs to " + ownerWithRole() + ": there is no extension block in " //$NON-NLS-1$ //$NON-NLS-2$
                + mdoName() + ", so the module state cannot be recorded."; //$NON-NLS-1$
            if (context.address.isConfiguration())
            {
                return missing + " An extension root that EDT's own new-extension wizard creates carries that " //$NON-NLS-1$
                    + "block."; //$NON-NLS-1$
            }
            return missing + " EDT writes that block whenever it adopts an object, so the .mdo was changed outside " //$NON-NLS-1$
                + "EDT: to record the state, restore the block (for example from version control), then " + RERUN //$NON-NLS-1$
                + "."; //$NON-NLS-1$
        }

        private String alreadyExtendedParagraph()
        {
            String already = "The module state of " + ownerName() + " is already Extended (" //$NON-NLS-1$ //$NON-NLS-2$
                + stateLine("Extended") + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            if (export == Export.UNOBSERVABLE)
            {
                return already + "; the export of the .mdo was requested again, but its completion could " //$NON-NLS-1$
                    + "not be observed."; //$NON-NLS-1$
            }
            String why = export == Export.PENDING
                ? "the export of the .mdo, requested again to match the model, was not confirmed within " //$NON-NLS-1$
                    + seconds() + "s" //$NON-NLS-1$
                : "the export of the .mdo could not be scheduled"; //$NON-NLS-1$
            return already + ", but " + why + ", so the .mdo on disk may still lack that line. Nothing was " //$NON-NLS-1$ //$NON-NLS-2$
                + "rolled back; check the line before committing, and if it is still missing once list_projects " //$NON-NLS-1$
                + "reports the project ready, " + RERUN + "."; //$NON-NLS-1$ //$NON-NLS-2$
        }

        /**
         * The error a failed step turns the write into. It starts with what IS done - the text is
         * written - and says not to re-send the edit, because a caller retrying on an error would apply
         * it twice; where a retry helps, it names the one that re-runs only this step.
         *
         * @return the message, or {@code null} when the step did not fail
         */
        public String failureMessage()
        {
            if (!failed())
            {
                return null;
            }
            String written = "The module text was written to " + context.modulePath; //$NON-NLS-1$
            switch (outcome)
            {
            case EXPORT_NOT_CONFIRMED:
                String why = export == Export.PENDING ? "was not confirmed within " + seconds() + "s" //$NON-NLS-1$ //$NON-NLS-2$
                    : "could not be scheduled"; //$NON-NLS-1$
                return written + ", and the module state of " + ownerName() + " was switched from None to " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Extended in EDT's model, but the export of its .mdo " + why + ", so " + mdoName() //$NON-NLS-1$ //$NON-NLS-2$
                    + " may still lack " + element("Extended") + ". Nothing was rolled back. " + STANDS //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + " And do not commit the working tree in this state: once list_projects reports the " //$NON-NLS-1$
                    + "project ready, check " + mdoName() + " for that line, and if it is still missing, " //$NON-NLS-1$ //$NON-NLS-2$
                    + RERUN + " - that exports the .mdo again."; //$NON-NLS-1$
            case FAILED:
            default:
                return written + failedMessage();
            }
        }

        private String failedMessage()
        {
            switch (cause)
            {
            case MODEL_UNAVAILABLE:
                // BmModelResolver's own actionable error, with this step's rerun as the operation to retry.
                return ", but whether its owner '" + context.address.ownerFqn + "' is adopted from the base " //$NON-NLS-1$ //$NON-NLS-2$
                    + "configuration - and so whether its module state is to be recorded as Extended - could not " //$NON-NLS-1$
                    + "be checked. " + detail; //$NON-NLS-1$
            case BATCH_SESSION:
                return ", but the module state of " + ownerName() + " could not be set to Extended: EDT is " //$NON-NLS-1$ //$NON-NLS-2$
                    + "running a batch operation on the model of project '" + context.projectName + "' (" //$NON-NLS-1$ //$NON-NLS-2$
                    + detail + "). This is transient. " + STANDS + " Wait a few seconds, then " + RERUN + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case SERVICE:
                return ", but the module state of " + ownerWithRole() + ", could not be checked: " + detail //$NON-NLS-1$ //$NON-NLS-2$
                    + ". " + STANDS + " Restart EDT, then " + RERUN + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case UNEXPECTED:
            default:
                return ", but setting the module state of " + ownerName() + " to Extended failed: " + detail //$NON-NLS-1$ //$NON-NLS-2$
                    + ". " + STANDS + " Check the EDT log; once the project is ready, " + RERUN //$NON-NLS-1$ //$NON-NLS-2$
                    + " - it sets the state if it is still None."; //$NON-NLS-1$
            }
        }

        /** Why a FAILED step could not finish, in a clause - for the note on the ordinary-application module. */
        private String failureReason()
        {
            switch (cause)
            {
            case MODEL_UNAVAILABLE:
                return "EDT had not registered the BM model of project '" + context.projectName + "' yet"; //$NON-NLS-1$ //$NON-NLS-2$
            case BATCH_SESSION:
                return "EDT was running a batch operation on the model of project '" + context.projectName //$NON-NLS-1$
                    + "' (" + detail + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            case SERVICE:
                return detail;
            case UNEXPECTED:
            default:
                return "setting it to Extended failed: " + detail; //$NON-NLS-1$
            }
        }

        /** What happened to the export of the {@code .mdo} after a switch, ending the sentence. */
        private String exportClause()
        {
            switch (export)
            {
            case DRAINED:
                return ", and the .mdo was exported."; //$NON-NLS-1$
            case UNOBSERVABLE:
                return ", and the export of the .mdo was requested; its completion could not be observed."; //$NON-NLS-1$
            case PENDING:
                return ", but the export of the .mdo was not confirmed within " + seconds() + "s."; //$NON-NLS-1$ //$NON-NLS-2$
            case NOT_SCHEDULED:
            default:
                return ", but the export of the .mdo could not be scheduled."; //$NON-NLS-1$
            }
        }

        /** @return whether the export is known to have been made, or at least requested and not refused */
        private boolean confirmed()
        {
            return export == Export.DRAINED || export == Export.UNOBSERVABLE;
        }

        /**
         * @return whether the module is the root's ordinary-application module, which EDT does not list
         *     among the properties an extension can extend
         */
        private boolean ordinaryApplicationModule()
        {
            return context != null && context.address.isOrdinaryApplicationModule();
        }

        /** The owner by name: {@code 'Catalog.X'}, or the extension's root. */
        private String ownerName()
        {
            return context.address.isConfiguration() ? "the extension's root object (Configuration)" //$NON-NLS-1$
                : "'" + context.address.ownerFqn + "'"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        /** The owner by name, with what makes its module state matter. */
        private String ownerWithRole()
        {
            return ownerName() + (context.address.isConfiguration() ? ", adopted from the base configuration" //$NON-NLS-1$
                : ", an object adopted into this extension"); //$NON-NLS-1$
        }

        /** The {@code .mdo} the state lives in, by path when the model named its top object. */
        private String mdoName()
        {
            return context.mdoPath != null ? context.mdoPath : "the .mdo of its top object"; //$NON-NLS-1$
        }

        /** The state line as the {@code .mdo} holds it, with the file it is in. */
        private String stateLine(String state)
        {
            return element(state) + " in " + mdoName(); //$NON-NLS-1$
        }

        /** The {@code .mdo} element of the module's state: the extension feature is named as the module's. */
        private String element(String state)
        {
            String name = context.feature == null ? context.address.stem : context.feature.getName();
            return "<" + name + ">" + state + "</" + name + ">"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }

        private static long seconds()
        {
            return EXPORT_DEADLINE_MS / 1000;
        }
    }
}
