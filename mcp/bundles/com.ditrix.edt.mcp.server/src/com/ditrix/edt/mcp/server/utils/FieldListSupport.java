/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.InternalEObject;

import com._1c.g5.v8.bm.core.BmUriUtil;
import com._1c.g5.v8.bm.core.IBmEngine;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.mcore.DerivedField;
import com._1c.g5.v8.dt.mcore.Field;
import com._1c.g5.v8.dt.mcore.FieldSource;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.SourceType;
import com._1c.g5.v8.dt.metadata.mdclass.CommonAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.DbObjectAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.ExchangePlan;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com.google.gson.JsonElement;

/**
 * A list of an object's OWN fields - {@code inputByString} ("Ввод по строке") and
 * {@code dataLockFields} ("Поля блокировки данных") of the eight BasicDbObject kinds (issue #740).
 *
 * <h2>What the model holds</h2>
 * <p>Both are {@code refers mcore.Field[]} declared once, on {@code BasicDbObject}
 * ({@code model/MdClass.xcore} :1125 / :1151 on 2026.2, :1124 / :1150 on 2026.1). The fields they point
 * at are TRANSIENT derived data: {@code FieldSource.fields} is {@code contains transient Field[]},
 * rebuilt by EDT's MdFieldInferrer on every recompute, so neither a field object nor its BM id survives
 * from one transaction to the next. What does survive is the field id - {@code fieldIdPrefix + ":" +
 * name}, e.g. {@code StandardAttribute:Description} or {@code Attribute:Weight}. EDT's own initializer
 * (AbstractBasicDbObjectInitializer.getInputByStringFieldUris, identical in md 27.1 and 28.0) therefore
 * stores an entry as a {@code Field} PROXY whose URI is the owner's BM URI plus
 * {@code /fields:<encoded field id>}, and {@link #apply} writes exactly that.</p>
 *
 * <h2>The address grammar</h2>
 * <p>The address of a field is the symbolic name EDT writes into the {@code .mdo}
 * (MdSymbolicNameServiceDelegate): {@code <Type>.<Object>.<Prefix>.<Name>} -
 * {@code Catalog.Goods.StandardAttribute.Description}, {@code Catalog.Goods.Attribute.Weight},
 * {@code Task.Job.AddressingAttribute.Executor}, {@code ChartOfAccounts.Main.AccountingFlag.Currency} -
 * and {@code CommonAttribute.<Name>} for a common attribute's field. It is read back in English, as
 * stored; it is accepted bilingual (the type and kind tokens through {@link MetadataTypeUtils}, a
 * standard field's name through the model's own {@code nameRu}).</p>
 *
 * <h2>Which fields are offered</h2>
 * <p>Read from the model, never tabulated here: EDT's property editor lists
 * {@code FieldsSourceUtil.getFields(object, f -> f.getSourceTypes().contains(SOURCE))} with
 * {@link SourceType#INPUT_BY_STRING} for input by string and {@link SourceType#FIELDS_FOR_LOCKING} for
 * data locking ({@code FieldsSourceContentProvider$InputByStringContentProvider} /
 * {@code $DataLockFieldsContentProvider}, md.ui). Two corrections are applied on top, the two places
 * where EDT's model validator is narrower than its editor ({@code validatorRejects}): a task's
 * addressing attribute is not offered for input by string ({@code ValidationUtil.isInputByStringReady}
 * accepts a derived input-by-string field only from an object attribute or a common attribute; error
 * 4), and an exchange plan's ExchangeDate, and its Code / Description while the length is 0, are not
 * offered for data locking ({@code FixedFieldDescriptorProvider.createExchangePlanDataLockFields};
 * error 81) - each would be written and then marked. The intersection of the two is what this class
 * offers.</p>
 */
public final class FieldListSupport
{
    /**
     * The field lists this class handles, each with the source type EDT's editor offers its fields by.
     *
     * <p>A closed table measured from the model: {@code mcore.Field[]} occurs five times in
     * {@code MdClass.xcore}, and only the two {@code BasicDbObject} lists are admitted. The other three
     * belong to an external data source {@code Table} ({@code keyFields} / {@code inputByString} /
     * {@code dataLockFields}, :3725 / :3739 / :3794), which modify_metadata cannot address; they stay
     * excluded (fail-closed) rather than being admitted by a type rule nobody measured them against.</p>
     */
    public static final Map<EReference, SourceType> ELIGIBILITY;

    static
    {
        Map<EReference, SourceType> table = new LinkedHashMap<>();
        table.put(MdClassPackage.Literals.BASIC_DB_OBJECT__INPUT_BY_STRING, SourceType.INPUT_BY_STRING);
        table.put(MdClassPackage.Literals.BASIC_DB_OBJECT__DATA_LOCK_FIELDS, SourceType.FIELDS_FOR_LOCKING);
        ELIGIBILITY = Collections.unmodifiableMap(table);
    }

    /**
     * The object properties that decide whether EDT offers a standard field for a field list: each
     * property maps to the lists it decides - by their source type, {@link SourceType#INPUT_BY_STRING}
     * or {@link SourceType#FIELDS_FOR_LOCKING} - and, per list, to the English names of the standard
     * fields it decides there. The one source of modify_metadata's batch guard
     * ({@link #namedFieldGovernedBy}).
     *
     * <p>Measured from EDT's MdFieldInferrer (md 27.1 {@code md.resource}, md 28.0
     * {@code internal.md.bm.derived.inferrers}; the same rules on both lines). Each of these standard
     * fields EXISTS whatever the property says; the property only decides whether the inferrer gives
     * the field the list's source type:</p>
     * <ul>
     * <li>{@code codeLength} / {@code descriptionLength} / {@code numberLength}: Code / Description /
     * Number, for both lists, while the length is not 0;</li>
     * <li>{@code hierarchical}: Parent and IsFolder, for data locking - on a catalog and on a chart of
     * characteristic types, which both declare it;</li>
     * <li>{@code hierarchyType}: a catalog's IsFolder, for data locking (it needs a hierarchical
     * catalog of HierarchyFoldersAndItems);</li>
     * <li>{@code owners}: a catalog's Owner, for data locking, while the catalog has an owner;</li>
     * <li>{@code actionPeriodUse}: a chart of calculation types' ActionPeriodIsBasic, for data
     * locking.</li>
     * </ul>
     * <p>No other property of the eight kinds decides either list: a chart of accounts' Parent is
     * lockable unconditionally and its Order is never offered, whatever its {@code orderLength}; a
     * task's RoutePoint and BusinessProcess, a business process's Started / Completed / HeadTask and
     * every Date and DeletionMark are lockable unconditionally; Posted and ValueType are never offered.
     * Keyed by the feature name, which every kind declaring the property shares - so on an exchange
     * plan, whose Code and Description EDT offers for input by string whatever the lengths, the guard
     * costs a second call there; for data locking an exchange plan's lengths do decide them, through
     * EDT's validator ({@code validatorRejects}).</p>
     */
    public static final Map<String, Map<SourceType, List<String>>> STANDARD_FIELDS_DECIDED_BY;

    static
    {
        Map<String, Map<SourceType, List<String>>> table = new LinkedHashMap<>();
        decides(table, MdClassPackage.Literals.CATALOG__CODE_LENGTH, "Code", SourceType.INPUT_BY_STRING, //$NON-NLS-1$
            SourceType.FIELDS_FOR_LOCKING);
        decides(table, MdClassPackage.Literals.CATALOG__DESCRIPTION_LENGTH, "Description", //$NON-NLS-1$
            SourceType.INPUT_BY_STRING, SourceType.FIELDS_FOR_LOCKING);
        decides(table, MdClassPackage.Literals.DOCUMENT__NUMBER_LENGTH, "Number", SourceType.INPUT_BY_STRING, //$NON-NLS-1$
            SourceType.FIELDS_FOR_LOCKING);
        decides(table, MdClassPackage.Literals.CATALOG__HIERARCHICAL, "Parent", SourceType.FIELDS_FOR_LOCKING); //$NON-NLS-1$
        decides(table, MdClassPackage.Literals.CATALOG__HIERARCHICAL, "IsFolder", SourceType.FIELDS_FOR_LOCKING); //$NON-NLS-1$
        decides(table, MdClassPackage.Literals.CATALOG__HIERARCHY_TYPE, "IsFolder", SourceType.FIELDS_FOR_LOCKING); //$NON-NLS-1$
        decides(table, MdClassPackage.Literals.CATALOG__OWNERS, "Owner", SourceType.FIELDS_FOR_LOCKING); //$NON-NLS-1$
        decides(table, MdClassPackage.Literals.CHART_OF_CALCULATION_TYPES__ACTION_PERIOD_USE, "ActionPeriodIsBasic", //$NON-NLS-1$
            SourceType.FIELDS_FOR_LOCKING);
        for (Map.Entry<String, Map<SourceType, List<String>>> entry : table.entrySet())
        {
            entry.getValue().replaceAll((source, fields) -> Collections.unmodifiableList(fields));
            entry.setValue(Collections.unmodifiableMap(entry.getValue()));
        }
        STANDARD_FIELDS_DECIDED_BY = Collections.unmodifiableMap(table);
    }

    /**
     * How many addresses a listing prints before {@link Pagination#boundedListing} counts the rest - 25,
     * as {@link AppearanceValueBuilder} lists its candidates. An ERP-size document offers every lockable
     * attribute for data locking, which is several KB per row otherwise.
     */
    public static final int MAX_LISTED = 25;

    /** EDT's prefix of a standard field (MdFieldInferrer.newFixedField), and its address's kind segment. */
    static final String STANDARD_ATTRIBUTE = "StandardAttribute"; //$NON-NLS-1$

    /** The prefix of a common attribute's field: its address is the attribute's own two-segment name. */
    static final String COMMON_ATTRIBUTE = "CommonAttribute"; //$NON-NLS-1$

    /** Where a field sits inside its owner's BM URI: {@code /fields:}. */
    private static final String FIELDS_PATH = "/" + McorePackage.Literals.FIELD_SOURCE__FIELDS.getName() + ":"; //$NON-NLS-1$ //$NON-NLS-2$

    private static final String GRAMMAR = "A field address is '<Type>.<Object>.<Kind>.<Name>' - e.g. " //$NON-NLS-1$
        + "'Catalog.Goods.StandardAttribute.Description' or 'Catalog.Goods.Attribute.Weight' (Kind: " //$NON-NLS-1$
        + "StandardAttribute / Attribute / AddressingAttribute / AccountingFlag, in English or Russian) - " //$NON-NLS-1$
        + "or 'CommonAttribute.<Name>' for a common attribute's field, as get_metadata_details with " //$NON-NLS-1$
        + "assignable:true lists them."; //$NON-NLS-1$

    private static final String INPUT_BY_STRING_RULE = "EDT offers a standard attribute (Code / Description " //$NON-NLS-1$
        + "/ Number) only while its length (codeLength / descriptionLength / numberLength) is not 0, and an " //$NON-NLS-1$
        + "attribute only when it has a single String or Number type and is indexed (indexing other than " //$NON-NLS-1$
        + "DontIndex); a task's addressing attribute is never accepted, because EDT's validator rejects it"; //$NON-NLS-1$

    private static final String DATA_LOCK_RULE = "EDT offers a field for data locking only when it can " //$NON-NLS-1$
        + "be locked: a String field needs a non-zero length, a ValueStorage or TypeDescription field never " //$NON-NLS-1$
        + "qualifies, and a standard field only while the object has it (a Code / Description / Number " //$NON-NLS-1$
        + "with a non-zero length, a Parent on a hierarchical object, an IsFolder on a hierarchical catalog " //$NON-NLS-1$
        + "of HierarchyFoldersAndItems or a hierarchical chart of characteristic types, an Owner on a " //$NON-NLS-1$
        + "subordinate one, an ActionPeriodIsBasic while actionPeriodUse is true)"; //$NON-NLS-1$

    private static final String INPUT_BY_STRING_VALIDATOR_RULE = "EDT's property editor offers it, but EDT's " //$NON-NLS-1$
        + "validator accepts a derived input-by-string field only from an attribute or a common attribute and " //$NON-NLS-1$
        + "marks anything else - a task's addressing attribute - as an error"; //$NON-NLS-1$

    private static final String DATA_LOCK_VALIDATOR_RULE = "EDT's property editor offers it, but EDT's validator " //$NON-NLS-1$
        + "accepts an exchange plan's Code and Description for data locking only while their length " //$NON-NLS-1$
        + "(codeLength / descriptionLength) is not 0, never its ExchangeDate, and marks such an entry as an " //$NON-NLS-1$
        + "error"; //$NON-NLS-1$

    private FieldListSupport()
    {
        // utility class
    }

    /**
     * Whether {@code feature} is a field list: a non-containment, many-valued reference to mcore
     * {@code Field} that is in {@link #ELIGIBILITY}. Classified by the target type (the rule the
     * introspector follows for every kind), gated by the measured table.
     *
     * @param feature the feature (may be {@code null})
     * @return whether it is one of the two field lists
     */
    public static boolean isFieldListFeature(EStructuralFeature feature)
    {
        if (!(feature instanceof EReference))
        {
            return false;
        }
        EReference reference = (EReference)feature;
        EClass target = reference.getEReferenceType();
        return !reference.isContainment() && reference.isMany() && target != null
            && McorePackage.Literals.FIELD.isSuperTypeOf(target) && ELIGIBILITY.containsKey(reference);
    }

    /**
     * Whether {@code owner} is an object a configuration extension ADOPTED from its base configuration.
     * An extension cannot change an adopted object's field lists: none of EDT's 21 adoption property
     * delegates (md.extension_v8.3.8 .. v8.5.1) maps either feature, {@code MdClassExtension.xcore}
     * declares no state for them, and EDT's own editor hides both lists on an adopted object.
     *
     * @param owner the object (may be {@code null})
     * @return whether it is adopted
     */
    public static boolean isAdopted(EObject owner)
    {
        return owner instanceof MdObject && ((MdObject)owner).getObjectBelonging() == ObjectBelonging.ADOPTED;
    }

    /**
     * The Allowed values of a field list: the addresses EDT offers for it, or none on an adopted object,
     * which cannot change the list at all.
     *
     * @param owner the object holding the list
     * @param feature the list feature
     * @return the offered addresses in model order (never {@code null})
     */
    public static List<String> allowedValues(EObject owner, EStructuralFeature feature)
    {
        return isAdopted(owner) ? Collections.emptyList() : eligibleAddresses(owner, feature);
    }

    /**
     * The addresses of the fields of {@code owner} EDT offers for {@code feature}, in model order.
     * Never throws: a field list that cannot be read offers nothing, and the preparation reports that
     * case on its own ({@link #resolve}).
     *
     * @param owner the object holding the list
     * @param feature the list feature
     * @return the offered addresses (never {@code null})
     */
    public static List<String> eligibleAddresses(EObject owner, EStructuralFeature feature)
    {
        SourceType source = ELIGIBILITY.get(feature);
        if (source == null || !(owner instanceof FieldSource))
        {
            return Collections.emptyList();
        }
        try
        {
            List<String> addresses = new ArrayList<>();
            for (Field field : ((FieldSource)owner).getFields())
            {
                String address = isOffered(owner, field, source) ? addressOf(field) : null;
                if (address != null)
                {
                    addresses.add(address);
                }
            }
            return addresses;
        }
        catch (RuntimeException e)
        {
            return Collections.emptyList();
        }
    }

    /**
     * The address EDT stores for one entry of a field list, from either form the entry takes: a stored
     * PROXY (its BM URI is decoded, {@link #addressOfUri}) or a field the proxy resolved to (its owner's
     * address, prefix and name). The two forms of the same entry give the same string.
     *
     * <p>For a resolved field this ports the 3-line naming rule of
     * {@code MdSymbolicNameServiceDelegate.generateFieldSymbolicName} instead of calling
     * {@code MdUtil.getFullyQualifiedName}: that one needs Guice-backed providers, and for a derived
     * field it returns the SOURCE object's FQN - which, for the five prefixes a BasicDbObject's own
     * fields carry, is exactly owner + prefix + name.</p>
     *
     * @param element a list entry (may be {@code null} or anything else)
     * @return the address, or {@code null} when it cannot be determined
     */
    public static String addressOf(Object element)
    {
        if (!(element instanceof EObject))
        {
            return null;
        }
        EObject object = (EObject)element;
        if (object.eIsProxy())
        {
            return object instanceof InternalEObject ? addressOfUri(((InternalEObject)object).eProxyURI()) : null;
        }
        if (!(object instanceof Field))
        {
            return null;
        }
        Field field = (Field)object;
        EObject owner = field.eContainer();
        String prefix = field.getFieldIdPrefix();
        String name = field.getName();
        if (!(owner instanceof MdObject) || isBlank(prefix) || isBlank(name))
        {
            return null;
        }
        return symbolicName(MetadataNodeResolver.addressOf((MdObject)owner), prefix, name);
    }

    /**
     * The address of a stored proxy, decoded from its BM URI exactly as
     * {@code MdSymbolicNameServiceDelegate.generateFieldSymbolicName(URI)} does: the top object's FQN,
     * then the field id after {@code /fields:} split at its first colon.
     *
     * @param uri the proxy URI (may be {@code null})
     * @return the address, or {@code null} for anything that is not a field of a top object
     */
    static String addressOfUri(URI uri)
    {
        try
        {
            if (uri == null || !BmUriUtil.isBmUri(uri))
            {
                return null;
            }
            String top = BmUriUtil.extractTopObjectFqn(uri);
            String path = BmUriUtil.extractContainmentPath(uri);
            if (isBlank(top) || path == null || !path.startsWith(FIELDS_PATH))
            {
                return null;
            }
            String encoded = path.substring(FIELDS_PATH.length());
            if (encoded.isEmpty() || encoded.indexOf('/') >= 0)
            {
                return null;
            }
            String fieldId = BmUriUtil.decodeCollectionItemId(encoded);
            int colon = fieldId == null ? -1 : fieldId.indexOf(':');
            if (colon <= 0 || colon == fieldId.length() - 1)
            {
                return null;
            }
            return symbolicName(top, fieldId.substring(0, colon), fieldId.substring(colon + 1));
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /**
     * The symbolic name of a field: {@code CommonAttribute.<name>} for a common attribute's field,
     * {@code <owner>.<prefix>.<name>} for every other.
     */
    static String symbolicName(String ownerAddress, String prefix, String name)
    {
        return COMMON_ATTRIBUTE.equals(prefix) ? COMMON_ATTRIBUTE + '.' + name
            : ownerAddress + '.' + prefix + '.' + name;
    }

    /**
     * Resolves a field-list value to the field ids it names, refusing anything EDT would not hold.
     *
     * <p>The value is a JSON array of addresses (a bare address is a one-element list; {@code []}
     * clears). Each address must name a field of {@code owner} ITSELF, and that field must be offered
     * for this list - judged on the object as it stands, before the call. Every refusal names what it
     * refused, and a refused address also lists what IS offered; nothing is resolved on a refusal.</p>
     *
     * @param owner the object holding the list
     * @param feature the list feature (one of {@link #ELIGIBILITY})
     * @param property the property name as the call spelled it, for the messages
     * @param value the wire value
     * @param fieldIdsOut receives the field ids in order, only when everything resolved
     * @return a plain-text refusal, or {@code null} on success
     */
    public static String resolve(EObject owner, EStructuralFeature feature, String property, JsonElement value,
        List<String> fieldIdsOut)
    {
        SourceType source = ELIGIBILITY.get(feature);
        if (source == null || !(owner instanceof FieldSource) || !(owner instanceof MdObject))
        {
            return "'" + property + "' is not a field list of this object."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        String ownerAddress = MetadataNodeResolver.addressOf((MdObject)owner);
        if (isAdopted(owner))
        {
            return "'" + property + "' of " + ownerAddress + " cannot be changed here: " + ownerAddress //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " is an adopted object, and an extension cannot change an adopted object's " //$NON-NLS-1$
                + "input-by-string or data-lock fields (EDT keeps no extension state for them and its own " //$NON-NLS-1$
                + "editor hides both lists). Change it in the base configuration."; //$NON-NLS-1$
        }
        List<String> entries = new ArrayList<>();
        String shapeError = readEntries(property, value, entries);
        if (shapeError != null)
        {
            return shapeError;
        }
        if (entries.isEmpty())
        {
            return null;
        }
        List<Field> fields = fieldsOf(owner);
        if (fields.isEmpty())
        {
            // Unknown, not "nothing offered": derived data EDT has not built yet.
            return "EDT has not computed the fields of " + ownerAddress + " yet, so '" + entries.get(0) //$NON-NLS-1$ //$NON-NLS-2$
                + "' cannot be checked. Nothing was written; retry in a moment (an empty list, [], needs " //$NON-NLS-1$
                + "no check)."; //$NON-NLS-1$
        }
        List<String> offered = eligibleAddresses(owner, feature);
        List<String> current = currentAddresses(owner, feature);
        List<String> ids = new ArrayList<>();
        List<String> spellings = new ArrayList<>();
        for (String entry : entries)
        {
            Target target = new Target();
            String parseError = parse(entry, (MdObject)owner, ownerAddress, property, target);
            if (parseError != null)
            {
                return parseError + offeredSentence(property, ownerAddress, offered);
            }
            Field match = find(fields, target.kind, target.name);
            if (match == null)
            {
                String why = containsIgnoreCase(current, target.canonical)
                    ? "' is in the current list, but " + ownerAddress //$NON-NLS-1$
                        + " no longer has that field - drop it from the list." //$NON-NLS-1$
                    : "': " + ownerAddress + " has no field '" + target.kind + '.' + target.name //$NON-NLS-1$ //$NON-NLS-2$
                        + "' (a field is named by its programmatic Name, never by its synonym)."; //$NON-NLS-1$
                return "'" + entry + why + offeredSentence(property, ownerAddress, offered); //$NON-NLS-1$
            }
            if (!isOffered(owner, match, source))
            {
                String address = addressOf(match);
                boolean inCurrent = address != null && containsIgnoreCase(current, address);
                String why;
                String rule;
                if (match.getSourceTypes().contains(source))
                {
                    // EDT's editor offers it and only its validator rejects it (validatorRejects): saying
                    // "no longer offered" would contradict the rule printed right after it.
                    why = inCurrent
                        ? "' is in the current list, but EDT's validator rejects it in '" + property //$NON-NLS-1$
                            + "' - drop it. " //$NON-NLS-1$
                        : "' is a field of " + ownerAddress + " that EDT's validator rejects in '" + property //$NON-NLS-1$ //$NON-NLS-2$
                            + "'. "; //$NON-NLS-1$
                    rule = source == SourceType.INPUT_BY_STRING ? INPUT_BY_STRING_VALIDATOR_RULE
                        : DATA_LOCK_VALIDATOR_RULE;
                }
                else
                {
                    why = inCurrent
                        ? "' is in the current list, but EDT no longer offers it for '" + property + "' - drop it. " //$NON-NLS-1$ //$NON-NLS-2$
                        : "' is a field of " + ownerAddress + ", but EDT does not offer it for '" + property + "'. "; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    rule = source == SourceType.INPUT_BY_STRING ? INPUT_BY_STRING_RULE : DATA_LOCK_RULE;
                }
                return "'" + entry + why + rule + '.' + offeredSentence(property, ownerAddress, offered); //$NON-NLS-1$
            }
            String id = fieldIdOf(match);
            int earlier = ids.indexOf(id);
            if (earlier >= 0)
            {
                return "'" + property + "' names the field " + addressOf(match) + " twice (as '" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + spellings.get(earlier) + "' and as '" + entry + "'). Give each field once."; //$NON-NLS-1$ //$NON-NLS-2$
            }
            ids.add(id);
            spellings.add(entry);
        }
        fieldIdsOut.addAll(ids);
        return null;
    }

    /**
     * The proxy URI of a field id in a top object - the platform's initializer recipe:
     * {@code createContainedBmObjectUri(engineId, topFqn, "/fields:" + encodeCollectionItemId(fieldId))}.
     */
    static URI proxyUri(String engineId, String topFqn, String fieldId)
    {
        return BmUriUtil.createContainedBmObjectUri(engineId, topFqn,
            FIELDS_PATH + BmUriUtil.encodeCollectionItemId(fieldId));
    }

    /**
     * Replaces the list on {@code target} - the object as re-fetched inside the write transaction - by
     * proxies of {@code fieldIds}. Only field ids cross the prepare/write boundary: the derived fields
     * may have been rebuilt in between, and a proxy is what EDT itself stores and resolves later.
     *
     * @param target the top object in the write transaction
     * @param feature the list feature
     * @param fieldIds the field ids, in order
     * @throws IllegalStateException when {@code target} is not an attached BM top object
     */
    public static void apply(EObject target, EStructuralFeature feature, List<String> fieldIds)
    {
        IBmObject top = target instanceof IBmObject ? (IBmObject)target : null;
        IBmEngine engine = top != null && top.bmIsTop() ? top.bmGetEngine() : null;
        String topFqn = engine != null ? top.bmGetFqn() : null;
        if (engine == null || isBlank(engine.getId()) || isBlank(topFqn))
        {
            throw new IllegalStateException("'" + feature.getName() //$NON-NLS-1$
                + "' can only be written to a top object attached to a BM engine, " //$NON-NLS-1$
                + "which addresses its fields"); //$NON-NLS-1$
        }
        List<URI> uris = new ArrayList<>();
        for (String fieldId : fieldIds)
        {
            uris.add(proxyUri(engine.getId(), topFqn, fieldId));
        }
        replace(target, feature, uris);
    }

    /**
     * Replaces the whole list by proxies of {@code proxyUris}, in order.
     *
     * <p>Deliberately NOT bracketed in {@code eSetDeliver(false)} / {@code eSetDeliver(true)} the way
     * AbstractBasicDbObjectInitializer.updateReferences is: the initializer fills a brand-new object,
     * while this changes an existing one, and the change notification is what tells BM's transaction
     * ({@code BmObject.eNotify} hands it to the transaction service) and EDT's onChange revalidation
     * rules for these features that the list changed.</p>
     */
    @SuppressWarnings("unchecked")
    static void replace(EObject target, EStructuralFeature feature, List<URI> proxyUris)
    {
        EList<EObject> list = (EList<EObject>)target.eGet(feature);
        List<EObject> proxies = new ArrayList<>();
        for (URI uri : proxyUris)
        {
            Field proxy = McoreFactory.eINSTANCE.createField();
            ((InternalEObject)proxy).eSetProxyURI(uri);
            proxies.add(proxy);
        }
        list.clear();
        list.addAll(proxies);
    }

    /**
     * The address of the standard field a field-list value NAMES whose offer for that list
     * {@code property} decides ({@link #STANDARD_FIELDS_DECIDED_BY}), or {@code null} when it names no
     * such field - the question of modify_metadata's batch guard: such a list may not share a call with
     * that property, every other list may. A property decides a field only for the lists it is listed
     * for: {@code hierarchical} never makes Parent an input-by-string field, so a Parent in
     * {@code inputByString} is left to {@link #resolve}'s own refusal, which no second call would lift.
     *
     * <p>Read with the parser and the field lookup {@link #resolve} uses, minus every eligibility
     * check: the question is which field an entry names, not whether EDT offers it now - so the answer
     * is the same whether the property is about to take the field away or to bring it back. A value of
     * the wrong shape, or an entry the parser cannot place, is skipped - {@link #resolve} refuses it on
     * its own, so nothing is written either way. A standard field named in English is recognized even
     * before EDT has computed the fields; a Russian name is looked up in them.</p>
     *
     * @param owner the object holding the list
     * @param list the list feature (one of {@link #ELIGIBILITY})
     * @param value the list's wire value
     * @param property the property batched with it (a key of {@link #STANDARD_FIELDS_DECIDED_BY})
     * @return the canonical address of the decided field the value names, or {@code null}
     */
    public static String namedFieldGovernedBy(EObject owner, EStructuralFeature list, JsonElement value,
        String property)
    {
        List<String> decided = fieldsDecidedBy(property, list);
        if (decided.isEmpty() || !(owner instanceof MdObject))
        {
            return null;
        }
        // The reader and the parser take a property name only to word a refusal; none is wanted here.
        List<String> entries = new ArrayList<>();
        if (readEntries(property, value, entries) != null)
        {
            return null;
        }
        MdObject object = (MdObject)owner;
        String ownerAddress = MetadataNodeResolver.addressOf(object);
        List<Field> fields = owner instanceof FieldSource ? fieldsOf(owner) : Collections.<Field> emptyList();
        for (String entry : entries)
        {
            Target target = new Target();
            if (parse(entry, object, ownerAddress, property, target) != null
                || !STANDARD_ATTRIBUTE.equals(target.kind))
            {
                continue;
            }
            Field match = find(fields, target.kind, target.name);
            String named = match != null ? match.getName() : target.name;
            for (String field : decided)
            {
                if (field.equalsIgnoreCase(named))
                {
                    return symbolicName(ownerAddress, STANDARD_ATTRIBUTE, field);
                }
            }
        }
        return null;
    }

    // ---- internals ------------------------------------------------------------------------------

    /**
     * Adds one row to {@link #STANDARD_FIELDS_DECIDED_BY}: {@code property} decides {@code field} for
     * {@code lists}.
     */
    private static void decides(Map<String, Map<SourceType, List<String>>> table, EStructuralFeature property,
        String field, SourceType... lists)
    {
        Map<SourceType, List<String>> byList = table.computeIfAbsent(property.getName(),
            name -> new EnumMap<>(SourceType.class));
        for (SourceType list : lists)
        {
            byList.computeIfAbsent(list, source -> new ArrayList<>()).add(field);
        }
    }

    /** The standard fields {@code property} decides for {@code list} ({@link #STANDARD_FIELDS_DECIDED_BY}). */
    private static List<String> fieldsDecidedBy(String property, EStructuralFeature list)
    {
        Map<SourceType, List<String>> byList = property == null ? null : STANDARD_FIELDS_DECIDED_BY.get(property);
        List<String> fields = byList == null ? null : byList.get(ELIGIBILITY.get(list));
        return fields == null ? Collections.<String> emptyList() : fields;
    }

    /** One parsed address: the kind (a field id prefix), the field name as given, the canonical form. */
    private static final class Target
    {
        String kind;
        String name;
        String canonical;
    }

    /**
     * Whether {@code field} of {@code owner} is offered for the list of {@code source}: the editor's
     * rule, narrowed by the validator's.
     */
    private static boolean isOffered(EObject owner, Field field, SourceType source)
    {
        return field != null && field.getSourceTypes().contains(source) && !validatorRejects(owner, field, source);
    }

    /**
     * Whether EDT's model validator rejects {@code field} of {@code owner} in the list of
     * {@code source} although EDT's editor offers it. On the eight kinds the validator is narrower than
     * MdFieldInferrer in two places (md 27.1 / 28.0, metadata 19.0 / 19.1):
     * <ul>
     * <li>input by string: ValidationUtil.isInputByStringReady accepts a derived field only from an
     * object attribute or a common attribute, so a task's addressing attribute is marked (error 4);</li>
     * <li>data locking on an exchange plan: FixedFieldDescriptorProvider.createExchangePlanDataLockFields
     * admits Code and Description only while their length is not 0 and has no ExchangeDate at all,
     * while the inferrer gives all three FIELDS_FOR_LOCKING whatever the lengths, so they are marked
     * (error 81). ValidationUtil.isDataLockReady judges a standard field by its name against that
     * table, and a derived field by its type, of which the inferrer never offers more. On the seven
     * other kinds every standard field the inferrer offers for data locking is in the validator's
     * table.</li>
     * </ul>
     */
    private static boolean validatorRejects(EObject owner, Field field, SourceType source)
    {
        if (source == SourceType.FIELDS_FOR_LOCKING)
        {
            return owner instanceof ExchangePlan && !(field instanceof DerivedField)
                && exchangePlanLockRejects((ExchangePlan)owner, field.getName());
        }
        if (source != SourceType.INPUT_BY_STRING || !(field instanceof DerivedField))
        {
            return false;
        }
        EObject origin = ((DerivedField)field).getSource();
        return !(origin instanceof DbObjectAttribute || origin instanceof CommonAttribute);
    }

    /**
     * Whether EDT's validator refuses the exchange plan's standard field {@code name} for data locking
     * ({@link #validatorRejects}).
     */
    private static boolean exchangePlanLockRejects(ExchangePlan plan, String name)
    {
        return "ExchangeDate".equals(name) //$NON-NLS-1$
            || ("Code".equals(name) && plan.getCodeLength() <= 0) //$NON-NLS-1$
            || ("Description".equals(name) && plan.getDescriptionLength() <= 0); //$NON-NLS-1$
    }

    /** Reads the wire value into address strings: an array of strings, or one string. */
    private static String readEntries(String property, JsonElement value, List<String> out)
    {
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString())
        {
            out.add(value.getAsString());
            return null;
        }
        if (value == null || !value.isJsonArray())
        {
            return "'" + property + "' takes a JSON array of field addresses, e.g. " //$NON-NLS-1$ //$NON-NLS-2$
                + "[\"Catalog.Goods.StandardAttribute.Description\"]; a single address string is a one-element " //$NON-NLS-1$
                + "list, and [] clears it. Got: " //$NON-NLS-1$
                + (value == null || value.isJsonNull() ? "nothing" : value.toString()) + ". " + GRAMMAR; //$NON-NLS-1$ //$NON-NLS-2$
        }
        int index = 0;
        for (JsonElement element : value.getAsJsonArray())
        {
            if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
            {
                return "Element at index " + index + " of '" + property + "' (" + element //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + ") is not a field address string. " + GRAMMAR; //$NON-NLS-1$
            }
            out.add(element.getAsString());
            index++;
        }
        return null;
    }

    /**
     * Parses one address against {@code owner}: the shape, the owner self-check and the kind token.
     * The owner part only re-states an object the call has already resolved, so it is compared
     * ё-insensitively; it must still be THIS object - a field list holds the object's own fields.
     */
    private static String parse(String entry, MdObject owner, String ownerAddress, String property, Target out)
    {
        String[] parts = entry.split("\\.", -1); //$NON-NLS-1$
        for (String part : parts)
        {
            if (part.isEmpty())
            {
                return "'" + entry + "' is not a field address. " + GRAMMAR; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        if (parts.length == 2 && COMMON_ATTRIBUTE.equals(MetadataTypeUtils.toEnglishSingular(parts[0])))
        {
            out.kind = COMMON_ATTRIBUTE;
            out.name = parts[1];
            out.canonical = COMMON_ATTRIBUTE + '.' + parts[1];
            return null;
        }
        if (parts.length != 4)
        {
            return "'" + entry + "' is not a field address. " + GRAMMAR; //$NON-NLS-1$ //$NON-NLS-2$
        }
        String type = MetadataTypeUtils.toEnglishSingular(parts[0]);
        if (type == null)
        {
            return "'" + entry + "': '" + parts[0] + "' is not a metadata type. '" + property + "' of " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                + ownerAddress + " holds fields of " + ownerAddress + " itself, addressed as '" + ownerAddress //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + ".<Kind>.<Name>'."; //$NON-NLS-1$
        }
        boolean sameType = type.equals(owner.eClass().getName());
        boolean sameObject = owner.getName() != null && MdNameNormalizer.normalizeYo(parts[1])
            .equalsIgnoreCase(MdNameNormalizer.normalizeYo(owner.getName()));
        if (!sameType || !sameObject)
        {
            return "'" + entry + "' names a field of " + type + '.' + parts[1] //$NON-NLS-1$ //$NON-NLS-2$
                + ", but '" + property + "' of " + ownerAddress + " holds fields of " + ownerAddress //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " itself only."; //$NON-NLS-1$
        }
        MetadataTypeUtils.NestedKindInfo kind = MetadataTypeUtils.resolveNestedKind(parts[2]);
        if (kind == null)
        {
            return "'" + entry + "': '" + parts[2] + "' is not a field kind - use StandardAttribute / " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "Attribute / AddressingAttribute / AccountingFlag (English or Russian); a common " //$NON-NLS-1$
                + "attribute's field is 'CommonAttribute.<Name>'."; //$NON-NLS-1$
        }
        out.kind = kind.getEnglish();
        out.name = parts[3];
        out.canonical = symbolicName(ownerAddress, out.kind, out.name);
        return null;
    }

    /**
     * The field of that kind and name - the kind compared with the field's own prefix, the name with
     * its English {@code name} and its Russian {@code nameRu}, case aside. The Russian names of the
     * standard fields are the model's own: MdFieldInferrer writes them as literals into
     * {@code nameRu}, so no list of names is kept here. An attribute's derived field carries its
     * programmatic Name as both {@code name} and {@code nameRu} (MdFieldInferrer.newDerivedField); a
     * common attribute's field has NO {@code nameRu} (CommonAttributeFieldInferrer sets it to null, md
     * 27.1 and 28.0), so it is matched by its Name alone, and the comparison is null-safe.
     */
    private static Field find(List<Field> fields, String kind, String name)
    {
        for (Field field : fields)
        {
            if (field != null && kind.equalsIgnoreCase(field.getFieldIdPrefix())
                && (name.equalsIgnoreCase(field.getName()) || name.equalsIgnoreCase(field.getNameRu())))
            {
                return field;
            }
        }
        return null;
    }

    private static List<Field> fieldsOf(EObject owner)
    {
        try
        {
            return new ArrayList<>(((FieldSource)owner).getFields());
        }
        catch (RuntimeException e)
        {
            return Collections.emptyList();
        }
    }

    /** The addresses the list holds now; an unreadable list only loses the "drop it" wording. */
    private static List<String> currentAddresses(EObject owner, EStructuralFeature feature)
    {
        List<String> addresses = new ArrayList<>();
        try
        {
            Object value = owner.eGet(feature);
            if (value instanceof EList<?>)
            {
                for (Object element : (EList<?>)value)
                {
                    String address = addressOf(element);
                    if (address != null)
                    {
                        addresses.add(address);
                    }
                }
            }
        }
        catch (RuntimeException e)
        {
            // only the wording of a refusal depends on it
        }
        return addresses;
    }

    /** {@code prefix + ":" + name} - the model's own derived fieldId, computed when it is not set. */
    private static String fieldIdOf(Field field)
    {
        String id = field.getFieldId();
        return id != null ? id : field.getFieldIdPrefix() + ':' + field.getName();
    }

    private static String offeredSentence(String property, String ownerAddress, List<String> offered)
    {
        return offered.isEmpty()
            ? " EDT offers no field of " + ownerAddress + " for '" + property + "' right now." //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            : " Offered for '" + property + "': " + Pagination.boundedListing(offered, MAX_LISTED) + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static boolean containsIgnoreCase(List<String> values, String value)
    {
        for (String candidate : values)
        {
            if (candidate.equalsIgnoreCase(value))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlank(String value)
    {
        return value == null || value.isEmpty();
    }
}
