/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.InternalEObject;
import org.junit.Test;

import com._1c.g5.v8.bm.core.BmUriUtil;
import com._1c.g5.v8.dt.metadata.mdclass.AutoRegistrationChanges;
import com._1c.g5.v8.dt.metadata.mdclass.CalculationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.ExchangePlan;
import com._1c.g5.v8.dt.metadata.mdclass.ExchangePlanContentItem;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Recalculation;

/**
 * Pins the {@code ### Content} section {@link UniversalMetadataFormatter} renders for an
 * {@link ExchangePlan} (issue #908): one {@code Object | AutoRecord} row per member - the address
 * and the {@code Allow} / {@code Deny} flag {@code modify_metadata}'s {@code content[]} writes -
 * instead of the generic EObject fallback that printed only the element class name. Built from
 * detached in-memory objects (no project, no services), in both the default and the full view.
 */
public class ExchangePlanContentFormatterTest
{
    private final UniversalMetadataFormatter f = UniversalMetadataFormatter.getInstance();

    private static final String EN = "en"; //$NON-NLS-1$

    private static final String HEADER = "| Object | AutoRecord |"; //$NON-NLS-1$

    private static ExchangePlan plan()
    {
        ExchangePlan plan = MdClassFactory.eINSTANCE.createExchangePlan();
        plan.setName("Node"); //$NON-NLS-1$
        return plan;
    }

    private static ExchangePlanContentItem item(MdObject member, AutoRegistrationChanges autoRecord)
    {
        ExchangePlanContentItem item = (ExchangePlanContentItem)MdClassFactory.eINSTANCE
            .create(MdClassPackage.Literals.EXCHANGE_PLAN_CONTENT_ITEM);
        item.setMdObject(member);
        if (autoRecord != null)
        {
            item.setAutoRecord(autoRecord);
        }
        return item;
    }

    private static Catalog catalog(String name)
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName(name);
        return catalog;
    }

    private static Document document(String name)
    {
        Document document = MdClassFactory.eINSTANCE.createDocument();
        document.setName(name);
        return document;
    }

    private String[] bothViews(ExchangePlan plan)
    {
        return new String[] { f.format(plan, false, EN), f.format(plan, true, EN) };
    }

    @Test
    public void testContentRendersEachMembersAddressAndAutoRecord()
    {
        ExchangePlan plan = plan();
        plan.getContent().add(item(catalog("Goods"), AutoRegistrationChanges.ALLOW)); //$NON-NLS-1$
        plan.getContent().add(item(document("Order"), AutoRegistrationChanges.DENY)); //$NON-NLS-1$

        for (String md : bothViews(plan))
        {
            assertTrue(md, md.contains("### Content\n\n" + HEADER + "\n|---|---|\n" //$NON-NLS-1$ //$NON-NLS-2$
                + "| Catalog.Goods | Allow |\n| Document.Order | Deny |\n")); //$NON-NLS-1$
            assertFalse("the element class name must not stand in for the content:\n" + md, //$NON-NLS-1$
                md.contains("ExchangePlanContentItem")); //$NON-NLS-1$
        }
    }

    @Test
    public void testAnUnsetAutoRecordReadsAsThePlatformDefaultDeny()
    {
        // EDT writes no <autoRecord> element for Deny (the EMF default); the read side must still
        // say Deny rather than a dash or nothing.
        ExchangePlan plan = plan();
        plan.getContent().add(item(catalog("Goods"), null)); //$NON-NLS-1$

        for (String md : bothViews(plan))
        {
            assertTrue(md, md.contains("| Catalog.Goods | Deny |")); //$NON-NLS-1$
        }
    }

    @Test
    public void testAnEmptyContentListRendersNoContentSection()
    {
        for (String md : bothViews(plan()))
        {
            assertFalse(md, md.contains(HEADER));
            assertFalse(md, md.contains("### Content")); //$NON-NLS-1$
        }
    }

    @Test
    public void testAMemberAddressIsTableSafe()
    {
        ExchangePlan plan = plan();
        plan.getContent().add(item(catalog("Odd|Name\nBreak"), AutoRegistrationChanges.ALLOW)); //$NON-NLS-1$

        String md = f.format(plan, false, EN);
        assertTrue(md, md.contains("| Catalog.Odd\\|Name Break | Allow |")); //$NON-NLS-1$
    }

    @Test
    public void testARecalculationMemberIsPrintedByItsFullAddress()
    {
        // A recalculation is not a top object: Type.Name alone ("Recalculation.Redo") names nothing
        // modify_metadata resolves; its address goes through the owning calculation register.
        CalculationRegister register = MdClassFactory.eINSTANCE.createCalculationRegister();
        register.setName("Payroll"); //$NON-NLS-1$
        Recalculation recalculation = MdClassFactory.eINSTANCE.createRecalculation();
        recalculation.setName("Redo"); //$NON-NLS-1$
        register.getRecalculations().add(recalculation);
        ExchangePlan plan = plan();
        plan.getContent().add(item(recalculation, AutoRegistrationChanges.ALLOW));

        String md = f.format(plan, false, EN);
        assertTrue(md, md.contains("| CalculationRegister.Payroll.Recalculation.Redo | Allow |")); //$NON-NLS-1$
    }

    @Test
    public void testAMissingMemberIsMarkedNotDropped()
    {
        ExchangePlan plan = plan();
        plan.getContent().add(item(null, AutoRegistrationChanges.ALLOW));
        plan.getContent().add(item(catalog("Goods"), AutoRegistrationChanges.DENY)); //$NON-NLS-1$

        String md = f.format(plan, false, EN);
        assertTrue(md, md.contains("| [unresolved metadata] | Allow |\n| Catalog.Goods | Deny |")); //$NON-NLS-1$
    }

    @Test
    public void testAnUnresolvedMemberShowsTheAddressItsReferenceStillCarries()
    {
        Document gone = MdClassFactory.eINSTANCE.createDocument();
        ((InternalEObject)gone).eSetProxyURI(BmUriUtil.createTopBmObjectUri("Project", "Document.Gone")); //$NON-NLS-1$ //$NON-NLS-2$
        ExchangePlan plan = plan();
        plan.getContent().add(item(gone, AutoRegistrationChanges.ALLOW));

        String md = f.format(plan, true, EN);
        assertTrue(md, md.contains("| [unresolved metadata] Document.Gone | Allow |")); //$NON-NLS-1$
        assertFalse(md, md.contains("Document.null")); //$NON-NLS-1$
    }

    @Test
    public void testAnUnresolvedMemberWithAForeignUriShowsTheUri()
    {
        Catalog gone = MdClassFactory.eINSTANCE.createCatalog();
        ((InternalEObject)gone).eSetProxyURI(URI.createURI("unresolved:/Catalog.Gone")); //$NON-NLS-1$
        ExchangePlan plan = plan();
        plan.getContent().add(item(gone, AutoRegistrationChanges.DENY));

        String md = f.format(plan, false, EN);
        assertTrue(md, md.contains("| [unresolved metadata] unresolved:/Catalog.Gone | Deny |")); //$NON-NLS-1$
    }

    @Test
    public void testALargeContentListIsCappedAndTheNoticeCountsTheOmittedFlags()
    {
        int cap = AbstractMetadataFormatter.MAX_DYNAMIC_ROWS;
        ExchangePlan plan = plan();
        for (int i = 0; i < cap; i++)
        {
            plan.getContent().add(item(catalog("Shown" + i), AutoRegistrationChanges.DENY)); //$NON-NLS-1$
        }
        // Past the cap: 3 Allow and 2 Deny members must still be counted by flag.
        plan.getContent().add(item(catalog("HiddenA0"), AutoRegistrationChanges.ALLOW)); //$NON-NLS-1$
        plan.getContent().add(item(catalog("HiddenD0"), AutoRegistrationChanges.DENY)); //$NON-NLS-1$
        plan.getContent().add(item(catalog("HiddenA1"), AutoRegistrationChanges.ALLOW)); //$NON-NLS-1$
        plan.getContent().add(item(catalog("HiddenA2"), AutoRegistrationChanges.ALLOW)); //$NON-NLS-1$
        plan.getContent().add(item(document("HiddenD1"), null)); //$NON-NLS-1$

        for (String md : bothViews(plan))
        {
            assertTrue(md, md.contains("| Catalog.Shown" + (cap - 1) + " | Deny |")); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(md, md.contains("Hidden")); //$NON-NLS-1$
            assertTrue(md, md.contains("| [truncated] | 5 more content items omitted (section capped at " //$NON-NLS-1$
                + cap + "): 3 Allow, 2 Deny |")); //$NON-NLS-1$
            int section = md.indexOf("### Content"); //$NON-NLS-1$
            assertEquals("cap rows + header + separator + notice", cap + 3, //$NON-NLS-1$
                md.substring(section).split("\n\n")[1].split("\n").length); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testAContentListAtTheCapHasNoNotice()
    {
        int cap = AbstractMetadataFormatter.MAX_DYNAMIC_ROWS;
        ExchangePlan plan = plan();
        for (int i = 0; i < cap; i++)
        {
            plan.getContent().add(item(catalog("Shown" + i), AutoRegistrationChanges.ALLOW)); //$NON-NLS-1$
        }

        String md = f.format(plan, false, EN);
        assertTrue(md, md.contains("| Catalog.Shown" + (cap - 1) + " | Allow |")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(md, md.contains("[truncated]")); //$NON-NLS-1$
    }
}
