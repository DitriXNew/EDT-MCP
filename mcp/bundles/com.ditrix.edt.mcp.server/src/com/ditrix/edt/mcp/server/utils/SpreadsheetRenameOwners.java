// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;

import com._1c.g5.v8.bm.core.BmUriUtil;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.core.platform.IDependentProject;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.HostKind;

/**
 * Identities, not UI titles, of the extra owners in EDT's metadata rename cascade.
 * The public IRefactoring exposes no target accessor. Use the native selection rule: a dependent
 * extension with an object at the original BM FQN whose adoption link has the original UUID.
 */
public final class SpreadsheetRenameOwners
{
    private SpreadsheetRenameOwners()
    {
    }

    record BaseIdentity(String fqn, String containment, UUID uuid, EClass type)
    {
        URI extensionUri(String projectId)
        {
            return containment == null ? BmUriUtil.createTopBmObjectUri(projectId, fqn)
                : BmUriUtil.createContainedBmObjectUri(projectId, fqn, containment);
        }
    }

    record Counterpart(IProject project, URI uri, UUID uuid, UUID baseUuid)
    {
        MdObject resolve(IBmTransaction transaction, EClass type)
        {
            var object = transaction.getObjectByUri(uri);
            if (object instanceof MdObject owner && type == owner.eClass() && uuid.equals(owner.getUuid())
                && baseUuid.equals(owner.getExtendedConfigurationObject()))
            {
                return owner;
            }
            return null;
        }
    }

    /** Immutable dependent-owner snapshot taken before EDT constructs the rename groups. */
    public static final class Plan
    {
        private final List<Counterpart> counterparts;

        Plan(List<Counterpart> counterparts)
        {
            this.counterparts = counterparts == null ? null : List.copyOf(counterparts);
        }

        boolean covers(Plan current, int groups)
        {
            return counterparts != null && current != null && counterparts.size() + 1 == groups
                && counterparts.equals(current.counterparts);
        }

        List<Counterpart> counterparts()
        {
            return counterparts;
        }
    }

    /** Preview and non-spreadsheet owners never need an extension-guard snapshot. */
    public static Plan capture(IProject project, MdObject owner)
    {
        if (HostKind.current() != HostKind.WINDOWED || !SpreadsheetRenameGuard.mayOwnSpreadsheets(owner)
            || !(owner instanceof IBmObject bmOwner))
        {
            return null;
        }
        try
        {
            BaseIdentity base = ReadToolExecution.readOnHost(project, "Resolve spreadsheet rename base owner", //$NON-NLS-1$
                () -> baseIdentity(owner, bmOwner));
            var activator = Activator.getDefault();
            if (base == null || activator == null || activator.getDtProjectManager() == null
                || activator.getV8ProjectManager() == null)
            {
                return new Plan(null);
            }
            IDtProject dtProject = activator.getDtProjectManager().getDtProject(project);
            if (dtProject == null)
            {
                return new Plan(null);
            }
            List<Counterpart> counterparts = new ArrayList<>();
            for (IDtProject extension : IDependentProject.getDependent(dtProject,
                activator.getV8ProjectManager().getProjects(), IExtensionProject.class))
            {
                IProject extensionProject = extension.getWorkspaceProject();
                if (extensionProject == null || !extensionProject.isOpen())
                {
                    return new Plan(null);
                }
                URI uri = base.extensionUri(extension.getId());
                var model = BmModelResolver.resolve(extensionProject);
                if (!model.isAvailable())
                {
                    return new Plan(null);
                }
                Counterpart target = BmTransactions.read(model.getModel(), "Resolve spreadsheet rename extension owner", //$NON-NLS-1$
                    (tx, monitor) -> counterpart(extensionProject, uri, base.uuid(), base.type(), tx.getObjectByUri(uri)));
                if (target != null)
                {
                    counterparts.add(target);
                }
            }
            counterparts.sort(Comparator.comparing(target -> target.project().getName()));
            return new Plan(counterparts);
        }
        catch (RuntimeException e)
        {
            // The caller distinguishes an unknown snapshot from a verified empty set. A single
            // native group is independently known to be the original target; a cascade is not.
            return new Plan(null);
        }
    }

    static BaseIdentity baseIdentity(MdObject owner, IBmObject object)
    {
        UUID id = owner.getUuid();
        if (id == null)
        {
            return null;
        }
        EClass type = owner.eClass();
        if (object.bmIsTop())
        {
            return new BaseIdentity(object.bmGetFqn(), null, id, type);
        }
        URI uri = object.bmGetUri();
        if (uri == null || !BmUriUtil.isBmUri(uri))
        {
            return null;
        }
        String containment = BmUriUtil.extractContainmentPath(uri);
        return containment == null ? null : new BaseIdentity(BmUriUtil.extractTopObjectFqn(uri), containment, id, type);
    }

    static Counterpart counterpart(IProject project, URI exactUri, UUID baseUuid, EClass type, IBmObject resolved)
    {
        if (resolved == null)
        {
            return null;
        }
        if (!(resolved instanceof MdObject owner))
        {
            throw new IllegalStateException("EDT resolved a non-metadata rename owner."); //$NON-NLS-1$
        }
        if (!baseUuid.equals(owner.getExtendedConfigurationObject()))
        {
            return null;
        }
        if (owner.eClass() != type || owner.getUuid() == null)
        {
            // The native pass uses the adoption UUID. A matching link with a different type or
            // no stable UUID is an unknown cascade, not evidence that this owner can be skipped.
            throw new IllegalStateException("EDT rename counterpart identity cannot be verified."); //$NON-NLS-1$
        }
        return new Counterpart(project, exactUri, owner.getUuid(), baseUuid);
    }

    static String changedScope()
    {
        return "Could not verify the spreadsheet owners affected by the dependent-extension rename. " //$NON-NLS-1$
            + "Nothing was renamed. Refresh the affected projects, preview again, and retry."; //$NON-NLS-1$
    }
}
