/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import org.junit.Test;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.InternalEObject;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.metadata.dbview.DbViewFactory;
import com._1c.g5.v8.dt.metadata.dbview.DbViewFieldDef;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogCommand;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogTabularSection;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.DocumentTabularSection;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegisterAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegisterDimension;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegisterResource;
import com._1c.g5.v8.dt.metadata.mdclass.IntegrationService;
import com._1c.g5.v8.dt.metadata.mdclass.IntegrationServiceChannel;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.StandardAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.TabularSectionAttribute;
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

    // ==================== unresolved targets (deleted outside EDT) ====================

    @Test
    public void testRenderNamesAnUnresolvedTargetByItsAddressNotItsEClass()
    {
        // Before: an entry on a deleted object rendered as "Catalog" - the EClass, not the address.
        RoleDescription description = RightsFactory.eINSTANCE.createRoleDescription();
        ObjectRights gone = newObjectRights("Read", RightValue.SET); //$NON-NLS-1$
        gone.setObject(proxy("unresolved:/Catalog.Gone")); //$NON-NLS-1$
        description.getRights().add(gone);

        String md = RoleRightsReader.render("Role.R", description, false, "en", 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(md, md.contains("| Catalog.Gone (unresolved) | Read | allowed |")); //$NON-NLS-1$
        assertFalse(md, md.contains("| Catalog | Read |")); //$NON-NLS-1$
        assertTrue(md, md.contains("**1 entry(ies) marked (unresolved)**")); //$NON-NLS-1$
        assertTrue(md, md.contains("resync_to_disk")); //$NON-NLS-1$
    }

    @Test
    public void testUnresolvedEntryWithOnlyDefaultCellsStillGetsTheNotice()
    {
        // Filtered out of the default matrix (PROVIDED, no RLS), yet it still blocks EDT's tasks.
        RoleDescription description = RightsFactory.eINSTANCE.createRoleDescription();
        ObjectRights gone = newObjectRights("Read", RightValue.PROVIDED); //$NON-NLS-1$
        gone.setObject(proxy("unresolved:/Catalog.Gone")); //$NON-NLS-1$
        description.getRights().add(gone);

        String md = RoleRightsReader.render("Role.R", description, false, "en", 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(md, md.contains("_(no non-default rights)_")); //$NON-NLS-1$
        assertTrue(md, md.contains("**1 entry(ies) marked (unresolved)**")); //$NON-NLS-1$
        assertTrue(md, md.contains("resync_to_disk")); //$NON-NLS-1$
    }

    @Test
    public void testNoUnresolvedNoticeForALiveRole()
    {
        assertEquals("", RoleRightsReader.unresolvedNotice(0)); //$NON-NLS-1$
        RoleDescription description = RightsFactory.eINSTANCE.createRoleDescription();
        description.getRights().add(newObjectRights("Read", RightValue.SET)); //$NON-NLS-1$
        String md = RoleRightsReader.render("Role.R", description, false, "en", 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(md, md.contains("(unresolved)")); //$NON-NLS-1$
    }

    @Test
    public void testUnresolvedRlsFieldIsMarkedNotBlank()
    {
        // Before: a field whose attribute was deleted rendered as an EMPTY Fields cell.
        RoleDescription description = RightsFactory.eINSTANCE.createRoleDescription();
        ObjectRights catalog = newObjectRights("Read", RightValue.SET); //$NON-NLS-1$
        Rls rls = RightsFactory.eINSTANCE.createRls();
        rls.setCondition("WHERE AttrA = 1"); //$NON-NLS-1$
        DbViewFieldDef field = DbViewFactory.eINSTANCE.createDbViewFieldFieldDef();
        ((InternalEObject)field).eSetProxyURI(URI.createURI("unresolved:/AttrA")); //$NON-NLS-1$
        rls.getFields().add(field);
        catalog.getRights().get(0).getRestrictionsByCondition().add(rls);
        description.getRights().add(catalog);

        String md = RoleRightsReader.render("Role.R", description, false, "en", 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(md, md.contains("| AttrA (unresolved) | WHERE AttrA = 1 |")); //$NON-NLS-1$
    }

    // ==================== #685: a subordinate target is named by its full address ====================

    @Test
    public void testAnAttachedAttributeIsNamedByItsAddressNotByItsOwner()
    {
        // The issue's reproduction as BM attaches it: the catalog is a top object with a BM FQN, its
        // attribute is not. Before: the attribute row was named bmGetTopObject().bmGetFqn() - the
        // CATALOG's FQN - so the two rows could not be told apart.
        Catalog catalog = attachedTop(Catalog.class, MdClassPackage.Literals.CATALOG, "OrphProbeB", //$NON-NLS-1$
            "Catalog.OrphProbeB"); //$NON-NLS-1$
        CatalogAttribute kod2 = attachedChild(CatalogAttribute.class, MdClassPackage.Literals.CATALOG_ATTRIBUTE,
            "Kod2", catalog, MdClassPackage.Literals.CATALOG__ATTRIBUTES); //$NON-NLS-1$
        RoleDescription description = RightsFactory.eINSTANCE.createRoleDescription();
        description.getRights().add(rightsOn(catalog, "Read", RightValue.SET)); //$NON-NLS-1$
        description.getRights().add(rightsOn(kod2, "View", RightValue.SET)); //$NON-NLS-1$

        String md = RoleRightsReader.render("Role.OrphProbeRole", description, true, "en", 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(md, md.contains("| Catalog.OrphProbeB.Attribute.Kod2 | View | allowed |")); //$NON-NLS-1$
        assertTrue(md, md.contains("| Catalog.OrphProbeB | Read | allowed |")); //$NON-NLS-1$
        assertFalse(md, md.contains("| Catalog.OrphProbeB | View |")); //$NON-NLS-1$
    }

    @Test
    public void testTheDefaultViewNamesTheAttributeByItsAddressToo()
    {
        Catalog catalog = attachedTop(Catalog.class, MdClassPackage.Literals.CATALOG, "OrphProbeB", //$NON-NLS-1$
            "Catalog.OrphProbeB"); //$NON-NLS-1$
        CatalogAttribute kod2 = attachedChild(CatalogAttribute.class, MdClassPackage.Literals.CATALOG_ATTRIBUTE,
            "Kod2", catalog, MdClassPackage.Literals.CATALOG__ATTRIBUTES); //$NON-NLS-1$
        RoleDescription description = RightsFactory.eINSTANCE.createRoleDescription();
        description.getRights().add(rightsOn(kod2, "View", RightValue.SET)); //$NON-NLS-1$

        String md = RoleRightsReader.render("Role.OrphProbeRole", description, false, "en", 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(md, md.contains("| Catalog.OrphProbeB.Attribute.Kod2 | View | allowed |")); //$NON-NLS-1$
    }

    @Test
    public void testAnAttachedTopObjectIsStillNamedByItsTypeAndName()
    {
        // For a configuration top object its BM FQN and its strict address are the same string, so this
        // pins the rendered cell, not which of the two branches of objectFqnOf produced it - the order
        // of those branches has no observable effect on a live model.
        Catalog catalog = attachedTop(Catalog.class, MdClassPackage.Literals.CATALOG, "OrphProbeB", //$NON-NLS-1$
            "Catalog.OrphProbeB"); //$NON-NLS-1$
        RoleDescription description = RightsFactory.eINSTANCE.createRoleDescription();
        description.getRights().add(rightsOn(catalog, "Read", RightValue.SET)); //$NON-NLS-1$

        String md = RoleRightsReader.render("Role.R", description, false, "en", 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(md, md.contains("| Catalog.OrphProbeB | Read | allowed |")); //$NON-NLS-1$
    }

    @Test
    public void testAnAttachedUnaddressableSubordinateKeepsItsTopObjectsFqn()
    {
        // An integration service channel carries rights, but modify_metadata has no kind token for it
        // (rights[].object cannot name it), so the row keeps the FQN of the top object it belongs to
        // (here also its direct owner) - never an address the writer would reject.
        IntegrationService bus = attachedTop(IntegrationService.class, MdClassPackage.Literals.INTEGRATION_SERVICE,
            "Bus", "IntegrationService.Bus"); //$NON-NLS-1$ //$NON-NLS-2$
        IntegrationServiceChannel in = attachedChild(IntegrationServiceChannel.class,
            MdClassPackage.Literals.INTEGRATION_SERVICE_CHANNEL, "In", bus, //$NON-NLS-1$
            MdClassPackage.Literals.INTEGRATION_SERVICE__INTEGRATION_SERVICE_CHANNELS);
        RoleDescription description = RightsFactory.eINSTANCE.createRoleDescription();
        description.getRights().add(rightsOn(in, "Use", RightValue.SET)); //$NON-NLS-1$

        String md = RoleRightsReader.render("Role.R", description, false, "en", 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(md, md.contains("| IntegrationService.Bus | Use | allowed |")); //$NON-NLS-1$
    }

    @Test
    public void testAStandardAttributeOfATabularSectionIsShownUnderItsTopObjectNotItsOwner()
    {
        // The guide promises "the FQN of the top object it belongs to". A standard attribute is not an
        // MdObject, so it has no strict address; its direct owner here is the tabular section, but
        // the row must name the catalog - the top object - exactly as the guide says.
        Catalog catalog = attachedTop(Catalog.class, MdClassPackage.Literals.CATALOG, "OrphProbeB", //$NON-NLS-1$
            "Catalog.OrphProbeB"); //$NON-NLS-1$
        CatalogTabularSection goods = attachedChild(CatalogTabularSection.class,
            MdClassPackage.Literals.CATALOG_TABULAR_SECTION, "Goods", catalog, //$NON-NLS-1$
            MdClassPackage.Literals.CATALOG__TABULAR_SECTIONS);
        StandardAttribute lineNumber = mock(StandardAttribute.class, withSettings().extraInterfaces(IBmObject.class));
        when(lineNumber.eClass()).thenReturn(MdClassPackage.Literals.STANDARD_ATTRIBUTE);
        when(lineNumber.eContainer()).thenReturn(goods);
        when(lineNumber.eContainingFeature())
            .thenReturn(MdClassPackage.Literals.BASIC_TABULAR_SECTION__STANDARD_ATTRIBUTES);
        when(((IBmObject)lineNumber).bmIsTop()).thenReturn(false);
        when(((IBmObject)lineNumber).bmGetTopObject()).thenReturn((IBmObject)catalog);
        RoleDescription description = RightsFactory.eINSTANCE.createRoleDescription();
        description.getRights().add(rightsOn(lineNumber, "View", RightValue.SET)); //$NON-NLS-1$

        String md = RoleRightsReader.render("Role.R", description, false, "en", 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(md, md.contains("| Catalog.OrphProbeB | View | allowed |")); //$NON-NLS-1$
        assertFalse(md, md.contains("TabularSection.Goods")); //$NON-NLS-1$
    }

    @Test
    public void testATabularSectionAndItsAttributeAreNamedByTheirAddresses()
    {
        Document sale = MdClassFactory.eINSTANCE.createDocument();
        sale.setName("Sale"); //$NON-NLS-1$
        DocumentTabularSection goods = MdClassFactory.eINSTANCE.createDocumentTabularSection();
        goods.setName("Goods"); //$NON-NLS-1$
        sale.getTabularSections().add(goods);
        TabularSectionAttribute price = MdClassFactory.eINSTANCE.createTabularSectionAttribute();
        price.setName("Price"); //$NON-NLS-1$
        goods.getAttributes().add(price);
        RoleDescription description = RightsFactory.eINSTANCE.createRoleDescription();
        description.getRights().add(rightsOn(goods, "View", RightValue.SET)); //$NON-NLS-1$
        description.getRights().add(rightsOn(price, "Edit", RightValue.UNSET)); //$NON-NLS-1$

        String md = RoleRightsReader.render("Role.R", description, false, "en", 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(md, md.contains("| Document.Sale.TabularSection.Goods | View | allowed |")); //$NON-NLS-1$
        assertTrue(md, md.contains("| Document.Sale.TabularSection.Goods.Attribute.Price | Edit | denied |")); //$NON-NLS-1$
    }

    @Test
    public void testRegisterDimensionResourceAndAttributeAreNamedByTheirAddresses()
    {
        InformationRegister prices = MdClassFactory.eINSTANCE.createInformationRegister();
        prices.setName("Prices"); //$NON-NLS-1$
        InformationRegisterDimension product = MdClassFactory.eINSTANCE.createInformationRegisterDimension();
        product.setName("Product"); //$NON-NLS-1$
        prices.getDimensions().add(product);
        InformationRegisterResource price = MdClassFactory.eINSTANCE.createInformationRegisterResource();
        price.setName("Price"); //$NON-NLS-1$
        prices.getResources().add(price);
        InformationRegisterAttribute note = MdClassFactory.eINSTANCE.createInformationRegisterAttribute();
        note.setName("Note"); //$NON-NLS-1$
        prices.getAttributes().add(note);
        RoleDescription description = RightsFactory.eINSTANCE.createRoleDescription();
        description.getRights().add(rightsOn(product, "View", RightValue.SET)); //$NON-NLS-1$
        description.getRights().add(rightsOn(price, "View", RightValue.SET)); //$NON-NLS-1$
        description.getRights().add(rightsOn(note, "View", RightValue.SET)); //$NON-NLS-1$

        String md = RoleRightsReader.render("Role.R", description, false, "en", 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(md, md.contains("| InformationRegister.Prices.Dimension.Product | View | allowed |")); //$NON-NLS-1$
        assertTrue(md, md.contains("| InformationRegister.Prices.Resource.Price | View | allowed |")); //$NON-NLS-1$
        assertTrue(md, md.contains("| InformationRegister.Prices.Attribute.Note | View | allowed |")); //$NON-NLS-1$
    }

    @Test
    public void testACommandIsNamedByItsAddress()
    {
        Catalog goods = MdClassFactory.eINSTANCE.createCatalog();
        goods.setName("Goods"); //$NON-NLS-1$
        CatalogCommand print = MdClassFactory.eINSTANCE.createCatalogCommand();
        print.setName("Print"); //$NON-NLS-1$
        goods.getCommands().add(print);
        RoleDescription description = RightsFactory.eINSTANCE.createRoleDescription();
        description.getRights().add(rightsOn(print, "View", RightValue.SET)); //$NON-NLS-1$

        String md = RoleRightsReader.render("Role.R", description, false, "en", 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(md, md.contains("| Catalog.Goods.Command.Print | View | allowed |")); //$NON-NLS-1$
    }

    @Test
    public void testAnRlsRowOnASubordinateTargetNamesItsAddress()
    {
        // The RLS table names its object through the same address as the matrix (wiring pin for
        // renderRls): a row on an attribute must not be filed under the owner.
        Catalog goods = MdClassFactory.eINSTANCE.createCatalog();
        goods.setName("Goods"); //$NON-NLS-1$
        CatalogAttribute weight = MdClassFactory.eINSTANCE.createCatalogAttribute();
        weight.setName("Weight"); //$NON-NLS-1$
        goods.getAttributes().add(weight);
        ObjectRights onWeight = rightsOn(weight, "Read", RightValue.SET); //$NON-NLS-1$
        onWeight.getRights().get(0).getRestrictionsByCondition().add(newRls("WHERE TRUE")); //$NON-NLS-1$
        RoleDescription description = RightsFactory.eINSTANCE.createRoleDescription();
        description.getRights().add(onWeight);

        String md = RoleRightsReader.render("Role.R", description, false, "en", 0); //$NON-NLS-1$ //$NON-NLS-2$
        String rls = md.substring(md.indexOf("## Row-level security (RLS)")); //$NON-NLS-1$
        assertTrue(md, rls.contains("| Catalog.Goods.Attribute.Weight | Read | (whole object) | WHERE TRUE |")); //$NON-NLS-1$
    }

    @Test
    public void testAnUnaddressableDetachedSubordinateKeepsItsFallback()
    {
        // No kind token addresses an integration service channel, so no address is rendered for it:
        // the detached object keeps the eClass fallback it always had.
        IntegrationService bus = MdClassFactory.eINSTANCE.createIntegrationService();
        bus.setName("Bus"); //$NON-NLS-1$
        IntegrationServiceChannel in = MdClassFactory.eINSTANCE.createIntegrationServiceChannel();
        in.setName("In"); //$NON-NLS-1$
        bus.getIntegrationServiceChannels().add(in);
        RoleDescription description = RightsFactory.eINSTANCE.createRoleDescription();
        description.getRights().add(rightsOn(in, "Use", RightValue.SET)); //$NON-NLS-1$

        String md = RoleRightsReader.render("Role.R", description, false, "en", 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(md, md.contains("| IntegrationServiceChannel | Use | allowed |")); //$NON-NLS-1$
    }

    @Test
    public void testAnUnnamedDetachedSubordinateKeepsTheEClassFallback()
    {
        // Without names there is no address to build; the eClass fallback stays exactly as before.
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        CatalogAttribute attribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
        catalog.getAttributes().add(attribute);
        RoleDescription description = RightsFactory.eINSTANCE.createRoleDescription();
        description.getRights().add(rightsOn(attribute, "View", RightValue.SET)); //$NON-NLS-1$

        String md = RoleRightsReader.render("Role.R", description, false, "en", 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(md, md.contains("| CatalogAttribute | View | allowed |")); //$NON-NLS-1$
    }

    @Test
    public void testTheRoleSectionSaysTheObjectColumnIsTheWritableAddress()
    {
        // The guide is what tells a caller that a matrix cell can be fed back to modify_metadata.
        String guide = GuideLoader.load("get_metadata_details"); //$NON-NLS-1$
        assertTrue(guide, guide.contains("The Object column holds the object's full address - " //$NON-NLS-1$
            + "`Catalog.X.Attribute.Y` for an attribute, not `Catalog.X` - the same address " //$NON-NLS-1$
            + "`rights[].object` of `modify_metadata` accepts;")); //$NON-NLS-1$
    }

    @Test
    public void testTheRoleSectionNamesTheTopObjectForAnUnaddressableSubordinate()
    {
        // The fallback is the TOP object's FQN (bmGetTopObject), not the direct owner's: a standard
        // attribute of a tabular section is listed under the catalog, not under the tabular section.
        String guide = GuideLoader.load("get_metadata_details"); //$NON-NLS-1$
        assertTrue(guide, guide.contains("(a standard attribute, an integration service channel) is shown " //$NON-NLS-1$
            + "under the FQN of the top object it belongs to.")); //$NON-NLS-1$
        assertFalse(guide, guide.contains("is shown under its owner's FQN")); //$NON-NLS-1$
    }

    /** A BM-attached top object: {@code bmIsTop()} and its BM FQN, as a live model answers them. */
    private static <T extends MdObject> T attachedTop(Class<T> type, EClass eClass, String name, String fqn)
    {
        T top = mock(type, withSettings().extraInterfaces(IBmObject.class));
        when(top.eClass()).thenReturn(eClass);
        when(top.getName()).thenReturn(name);
        when(((IBmObject)top).bmIsTop()).thenReturn(true);
        when(((IBmObject)top).bmGetFqn()).thenReturn(fqn);
        return top;
    }

    /**
     * A BM-attached subordinate: contained in {@code owner} through {@code feature}, not a top object,
     * and answering {@code bmGetTopObject()} with its owner - the call the old reader named it by.
     */
    private static <T extends MdObject> T attachedChild(Class<T> type, EClass eClass, String name, MdObject owner,
        EReference feature)
    {
        T child = mock(type, withSettings().extraInterfaces(IBmObject.class));
        when(child.eClass()).thenReturn(eClass);
        when(child.getName()).thenReturn(name);
        when(child.eContainer()).thenReturn(owner);
        when(child.eContainingFeature()).thenReturn(feature);
        when(((IBmObject)child).bmIsTop()).thenReturn(false);
        when(((IBmObject)child).bmGetTopObject()).thenReturn((IBmObject)owner);
        return child;
    }

    private static ObjectRights rightsOn(EObject target, String rightName, RightValue value)
    {
        ObjectRights objectRights = RightsFactory.eINSTANCE.createObjectRights();
        objectRights.setObject(target);
        objectRights.getRights().add(newObjectRight(rightName, value));
        return objectRights;
    }

    private static EObject proxy(String uri)
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        ((InternalEObject)catalog).eSetProxyURI(URI.createURI(uri));
        return catalog;
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
