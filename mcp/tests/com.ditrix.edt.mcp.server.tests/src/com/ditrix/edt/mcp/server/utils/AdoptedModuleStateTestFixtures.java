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
     * in order, so a test can pin WHAT was exported and that the wait came after the export.
     * Package-visible, as the seam it implements.
     */
    static final class FakeEnvironment implements AdoptedModuleState.Environment
    {
        /** Whether the project is a configuration extension. */
        public boolean extension = true;
        /** The project's BM model; {@code null} models the transient window without one. */
        public IBmModel model;
        /** EDT's adopted-property service; {@code null} models an inactive md.extension bundle. */
        public IMdAdoptedPropertyAccess access;
        /** What {@link #forceExport} answers. */
        public boolean exportAccepted = true;
        /** What {@link #forceExport} throws instead of answering, when set. */
        public RuntimeException exportFailure;
        /** What {@link #awaitExport} answers. */
        public BuildUtils.DiskExportState exportState = BuildUtils.DiskExportState.DRAINED;
        /** What {@link #topObjectFqn} answers instead of computing, when set. */
        public String topFqnOverride;
        /** Every call, in order: {@code isExtension}, {@code model}, {@code access}, {@code forceExport:<fqn>}, {@code awaitExport:<ms>}. */
        public final List<String> calls = new ArrayList<>();

        @Override
        public boolean isExtensionProject(IProject project)
        {
            calls.add("isExtension"); //$NON-NLS-1$
            return extension;
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
            if (exportFailure != null)
            {
                throw exportFailure;
            }
            return exportAccepted;
        }

        @Override
        public BuildUtils.DiskExportState awaitExport(IProject project, long timeoutMs)
        {
            calls.add("awaitExport:" + timeoutMs); //$NON-NLS-1$
            return exportState;
        }

        /** @return the calls that touched the export queue, in order */
        public List<String> exportCalls()
        {
            List<String> out = new ArrayList<>();
            for (String call : calls)
            {
                if (call.startsWith("forceExport:") || call.startsWith("awaitExport:")) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    out.add(call);
                }
            }
            return out;
        }
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
        IMdAdoptedPropertyAccess access = mock(IMdAdoptedPropertyAccess.class);
        when(access.hasPropertyState(any(EObject.class), any(EStructuralFeature.class), eq(before)))
            .thenReturn(Boolean.TRUE);
        FakeEnvironment env = new FakeEnvironment();
        env.model = model(identityTransaction(), new boolean[1]);
        env.access = access;
        env.exportState = exported;
        return AdoptedModuleState.ensureExtended(project(),
            MetadataScope.ofConfiguration(extensionWithAdoptedModule()), MODULE_PATH, env);
    }

    /** @return the result of a write that switched the module from None to Extended, export drained */
    public static AdoptedModuleState.Result switched()
    {
        return run(MdPropertyState.NONE, BuildUtils.DiskExportState.DRAINED);
    }

    /**
     * @param exported how the export wait ends
     * @return the result of a write into a module that already was Extended
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
