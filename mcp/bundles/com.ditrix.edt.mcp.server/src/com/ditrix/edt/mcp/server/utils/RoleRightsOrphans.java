/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.InternalEObject;

import com._1c.g5.v8.bm.core.BmUriUtil;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com._1c.g5.v8.dt.metadata.mdclass.Role;
import com._1c.g5.v8.dt.rights.model.ObjectRight;
import com._1c.g5.v8.dt.rights.model.ObjectRights;
import com._1c.g5.v8.dt.rights.model.Rls;
import com._1c.g5.v8.dt.rights.model.RoleDescription;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tools.base.WriteScope;

/**
 * Finds, and removes on request, role-rights entries whose target object no longer exists.
 *
 * <p>An {@link ObjectRights} holds its target as a BM reference. When the target is deleted outside
 * EDT's delete refactoring (a git merge, a file removed on disk), the importer keeps the entry and
 * leaves an unresolved proxy in the model; EDT's own rights tasks then fail on the role
 * ({@code SecuredObject.compareTo} has no UUID for the proxy). Each entry is judged three-way:
 * {@link Verdict#PRESENT}, {@link Verdict#ABSENT} (proven gone) or {@link Verdict#UNDETERMINED}
 * (model not ready, an adopted role or an extension, or an address this code cannot walk). Only
 * {@code ABSENT} entries are ever removed.</p>
 *
 * <p>A dangling RLS field (a field reference whose attribute was deleted - EDT's rights delete
 * contributor does not clean {@code Rls.fields}) is reported only, never removed.</p>
 *
 * <p>Every EObject method here must run inside a BM transaction; {@link #addressOf} and
 * {@link #topFqnOf} are pure.</p>
 */
public final class RoleRightsOrphans
{
    /** The proxy scheme the rights importer leaves on a reference it could not convert. */
    static final String UNRESOLVED_SCHEME = "unresolved"; //$NON-NLS-1$

    /** Marker of a lazy-link proxy fragment; the symbolic name follows the last {@code ::}. */
    private static final String LAZY_LINK_MARKER = "lazyLink_"; //$NON-NLS-1$

    /** The DB-view path segment that precedes a field name in a field proxy's fragment. */
    private static final String FIELDS_SEGMENT = "fields:"; //$NON-NLS-1$

    /** The label of an entry that names no target at all. */
    public static final String NO_TARGET = "(no target)"; //$NON-NLS-1$

    /** The three verdicts on a rights entry's target. */
    public enum Verdict
    {
        /** The target exists (the reference resolves, or the address walks to a live node). */
        PRESENT("present"), //$NON-NLS-1$
        /** The target is proven absent from the configuration - the only removable verdict. */
        ABSENT("absent"), //$NON-NLS-1$
        /** Absence could not be proven; the entry is never removed. */
        UNDETERMINED("undetermined"); //$NON-NLS-1$

        private final String label;

        Verdict(String label)
        {
            this.label = label;
        }

        /** @return the lower-case wire label */
        public String label()
        {
            return label;
        }
    }

    /** A verdict plus, for {@code UNDETERMINED}, the reason. */
    public static final class Judgement
    {
        public final Verdict verdict;
        public final String reason;

        Judgement(Verdict verdict, String reason)
        {
            this.verdict = verdict;
            this.reason = reason;
        }

        static Judgement of(Verdict verdict)
        {
            return new Judgement(verdict, null);
        }

        static Judgement undetermined(String reason)
        {
            return new Judgement(Verdict.UNDETERMINED, reason);
        }
    }

    /** One rights entry whose target did not resolve. */
    public static final class Entry
    {
        public final String role;
        public final String target;
        public final Verdict verdict;
        public final String reason;
        public final List<String> rights;
        public final boolean hasRls;

        Entry(String role, String target, Judgement judgement, List<String> rights, boolean hasRls)
        {
            this.role = role;
            this.target = target;
            this.verdict = judgement.verdict;
            this.reason = judgement.reason;
            this.rights = rights;
            this.hasRls = hasRls;
        }
    }

    /** One RLS field reference that does not resolve (report-only). */
    public static final class RlsField
    {
        public final String role;
        public final String target;
        public final String right;
        public final String field;

        RlsField(String role, String target, String right, String field)
        {
            this.role = role;
            this.target = target;
            this.right = right;
            this.field = field;
        }
    }

    /** The outcome of a scan (and, when requested, of the removal done in the same transaction). */
    public static final class Scan
    {
        public int rolesScanned;
        public final List<Entry> entries = new ArrayList<>();
        public final List<RlsField> rlsFields = new ArrayList<>();
        /** The entries removed in the transaction; claimed by the caller only after it commits. */
        public final List<Entry> removed = new ArrayList<>();
        /** Top-object FQNs of the rights resources that lost an entry (to force-export). */
        public final Set<String> changedRightsFqns = new LinkedHashSet<>();
        /** Top-object FQNs of the roles that lost an entry. */
        public final Set<String> changedRoleFqns = new LinkedHashSet<>();

        /** @return how many entries were judged {@code verdict} */
        public int count(Verdict verdict)
        {
            int n = 0;
            for (Entry entry : entries)
            {
                if (entry.verdict == verdict)
                {
                    n++;
                }
            }
            return n;
        }
    }

    /** The outcome of {@link #sweep}: the scan, whether its removal committed, and any warning. */
    public static final class Sweep
    {
        /** The final scan (the write-transaction one when a removal ran). */
        public Scan scan = new Scan();
        /** {@code true} only after the removal transaction committed. */
        public boolean removedFromModel;
        /** Set when the sweep or the re-export could not complete cleanly. */
        public String warning;

        /** @return the entries actually removed (empty unless the removal committed) */
        public List<Entry> removed()
        {
            return removedFromModel ? scan.removed : List.of();
        }
    }

    private RoleRightsOrphans()
    {
        // utility class
    }

    /**
     * Reports the project's orphaned role-rights entries and, when {@code remove} is set, removes
     * the {@code ABSENT} ones and force-exports each changed role and rights resource. The removal
     * re-judges every entry inside the write transaction, so it never acts on a stale report.
     *
     * @param project the workspace project
     * @param model the project's BM model
     * @param remove remove the proven-absent entries
     * @return the sweep (never {@code null})
     */
    public static Sweep sweep(IProject project, IBmModel model, boolean remove)
    {
        Sweep sweep = new Sweep();
        boolean ready = ProjectStateChecker.checkProjectState(project).isReady();
        try
        {
            sweep.scan = BmTransactions.read(model, "FindOrphanRoleRights", //$NON-NLS-1$
                (tx, pm) -> scan(tx, ready, isBaseConfiguration(tx), false));
            if (!remove || sweep.scan.count(Verdict.ABSENT) == 0)
            {
                return sweep;
            }
            sweep.scan = BmTransactions.write(model, "CleanOrphanRoleRights", //$NON-NLS-1$
                (tx, pm) -> scan(tx, ready, isBaseConfiguration(tx), true));
            sweep.removedFromModel = !sweep.scan.removed.isEmpty();
        }
        catch (RuntimeException e)
        {
            Activator.logError("Could not sweep orphaned role rights", e); //$NON-NLS-1$
            sweep.warning = "The orphaned role-rights sweep did not complete: " //$NON-NLS-1$
                + PlatformFailures.describe(e) + ". Nothing was removed; retry when the project is ready."; //$NON-NLS-1$
            return sweep;
        }
        if (sweep.removedFromModel)
        {
            WriteScope.recordWrite(project);
            List<String> fqns = new ArrayList<>(sweep.scan.changedRoleFqns);
            fqns.addAll(sweep.scan.changedRightsFqns);
            if (!BmTransactions.forceExportToDisk(project, fqns))
            {
                sweep.warning = "Orphaned entries were removed in the model but the export of the " //$NON-NLS-1$
                    + "changed roles was not accepted; run resync_to_disk again or clean_project."; //$NON-NLS-1$
            }
        }
        return sweep;
    }

    /** @return whether the transaction's configuration is a base (native) configuration */
    private static boolean isBaseConfiguration(IBmTransaction tx)
    {
        Iterator<IBmObject> configs = tx.getTopObjectIterator(MdClassPackage.Literals.CONFIGURATION);
        if (!configs.hasNext())
        {
            return false;
        }
        IBmObject config = configs.next();
        return config instanceof Configuration
            && ((Configuration)config).getObjectBelonging() == ObjectBelonging.NATIVE;
    }

    /**
     * Scans every role of the transaction's model for rights entries whose target does not
     * resolve, and removes the {@code ABSENT} ones when {@code remove} is set. Must run inside a BM
     * transaction (a write transaction when {@code remove} is set).
     *
     * @param tx the open transaction
     * @param modelReady whether the project's model is fully loaded; when not, no entry is judged
     *            {@code ABSENT}
     * @param baseConfiguration whether the project is a base configuration (an extension's
     *            absence is not provable here)
     * @param remove remove the {@code ABSENT} entries
     * @return the scan (never {@code null})
     */
    public static Scan scan(IBmTransaction tx, boolean modelReady, boolean baseConfiguration, boolean remove)
    {
        Scan scan = new Scan();
        Iterator<IBmObject> roles = tx.getTopObjectIterator(MdClassPackage.Literals.ROLE);
        while (roles.hasNext())
        {
            IBmObject object = roles.next();
            if (!(object instanceof Role))
            {
                continue;
            }
            Role role = (Role)object;
            if (!(role.getRights() instanceof RoleDescription))
            {
                continue;
            }
            scan.rolesScanned++;
            String blocker = !modelReady ? "the project model is not fully loaded; retry when it is ready" //$NON-NLS-1$
                : !baseConfiguration ? "an extension project: a target outside it cannot be judged absent" //$NON-NLS-1$
                    : role.getObjectBelonging() != ObjectBelonging.NATIVE
                        ? "an adopted role: its rights belong to the base configuration" : null; //$NON-NLS-1$
            scanRole(tx, role, (RoleDescription)role.getRights(), blocker, remove, scan);
        }
        return scan;
    }

    private static void scanRole(IBmTransaction tx, Role role, RoleDescription description, String blocker,
        boolean remove, Scan scan)
    {
        String roleFqn = fqnOf(role);
        List<ObjectRights> toRemove = new ArrayList<>();
        List<Entry> removedHere = new ArrayList<>();
        for (ObjectRights objectRights : description.getRights())
        {
            // The resolving getter: a live BM target comes back resolved, a deleted one stays a proxy.
            EObject target = objectRights.getObject();
            String targetLabel = target != null && target.eIsProxy() ? addressOrUri(target) : fqnOf(target);
            if (target != null && !target.eIsProxy())
            {
                collectRlsFields(roleFqn, targetLabel, objectRights, scan);
                continue;
            }
            Judgement judgement = judge(tx, target, blocker);
            if (judgement.verdict == Verdict.PRESENT)
            {
                collectRlsFields(roleFqn, targetLabel, objectRights, scan);
                continue;
            }
            Entry entry = new Entry(roleFqn, targetLabel, judgement, rightsOf(objectRights),
                hasRls(objectRights));
            scan.entries.add(entry);
            if (remove && judgement.verdict == Verdict.ABSENT)
            {
                toRemove.add(objectRights);
                removedHere.add(entry);
            }
        }
        if (!toRemove.isEmpty())
        {
            description.getRights().removeAll(toRemove);
            scan.removed.addAll(removedHere);
            if (role instanceof IBmObject && topFqnOf((IBmObject)role) != null)
            {
                scan.changedRoleFqns.add(roleFqn);
            }
            String rightsFqn = description instanceof IBmObject ? topFqnOf((IBmObject)description) : null;
            if (rightsFqn != null)
            {
                scan.changedRightsFqns.add(rightsFqn);
            }
        }
    }

    /**
     * Judges an unresolved (or {@code null}) target.
     *
     * @param tx the open transaction
     * @param raw the target read without proxy resolution
     * @param blocker a reason no absence can be proven here, or {@code null}
     * @return the judgement
     */
    static Judgement judge(IBmTransaction tx, EObject raw, String blocker)
    {
        if (raw == null)
        {
            return Judgement.undetermined("the entry names no target"); //$NON-NLS-1$
        }
        if (!raw.eIsProxy())
        {
            return Judgement.of(Verdict.PRESENT);
        }
        URI uri = ((InternalEObject)raw).eProxyURI();
        if (uri != null && BmUriUtil.isBmUri(uri))
        {
            try
            {
                if (tx.getObjectByUri(uri) != null)
                {
                    return Judgement.of(Verdict.PRESENT);
                }
            }
            catch (RuntimeException e) // NOSONAR an unreadable URI is a verdict, not a failure
            {
                return Judgement.undetermined("the reference URI cannot be looked up"); //$NON-NLS-1$
            }
        }
        String address = addressOf(uri);
        if (address == null)
        {
            return Judgement.undetermined("the reference carries no readable address"); //$NON-NLS-1$
        }
        Verdict byAddress = judgeAddress(tx, address);
        if (byAddress == Verdict.PRESENT)
        {
            return Judgement.undetermined("the target exists but the reference is stale; " //$NON-NLS-1$
                + "clean_project re-links it"); //$NON-NLS-1$
        }
        if (byAddress == Verdict.UNDETERMINED)
        {
            return Judgement.undetermined("the address cannot be walked in the model"); //$NON-NLS-1$
        }
        return blocker != null ? Judgement.undetermined(blocker) : Judgement.of(Verdict.ABSENT);
    }

    /**
     * Walks a metadata address ({@code Type.Name[.Kind.Name...]}) through the model.
     *
     * @return {@code ABSENT} when a segment of a walkable address is missing, {@code PRESENT} when
     *         every segment exists, {@code UNDETERMINED} when a segment cannot be judged (an unknown
     *         type or kind, a case-only match, a malformed address)
     */
    static Verdict judgeAddress(IBmTransaction tx, String address)
    {
        String[] parts = address.split("\\."); //$NON-NLS-1$
        if (!MetadataNodeResolver.isValidArity(parts.length))
        {
            return Verdict.UNDETERMINED;
        }
        String type = MetadataTypeUtils.toEnglishSingular(parts[0]);
        if (type == null || "Configuration".equals(type)) //$NON-NLS-1$
        {
            return Verdict.UNDETERMINED;
        }
        String topFqn = type + '.' + parts[1];
        IBmObject top = tx.getTopObjectByFqn(topFqn);
        if (top == null)
        {
            return tx.getTopObjectsByFqnIgnoreCase(topFqn).hasNext() ? Verdict.UNDETERMINED : Verdict.ABSENT;
        }
        if (!(top instanceof MdObject))
        {
            return Verdict.UNDETERMINED;
        }
        MdObject current = (MdObject)top;
        for (int i = 2; i + 1 < parts.length; i += 2)
        {
            String featureName = MetadataNodeResolver.featureNameForKind(parts[i]);
            EStructuralFeature feature = featureName != null ? current.eClass().getEStructuralFeature(featureName)
                : null;
            if (feature == null || !feature.isMany())
            {
                return Verdict.UNDETERMINED;
            }
            MdObject child = null;
            boolean caseOnly = false;
            for (Object element : (List<?>)current.eGet(feature))
            {
                if (element instanceof MdObject)
                {
                    String name = ((MdObject)element).getName();
                    if (parts[i + 1].equals(name))
                    {
                        child = (MdObject)element;
                        break;
                    }
                    caseOnly |= parts[i + 1].equalsIgnoreCase(name);
                }
            }
            if (child == null)
            {
                return caseOnly ? Verdict.UNDETERMINED : Verdict.ABSENT;
            }
            current = child;
        }
        return Verdict.PRESENT;
    }

    /**
     * Reads the metadata address a proxy URI names. Pure.
     *
     * @param uri the proxy URI (may be {@code null})
     * @return the address ({@code Catalog.Goods}, {@code Catalog.Goods.Attribute.Weight}), or
     *         {@code null} when the URI carries none
     */
    public static String addressOf(URI uri)
    {
        if (uri == null)
        {
            return null;
        }
        if (UNRESOLVED_SCHEME.equals(uri.scheme()))
        {
            String path = uri.path();
            return path != null && path.length() > 1 ? path.substring(1) : null;
        }
        String fragment = uri.fragment();
        if (fragment != null && fragment.contains(LAZY_LINK_MARKER) && fragment.contains("::")) //$NON-NLS-1$
        {
            String name = fragment.substring(fragment.lastIndexOf("::") + 2); //$NON-NLS-1$
            return name.isEmpty() ? null : name;
        }
        if (BmUriUtil.isBmUri(uri) && BmUriUtil.isTopObjectUri(uri))
        {
            return topFqnOf(uri);
        }
        return null;
    }

    /** @return the top-object FQN of a BM URI, or {@code null}. Pure. */
    static String topFqnOf(URI uri)
    {
        if (uri == null || !BmUriUtil.isBmUri(uri))
        {
            return null;
        }
        String fqn = BmUriUtil.extractTopObjectFqn(uri);
        return fqn == null || fqn.isEmpty() ? null : fqn;
    }

    /**
     * A human label for a target that did not resolve: its address, else the raw URI.
     *
     * @param raw an unresolved proxy
     * @return the label
     */
    public static String addressOrUri(EObject raw)
    {
        URI uri = raw instanceof InternalEObject ? ((InternalEObject)raw).eProxyURI() : null;
        String address = addressOf(uri);
        if (address != null)
        {
            return address;
        }
        return uri != null ? uri.toString() : raw.eClass().getName();
    }

    /**
     * A human label for an RLS field that did not resolve: the field name its DB-view path still
     * carries ({@code ...#/dbViewDefs/mainView/fields:AttrA} gives {@code AttrA}), else
     * {@link #addressOrUri}.
     *
     * @param field an unresolved field proxy
     * @return the label
     */
    public static String fieldLabel(EObject field)
    {
        URI uri = field instanceof InternalEObject ? ((InternalEObject)field).eProxyURI() : null;
        return fieldNameOf(uri) != null ? fieldNameOf(uri) : addressOrUri(field);
    }

    /** @return the field name after the last {@code fields:} of a proxy fragment, or {@code null}. Pure. */
    static String fieldNameOf(URI uri)
    {
        String fragment = uri != null ? uri.fragment() : null;
        int at = fragment != null ? fragment.lastIndexOf(FIELDS_SEGMENT) : -1;
        if (at < 0)
        {
            return null;
        }
        String name = fragment.substring(at + FIELDS_SEGMENT.length());
        return name.isEmpty() || name.indexOf('/') >= 0 ? null : name;
    }

    /** @return the RLS fields of {@code rls} that stay proxies after resolution */
    public static List<EObject> unresolvedFields(Rls rls)
    {
        List<EObject> result = new ArrayList<>();
        for (Object field : rls.getFields())
        {
            if (field instanceof EObject && ((EObject)field).eIsProxy())
            {
                result.add((EObject)field);
            }
        }
        return result;
    }

    private static void collectRlsFields(String roleFqn, String targetLabel, ObjectRights objectRights,
        Scan scan)
    {
        for (ObjectRight right : objectRights.getRights())
        {
            for (Rls rls : right.getRestrictionsByCondition())
            {
                for (EObject field : unresolvedFields(rls))
                {
                    scan.rlsFields.add(new RlsField(roleFqn, targetLabel, rightName(right), fieldLabel(field)));
                }
            }
        }
    }

    private static List<String> rightsOf(ObjectRights objectRights)
    {
        List<String> rights = new ArrayList<>();
        for (ObjectRight right : objectRights.getRights())
        {
            rights.add(rightName(right) + '=' + RoleRightsReader.rightValueLabel(right.getValue()));
        }
        return rights;
    }

    private static boolean hasRls(ObjectRights objectRights)
    {
        for (ObjectRight right : objectRights.getRights())
        {
            if (!right.getRestrictionsByCondition().isEmpty())
            {
                return true;
            }
        }
        return false;
    }

    private static String rightName(ObjectRight right)
    {
        return RoleRightsReader.rightNameOf(right.getRight(), null);
    }

    private static String fqnOf(EObject object)
    {
        String fqn = object instanceof IBmObject ? topFqnOf((IBmObject)object) : null;
        if (fqn != null)
        {
            return fqn;
        }
        return object != null ? object.eClass().getName() : NO_TARGET;
    }

    /** @return the FQN of the top object {@code bm} belongs to, or {@code null} when it has none */
    private static String topFqnOf(IBmObject bm)
    {
        try
        {
            IBmObject top = bm.bmIsTop() ? bm : bm.bmGetTopObject();
            String fqn = top != null ? top.bmGetFqn() : null;
            return fqn == null || fqn.isEmpty() ? null : fqn;
        }
        catch (RuntimeException e) // NOSONAR a detached object has no FQN; the caller falls back
        {
            return null;
        }
    }
}
