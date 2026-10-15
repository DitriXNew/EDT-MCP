/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.md.extension.IMdAdoptedPropertyAccess;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com._1c.g5.v8.dt.metadata.mdclass.extension.MdClassExtensionFactory;
import com._1c.g5.v8.dt.metadata.mdclass.extension.type.MdPropertyState;

/**
 * Builds {@link AdoptedModuleState.Result}s the way production does - by running
 * {@link AdoptedModuleState#ensureExtended(IProject, MetadataScope, String, AdoptedModuleState.Environment)}
 * against an in-memory extension - for the tests of the tool that renders them, which live in another
 * package and must not fake a result the step could never produce.
 */
public final class AdoptedModuleStateTestFixtures
{
    /** The module path of the adopted common module every fixture result is about. */
    public static final String MODULE_PATH = "src/CommonModules/DrySignal/Module.bsl"; //$NON-NLS-1$

    private AdoptedModuleStateTestFixtures()
    {
        // Utility class
    }

    /**
     * A fake platform: answers the questions {@link AdoptedModuleState} asks and records every call,
     * in order, so a test can pin WHAT was exported, that the wait came after the export and that the
     * {@code .mdo} was read back after the wait. Its clock is virtual: the export request
     * ({@link #exportRequestMs}), the queue's wait ({@link #exportWaitMs}, never longer than the timeout it is
     * given - BuildUtils bounds it), each read ({@link #readTimes}, cut at its timeout - the bounded read's
     * contract) and {@link #pause} move it, so a test can run the whole 60s budget at once and pin where it ends.
     * Package-visible, as the seam it implements.
     */
    static final class FakeEnvironment implements AdoptedModuleState.Environment
    {
        /** Whether EDT's project registry names the project a configuration extension. */
        public boolean extension = true;
        /** Whether the project carries the extension's persistent nature; {@code null} models natures that cannot be read. */
        public Boolean nature = Boolean.TRUE;
        /** The project's BM model; {@code null} models the transient window without one. */
        public IBmModel model;
        /** EDT's adopted-property service; {@code null} models an inactive md.extension bundle. */
        public IMdAdoptedPropertyAccess access;
        /** What {@link #forceExport} answers. */
        public boolean exportAccepted = true;
        /** What {@link #forceExport} throws instead of answering, when set - after its time is spent. */
        public RuntimeException exportFailure;
        /** How long {@link #forceExport} takes on the virtual clock: the request itself counts against the budget. */
        public long exportRequestMs;
        /** What {@link #awaitExport} answers. */
        public BuildUtils.DiskExportState exportState = BuildUtils.DiskExportState.DRAINED;
        /**
         * How long {@link #awaitExport} takes on the virtual clock - never longer than the timeout it is given, as
         * BuildUtils' bounded wait: {@code Long.MAX_VALUE} models a wait that uses all it is given.
         */
        public long exportWaitMs;
        /** What {@link #topObjectFqn} answers instead of computing, when set. */
        public String topFqnOverride;
        /**
         * What the reads of the {@code .mdo} find, one entry per read, the last one repeating. Empty: a
         * healthy export - the line is on disk once an accepted export request's queue DRAINED, and not before.
         */
        public final List<AdoptedModuleState.LineOnDisk> disk = new ArrayList<>();
        /**
         * How long the reads of the {@code .mdo} take on the virtual clock, one entry per read, the last one repeating;
         * empty: no time. A read that takes longer than its timeout is cut there and answers UNREADABLE, whatever
         * {@link #disk} holds - the contract of the bounded read: the caller stops waiting at the timeout.
         */
        public final List<Long> readTimes = new ArrayList<>();
        /** The timeout each read was given, in order. */
        public final List<Long> readTimeouts = new ArrayList<>();
        /** The virtual clock at the start of each read, in order. */
        public final List<Long> readStarts = new ArrayList<>();
        /** What {@link #readStateLine} throws - after recording the read - instead of answering, when set. */
        public RuntimeException readFailure;
        /**
         * When set, answers each read of the {@code .mdo} - after the read is recorded, and taking no time on the
         * virtual clock - in place of {@link #disk} and {@link #readTimes}: a test runs the platform's bounded read
         * through it.
         */
        public ReadThrough readThrough;
        /** Whether {@link #pause} is interrupted (and answers {@code false}). */
        public boolean interrupted;
        /** The virtual clock, in ms. */
        public long clock;
        /**
         * Every call, in order: {@code isExtension}, {@code nature}, {@code model}, {@code access},
         * {@code forceExport:<fqn>}, {@code awaitExport:<ms>}, {@code readMdo:<path>#<line>}, {@code pause:<ms>}.
         */
        public final List<String> calls = new ArrayList<>();

        private boolean exportRequested;
        private int reads;

        @Override
        public boolean isExtensionProject(IProject project)
        {
            calls.add("isExtension"); //$NON-NLS-1$
            return extension;
        }

        @Override
        public Boolean hasExtensionNature(IProject project)
        {
            calls.add("nature"); //$NON-NLS-1$
            return nature;
        }

        /**
         * The model as EDT's shared resolver reports it - through {@link BmModelResolver} itself, so an
         * unavailable model carries the resolver's own actionable error, not one this fake would invent.
         */
        @Override
        public BmModelResolver.Resolution model(IProject project)
        {
            calls.add("model"); //$NON-NLS-1$
            IBmModelManager manager = mock(IBmModelManager.class);
            when(manager.getModel(project)).thenReturn(model);
            return BmModelResolver.resolve(project, manager);
        }

        @Override
        public IMdAdoptedPropertyAccess access()
        {
            calls.add("access"); //$NON-NLS-1$
            return access;
        }

        /**
         * The top object as BM sees it: the root of the containment tree (a configuration collection
         * only REFERS to its top objects, a command is CONTAINED by its owner), named
         * {@code EClass.Name}, or {@code Configuration} for the root itself.
         */
        @Override
        public String topObjectFqn(EObject owner)
        {
            if (topFqnOverride != null)
            {
                return topFqnOverride;
            }
            EObject top = EcoreUtil.getRootContainer(owner);
            return top instanceof Configuration ? "Configuration" //$NON-NLS-1$
                : top.eClass().getName() + "." + ((MdObject)top).getName(); //$NON-NLS-1$
        }

        @Override
        public boolean forceExport(IProject project, String topObjectFqn)
        {
            calls.add("forceExport:" + topObjectFqn); //$NON-NLS-1$
            clock += exportRequestMs;
            if (exportFailure != null)
            {
                throw exportFailure;
            }
            exportRequested |= exportAccepted;
            return exportAccepted;
        }

        @Override
        public BuildUtils.DiskExportState awaitExport(IProject project, long timeoutMs)
        {
            calls.add("awaitExport:" + timeoutMs); //$NON-NLS-1$
            clock += Math.max(0L, Math.min(exportWaitMs, timeoutMs));
            return exportState;
        }

        @Override
        public AdoptedModuleState.LineOnDisk readStateLine(IProject project, String mdoPath,
            AdoptedModuleState.StateLine line, long timeoutMs)
        {
            calls.add("readMdo:" + mdoPath + "#" + line); //$NON-NLS-1$ //$NON-NLS-2$
            readTimeouts.add(timeoutMs);
            readStarts.add(clock);
            int read = reads++;
            if (readFailure != null)
            {
                throw readFailure;
            }
            if (readThrough != null)
            {
                return readThrough.read(project, mdoPath, line, timeoutMs);
            }
            long takes = readTimes.isEmpty() ? 0L : readTimes.get(Math.min(read, readTimes.size() - 1));
            if (takes > timeoutMs)
            {
                // Cut at the timeout, as the bounded read is: the caller stops waiting, and a read that has not
                // answered by then says nothing either way.
                clock += Math.max(0L, timeoutMs);
                return AdoptedModuleState.LineOnDisk.UNREADABLE;
            }
            clock += takes;
            if (disk.isEmpty())
            {
                return exportRequested && exportState == BuildUtils.DiskExportState.DRAINED
                    ? AdoptedModuleState.LineOnDisk.PRESENT : AdoptedModuleState.LineOnDisk.ABSENT;
            }
            return disk.get(Math.min(read, disk.size() - 1));
        }

        @Override
        public long currentTimeMillis()
        {
            return clock;
        }

        /** An interrupted pause still takes its time - so a step that ignored the interrupt would read on, not spin. */
        @Override
        public boolean pause(long ms)
        {
            calls.add("pause:" + ms); //$NON-NLS-1$
            clock += ms;
            return !interrupted;
        }

        /** @return the calls that touched the export queue, in order */
        public List<String> exportCalls()
        {
            return callsStartingWith("forceExport:", "awaitExport:"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        /** @return the reads of the {@code .mdo}, in order - each {@code readMdo:<path>#<line>} */
        public List<String> mdoReads()
        {
            return callsStartingWith("readMdo:"); //$NON-NLS-1$
        }

        /** @return every call that touched the queue or the disk, or paused, in order */
        public List<String> exportAndDiskCalls()
        {
            return callsStartingWith("forceExport:", "awaitExport:", "readMdo:", "pause:"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }

        private List<String> callsStartingWith(String... prefixes)
        {
            List<String> out = new ArrayList<>();
            for (String call : calls)
            {
                for (String prefix : prefixes)
                {
                    if (call.startsWith(prefix))
                    {
                        out.add(call);
                        break;
                    }
                }
            }
            return out;
        }
    }

    /** A read of the {@code .mdo} a test answers: {@link FakeEnvironment#readThrough}. */
    @FunctionalInterface
    interface ReadThrough
    {
        /**
         * @param project the project
         * @param mdoPath the project-relative {@code .mdo}
         * @param line the line to look for
         * @param timeoutMs what is left of the budget
         * @return what the read found
         */
        AdoptedModuleState.LineOnDisk read(IProject project, String mdoPath, AdoptedModuleState.StateLine line,
            long timeoutMs);
    }

    /**
     * A BM model whose read and write tasks run their body at once against {@code tx}, the way the
     * platform runs a task: {@code executeReadonlyTask} for a read, {@code execute} for a write.
     *
     * @param tx the transaction both kinds of task receive
     * @param inWrite set to {@code true} while a WRITE task body runs, so a test can see where a call happened
     * @return the model
     */
    public static IBmModel model(IBmTransaction tx, boolean[] inWrite)
    {
        IBmModel model = mock(IBmModel.class);
        when(model.executeReadonlyTask(any())).thenAnswer(inv -> {
            IBmTask<?> task = inv.getArgument(0);
            return task.execute(tx, null);
        });
        when(model.execute(any())).thenAnswer(inv -> {
            IBmTask<?> task = inv.getArgument(0);
            inWrite[0] = true;
            try
            {
                return task.execute(tx, null);
            }
            finally
            {
                inWrite[0] = false;
            }
        });
        return model;
    }

    /** @return a project handle named {@code tests}, as the fixture extension is */
    public static IProject project()
    {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("tests"); //$NON-NLS-1$
        return project;
    }

    /** @return a transaction whose {@code toTransactionObject} hands back the object itself */
    public static IBmTransaction identityTransaction()
    {
        IBmTransaction tx = mock(IBmTransaction.class);
        when(tx.toTransactionObject(any())).thenAnswer(inv -> inv.getArgument(0));
        return tx;
    }

    /** @return an extension root holding the adopted common module {@code DrySignal} with its extension block */
    public static Configuration extensionWithAdoptedModule()
    {
        Configuration root = MdClassFactory.eINSTANCE.createConfiguration();
        root.setName("tests"); //$NON-NLS-1$
        root.setObjectBelonging(ObjectBelonging.ADOPTED);
        root.setExtension(MdClassExtensionFactory.eINSTANCE.createConfigurationExtension());
        CommonModule module = MdClassFactory.eINSTANCE.createCommonModule();
        module.setName("DrySignal"); //$NON-NLS-1$
        module.setObjectBelonging(ObjectBelonging.ADOPTED);
        module.setExtension(MdClassExtensionFactory.eINSTANCE.createCommonModuleExtension());
        root.getCommonModules().add(module);
        return root;
    }

    /**
     * Runs the step for {@link #MODULE_PATH} against an extension whose adopted module reads as
     * {@code before}.
     *
     * @param before the module state the property service reports ({@code NONE}, {@code EXTENDED}, or a
     *     state the step leaves as it is)
     * @param exported how the export wait ends
     * @return the step's result
     */
    public static AdoptedModuleState.Result run(MdPropertyState before, BuildUtils.DiskExportState exported)
    {
        return AdoptedModuleState.ensureExtended(project(),
            MetadataScope.ofConfiguration(extensionWithAdoptedModule()), MODULE_PATH, platform(before, exported));
    }

    /** A fake platform whose adopted module reads as {@code before} and whose export wait ends {@code exported}. */
    private static FakeEnvironment platform(MdPropertyState before, BuildUtils.DiskExportState exported)
    {
        IMdAdoptedPropertyAccess access = mock(IMdAdoptedPropertyAccess.class);
        when(access.hasPropertyState(any(EObject.class), any(EStructuralFeature.class), eq(before)))
            .thenReturn(Boolean.TRUE);
        FakeEnvironment env = new FakeEnvironment();
        env.model = model(identityTransaction(), new boolean[1]);
        env.access = access;
        env.exportState = exported;
        return env;
    }

    /**
     * @return the result of a switch whose export queue DRAINED while the {@code .mdo}, read back to the end of
     *     the budget, never carried the line - EDT drains also when it fails to write a file: a failure
     */
    public static AdoptedModuleState.Result switchedButNotOnDisk()
    {
        return notOnDisk(MdPropertyState.NONE);
    }

    /**
     * @return the result of a write into a module that already was Extended - EDT's own listener may flip the
     *     model first - whose export queue DRAINED while the {@code .mdo}, read back to the end of the budget,
     *     never carried the line: the same failure as {@link #switchedButNotOnDisk()}, one evidence giving one
     *     verdict
     */
    public static AdoptedModuleState.Result alreadyExtendedButNotOnDisk()
    {
        return notOnDisk(MdPropertyState.EXTENDED);
    }

    /** A step whose export queue DRAINED while the {@code .mdo}, read back, never carried the line. */
    private static AdoptedModuleState.Result notOnDisk(MdPropertyState before)
    {
        FakeEnvironment env = platform(before, BuildUtils.DiskExportState.DRAINED);
        env.disk.add(AdoptedModuleState.LineOnDisk.ABSENT);
        return AdoptedModuleState.ensureExtended(project(),
            MetadataScope.ofConfiguration(extensionWithAdoptedModule()), MODULE_PATH, env);
    }

    /**
     * @param lineOnDisk whether the {@code .mdo}, read once, already carries the module's state line
     * @return the result of a switch whose export request the platform refused: the {@code .mdo} is read once, and a
     *     line already there confirms it as an export does - a success; none is a failure
     */
    public static AdoptedModuleState.Result switchedWithARefusedExport(boolean lineOnDisk)
    {
        return refusedExport(MdPropertyState.NONE, lineOnDisk);
    }

    /**
     * @param lineOnDisk whether the {@code .mdo}, read once, already carries the module's state line - as when the
     *     state was set in EDT's UI, or by an earlier write
     * @return the result of a write into a module that already was Extended, whose export request the platform
     *     refused: decided by the {@code .mdo}, read once, as after a switch
     */
    public static AdoptedModuleState.Result alreadyExtendedWithARefusedExport(boolean lineOnDisk)
    {
        return refusedExport(MdPropertyState.EXTENDED, lineOnDisk);
    }

    /** A step whose export request the platform refused, with or without the line in the {@code .mdo}. */
    private static AdoptedModuleState.Result refusedExport(MdPropertyState before, boolean lineOnDisk)
    {
        FakeEnvironment env = platform(before, BuildUtils.DiskExportState.DRAINED);
        env.exportAccepted = false;
        env.disk.add(lineOnDisk ? AdoptedModuleState.LineOnDisk.PRESENT : AdoptedModuleState.LineOnDisk.ABSENT);
        return AdoptedModuleState.ensureExtended(project(),
            MetadataScope.ofConfiguration(extensionWithAdoptedModule()), MODULE_PATH, env);
    }

    /** @return the result of a write that switched the module from None to Extended, export drained */
    public static AdoptedModuleState.Result switched()
    {
        return run(MdPropertyState.NONE, BuildUtils.DiskExportState.DRAINED);
    }

    /**
     * @param exported how the export wait ends - DRAINED puts the line in the {@code .mdo}, any other answer leaves
     *     it out
     * @return the result of a write into a module that already was Extended: a success when the line is on disk,
     *     a failure when it is not, as after a switch
     */
    public static AdoptedModuleState.Result alreadyExtended(BuildUtils.DiskExportState exported)
    {
        return run(MdPropertyState.EXTENDED, exported);
    }

    /** @return the result of a switch whose .mdo export was not confirmed - a failure */
    public static AdoptedModuleState.Result exportNotConfirmed()
    {
        return run(MdPropertyState.NONE, BuildUtils.DiskExportState.PENDING);
    }

    /** @return the result of a write into a module whose state is Checked - left as it is, a success */
    public static AdoptedModuleState.Result keptChecked()
    {
        return run(MdPropertyState.CHECKED, BuildUtils.DiskExportState.DRAINED);
    }

    /** @return the result of a write into the module of an adopted object with no extension block - a success */
    public static AdoptedModuleState.Result noExtensionBlock()
    {
        IMdAdoptedPropertyAccess access = mock(IMdAdoptedPropertyAccess.class);
        when(access.hasPropertyState(any(EObject.class), any(EStructuralFeature.class), eq(MdPropertyState.NONE)))
            .thenReturn(Boolean.TRUE);
        FakeEnvironment env = new FakeEnvironment();
        env.model = model(identityTransaction(), new boolean[1]);
        env.access = access;
        Configuration root = extensionWithAdoptedModule();
        root.getCommonModules().get(0).setExtension(null);
        return AdoptedModuleState.ensureExtended(project(), MetadataScope.ofConfiguration(root), MODULE_PATH, env);
    }

    /** @return the result of a step that could not run because the project's BM model was unavailable */
    public static AdoptedModuleState.Result failedForWantOfAModel()
    {
        FakeEnvironment env = new FakeEnvironment();
        env.access = mock(IMdAdoptedPropertyAccess.class);
        return AdoptedModuleState.ensureExtended(project(),
            MetadataScope.ofConfiguration(extensionWithAdoptedModule()), MODULE_PATH, env);
    }
}
