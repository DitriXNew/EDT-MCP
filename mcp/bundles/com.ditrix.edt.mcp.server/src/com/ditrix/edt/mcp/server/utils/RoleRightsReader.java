/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.mcore.DuallyNamedElement;
import com._1c.g5.v8.dt.metadata.mdclass.AbstractRoleDescription;
import com._1c.g5.v8.dt.rights.model.ObjectRight;
import com._1c.g5.v8.dt.rights.model.ObjectRights;
import com._1c.g5.v8.dt.rights.model.RestrictionTemplate;
import com._1c.g5.v8.dt.rights.model.Right;
import com._1c.g5.v8.dt.rights.model.RightValue;
import com._1c.g5.v8.dt.rights.model.Rls;
import com._1c.g5.v8.dt.rights.model.RoleDescription;

/**
 * Shared READER for a 1C {@code Role}'s access-rights matrix: it renders the editable
 * {@code com._1c.g5.v8.dt.rights.model.RoleDescription} (the concrete access matrix that
 * {@code Role.getRights()} points to) to a full Markdown document - the three role-property booleans, an
 * object-&gt;right matrix, an RLS (row-level security) restrictions section and an RLS-templates section.
 *
 * <p>The rights model is a cross-model resource of the {@code Role} top object, so the supplied
 * {@link RoleDescription} EObject (and every {@link ObjectRights}/{@link ObjectRight}/{@link Rls} it
 * contains) must still be inside its BM read transaction when {@link #render} runs. This mirrors
 * {@link FormStructureReader} (the shared form reader): the single home for the role-read logic that
 * {@code get_metadata_details} (a Role FQN renders its rights) uses.</p>
 *
 * <p>By default the matrix lists only the objects that carry an AUTHORED (non-default) cell - a right
 * whose value is {@code SET}/{@code UNSET} or an object bearing an RLS restriction - plus a summary count;
 * a designer's untouched {@code PROVIDED} cell is the metamodel default and is noise, so it is skipped
 * (mirroring {@code FormStructureReader}'s eIsSet filter). The {@code full} flag renders every object that
 * has any right cell. The matrix is paginated by object to keep a large role's response small.</p>
 *
 * <p>The three pure helpers - {@link #isNonDefault}, {@link #rightValueLabel}, {@link #rightNameOf} - are
 * Display-free and unit-tested directly; the {@link #render} entry point walks the live (transaction-bound)
 * model.</p>
 */
public final class RoleRightsReader
{
    /** The Russian language CODE; selects the {@code nameRu} right/field name over the English {@code name}. */
    private static final String LANG_RU = "ru"; //$NON-NLS-1$

    /** Default number of matrix OBJECTS rendered per page when the caller does not render {@code full}. */
    private static final int DEFAULT_OBJECT_LIMIT = 100;

    /** Upper bound on matrix objects rendered even in {@code full} mode, guarding a pathological role. */
    private static final int MAX_OBJECT_LIMIT = 1000;

    /** Label for a {@link RightValue#SET} cell (the right is explicitly allowed). */
    static final String LABEL_ALLOWED = "allowed"; //$NON-NLS-1$

    /** Label for a {@link RightValue#UNSET} cell (the right is explicitly denied). */
    static final String LABEL_DENIED = "denied"; //$NON-NLS-1$

    /** Label for a {@link RightValue#PROVIDED} cell (the right falls back to its default/inherited value). */
    static final String LABEL_DEFAULT = "default"; //$NON-NLS-1$

    private RoleRightsReader()
    {
        // utility class
    }

    /**
     * @return {@code true} when {@code roleRights} is the concrete editable {@link RoleDescription} access
     *         matrix (not {@code null} and not the bare {@code AbstractRoleDescription} marker). Callers
     *         guard on this before rendering, so a role with no editable rights model degrades to a note.
     */
    public static boolean hasRightsMatrix(AbstractRoleDescription roleRights)
    {
        return roleRights instanceof RoleDescription;
    }

    /**
     * Renders the FULL role-rights document to Markdown: the three role-property booleans, the
     * object-&gt;right matrix (default: only objects with a non-default/authored cell + a summary count,
     * paginated; {@code full} renders every object with any cell), an RLS restrictions section and an RLS
     * templates section. Pure aside from reading the supplied EObjects, which must still be inside the read
     * transaction when this runs. Never throws on a missing feature - a null matrix renders the note only.
     *
     * @param roleFqn the (normalized) Role FQN, for the heading (e.g. {@code Role.FullAccess})
     * @param roleRights the {@code Role.getRights()} value (may be {@code null} or a bare
     *            {@code AbstractRoleDescription}); only a concrete {@link RoleDescription} renders a matrix
     * @param full render every object with any right cell (true) or only the authored/non-default objects
     *            plus a summary count and pagination (false)
     * @param language the resolved right/field-name language CODE (e.g. {@code "en"}/{@code "ru"}, may be
     *            {@code null})
     * @param offset the 0-based object offset for the paginated matrix (ignored in {@code full} mode)
     * @return the Markdown document
     */
    public static String render(String roleFqn, AbstractRoleDescription roleRights, boolean full,
        String language, int offset)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("# Role Rights: ").append(roleFqn).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        if (!(roleRights instanceof RoleDescription))
        {
            // A Role with no editable rights model (e.g. an empty/legacy or not-yet-built role) has no
            // matrix to render; surface a note rather than an empty document.
            sb.append("_(this role has no editable rights model)_\n"); //$NON-NLS-1$
            return sb.toString();
        }
        RoleDescription description = (RoleDescription)roleRights;

        renderProperties(sb, description);
        renderMatrix(sb, description, full, language, offset);
        renderRls(sb, description, language);
        renderTemplates(sb, description);
        return sb.toString();
    }

    /** Renders the {@code ## Properties} table (the three role-level booleans). */
    private static void renderProperties(StringBuilder sb, RoleDescription description)
    {
        sb.append("## Properties\n\n"); //$NON-NLS-1$
        sb.append(MarkdownUtils.tableHeader("Property", "Value")); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(MarkdownUtils.tableRow("Set rights for new objects", //$NON-NLS-1$
            Boolean.toString(description.isSetForNewObjects())));
        sb.append(MarkdownUtils.tableRow("Set rights for attributes by default", //$NON-NLS-1$
            Boolean.toString(description.isSetForAttributesByDefault())));
        sb.append(MarkdownUtils.tableRow("Independent rights of child objects", //$NON-NLS-1$
            Boolean.toString(description.isIndependentRightsOfChildObjects())));
        sb.append('\n');
    }

    /**
     * Renders the {@code ## Rights matrix} table (Object / Right / Value): one row per authored cell,
     * grouped by object. By default only objects that carry at least one non-default cell are listed (with
     * a summary count and pagination); {@code full} lists every object with any cell. Objects are the paged
     * unit so a role with thousands of objects stays small.
     */
    private static void renderMatrix(StringBuilder sb, RoleDescription description, boolean full,
        String language, int offset)
    {
        sb.append("## Rights matrix\n\n"); //$NON-NLS-1$

        MatrixSelection selection = selectMatrixObjects(description, full);
        List<ObjectRights> selected = selection.selected;
        if (selected.isEmpty())
        {
            sb.append(full ? "_(no rights)_\n\n" : "_(no non-default rights)_\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        int total = selected.size();
        int limit = full ? MAX_OBJECT_LIMIT : DEFAULT_OBJECT_LIMIT;
        int from = full ? 0 : Math.max(0, offset);
        from = Math.min(from, total);
        int to = Math.min(from + limit, total);

        sb.append("**Objects with non-default rights:** ").append(selection.totalWithAuthored); //$NON-NLS-1$
        sb.append(matrixWindowNotice(from, to, total, full));
        sb.append("\n\n"); //$NON-NLS-1$

        sb.append(MarkdownUtils.tableHeader("Object", "Right", "Value")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        renderMatrixRows(sb, selected, from, to, full, language);
        sb.append('\n');
    }

    /**
     * Selects the {@link ObjectRights} to render and counts the objects carrying an authored cell. In
     * {@code full} mode every object with any right cell is selected; otherwise only the objects that
     * carry an authored (non-default) cell - an explicit {@code SET}/{@code UNSET} or an RLS. This is the
     * eIsSet filter: a designer's untouched {@code PROVIDED} cell is the metamodel default and is noise.
     */
    private static MatrixSelection selectMatrixObjects(RoleDescription description, boolean full)
    {
        List<ObjectRights> selected = new ArrayList<>();
        int totalWithAuthored = 0;
        for (ObjectRights objectRights : description.getRights())
        {
            boolean authored = hasAuthoredCell(objectRights);
            if (authored)
            {
                totalWithAuthored++;
            }
            if (full ? !objectRights.getRights().isEmpty() : authored)
            {
                selected.add(objectRights);
            }
        }
        return new MatrixSelection(selected, totalWithAuthored);
    }

    /**
     * Appends one matrix row per rendered cell for the selected objects in the {@code [from, to)} window.
     * In the default (non-full) view the individual {@code PROVIDED} cells of an object that still
     * qualifies (via another {@code SET}/{@code UNSET} cell or an RLS) are dropped so the matrix shows
     * only what was authored; {@code full} mode keeps every cell.
     */
    private static void renderMatrixRows(StringBuilder sb, List<ObjectRights> selected, int from, int to,
        boolean full, String language)
    {
        for (int i = from; i < to; i++)
        {
            ObjectRights objectRights = selected.get(i);
            String objectFqn = objectFqnOf(objectRights.getObject());
            for (ObjectRight right : objectRights.getRights())
            {
                if (!full && !isNonDefault(right))
                {
                    continue;
                }
                sb.append(MarkdownUtils.tableRow(objectFqn, rightNameOf(right.getRight(), language),
                    rightValueLabel(right.getValue())));
            }
        }
    }

    /** The selected matrix objects plus the count of objects carrying an authored (non-default) cell. */
    private static final class MatrixSelection
    {
        final List<ObjectRights> selected;
        final int totalWithAuthored;

        MatrixSelection(List<ObjectRights> selected, int totalWithAuthored)
        {
            this.selected = selected;
            this.totalWithAuthored = totalWithAuthored;
        }
    }

    /**
     * @return the object-window notice appended after the matrix count, or an empty string when the whole
     *         selection fits on one page. It reports the 1-based object window actually shown out of the
     *         total and, when more objects remain (default view only), the concrete next {@code
     *         roleObjectOffset} to page forward and the {@code full: true} escape hatch - so a role that
     *         authors more than {@link #DEFAULT_OBJECT_LIMIT} objects is fully reachable. Pure/Display-free.
     *
     * @param from the 0-based index of the first object shown (inclusive)
     * @param to the 0-based index just past the last object shown (exclusive)
     * @param total the total number of selected objects
     * @param full whether the caller rendered the {@code full} view (which is not paged by offset)
     */
    static String matrixWindowNotice(int from, int to, int total, boolean full)
    {
        if (to - from >= total)
        {
            return ""; //$NON-NLS-1$
        }
        StringBuilder notice = new StringBuilder();
        if (from >= to)
        {
            // Degenerate over-page request (offset past the last object): no rows on this page.
            notice.append(" (offset past the last of ").append(total) //$NON-NLS-1$
                .append(" objects; lower roleObjectOffset or use full:true)"); //$NON-NLS-1$
            return notice.toString();
        }
        notice.append(" (showing objects ").append(from + 1).append('-').append(to) //$NON-NLS-1$
            .append(" of ").append(total); //$NON-NLS-1$
        if (!full && to < total)
        {
            // More authored objects remain past this page; tell the caller exactly how to reach them.
            notice.append("; pass roleObjectOffset=").append(to) //$NON-NLS-1$
                .append(" for the next page, or full:true to render every object"); //$NON-NLS-1$
        }
        else if (full)
        {
            // Full mode is capped at MAX_OBJECT_LIMIT and is not offset-paged; name the cap.
            notice.append(", capped at ").append(MAX_OBJECT_LIMIT); //$NON-NLS-1$
        }
        notice.append(')');
        return notice.toString();
    }

    /**
     * Renders the {@code ## Row-level security (RLS)} section: one row per {@link Rls} restriction across
     * every object+right, with the restricted fields (empty = whole-object) and the condition text. The
     * condition (a 1C query fragment) and the field list can contain {@code |} / newlines, so every cell
     * goes through the shared escaping table builder.
     */
    private static void renderRls(StringBuilder sb, RoleDescription description, String language)
    {
        sb.append("## Row-level security (RLS)\n\n"); //$NON-NLS-1$
        List<String[]> rows = new ArrayList<>();
        for (ObjectRights objectRights : description.getRights())
        {
            String objectFqn = objectFqnOf(objectRights.getObject());
            for (ObjectRight right : objectRights.getRights())
            {
                String rightName = rightNameOf(right.getRight(), language);
                for (Rls rls : right.getRestrictionsByCondition())
                {
                    rows.add(new String[] {objectFqn, rightName, rlsFieldsOf(rls, language),
                        rls.getCondition() != null ? rls.getCondition() : ""}); //$NON-NLS-1$
                }
            }
        }
        if (rows.isEmpty())
        {
            sb.append("_(no RLS restrictions)_\n\n"); //$NON-NLS-1$
            return;
        }
        sb.append(MarkdownUtils.tableHeader("Object", "Right", "Fields", "Condition")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        for (String[] row : rows)
        {
            sb.append(MarkdownUtils.tableRow(row[0], row[1], row[2], row[3]));
        }
        sb.append('\n');
    }

    /**
     * Renders the {@code ## RLS templates} section (Name / Condition). The condition text can contain
     * {@code |} / newlines, so every cell is escaped by the shared table builder.
     */
    private static void renderTemplates(StringBuilder sb, RoleDescription description)
    {
        sb.append("## RLS templates\n\n"); //$NON-NLS-1$
        List<RestrictionTemplate> templates = description.getTemplates();
        if (templates.isEmpty())
        {
            sb.append("_(no RLS templates)_\n\n"); //$NON-NLS-1$
            return;
        }
        sb.append(MarkdownUtils.tableHeader("Name", "Condition")); //$NON-NLS-1$ //$NON-NLS-2$
        for (RestrictionTemplate template : templates)
        {
            sb.append(MarkdownUtils.tableRow(template.getName() != null ? template.getName() : "", //$NON-NLS-1$
                template.getCondition() != null ? template.getCondition() : "")); //$NON-NLS-1$
        }
        sb.append('\n');
    }

    // ==================== Pure helpers (Display-free, unit-tested directly) ====================

    /**
     * @return {@code true} when the object carries at least one authored (non-default) cell - a right
     *         whose value is not {@code PROVIDED}, or a right bearing an RLS restriction. A designer's
     *         untouched {@code PROVIDED} cell with no RLS is the metamodel default and does not qualify.
     */
    static boolean hasAuthoredCell(ObjectRights objectRights)
    {
        if (objectRights == null)
        {
            return false;
        }
        for (ObjectRight right : objectRights.getRights())
        {
            if (isNonDefault(right))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * @return {@code true} when the cell is authored/non-default - its value is not {@code PROVIDED}
     *         (i.e. an explicit {@code SET}/{@code UNSET}), or it carries an RLS restriction. Mirrors the
     *         form reader's {@code eIsSet} filter: a {@code PROVIDED} cell with no RLS is the untouched
     *         default and is noise. Null-safe (a {@code null} cell is treated as default).
     */
    static boolean isNonDefault(ObjectRight right)
    {
        if (right == null)
        {
            return false;
        }
        if (right.getValue() != null && right.getValue() != RightValue.PROVIDED)
        {
            return true;
        }
        return !right.getRestrictionsByCondition().isEmpty();
    }

    /**
     * @return the display label for a {@link RightValue} tri-state: {@code SET}-&gt;{@code allowed},
     *         {@code UNSET}-&gt;{@code denied}, {@code PROVIDED}/{@code null}-&gt;{@code default}. The
     *         value is NOT a boolean - {@code PROVIDED} means the right falls back to its inherited/default
     *         value rather than being explicitly allowed or denied.
     */
    static String rightValueLabel(RightValue value)
    {
        if (value == RightValue.SET)
        {
            return LABEL_ALLOWED;
        }
        if (value == RightValue.UNSET)
        {
            return LABEL_DENIED;
        }
        return LABEL_DEFAULT;
    }

    /**
     * @return the right's name for the given language CODE - {@code nameRu} for {@code "ru"}, otherwise the
     *         English {@code name} - falling back to the other when the preferred one is blank, then to
     *         {@code "(unnamed)"}. The right name is bilingual (a {@code DuallyNamedElement}); the synonym
     *         map is NOT involved (a right has no synonym). Null-safe.
     */
    static String rightNameOf(Right right, String language)
    {
        if (right == null)
        {
            return "(unnamed)"; //$NON-NLS-1$
        }
        boolean ru = LANG_RU.equalsIgnoreCase(language);
        String preferred = safe(ru ? right.getNameRu() : right.getName());
        if (!preferred.isEmpty())
        {
            return preferred;
        }
        String other = safe(ru ? right.getName() : right.getNameRu());
        return other.isEmpty() ? "(unnamed)" : other; //$NON-NLS-1$
    }

    // ==================== transaction-bound EObject helpers ====================

    /**
     * @return the guarded object's metadata FQN - its BM FQN when it is a top object, else the FQN of the
     *         top object it belongs to (a sub-object such as an attribute), else its EClass name. The
     *         object is transaction-bound, so this must run inside the read boundary.
     */
    private static String objectFqnOf(EObject object)
    {
        if (object == null)
        {
            return "(unknown)"; //$NON-NLS-1$
        }
        if (object instanceof IBmObject)
        {
            try
            {
                IBmObject bm = (IBmObject)object;
                if (bm.bmIsTop())
                {
                    return safeFqn(bm.bmGetFqn(), object);
                }
                IBmObject top = bm.bmGetTopObject();
                if (top != null)
                {
                    return safeFqn(top.bmGetFqn(), object);
                }
            }
            catch (RuntimeException e) // NOSONAR bmGetFqn asserts the object is BM-attached; a detached/odd object (e.g. a headless unit test, or an unusual model state) degrades to its eClass label instead of crashing the whole render
            {
                // fall through to the eClass fallback below
            }
        }
        return object.eClass().getName();
    }

    private static String safeFqn(String fqn, EObject fallback)
    {
        return fqn != null && !fqn.isEmpty() ? fqn : fallback.eClass().getName();
    }

    /**
     * @return the comma-joined names of the RLS-restricted fields for the given language CODE, or
     *         {@code "(whole object)"} when the restriction applies to the whole object (no fields). Each
     *         field is a {@link DuallyNamedElement} (a {@code DbViewFieldDef}), read bilingually without a
     *         compile-time dependency on the dbview package.
     */
    private static String rlsFieldsOf(Rls rls, String language)
    {
        List<String> names = new ArrayList<>();
        boolean ru = LANG_RU.equalsIgnoreCase(language);
        for (Object field : rls.getFields())
        {
            if (field instanceof DuallyNamedElement)
            {
                DuallyNamedElement named = (DuallyNamedElement)field;
                String preferred = safe(ru ? named.getNameRu() : named.getName());
                String fallback = safe(ru ? named.getName() : named.getNameRu());
                names.add(preferred.isEmpty() ? fallback : preferred);
            }
        }
        if (names.isEmpty())
        {
            return "(whole object)"; //$NON-NLS-1$
        }
        return String.join(", ", names); //$NON-NLS-1$
    }

    private static String safe(String value)
    {
        return value != null ? value : ""; //$NON-NLS-1$
    }
}
