/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.metadata;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.StandardAttribute;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com.ditrix.edt.mcp.server.utils.MetadataLanguageUtils;

/**
 * Base class for metadata formatters with common utility methods.
 */
public abstract class AbstractMetadataFormatter implements IMetadataFormatter
{
    protected static final String YES = "Yes"; //$NON-NLS-1$
    protected static final String NO = "No"; //$NON-NLS-1$
    protected static final String DASH = "-"; //$NON-NLS-1$
    private static final String PROPERTY = "Property"; //$NON-NLS-1$
    private static final String VALUE = "Value"; //$NON-NLS-1$
    private static final String SYNONYM = "Synonym"; //$NON-NLS-1$

    /**
     * Per-section row cap for the unbounded EMF-reflection dumps
     * ({@link #formatAllDynamicProperties} over every structural feature, and
     * {@link #formatReferenceCollection} over every referencing object). Without
     * it, the {@code full:true} details of a heavily-referenced object can produce
     * an arbitrarily large section even before the global output guard runs. When a
     * section has more rows than this it is cut here and a single notice row records
     * how many were omitted; below the cap the output is byte-for-byte identical, so
     * ordinary objects are unaffected. This bounds the dump per section, while the
     * central {@code OutputSizeGuard} caps the assembled result as a whole.
     */
    protected static final int MAX_DYNAMIC_ROWS = 200;


    /**
     * Gets synonym for the specified language with fallback.
     */
    protected String getSynonym(EMap<String, String> synonymMap, String language)
    {
        return MetadataLanguageUtils.getSynonymForLanguage(synonymMap == null ? null : synonymMap.map(), language);
    }
    
    /**
     * Formats TypeDescription to a human-readable string.
     */
    protected String formatType(TypeDescription typeDesc)
    {
        if (typeDesc == null)
        {
            return DASH;
        }
        
        EList<TypeItem> types = typeDesc.getTypes();
        if (types == null || types.isEmpty())
        {
            return DASH;
        }
        
        StringBuilder sb = new StringBuilder();
        for (TypeItem typeItem : types)
        {
            if (sb.length() > 0)
            {
                sb.append(", "); //$NON-NLS-1$
            }
            String typeName = McoreUtil.getTypeName(typeItem);
            if (typeName == null || typeName.isEmpty())
            {
                typeName = McoreUtil.getTypeNameRu(typeItem);
            }
            if (typeName == null || typeName.isEmpty())
            {
                typeName = typeItem.getClass().getSimpleName();
            }
            sb.append(typeName);
        }
        
        return sb.length() > 0 ? sb.toString() : DASH;
    }
    
    /**
     * Escapes special markdown characters in table cells.
     */
    protected String escapeTableCell(String value)
    {
        if (value == null)
        {
            return DASH;
        }
        return value.replace("|", "\\|").replace("\r", "").replace("\n", " "); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    }
    
    /**
     * Formats boolean value.
     */
    protected String formatBoolean(boolean value)
    {
        return value ? YES : NO;
    }
    
    /**
     * Formats enum value - returns name or dash if null.
     */
    protected String formatEnum(Object enumValue)
    {
        if (enumValue == null)
        {
            return DASH;
        }
        return enumValue.toString();
    }
    
    /**
     * Starts a markdown table with given headers.
     */
    protected void startTable(StringBuilder sb, String... headers)
    {
        sb.append("| "); //$NON-NLS-1$
        for (int i = 0; i < headers.length; i++)
        {
            if (i > 0)
            {
                sb.append(" | "); //$NON-NLS-1$
            }
            sb.append(headers[i]);
        }
        sb.append(" |\n"); //$NON-NLS-1$
        
        sb.append("|"); //$NON-NLS-1$
        for (int i = 0; i < headers.length; i++)
        {
            sb.append("---|"); //$NON-NLS-1$
        }
        sb.append("\n"); //$NON-NLS-1$
    }
    
    /**
     * Adds a row to markdown table.
     */
    protected void addTableRow(StringBuilder sb, String... values)
    {
        sb.append("| "); //$NON-NLS-1$
        for (int i = 0; i < values.length; i++)
        {
            if (i > 0)
            {
                sb.append(" | "); //$NON-NLS-1$
            }
            sb.append(escapeTableCell(values[i]));
        }
        sb.append(" |\n"); //$NON-NLS-1$
    }
    
    /**
     * Adds a property row (Property | Value format).
     */
    protected void addPropertyRow(StringBuilder sb, String property, String value)
    {
        addTableRow(sb, property, value != null ? value : DASH);
    }
    
    /**
     * Adds a property row with boolean value.
     */
    protected void addPropertyRow(StringBuilder sb, String property, boolean value)
    {
        addTableRow(sb, property, formatBoolean(value));
    }
    
    /**
     * Adds a property row with integer value.
     */
    protected void addPropertyRow(StringBuilder sb, String property, int value)
    {
        addTableRow(sb, property, String.valueOf(value));
    }
    
    /**
     * Creates a section header.
     */
    protected void addSectionHeader(StringBuilder sb, String title)
    {
        sb.append("\n### ").append(title).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
    }
    
    /**
     * Creates main object header.
     */
    protected void addMainHeader(StringBuilder sb, String type, String name)
    {
        sb.append("## ").append(type).append(": ").append(name).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
    
    /**
     * Format basic properties common to all MdObjects.
     */
    protected void formatBasicProperties(StringBuilder sb, MdObject mdObject, String language)
    {
        addSectionHeader(sb, "Basic Properties"); //$NON-NLS-1$
        startTable(sb, PROPERTY, VALUE);
        addPropertyRow(sb, "Name", mdObject.getName()); //$NON-NLS-1$
        addPropertyRow(sb, SYNONYM, getSynonym(mdObject.getSynonym(), language));
        
        String comment = mdObject.getComment();
        if (comment != null && !comment.isEmpty())
        {
            addPropertyRow(sb, "Comment", comment); //$NON-NLS-1$
        }
    }
    
    /**
     * Format StandardAttributes section if the object has them.
     * StandardAttributes are only present in certain metadata types like BasicDbObject, BasicTabularSection, etc.
     * 
     * @param sb StringBuilder to append to
     * @param eObject The metadata object (will check if it has getStandardAttributes() method)
     * @param language Language for synonyms
     */
    protected void formatStandardAttributes(StringBuilder sb, EObject eObject, String language)
    {
        // Use reflection to check if this object has getStandardAttributes() method
        // This method exists in: BasicDbObject, BasicTabularSection, and Register types
        try
        {
            java.lang.reflect.Method method = eObject.getClass().getMethod("getStandardAttributes"); //$NON-NLS-1$
            @SuppressWarnings("unchecked")
            EList<StandardAttribute> standardAttributes = (EList<StandardAttribute>) method.invoke(eObject);
            
            if (standardAttributes != null && !standardAttributes.isEmpty())
            {
                addSectionHeader(sb, "StandardAttributes"); //$NON-NLS-1$
                startTable(sb, "Name", SYNONYM, "Fill Checking", "Full Text Search", "Password Mode", "Multi Line", "Quick Choice", "Create On Input", "Data History"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
                
                for (StandardAttribute attr : standardAttributes)
                {
                    String name = attr.getName();
                    String synonym = getSynonym(attr.getSynonym(), language);
                    
                    addTableRow(sb, 
                        name != null ? name : DASH,
                        synonym != null && !synonym.isEmpty() ? synonym : DASH,
                        formatEnum(attr.getFillChecking()),
                        formatEnum(attr.getFullTextSearch()),
                        formatBoolean(attr.isPasswordMode()),
                        formatBoolean(attr.isMultiLine()),
                        formatEnum(attr.getQuickChoice()),
                        formatEnum(attr.getCreateOnInput()),
                        formatEnum(attr.getDataHistory())
                    );
                }
            }
        }
        catch (NoSuchMethodException e)
        {
            // This object doesn't have StandardAttributes - perfectly normal
            // Do nothing
        }
        catch (Exception e)
        {
            // Log error but don't fail the entire formatting
            System.err.println("Error formatting StandardAttributes: " + e.getMessage()); //$NON-NLS-1$
            e.printStackTrace();
        }
    }
    
    // ========== Dynamic EMF Reflection Methods ==========
    
    /**
     * Dynamically format ALL properties of an EObject using EMF reflection.
     * This method iterates over all structural features and formats them.
     * 
     * @param sb StringBuilder to append to
     * @param eObject The object to format
     * @param language Language for synonyms
     * @param sectionTitle Title for this section (e.g., "All Properties")
     */
    protected void formatAllDynamicProperties(StringBuilder sb, EObject eObject, String language, String sectionTitle)
    {
        addSectionHeader(sb, sectionTitle);
        startTable(sb, PROPERTY, VALUE);

        List<EStructuralFeature> features = eObject.eClass().getEAllStructuralFeatures();

        // Bound the dump: a row is emitted only for a rendered property, and at most
        // MAX_DYNAMIC_ROWS rows are emitted, after which a single notice row records
        // how many properties were omitted. Below the cap this is a pure no-op.
        int rendered = 0;
        int omitted = 0;
        for (EStructuralFeature feature : features)
        {
            if (isSkippableFeature(feature))
            {
                continue;
            }

            Object value = eObject.eGet(feature);
            String valueStr = formatDynamicValue(value, language);

            // Show all properties, even empty ones - use empty string for null/empty values
            if (valueStr == null || valueStr.equals(DASH))
            {
                // Check if it's an empty collection/list
                if (value instanceof Collection && ((Collection<?>) value).isEmpty())
                {
                    valueStr = ""; //$NON-NLS-1$
                }
                else if (value == null)
                {
                    continue; // Skip truly null values
                }
            }

            if (rendered >= MAX_DYNAMIC_ROWS)
            {
                omitted++;
                continue;
            }
            addPropertyRow(sb, formatFeatureName(feature.getName()), valueStr);
            rendered++;
        }
        if (omitted > 0)
        {
            appendTruncatedRow(sb, omitted, "properties"); //$NON-NLS-1$
        }
    }

    /**
     * Decides whether a structural feature is excluded from the dynamic property
     * dump: derived/transient/volatile features (computed, not stored) and
     * containment references (handled separately as collections) are skipped.
     *
     * @param feature the structural feature to test
     * @return {@code true} if the feature should be skipped
     */
    private static boolean isSkippableFeature(EStructuralFeature feature)
    {
        // Skip derived, transient, and volatile features (they're computed, not stored)
        if (feature.isDerived() || feature.isTransient() || feature.isVolatile())
        {
            return true;
        }

        // Skip containment references (handled separately as collections)
        return feature instanceof EReference && ((EReference) feature).isContainment();
    }

    /**
     * Appends a single section-truncation notice row to a two-column table. Emitted
     * only when a reflection dump exceeded {@link #MAX_DYNAMIC_ROWS}; it tells the
     * agent how many rows were omitted and how to see more. Both cells go through
     * the shared row escaping (via {@link #addTableRow}), so the notice can never
     * break the table.
     *
     * @param sb the table being built
     * @param omitted the number of rows omitted (must be positive to be meaningful)
     * @param unit the plural noun describing the omitted rows (e.g. "properties")
     */
    protected void appendTruncatedRow(StringBuilder sb, int omitted, String unit)
    {
        addTableRow(sb, "[truncated]", //$NON-NLS-1$
            omitted + " more " + unit + " omitted (section capped at " //$NON-NLS-1$ //$NON-NLS-2$
                + MAX_DYNAMIC_ROWS + "; query a more specific fqn for the rest)"); //$NON-NLS-1$
    }
    
    /**
     * Dynamically format properties, separating simple attributes from references.
     */
    protected void formatDynamicPropertiesSeparated(StringBuilder sb, EObject eObject, String language)
    {
        // Collect simple attributes and references separately
        List<EAttribute> simpleAttributes = new ArrayList<>();
        List<EReference> singleReferences = new ArrayList<>();
        List<EReference> crossReferences = new ArrayList<>();
        classifySetFeatures(eObject, simpleAttributes, singleReferences, crossReferences);

        // Format simple attributes
        if (!simpleAttributes.isEmpty())
        {
            addSectionHeader(sb, "Properties"); //$NON-NLS-1$
            startTable(sb, PROPERTY, VALUE);
            appendFeatureValueRows(sb, eObject, simpleAttributes, language);
        }

        // Format single references (forms, modules, etc.)
        if (!singleReferences.isEmpty())
        {
            addSectionHeader(sb, "References"); //$NON-NLS-1$
            startTable(sb, PROPERTY, VALUE);
            appendFeatureValueRows(sb, eObject, singleReferences, language);
        }

        // Format cross-references (lists of references)
        if (!crossReferences.isEmpty())
        {
            for (EReference ref : crossReferences)
            {
                Object value = eObject.eGet(ref);
                if (value instanceof Collection && !((Collection<?>) value).isEmpty())
                {
                    formatReferenceCollection(sb, ref.getName(), (Collection<?>) value, language);
                }
            }
        }

        // Note: containment references are typically handled separately (attributes, tabular sections, etc.)
    }

    /**
     * Sorts the set, stored structural features of an object into the buckets used by
     * {@link #formatDynamicPropertiesSeparated}: simple attributes, single (non-many)
     * non-containment references, and many (cross) non-containment references.
     * Derived/transient/volatile and unset features are skipped, and containment
     * references are intentionally dropped (handled separately). Bucket population
     * order matches the original inline loop exactly.
     *
     * @param eObject the object whose features are inspected
     * @param simpleAttributes receives the set {@link EAttribute}s
     * @param singleReferences receives the set single non-containment references
     * @param crossReferences receives the set many non-containment references
     */
    private static void classifySetFeatures(EObject eObject, List<EAttribute> simpleAttributes,
        List<EReference> singleReferences, List<EReference> crossReferences)
    {
        List<EStructuralFeature> features = eObject.eClass().getEAllStructuralFeatures();
        for (EStructuralFeature feature : features)
        {
            if (isUnclassifiableFeature(eObject, feature))
            {
                continue;
            }
            classifyFeatureInto(feature, simpleAttributes, singleReferences, crossReferences);
        }
    }

    /**
     * Tests whether a feature is excluded from {@link #classifySetFeatures} before any
     * bucket routing: derived/transient/volatile (computed, not stored) and unset
     * features are skipped, matching the two leading {@code continue} guards of the
     * original inline loop.
     *
     * @param eObject the object whose feature-set state is checked
     * @param feature the structural feature to test
     * @return {@code true} if the feature should not be classified
     */
    private static boolean isUnclassifiableFeature(EObject eObject, EStructuralFeature feature)
    {
        if (feature.isDerived() || feature.isTransient() || feature.isVolatile())
        {
            return true;
        }
        return !eObject.eIsSet(feature);
    }

    /**
     * Routes one set, stored feature into its bucket: {@link EAttribute}s to
     * {@code simpleAttributes}, single non-containment references to
     * {@code singleReferences}, and many non-containment references to
     * {@code crossReferences}; containment references and non-attribute/reference
     * features are dropped. Matches the per-feature body of {@link #classifySetFeatures}.
     *
     * @param feature the feature to route
     * @param simpleAttributes receives a matching {@link EAttribute}
     * @param singleReferences receives a matching single non-containment reference
     * @param crossReferences receives a matching many non-containment reference
     */
    private static void classifyFeatureInto(EStructuralFeature feature, List<EAttribute> simpleAttributes,
        List<EReference> singleReferences, List<EReference> crossReferences)
    {
        if (feature instanceof EAttribute)
        {
            simpleAttributes.add((EAttribute) feature);
        }
        else if (feature instanceof EReference)
        {
            EReference ref = (EReference) feature;
            if (ref.isContainment())
            {
                // Containment references are typically handled separately.
                return;
            }
            if (!ref.isMany())
            {
                singleReferences.add(ref);
            }
            else
            {
                crossReferences.add(ref);
            }
        }
    }

    /**
     * Appends a Property/Value row for each given feature whose formatted value is
     * non-empty (skipping {@code null}, empty and {@link #DASH} values), matching the
     * inline attribute/single-reference rendering blocks in
     * {@link #formatDynamicPropertiesSeparated}. The table header must already have
     * been emitted by the caller.
     *
     * @param sb the table being built
     * @param eObject the object whose feature values are read
     * @param features the features to render, in order
     * @param language language for synonym rendering
     */
    private void appendFeatureValueRows(StringBuilder sb, EObject eObject,
        List<? extends EStructuralFeature> features, String language)
    {
        for (EStructuralFeature feature : features)
        {
            Object value = eObject.eGet(feature);
            String valueStr = formatDynamicValue(value, language);
            if (valueStr != null && !valueStr.isEmpty() && !valueStr.equals(DASH))
            {
                addPropertyRow(sb, formatFeatureName(feature.getName()), valueStr);
            }
        }
    }
    
    /**
     * Format a collection of references.
     */
    @SuppressWarnings("rawtypes")
    protected void formatReferenceCollection(StringBuilder sb, String name, Collection<?> collection, String language)
    {
        // Check if this is an EMap collection (contains Map.Entry items)
        Object firstItem = collection.iterator().next();
        if (firstItem instanceof java.util.Map.Entry)
        {
            formatEntryCollection(sb, name, collection);
            return;
        }

        addSectionHeader(sb, formatFeatureName(name) + " (" + collection.size() + ")"); //$NON-NLS-1$ //$NON-NLS-2$

        boolean first = true;
        // Bound the MdObject reference rows: a heavily-referenced object can list
        // an arbitrary number of referencing objects here. At most MAX_DYNAMIC_ROWS
        // FQN rows are emitted, then a single notice row records how many were
        // omitted. Below the cap the output is byte-for-byte identical.
        int mdRendered = 0;
        int mdOmitted = 0;
        for (Object item : collection)
        {
            if (item instanceof MdObject)
            {
                if (first)
                {
                    startTable(sb, "FQN", SYNONYM); //$NON-NLS-1$
                    first = false;
                }
                if (mdRendered >= MAX_DYNAMIC_ROWS)
                {
                    mdOmitted++;
                    continue;
                }
                appendMdObjectRow(sb, (MdObject) item, language);
                mdRendered++;
            }
            else if (item instanceof EObject)
            {
                appendInlineReference(sb, first, formatEObjectReference((EObject) item));
                first = false;
            }
            else if (item != null)
            {
                appendInlineReference(sb, first, item.toString());
                first = false;
            }
        }
        if (mdOmitted > 0)
        {
            appendTruncatedRow(sb, mdOmitted, "references"); //$NON-NLS-1$
        }
        if (!first && !(collection.iterator().next() instanceof MdObject))
        {
            sb.append("\n\n"); //$NON-NLS-1$
        }
    }

    /**
     * Renders an {@code EMap}-style collection of {@link java.util.Map.Entry} items
     * (such as Synonym or ObjectPresentation) as a Language/Value table, exactly as
     * the inline EMap branch of {@link #formatReferenceCollection} did. Null keys and
     * values are rendered as {@link #DASH}.
     *
     * @param sb the buffer being built
     * @param name the raw feature name used for the section header
     * @param collection the entry collection (must be non-empty with Map.Entry items)
     */
    @SuppressWarnings("rawtypes")
    private void formatEntryCollection(StringBuilder sb, String name, Collection<?> collection)
    {
        // For EMap collections like Synonym, ObjectPresentation - format as key-value table
        addSectionHeader(sb, formatFeatureName(name) + " (" + collection.size() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        startTable(sb, "Language", VALUE); //$NON-NLS-1$
        for (Object item : collection)
        {
            java.util.Map.Entry entry = (java.util.Map.Entry) item;
            String key = entry.getKey() != null ? entry.getKey().toString() : DASH;
            String value = entry.getValue() != null ? entry.getValue().toString() : DASH;
            addTableRow(sb, key, value);
        }
    }

    /**
     * Appends a single FQN/Synonym table row for a referencing {@link MdObject}, using
     * its {@code Type.Name} fully-qualified name (e.g. {@code Catalog.Products}) and
     * localized synonym. Matches the MdObject branch of
     * {@link #formatReferenceCollection}.
     *
     * @param sb the table being built
     * @param mdObj the referencing metadata object
     * @param language language for synonym rendering
     */
    private void appendMdObjectRow(StringBuilder sb, MdObject mdObj, String language)
    {
        // Show full FQN: Type.Name (e.g. Catalog.Products)
        String fqn = mdObj.eClass().getName() + "." + mdObj.getName(); //$NON-NLS-1$
        addTableRow(sb, fqn, getSynonym(mdObj.getSynonym(), language));
    }

    /**
     * Appends one item to the inline, comma-separated reference list used for
     * non-{@link MdObject} entries in {@link #formatReferenceCollection}: the first
     * rendered item is prefixed with {@code "- "} and subsequent items with
     * {@code ", "}.
     *
     * @param sb the buffer being built
     * @param first {@code true} if no inline item has been emitted yet
     * @param text the already-formatted item text to append
     */
    private void appendInlineReference(StringBuilder sb, boolean first, String text)
    {
        if (first)
        {
            sb.append("- "); //$NON-NLS-1$
        }
        else
        {
            sb.append(", "); //$NON-NLS-1$
        }
        sb.append(text);
    }
    
    /**
     * Format a dynamic value based on its type.
     */
    protected String formatDynamicValue(Object value, String language)
    {
        if (value == null)
        {
            return DASH;
        }
        
        // Handle EMap (synonyms, presentations, etc.)
        if (value instanceof EMap)
        {
            @SuppressWarnings("unchecked")
            EMap<String, String> map = (EMap<String, String>) value;
            String result = getSynonym(map, language);
            // If getSynonym returns empty, the map might have non-string values
            if (result != null && !result.isEmpty())
            {
                return result;
            }
            // Fall through to treat as collection
        }
        
        // Handle TypeDescription
        if (value instanceof TypeDescription)
        {
            return formatType((TypeDescription) value);
        }
        
        // Handle boolean
        if (value instanceof Boolean)
        {
            return formatBoolean((Boolean) value);
        }
        
        // Handle enum
        if (value.getClass().isEnum())
        {
            return formatEnum(value);
        }
        
        // Handle EObject references using EObjectInspector
        if (value instanceof EObject)
        {
            return formatEObjectValue((EObject) value);
        }

        // Handle collections
        if (value instanceof Collection)
        {
            return formatCollectionValue((Collection<?>) value);
        }

        // Default: toString
        return value.toString();
    }

    /**
     * Formats a single {@link EObject} value via {@link EObjectInspector}, choosing the
     * rendering by its detected {@code FormatStyle}: a simple wrapper yields its primary
     * value, every other style (reference, expand, default) yields the formatted
     * reference. Matches the inline EObject switch of {@link #formatDynamicValue}.
     *
     * @param eObj the object to render (non-{@code null})
     * @return the formatted value string
     */
    private static String formatEObjectValue(EObject eObj)
    {
        // Use EObjectInspector to determine the best format style
        EObjectInspector.FormatStyle style = EObjectInspector.getFormatStyle(eObj);

        switch (style)
        {
            case SIMPLE_VALUE:
                // Simple wrapper (like StandardCommandGroup) - get primary value
                return EObjectInspector.getPrimaryValueAsString(eObj);

            case REFERENCE:
                // MdObject reference - show as Type.Name
                return EObjectInspector.formatReference(eObj);

            case EXPAND:
                // Complex object - show basic info (expansion handled elsewhere)
                return EObjectInspector.formatReference(eObj);

            default:
                return EObjectInspector.formatReference(eObj);
        }
    }

    /**
     * Formats a collection value for {@link #formatDynamicValue}: empty yields
     * {@link #DASH}; up to five items are joined inline (per-item via
     * {@link #formatCollectionItem}); larger collections collapse to an
     * {@code [N items]} count. Behavior is identical to the inline collection branch.
     *
     * @param coll the collection to render (non-{@code null})
     * @return the formatted value string
     */
    private String formatCollectionValue(Collection<?> coll)
    {
        if (coll.isEmpty())
        {
            return DASH;
        }
        // For small collections, show inline
        if (coll.size() <= 5)
        {
            StringBuilder sb = new StringBuilder();
            for (Object item : coll)
            {
                if (sb.length() > 0) sb.append(", "); //$NON-NLS-1$
                formatCollectionItem(sb, item);
            }
            return sb.toString();
        }
        // For larger collections, just show count
        return "[" + coll.size() + " items]"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Appends one inline-collection item to {@code sb}, dispatching on its runtime type
     * exactly as the inner loop of {@link #formatDynamicValue} did: a {@link java.util.Map.Entry}
     * contributes its non-null value, an {@link MdObject} its {@code Type.Name} FQN, any
     * other {@link EObject} its formatted reference, and any other non-null item its
     * {@code toString()}. The leading separator is the caller's responsibility.
     *
     * @param sb the buffer being built
     * @param item the item to render (may be {@code null}, in which case nothing is appended)
     */
    private void formatCollectionItem(StringBuilder sb, Object item)
    {
        // Handle Map.Entry (LocalStringMapEntry, etc.)
        if (item instanceof java.util.Map.Entry)
        {
            java.util.Map.Entry<?, ?> entry = (java.util.Map.Entry<?, ?>) item;
            Object entryValue = entry.getValue();
            if (entryValue != null)
            {
                sb.append(entryValue.toString());
            }
        }
        else if (item instanceof MdObject)
        {
            // Show full FQN: Type.Name (e.g. Catalog.Products)
            MdObject mdObj = (MdObject) item;
            sb.append(mdObj.eClass().getName()).append(".").append(mdObj.getName()); //$NON-NLS-1$
        }
        else if (item instanceof EObject)
        {
            sb.append(formatEObjectReference((EObject) item));
        }
        else if (item != null)
        {
            sb.append(item.toString());
        }
    }
    
    /**
     * Format an EObject reference to a readable string.
     * Uses EObjectInspector for proper type detection.
     */
    protected String formatEObjectReference(EObject eObj)
    {
        if (eObj == null)
        {
            return DASH;
        }
        
        // Use EObjectInspector to format the reference
        return EObjectInspector.formatReference(eObj);
    }
    
    /**
     * Convert camelCase feature name to human-readable format.
     * e.g., "codeLength" -> "Code Length"
     */
    protected String formatFeatureName(String name)
    {
        if (name == null || name.isEmpty())
        {
            return name;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(Character.toUpperCase(name.charAt(0)));
        for (int i = 1; i < name.length(); i++)
        {
            char c = name.charAt(i);
            if (Character.isUpperCase(c))
            {
                sb.append(' ');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
