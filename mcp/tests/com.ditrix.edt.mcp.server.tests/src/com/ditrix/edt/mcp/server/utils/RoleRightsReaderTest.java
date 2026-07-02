/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.rights.model.ObjectRight;
import com._1c.g5.v8.dt.rights.model.ObjectRights;
import com._1c.g5.v8.dt.rights.model.RestrictionTemplate;
import com._1c.g5.v8.dt.rights.model.Right;
import com._1c.g5.v8.dt.rights.model.RightValue;
import com._1c.g5.v8.dt.rights.model.Rls;
import com._1c.g5.v8.dt.rights.model.RightsFactory;
import com._1c.g5.v8.dt.rights.model.RoleDescription;

/**
 * Tests the pure role-read logic of {@link RoleRightsReader}: the tri-state {@code RightValue} label
 * ({@code rightValueLabel}), the bilingual right-name render ({@code rightNameOf}), the eIsSet/non-default
 * filter ({@code isNonDefault} / {@code hasAuthoredCell}), the concrete-matrix guard
 * ({@code hasRightsMatrix}) and the Markdown renderer ({@link RoleRightsReader#render}), exercised against
 * an in-memory {@link RoleDescription} built with {@link RightsFactory} (the same headless-EMF pattern the
 * metadata formatters use). The deep read of a real role model with genuine object FQNs and per-field RLS
 * is covered by the e2e suite (get_metadata_details on a Role FQN) against a live EDT.
 *
 * <p>Russian tokens are built from code points (the same {@code fromCp} pattern the sibling
 * {@code RoleRightsWriterTest} uses) so a non-UTF-8 Tycho build cannot corrupt the literal.</p>
 */
public class RoleRightsReaderTest
{
    // "Чтение" (Read) as code points - pure ASCII source, corruption-proof under any build encoding.
    private static final String RU_READ = fromCp(0x0427, 0x0442, 0x0435, 0x043d, 0x0438, 0x0435);

    private static String fromCp(int... cps)
    {
        return new String(cps, 0, cps.length);
    }

    // ==================== rightValueLabel: the tri-state (NOT boolean) ====================

    @Test
    public void testRightValueLabelSetIsAllowed()
    {
        assertEquals("allowed", RoleRightsReader.rightValueLabel(RightValue.SET)); //$NON-NLS-1$
    }

    @Test
    public void testRightValueLabelUnsetIsDenied()
    {
        assertEquals("denied", RoleRightsReader.rightValueLabel(RightValue.UNSET)); //$NON-NLS-1$
    }

    @Test
    public void testRightValueLabelProvidedIsDefault()
    {
        // PROVIDED is the tri-state's "fall back to the inherited/default value", NOT a boolean false.
        assertEquals("default", RoleRightsReader.rightValueLabel(RightValue.PROVIDED)); //$NON-NLS-1$
    }

    @Test
    public void testRightValueLabelNullIsDefault()
    {
        assertEquals("default", RoleRightsReader.rightValueLabel(null)); //$NON-NLS-1$
    }

    // ==================== rightNameOf: bilingual (name / nameRu) render ====================

    @Test
    public void testRightNameOfEnglishByCode()
    {
        Right right = newRight("Read", RU_READ); //$NON-NLS-1$
        // The right name is selected by language CODE: "en" returns the English name, "ru" the nameRu.
        assertEquals("Read", RoleRightsReader.rightNameOf(right, "en")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(RU_READ, RoleRightsReader.rightNameOf(right, "ru")); //$NON-NLS-1$
    }

    @Test
    public void testRightNameOfFallsBackWhenPreferredBlank()
    {
        // With only a Russian name, an "en" request falls back to the Russian one rather than blank.
        Right right = newRight(null, RU_READ);
        assertEquals(RU_READ, RoleRightsReader.rightNameOf(right, "en")); //$NON-NLS-1$
    }

    @Test
    public void testRightNameOfNullRightIsUnnamed()
    {
        assertEquals("(unnamed)", RoleRightsReader.rightNameOf(null, "en")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== isNonDefault / hasAuthoredCell: the eIsSet filter ====================

    @Test
    public void testIsNonDefaultSetIsAuthored()
    {
        assertTrue(RoleRightsReader.isNonDefault(newObjectRight("Read", RightValue.SET))); //$NON-NLS-1$
    }

    @Test
    public void testIsNonDefaultUnsetIsAuthored()
    {
        assertTrue(RoleRightsReader.isNonDefault(newObjectRight("Read", RightValue.UNSET))); //$NON-NLS-1$
    }

    @Test
    public void testIsNonDefaultProvidedNoRlsIsDefault()
    {
        // A PROVIDED cell with no RLS is the untouched metamodel default and does NOT qualify.
        assertFalse(RoleRightsReader.isNonDefault(newObjectRight("Read", RightValue.PROVIDED))); //$NON-NLS-1$
    }

    @Test
    public void testIsNonDefaultProvidedWithRlsIsAuthored()
    {
        // A PROVIDED cell that carries an RLS restriction IS authored (an RLS is real authored state).
        ObjectRight right = newObjectRight("Read", RightValue.PROVIDED); //$NON-NLS-1$
        right.getRestrictionsByCondition().add(newRls("A.Field = &X")); //$NON-NLS-1$
        assertTrue(RoleRightsReader.isNonDefault(right));
    }

    @Test
    public void testIsNonDefaultNullIsDefault()
    {
        assertFalse(RoleRightsReader.isNonDefault(null));
    }

    @Test
    public void testHasAuthoredCellDetectsAnyAuthoredCell()
    {
        ObjectRights objectRights = RightsFactory.eINSTANCE.createObjectRights();
        objectRights.getRights().add(newObjectRight("Read", RightValue.PROVIDED)); //$NON-NLS-1$
        assertFalse(RoleRightsReader.hasAuthoredCell(objectRights));
        objectRights.getRights().add(newObjectRight("Update", RightValue.SET)); //$NON-NLS-1$
        assertTrue(RoleRightsReader.hasAuthoredCell(objectRights));
    }

    @Test
    public void testHasAuthoredCellNullIsFalse()
    {
        assertFalse(RoleRightsReader.hasAuthoredCell(null));
    }

    // ==================== hasRightsMatrix / render: the concrete-matrix guard ====================

    @Test
    public void testHasRightsMatrixNullIsFalse()
    {
        // Role.getRights() may be null (no editable rights model) — the guard must be null-safe.
        assertFalse(RoleRightsReader.hasRightsMatrix(null));
    }

    @Test
    public void testHasRightsMatrixConcreteIsTrue()
    {
        assertTrue(RoleRightsReader.hasRightsMatrix(RightsFactory.eINSTANCE.createRoleDescription()));
    }

    @Test
    public void testRenderNullMatrixRendersNoteNotThrows()
    {
        // A null (or bare AbstractRoleDescription marker) rights value renders a note, never an empty
        // document and never a throw.
        String md = RoleRightsReader.render("Role.FullAccess", null, false, "en", 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(md.contains("# Role Rights: Role.FullAccess")); //$NON-NLS-1$
        assertTrue(md.contains("no editable rights model")); //$NON-NLS-1$
    }

    // ==================== render: properties / matrix / RLS / templates ====================

    @Test
    public void testRenderProperties()
    {
        RoleDescription description = RightsFactory.eINSTANCE.createRoleDescription();
        description.setSetForNewObjects(true);
        description.setSetForAttributesByDefault(false);
        description.setIndependentRightsOfChildObjects(true);

        String md = RoleRightsReader.render("Role.FullAccess", description, false, "en", 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(md.contains("## Properties")); //$NON-NLS-1$
        assertTrue(md.contains("| Set rights for new objects | true |")); //$NON-NLS-1$
        assertTrue(md.contains("| Set rights for attributes by default | false |")); //$NON-NLS-1$
        assertTrue(md.contains("| Independent rights of child objects | true |")); //$NON-NLS-1$
    }

    @Test
    public void testRenderMatrixDefaultShowsOnlyNonDefaultRows()
    {
        RoleDescription description = RightsFactory.eINSTANCE.createRoleDescription();
        ObjectRights catalog = newObjectRights("Read", RightValue.SET, "Update", RightValue.PROVIDED); //$NON-NLS-1$ //$NON-NLS-2$
        description.getRights().add(catalog);

        String md = RoleRightsReader.render("Role.FullAccess", description, false, "en", 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(md.contains("## Rights matrix")); //$NON-NLS-1$
        assertTrue(md.contains("**Objects with non-default rights:** 1")); //$NON-NLS-1$
        // The authored SET cell is shown; the untouched PROVIDED cell of the SAME object is dropped.
        assertTrue(md.contains("| Read | allowed |")); //$NON-NLS-1$
        assertFalse(md.contains("| Update | default |")); //$NON-NLS-1$
    }

    @Test
    public void testRenderMatrixFullShowsEveryCell()
    {
        RoleDescription description = RightsFactory.eINSTANCE.createRoleDescription();
        ObjectRights catalog = newObjectRights("Read", RightValue.SET, "Update", RightValue.PROVIDED); //$NON-NLS-1$ //$NON-NLS-2$
        description.getRights().add(catalog);

        String md = RoleRightsReader.render("Role.FullAccess", description, true, "en", 0); //$NON-NLS-1$ //$NON-NLS-2$
        // Full mode keeps every cell, including the PROVIDED (default) one.
        assertTrue(md.contains("| Read | allowed |")); //$NON-NLS-1$
        assertTrue(md.contains("| Update | default |")); //$NON-NLS-1$
    }

    // ==================== matrixWindowNotice: pagination window + next-offset hint ====================

    @Test
    public void testMatrixWindowNoticeEmptyWhenAllShown()
    {
        // The whole selection fits on one page -> no notice at all.
        assertEquals("", RoleRightsReader.matrixWindowNotice(0, 40, 40, false)); //$NON-NLS-1$
    }

    @Test
    public void testMatrixWindowNoticeFirstPageAdvisesNextOffset()
    {
        // Default view, first 100 of 250 shown -> report the window AND the concrete next roleObjectOffset
        // plus the full:true escape hatch, so objects 101+ are demonstrably reachable.
        String notice = RoleRightsReader.matrixWindowNotice(0, 100, 250, false);
        assertTrue(notice.contains("showing objects 1-100 of 250")); //$NON-NLS-1$
        assertTrue(notice.contains("roleObjectOffset=100")); //$NON-NLS-1$
        assertTrue(notice.contains("full:true")); //$NON-NLS-1$
    }

    @Test
    public void testMatrixWindowNoticeMiddlePageReportsWindow()
    {
        // A paged-in window (offset 100) reports the 1-based window it actually shows, not "showing 100".
        String notice = RoleRightsReader.matrixWindowNotice(100, 200, 250, false);
        assertTrue(notice.contains("showing objects 101-200 of 250")); //$NON-NLS-1$
        assertTrue(notice.contains("roleObjectOffset=200")); //$NON-NLS-1$
    }

    @Test
    public void testMatrixWindowNoticeLastPageHasNoNextOffset()
    {
        // On the final page there is nothing past it -> no next-offset hint.
        String notice = RoleRightsReader.matrixWindowNotice(200, 250, 250, false);
        assertTrue(notice.contains("showing objects 201-250 of 250")); //$NON-NLS-1$
        assertFalse(notice.contains("roleObjectOffset=")); //$NON-NLS-1$
    }

    @Test
    public void testMatrixWindowNoticeOverPageOffset()
    {
        // Offset past the last object (from clamped to total, empty page) -> a clear note, not "151-150".
        String notice = RoleRightsReader.matrixWindowNotice(150, 150, 150, false);
        assertTrue(notice.contains("offset past the last of 150 objects")); //$NON-NLS-1$
        assertFalse(notice.contains("151-150")); //$NON-NLS-1$
    }

    @Test
    public void testMatrixWindowNoticeFullModeNamesCap()
    {
        // Full mode is not offset-paged; when it is capped it names the cap rather than an offset hint.
        String notice = RoleRightsReader.matrixWindowNotice(0, 1000, 1500, true);
        assertTrue(notice.contains("showing objects 1-1000 of 1500")); //$NON-NLS-1$
        assertTrue(notice.contains("capped at 1000")); //$NON-NLS-1$
        assertFalse(notice.contains("roleObjectOffset=")); //$NON-NLS-1$
    }

    @Test
    public void testRenderMatrixNoNonDefaultRightsNote()
    {
        RoleDescription description = RightsFactory.eINSTANCE.createRoleDescription();
        ObjectRights catalog = newObjectRights("Read", RightValue.PROVIDED); //$NON-NLS-1$
        description.getRights().add(catalog);

        String md = RoleRightsReader.render("Role.FullAccess", description, false, "en", 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(md.contains("_(no non-default rights)_")); //$NON-NLS-1$
    }

    @Test
    public void testRenderRlsWholeObjectAndEscapedCondition()
    {
        RoleDescription description = RightsFactory.eINSTANCE.createRoleDescription();
        ObjectRight read = newObjectRight("Read", RightValue.SET); //$NON-NLS-1$
        // A condition with a raw '|' would break the table; the shared builder escapes it. Empty fields =
        // whole-object restriction.
        read.getRestrictionsByCondition().add(newRls("A.X = 1 OR A.Y | broken")); //$NON-NLS-1$
        ObjectRights catalog = RightsFactory.eINSTANCE.createObjectRights();
        catalog.getRights().add(read);
        description.getRights().add(catalog);

        String md = RoleRightsReader.render("Role.FullAccess", description, false, "en", 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(md.contains("## Row-level security (RLS)")); //$NON-NLS-1$
        assertTrue(md.contains("| (whole object) |")); //$NON-NLS-1$
        // The raw '|' inside the condition is escaped, so it cannot break the table layout.
        assertTrue(md.contains("A.Y \\| broken")); //$NON-NLS-1$
    }

    @Test
    public void testRenderRlsEmptyNote()
    {
        RoleDescription description = RightsFactory.eINSTANCE.createRoleDescription();
        String md = RoleRightsReader.render("Role.FullAccess", description, false, "en", 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(md.contains("_(no RLS restrictions)_")); //$NON-NLS-1$
    }

    @Test
    public void testRenderTemplatesEscapesCondition()
    {
        RoleDescription description = RightsFactory.eINSTANCE.createRoleDescription();
        RestrictionTemplate template = RightsFactory.eINSTANCE.createRestrictionTemplate();
        template.setName("ByCompany"); //$NON-NLS-1$
        template.setCondition("WHERE Company IN (&List) | tail"); //$NON-NLS-1$
        description.getTemplates().add(template);

        String md = RoleRightsReader.render("Role.FullAccess", description, false, "en", 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(md.contains("## RLS templates")); //$NON-NLS-1$
        assertTrue(md.contains("| ByCompany |")); //$NON-NLS-1$
        // The '|' in the template condition is escaped by the shared builder.
        assertTrue(md.contains("(&List) \\| tail")); //$NON-NLS-1$
    }

    @Test
    public void testRenderTemplatesEmptyNote()
    {
        RoleDescription description = RightsFactory.eINSTANCE.createRoleDescription();
        String md = RoleRightsReader.render("Role.FullAccess", description, false, "en", 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(md.contains("_(no RLS templates)_")); //$NON-NLS-1$
    }

    // ==================== in-memory model builders (headless EMF) ====================

    private static Right newRight(String name, String nameRu)
    {
        Right right = RightsFactory.eINSTANCE.createRight();
        right.setName(name);
        right.setNameRu(nameRu);
        return right;
    }

    private static ObjectRight newObjectRight(String rightName, RightValue value)
    {
        ObjectRight objectRight = RightsFactory.eINSTANCE.createObjectRight();
        objectRight.setRight(newRight(rightName, rightName));
        objectRight.setValue(value);
        return objectRight;
    }

    /**
     * Builds an {@link ObjectRights} for a fresh in-memory Catalog carrying the given right/value pairs
     * ({@code name1, value1, name2, value2, ...}). The object's EClass name ("Catalog") is what the reader
     * renders for the Object column when the object is not a transaction-bound top object.
     */
    private static ObjectRights newObjectRights(Object... rightValuePairs)
    {
        ObjectRights objectRights = RightsFactory.eINSTANCE.createObjectRights();
        objectRights.setObject(MdClassFactory.eINSTANCE.createCatalog());
        for (int i = 0; i + 1 < rightValuePairs.length; i += 2)
        {
            objectRights.getRights().add(
                newObjectRight((String)rightValuePairs[i], (RightValue)rightValuePairs[i + 1]));
        }
        return objectRights;
    }

    private static Rls newRls(String condition)
    {
        Rls rls = RightsFactory.eINSTANCE.createRls();
        rls.setCondition(condition);
        return rls;
    }
}
