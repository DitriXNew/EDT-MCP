/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.rename;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.md.refactoring.core.IMdRefactoringService;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogTabularSection;
import com._1c.g5.v8.dt.metadata.mdclass.CommonForm;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessorCommand;
import com._1c.g5.v8.dt.metadata.mdclass.Enum;
import com._1c.g5.v8.dt.metadata.mdclass.EnumValue;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegisterDimension;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegisterResource;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com._1c.g5.v8.dt.metadata.mdclass.TabularSectionAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.Template;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.PredefinedWriter;
import com.ditrix.edt.mcp.server.utils.ReferencedTopObjects;

/**
 * Issue #994: {@code rename_metadata_object} resolves its target through the ONE shared resolver
 * ({@code MetadataNodeResolver.resolveAddress}), so every member the metadata tools address - a
 * command, an owned form, a template, an enum value, besides the four kinds the old private resolver
 * knew - reaches EDT's {@code createMdObjectRenameRefactoring}, and an address with an odd number of
 * segments names nothing instead of renaming its owner.
 *
 * <p>Russian tokens are built from code points so a non-UTF-8 build cannot corrupt them.</p>
 */
public class MetadataRenameMemberKindsTest
{
    /** "Обработка" (DataProcessor). */
    private static final String RU_DATA_PROCESSOR = cp(0x041e, 0x0431, 0x0440, 0x0430, 0x0431, 0x043e, 0x0442,
        0x043a, 0x0430);
    /** "Справочник" (Catalog). */
    private static final String RU_CATALOG = cp(0x0421, 0x043f, 0x0440, 0x0430, 0x0432, 0x043e, 0x0447, 0x043d,
        0x0438, 0x043a);
    /** "Перечисление" (Enum). */
    private static final String RU_ENUM = cp(0x041f, 0x0435, 0x0440, 0x0435, 0x0447, 0x0438, 0x0441, 0x043b,
        0x0435, 0x043d, 0x0438, 0x0435);
    /** "Команда" (Command). */
    private static final String RU_COMMAND = cp(0x041a, 0x043e, 0x043c, 0x0430, 0x043d, 0x0434, 0x0430);
    /** "Команды" (Commands). */
    private static final String RU_COMMANDS = cp(0x041a, 0x043e, 0x043c, 0x0430, 0x043d, 0x0434, 0x044b);
    /** "Форма" (Form). */
    private static final String RU_FORM = cp(0x0424, 0x043e, 0x0440, 0x043c, 0x0430);
    /** "Макет" (Template). */
    private static final String RU_TEMPLATE = cp(0x041c, 0x0430, 0x043a, 0x0435, 0x0442);
    /** "ЗначениеПеречисления" (EnumValue). */
    private static final String RU_ENUM_VALUE = cp(0x0417, 0x043d, 0x0430, 0x0447, 0x0435, 0x043d, 0x0438,
        0x0435, 0x041f, 0x0435, 0x0440, 0x0435, 0x0447, 0x0438, 0x0441, 0x043b, 0x0435, 0x043d, 0x0438, 0x044f);
    /** "Реквизит" (Attribute). */
    private static final String RU_ATTRIBUTE = cp(0x0420, 0x0435, 0x043a, 0x0432, 0x0438, 0x0437, 0x0438, 0x0442);

    private Configuration config;
    private MetadataScope scope;
    private DataProcessor proc;
    private DataProcessorCommand open;
    private Catalog cat;
    private CatalogAttribute weight;
    private CatalogTabularSection lines;
    private TabularSectionAttribute price;
    private CatalogForm itemForm;
    private Template label;
    private Enum status;
    private EnumValue active;
    private InformationRegister prices;
    private InformationRegisterDimension product;
    private InformationRegisterResource amount;
    private CommonForm settings;
    private Subsystem sales;

    private static String cp(int... cps)
    {
        return new String(cps, 0, cps.length);
    }

    @Before
    public void setUp()
    {
        MdClassFactory f = MdClassFactory.eINSTANCE;
        config = f.createConfiguration();
        config.setName("Cfg"); //$NON-NLS-1$

        proc = f.createDataProcessor();
        proc.setName("Proc"); //$NON-NLS-1$
        open = f.createDataProcessorCommand();
        open.setName("Open"); //$NON-NLS-1$
        proc.getCommands().add(open);
        config.getDataProcessors().add(proc);

        cat = f.createCatalog();
        cat.setName("Cat"); //$NON-NLS-1$
        weight = f.createCatalogAttribute();
        weight.setName("Weight"); //$NON-NLS-1$
        cat.getAttributes().add(weight);
        lines = f.createCatalogTabularSection();
        lines.setName("Lines"); //$NON-NLS-1$
        price = f.createTabularSectionAttribute();
        price.setName("Price"); //$NON-NLS-1$
        lines.getAttributes().add(price);
        cat.getTabularSections().add(lines);
        itemForm = f.createCatalogForm();
        itemForm.setName("ItemForm"); //$NON-NLS-1$
        cat.getForms().add(itemForm);
        label = f.createTemplate();
        label.setName("Label"); //$NON-NLS-1$
        cat.getTemplates().add(label);
        config.getCatalogs().add(cat);

        status = f.createEnum();
        status.setName("Status"); //$NON-NLS-1$
        active = f.createEnumValue();
        active.setName("Active"); //$NON-NLS-1$
        status.getEnumValues().add(active);
        config.getEnums().add(status);

        prices = f.createInformationRegister();
        prices.setName("Prices"); //$NON-NLS-1$
        product = f.createInformationRegisterDimension();
        product.setName("Product"); //$NON-NLS-1$
        prices.getDimensions().add(product);
        amount = f.createInformationRegisterResource();
        amount.setName("Amount"); //$NON-NLS-1$
        prices.getResources().add(amount);
        config.getInformationRegisters().add(prices);

        settings = f.createCommonForm();
        settings.setName("Settings"); //$NON-NLS-1$
        config.getCommonForms().add(settings);

        sales = f.createSubsystem();
        sales.setName("Sales"); //$NON-NLS-1$
        config.getSubsystems().add(sales);

        scope = MetadataScope.ofConfiguration(config);
    }

    /** What {@code MetadataRenameService.rename} hands the resolver: the normalized FQN. */
    private MdObject resolve(String fqn)
    {
        return MetadataRenameService.resolveTarget(scope, MetadataTypeUtils.normalizeFqn(fqn));
    }

    // ==================== the kinds the old private resolver refused ====================

    @Test
    public void testADataProcessorCommandResolvesToThatCommand()
    {
        assertSame(open, resolve("DataProcessor.Proc.Command.Open")); //$NON-NLS-1$
    }

    @Test
    public void testAnOwnedFormObjectResolvesToThatForm()
    {
        assertSame(itemForm, resolve("Catalog.Cat.Form.ItemForm")); //$NON-NLS-1$
        assertSame(itemForm, resolve("Catalog.Cat.Forms.ItemForm")); //$NON-NLS-1$
    }

    @Test
    public void testATemplateResolvesToThatTemplate()
    {
        assertSame(label, resolve("Catalog.Cat.Template.Label")); //$NON-NLS-1$
    }

    @Test
    public void testAnEnumValueResolvesToThatValue()
    {
        assertSame(active, resolve("Enum.Status.EnumValue.Active")); //$NON-NLS-1$
    }

    @Test
    public void testRussianTypeAndKindTokensResolveTheSameMembers()
    {
        assertSame(open, resolve(RU_DATA_PROCESSOR + ".Proc." + RU_COMMAND + ".Open")); //$NON-NLS-1$ //$NON-NLS-2$
        assertSame(open, resolve(RU_DATA_PROCESSOR + ".Proc." + RU_COMMANDS + ".Open")); //$NON-NLS-1$ //$NON-NLS-2$
        assertSame(itemForm, resolve(RU_CATALOG + ".Cat." + RU_FORM + ".ItemForm")); //$NON-NLS-1$ //$NON-NLS-2$
        assertSame(label, resolve(RU_CATALOG + ".Cat." + RU_TEMPLATE + ".Label")); //$NON-NLS-1$ //$NON-NLS-2$
        assertSame(active, resolve(RU_ENUM + ".Status." + RU_ENUM_VALUE + ".Active")); //$NON-NLS-1$ //$NON-NLS-2$
        assertSame(weight, resolve(RU_CATALOG + ".Cat." + RU_ATTRIBUTE + ".Weight")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testPluralKindTokensAndALetterCaseDifferentMemberNameResolve()
    {
        assertSame(open, resolve("DataProcessor.Proc.Commands.open")); //$NON-NLS-1$
        assertSame(label, resolve("Catalog.Cat.Templates.LABEL")); //$NON-NLS-1$
        assertSame(active, resolve("Enum.Status.EnumValues.active")); //$NON-NLS-1$
    }

    @Test
    public void testTheResolvedMemberIsWhatEdtsRenameRefactoringIsAskedFor()
    {
        MdObject target = resolve("DataProcessor.Proc.Command.Open"); //$NON-NLS-1$
        IMdRefactoringService service = mock(IMdRefactoringService.class);
        when(service.createMdObjectRenameRefactoring(same(target), anyString()))
            .thenReturn(Collections.emptyList());
        MetadataRenameService rename = new MetadataRenameService();
        rename.useMonitor(new NullProgressMonitor());

        String result = rename.createMdClassRename(null, "DataProcessor.Proc.Command.Open", "OpenRenamed", //$NON-NLS-1$ //$NON-NLS-2$
            target, false, DisableRequest.parse(null), null, 20, new RenameProgress(), service);

        verify(service).createMdObjectRenameRefactoring(same(open), eq("OpenRenamed")); //$NON-NLS-1$
        assertTrue(result, result.contains("Failed to create rename refactoring")); //$NON-NLS-1$
    }

    // ==================== odd arity never falls back to the owner ====================

    @Test
    public void testAnOddArityAddressNamesNothingRatherThanItsOwner()
    {
        assertNull("'Catalog.Cat.Attribute' must not rename the catalog", resolve("Catalog.Cat.Attribute")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(resolve("Catalog.Cat.TabularSection.Lines.Attribute")); //$NON-NLS-1$
        assertNull(resolve("DataProcessor.Proc.Command")); //$NON-NLS-1$
    }

    @Test
    public void testTheOddArityRefusalSaysWhatIsMissing()
    {
        String message = MetadataRenameService.notFoundMessage(scope, "Catalog.Cat.Attribute"); //$NON-NLS-1$

        assertTrue(message, message.startsWith("Object not found: Catalog.Cat.Attribute. ")); //$NON-NLS-1$
        assertTrue(message, message.contains("odd number of segments: every kind token ('Attribute') " //$NON-NLS-1$
            + "must be followed by a name")); //$NON-NLS-1$
    }

    // ==================== parity: what resolved before still resolves ====================

    @Test
    public void testTheFourKindsTheOldResolverKnewStillResolve()
    {
        assertSame(cat, resolve("Catalog.Cat")); //$NON-NLS-1$
        assertSame(weight, resolve("Catalog.Cat.Attribute.Weight")); //$NON-NLS-1$
        assertSame(lines, resolve("Catalog.Cat.TabularSection.Lines")); //$NON-NLS-1$
        assertSame(price, resolve("Catalog.Cat.TabularSection.Lines.Attribute.Price")); //$NON-NLS-1$
        assertSame(product, resolve("InformationRegister.Prices.Dimension.Product")); //$NON-NLS-1$
        assertSame(amount, resolve("InformationRegister.Prices.Resource.Amount")); //$NON-NLS-1$
        assertSame(weight, resolve("Catalog.Cat.Attribute.weight")); //$NON-NLS-1$
    }

    @Test
    public void testACommonFormAndASubsystemResolveAsTopObjects()
    {
        assertSame(settings, resolve("CommonForm.Settings")); //$NON-NLS-1$
        assertSame(sales, resolve("Subsystem.Sales")); //$NON-NLS-1$
    }

    @Test
    public void testAMissingObjectOrMemberResolvesToNothing()
    {
        assertNull(resolve("Catalog.Missing")); //$NON-NLS-1$
        assertNull(resolve("Catalog.Cat.Attribute.Missing")); //$NON-NLS-1$
        assertNull(resolve("Catalog.Cat.Form.Missing")); //$NON-NLS-1$
        assertNull(resolve("JustAName")); //$NON-NLS-1$
    }

    @Test
    public void testTheExactResolverDoesNotRetryWithYoNormalized()
    {
        // create_metadata stores ye for yo by default; the rename addresses exactly, as it always did.
        Catalog yeCatalog = MdClassFactory.eINSTANCE.createCatalog();
        yeCatalog.setName(cp(0x0415, 0x043b, 0x043a, 0x0430)); // "Елка"
        config.getCatalogs().add(yeCatalog);

        assertNull(resolve("Catalog." + cp(0x0401, 0x043b, 0x043a, 0x0430))); //$NON-NLS-1$ // "Ёлка"
        assertSame(yeCatalog, resolve("Catalog." + cp(0x0415, 0x043b, 0x043a, 0x0430))); //$NON-NLS-1$
    }

    // ==================== the dispatch in front of the resolver is unchanged ====================

    @Test
    public void testAFormElementStillGoesToTheFormBranchAndAFormObjectDoesNot()
    {
        assertNotNull(FormElementWriter.parse("Catalog.Cat.Form.ItemForm.Command.Open")); //$NON-NLS-1$
        assertNull(FormElementWriter.parse("Catalog.Cat.Form.ItemForm")); //$NON-NLS-1$
        assertNull(FormElementWriter.parse("DataProcessor.Proc.Command.Open")); //$NON-NLS-1$
        assertNull(PredefinedWriter.parseRef("DataProcessor.Proc.Command.Open")); //$NON-NLS-1$
        assertNull(PredefinedWriter.parseRef("Enum.Status.EnumValue.Active")); //$NON-NLS-1$
    }

    @Test
    public void testAnExternalDataSourceMemberIsStillRefused()
    {
        assertNotNull(ReferencedTopObjects.memberOperationRefusal("rename_metadata_object", //$NON-NLS-1$
            "ExternalDataSource.Src.Table.Goods")); //$NON-NLS-1$
        assertNull(ReferencedTopObjects.memberOperationRefusal("rename_metadata_object", //$NON-NLS-1$
            "DataProcessor.Proc.Command.Open")); //$NON-NLS-1$
    }

    // ==================== the not-found text ====================

    @Test
    public void testAMissingMemberOfAnExistingOwnerNamesTheOwnersKinds()
    {
        String message = MetadataRenameService.notFoundMessage(scope, "DataProcessor.Proc.Comand.Open"); //$NON-NLS-1$

        assertTrue(message, message.startsWith("Object not found: DataProcessor.Proc.Comand.Open. ")); //$NON-NLS-1$
        assertTrue(message, message.contains("'DataProcessor.Proc' exists; its member kinds: ")); //$NON-NLS-1$
        String kinds = message.substring(message.indexOf("its member kinds: ") + "its member kinds: ".length()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(message, kinds.contains("Attribute")); //$NON-NLS-1$
        assertTrue(message, kinds.contains("Command")); //$NON-NLS-1$
        assertTrue(message, kinds.contains("Template")); //$NON-NLS-1$
        assertTrue(message, kinds.contains("Form")); //$NON-NLS-1$
        assertFalse("a data processor has no enum values", kinds.contains("EnumValue")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(message, message.contains("get_metadata_details on 'DataProcessor.Proc'")); //$NON-NLS-1$
    }

    @Test
    public void testAMissingNestedMemberNamesItsDirectOwner()
    {
        String message = MetadataRenameService.notFoundMessage(scope,
            "Catalog.Cat.TabularSection.Lines.Attribute.Missing"); //$NON-NLS-1$

        assertTrue(message, message.contains("'Catalog.Cat.TabularSection.Lines' exists; its member kinds: Attribute.")); //$NON-NLS-1$
    }

    @Test
    public void testTheNotFoundTextNoLongerLimitsTheChildKindsToFour()
    {
        String message = MetadataRenameService.notFoundMessage(scope, "Catalog.Missing"); //$NON-NLS-1$

        assertFalse(message, message.contains("Supported child types")); //$NON-NLS-1$
        assertFalse(message, message.contains("is not a rename target")); //$NON-NLS-1$
        assertTrue(message, message.contains("Check the FQN format")); //$NON-NLS-1$
        assertTrue(message, message.contains("'DataProcessor.Import.Command.Open'")); //$NON-NLS-1$
        assertTrue(message, message.contains("'Catalog.Products.Form.ItemForm'")); //$NON-NLS-1$
        assertFalse("a missing top object has no owner to describe", message.contains("exists; its member kinds")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== empty segments are malformed, not a shorter address ====================

    @Test
    public void testAnAddressWithAnEmptySegmentNamesNothing()
    {
        assertNull("a trailing '.' must not rename the form", resolve("Catalog.Cat.Form.ItemForm.")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(resolve("Catalog.Cat.")); //$NON-NLS-1$
        assertNull(resolve("Catalog..Cat")); //$NON-NLS-1$
        assertNull(resolve("DataProcessor.Proc.Command.Open.")); //$NON-NLS-1$
        assertSame("control: the same address without the dot resolves", itemForm, resolve("Catalog.Cat.Form.ItemForm")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTheEmptySegmentRefusalSaysSo()
    {
        String message = MetadataRenameService.notFoundMessage(scope, "Catalog.Cat.Form.ItemForm."); //$NON-NLS-1$

        assertTrue(message, message.startsWith("Object not found: Catalog.Cat.Form.ItemForm.. ")); //$NON-NLS-1$
        assertTrue(message, message.contains("empty segment")); //$NON-NLS-1$
    }

    // ==================== a new name already taken at the same place ====================

    @Test
    public void testANewNameTakenBySiblingMemberIsRefused()
    {
        EnumValue closed = MdClassFactory.eINSTANCE.createEnumValue();
        closed.setName("Closed"); //$NON-NLS-1$
        status.getEnumValues().add(closed);

        String refusal = MetadataRenameService.nameClashRefusal(scope, "Enum.Status.EnumValue.Active", active, //$NON-NLS-1$
            "closed"); //$NON-NLS-1$

        assertNotNull("two enum values of one name must not be produced", refusal); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("'Enum.Status.EnumValue.Closed' already exists")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Nothing was renamed")); //$NON-NLS-1$
    }

    @Test
    public void testANewNameTakenByAnotherKindOfOwnerOrTopObjectIsRefused()
    {
        DataProcessorCommand close = MdClassFactory.eINSTANCE.createDataProcessorCommand();
        close.setName("Close"); //$NON-NLS-1$
        proc.getCommands().add(close);
        CatalogForm listForm = MdClassFactory.eINSTANCE.createCatalogForm();
        listForm.setName("ListForm"); //$NON-NLS-1$
        cat.getForms().add(listForm);
        Catalog other = MdClassFactory.eINSTANCE.createCatalog();
        other.setName("Other"); //$NON-NLS-1$
        config.getCatalogs().add(other);

        assertNotNull(MetadataRenameService.nameClashRefusal(scope, "DataProcessor.Proc.Command.Open", open, "Close")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(MetadataRenameService.nameClashRefusal(scope, "Catalog.Cat.Form.ItemForm", itemForm, "LISTFORM")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(MetadataRenameService.nameClashRefusal(scope, "Catalog.Cat", cat, "Other")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAFreeNameOrACaseOnlyRenameIsNotAClash()
    {
        assertNull(MetadataRenameService.nameClashRefusal(scope, "Enum.Status.EnumValue.Active", active, "Open")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("a case-only rename of the target itself", //$NON-NLS-1$
            MetadataRenameService.nameClashRefusal(scope, "Enum.Status.EnumValue.Active", active, "ACTIVE")); //$NON-NLS-1$ //$NON-NLS-2$
        // A same-named member of ANOTHER kind is not at the same address: the grammar keeps the kinds apart.
        assertNull(MetadataRenameService.nameClashRefusal(scope, "Catalog.Cat.Template.Label", label, "Weight")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== the preview says what its rows do not list ====================

    @Test
    public void testThePreviewNoteNamesTheUnlistedCrossReferenceUpdates()
    {
        String note = MetadataRenameService.crossReferenceNote();

        assertTrue(note, note.startsWith("> ")); //$NON-NLS-1$
        assertTrue(note, note.contains("command interface")); //$NON-NLS-1$
        assertTrue(note, note.contains("role rights")); //$NON-NLS-1$
        assertTrue(note, note.contains("may not be listed as rows")); //$NON-NLS-1$
        assertTrue(note, note.endsWith("\n")); //$NON-NLS-1$
    }

    @Test
    public void testAMissingNestedSubsystemGetsTheSubsystemAddressingHint()
    {
        String message = MetadataRenameService.notFoundMessage(scope, "Subsystem.Sales.Subsystem.Missing"); //$NON-NLS-1$

        assertTrue(message, message.startsWith("Object not found: Subsystem.Sales.Subsystem.Missing. ")); //$NON-NLS-1$
        assertFalse(message, message.contains("member kinds")); //$NON-NLS-1$
        assertEquals(message, "Object not found: Subsystem.Sales.Subsystem.Missing. " //$NON-NLS-1$
            + com.ditrix.edt.mcp.server.utils.SubsystemUtils.addressingHint() + ".", message); //$NON-NLS-1$
    }
}
