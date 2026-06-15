/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.metadata;

import java.util.Collection;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.metadata.mdclass.BasicCommand;
import com._1c.g5.v8.dt.metadata.mdclass.BasicFeature;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTabularSection;
import com._1c.g5.v8.dt.metadata.mdclass.CharacteristicsDescription;
import com._1c.g5.v8.dt.metadata.mdclass.DbObjectAttribute;
import com._1c.g5.v8.dt.mcore.ColorValue;
import com._1c.g5.v8.dt.mcore.FontValue;
import com._1c.g5.v8.dt.mcore.Value;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com._1c.g5.v8.dt.metadata.mdclass.StandardAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.StyleItem;
import com.ditrix.edt.mcp.server.utils.ExtensionOriginUtils;
import com.ditrix.edt.mcp.server.utils.StyleValueBuilder;

/**
 * Universal metadata formatter that can format any MdObject type
 * using dynamic EMF reflection.
 * 
 * This replaces all individual type-specific formatters (CatalogFormatter,
 * DocumentFormatter, etc.) with a single universal implementation.
 */
public class UniversalMetadataFormatter extends AbstractMetadataFormatter
{
    private static final UniversalMetadataFormatter INSTANCE = new UniversalMetadataFormatter();

    /** Table column / property label for a value cell. */
    private static final String VALUE_TOKEN = "Value"; //$NON-NLS-1$

    /** Table column / property label for a synonym cell. */
    private static final String SYNONYM_TOKEN = "Synonym"; //$NON-NLS-1$

    /** Table column label for an origin cell. */
    private static final String ORIGIN_TOKEN = "Origin"; //$NON-NLS-1$

    /**
     * Gets the singleton instance.
     */
    public static UniversalMetadataFormatter getInstance()
    {
        return INSTANCE;
    }
    
    @Override
    public String getMetadataType()
    {
        // Universal formatter can handle any type
        return "*"; //$NON-NLS-1$
    }
    
    @Override
    public boolean canFormat(MdObject mdObject)
    {
        // Can format any MdObject
        return mdObject != null;
    }
    
    @Override
    public String format(MdObject mdObject, boolean full, String language)
    {
        if (mdObject == null)
        {
            return "Error: MdObject is null"; //$NON-NLS-1$
        }
        
        StringBuilder sb = new StringBuilder();
        
        // Get type name dynamically from EMF class
        String typeName = mdObject.eClass().getName();
        
        addMainHeader(sb, typeName, mdObject.getName());
        
        if (full)
        {
            // Full mode: use dynamic EMF reflection to show ALL properties
            formatAllDynamicProperties(sb, mdObject, language, "All Properties"); //$NON-NLS-1$
            
            // Format StandardAttributes if present (BasicDbObject, BasicTabularSection, Register types)
            formatStandardAttributes(sb, mdObject, language);
        }
        else
        {
            // Basic mode: show basic properties
            formatBasicProperties(sb, mdObject, language);
            
            // Format StandardAttributes if present (BasicDbObject, BasicTabularSection, Register types)
            formatStandardAttributes(sb, mdObject, language);
        }
        
        // Render the single-valued StyleItem value (Color / Font). It is a containment reference but
        // not a collection, so neither formatContainmentCollections (isMany only) nor
        // formatAllDynamicProperties (skips containment refs) shows it; without this branch the value
        // set by modify_metadata is invisible in get_metadata_details.
        if (mdObject instanceof StyleItem)
        {
            formatStyleItemValue(sb, (StyleItem) mdObject);
        }

        // Format containment collections (attributes, tabular sections, forms, commands, etc.)
        formatContainmentCollections(sb, mdObject, full, language);

        return sb.toString();
    }

    /**
     * Renders a {@link StyleItem}'s value (a {@link ColorValue} or {@link FontValue}) as a small
     * Property/Value table, so the color / font assigned by {@code modify_metadata} is visible in
     * get_metadata_details. The Color / Font rendering (incl. the AutoColor-first ordering) is shared
     * with the assignable view via {@link StyleValueBuilder}.
     */
    private void formatStyleItemValue(StringBuilder sb, StyleItem styleItem)
    {
        addSectionHeader(sb, VALUE_TOKEN);
        startTable(sb, "Property", VALUE_TOKEN); //$NON-NLS-1$
        addPropertyRow(sb, "Style Type", formatEnum(styleItem.getType())); //$NON-NLS-1$

        Value value = styleItem.getValue();
        if (value instanceof ColorValue)
        {
            addPropertyRow(sb, "Color", StyleValueBuilder.renderColor(((ColorValue) value).getValue())); //$NON-NLS-1$
        }
        else if (value instanceof FontValue)
        {
            addPropertyRow(sb, "Font", StyleValueBuilder.renderFont(((FontValue) value).getValue())); //$NON-NLS-1$
        }
        else
        {
            addPropertyRow(sb, VALUE_TOKEN, DASH);
        }
    }

    /**
     * Format all containment collections (attributes, tabular sections, forms, commands, etc.)
     */
    private void formatContainmentCollections(StringBuilder sb, MdObject mdObject, boolean full, String language)
    {
        for (EStructuralFeature feature : mdObject.eClass().getEAllStructuralFeatures())
        {
            if (!(feature instanceof EReference))
            {
                continue;
            }
            
            EReference ref = (EReference) feature;
            
            // Only process containment many-valued references
            if (!ref.isContainment() || !ref.isMany())
            {
                continue;
            }
            
            // Skip derived, transient, volatile
            if (ref.isDerived() || ref.isTransient() || ref.isVolatile())
            {
                continue;
            }
            
            if (!mdObject.eIsSet(ref))
            {
                continue;
            }
            
            Object value = mdObject.eGet(ref);
            if (!(value instanceof Collection))
            {
                continue;
            }
            
            Collection<?> collection = (Collection<?>) value;
            if (collection.isEmpty())
            {
                continue;
            }
            
            String collectionName = formatFeatureName(ref.getName());
            
            // Special handling for known collection types
            Object firstItem = collection.iterator().next();
            
            if (firstItem instanceof BasicForm)
            {
                formatFormsCollection(sb, collectionName, collection, language);
            }
            else if (firstItem instanceof BasicCommand)
            {
                formatCommandsCollection(sb, collectionName, collection, language);
            }
            else if (firstItem instanceof StandardAttribute)
            {
                // StandardAttributes are now formatted via formatStandardAttributes() method
                // Skip them here to avoid duplication
                continue;
            }
            else if (firstItem instanceof CharacteristicsDescription)
            {
                formatCharacteristicsCollection(sb, collectionName, collection, language);
            }
            else if (firstItem instanceof BasicTabularSection)
            {
                // Tabular Sections - format with extended details
                formatTabularSectionsExtended(sb, collectionName, collection, full, language);
            }
            else if (firstItem instanceof BasicFeature)
            {
                // Attributes - format with extended properties
                formatAttributesCollection(sb, collectionName, collection, full, language);
            }
            else if (firstItem instanceof java.util.Map.Entry)
            {
                // Handle EMap collections like Synonym, ObjectPresentation
                formatMapEntryCollection(sb, collectionName, collection, language);
            }
            else if (firstItem instanceof MdObject)
            {
                formatMdObjectCollection(sb, collectionName, collection, full, language);
            }
            else if (firstItem instanceof EObject)
            {
                formatEObjectCollection(sb, collectionName, collection, full, language);
            }
        }
    }
    
    /**
     * Format a collection of forms.
     */
    private void formatFormsCollection(StringBuilder sb, String name, Collection<?> forms, String language)
    {
        addSectionHeader(sb, name);
        // An Origin column is added only when at least one form is ADOPTED, i.e. this is an
        // extension's adopted object whose form(s) override the base. It marks which form is
        // overridden (core (adopted)) vs the extension's own (extension). A base configuration
        // never holds adopted forms, so its output is unchanged.
        boolean showOrigin = anyAdopted(forms);
        if (showOrigin)
        {
            startTable(sb, "Name", SYNONYM_TOKEN, "Form Type", ORIGIN_TOKEN); //$NON-NLS-1$ //$NON-NLS-2$
        }
        else
        {
            startTable(sb, "Name", SYNONYM_TOKEN, "Form Type"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        for (Object item : forms)
        {
            if (item instanceof BasicForm)
            {
                BasicForm form = (BasicForm) item;
                if (showOrigin)
                {
                    addTableRow(sb,
                        form.getName(),
                        getSynonym(form.getSynonym(), language),
                        formatEnum(form.getFormType()),
                        originCell(form));
                }
                else
                {
                    addTableRow(sb,
                        form.getName(),
                        getSynonym(form.getSynonym(), language),
                        formatEnum(form.getFormType()));
                }
            }
        }
    }

    /**
     * True when the collection holds at least one ADOPTED metadata object — i.e. an extension's
     * adopted object whose members override the base. Used to add an Origin column only where it
     * is meaningful, leaving a base configuration's output unchanged.
     */
    private static boolean anyAdopted(Collection<?> items)
    {
        for (Object item : items)
        {
            if (item instanceof MdObject && ((MdObject) item).getObjectBelonging() == ObjectBelonging.ADOPTED)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * The origin label for a member of an extension's adopted object: an ADOPTED member overrides
     * the base (core (adopted)); a NATIVE member is the extension's own (extension). Only called
     * for collections where {@link #anyAdopted(Collection)} is true.
     */
    private static String originCell(MdObject item)
    {
        return ExtensionOriginUtils.originLabel(item.getObjectBelonging(), true);
    }
    
    /**
     * Format a collection of commands.
     */
    private void formatCommandsCollection(StringBuilder sb, String name, Collection<?> commands, String language)
    {
        addSectionHeader(sb, name);
        startTable(sb, "Name", SYNONYM_TOKEN, "Group"); //$NON-NLS-1$ //$NON-NLS-2$
        
        for (Object item : commands)
        {
            if (item instanceof BasicCommand)
            {
                BasicCommand cmd = (BasicCommand) item;
                String group = formatCommandGroup(cmd.getGroup());
                addTableRow(sb,
                    cmd.getName(),
                    getSynonym(cmd.getSynonym(), language),
                    group);
            }
        }
    }
    
    /**
     * Format a collection of attributes (BasicFeature objects like CatalogAttribute, DocumentAttribute, etc.)
     * If full=true: shows extended properties (10 columns)
     * If full=false: shows compact format (Name, Synonym, Type)
     */
    private void formatAttributesCollection(StringBuilder sb, String name, Collection<?> items, boolean full, String language)
    {
        // Only add section header if name is not empty
        if (name != null && !name.isEmpty())
        {
            addSectionHeader(sb, name);
        }
        
        // An Origin column is added only when at least one attribute is ADOPTED (an extension's
        // adopted object whose attribute(s) override the base) - marking which attribute is
        // overridden (core (adopted)) vs the extension's own (extension). A base configuration
        // never holds adopted attributes, so its output keeps the original columns.
        boolean showOrigin = anyAdopted(items);
        if (full)
        {
            // Extended format with 10 columns (+ Origin when adopted attributes are present)
            java.util.List<String> headers = new java.util.ArrayList<>(java.util.Arrays.asList(
                "Name", SYNONYM_TOKEN, "Type", "Indexing", "Fill Checking", "Full Text Search", "Password Mode", "Multi Line", "Quick Choice", "Create On Input")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$
            if (showOrigin)
            {
                headers.add(ORIGIN_TOKEN);
            }
            startTable(sb, headers.toArray(new String[0]));

            for (Object item : items)
            {
                if (item instanceof BasicFeature)
                {
                    BasicFeature attr = (BasicFeature) item;

                    // Get indexing if it's DbObjectAttribute
                    String indexing = DASH;
                    if (attr instanceof DbObjectAttribute)
                    {
                        indexing = formatEnum(((DbObjectAttribute) attr).getIndexing());
                    }

                    // Get password mode using EMF reflection (it's in BasicFeature but not in interface)
                    String passwordMode = NO;
                    try
                    {
                        java.lang.reflect.Method method = attr.getClass().getMethod("isPasswordMode"); //$NON-NLS-1$
                        Boolean pwdMode = (Boolean) method.invoke(attr);
                        passwordMode = formatBoolean(pwdMode != null ? pwdMode : false);
                    }
                    catch (Exception e)
                    {
                        // Method doesn't exist or error - use default
                    }

                    // Get multiLine using EMF reflection
                    String multiLine = NO;
                    try
                    {
                        java.lang.reflect.Method method = attr.getClass().getMethod("isMultiLine"); //$NON-NLS-1$
                        Boolean mlMode = (Boolean) method.invoke(attr);
                        multiLine = formatBoolean(mlMode != null ? mlMode : false);
                    }
                    catch (Exception e)
                    {
                        // Method doesn't exist or error - use default
                    }

                    // Get fullTextSearch if it's DbObjectAttribute
                    String fullTextSearch = DASH;
                    if (attr instanceof DbObjectAttribute)
                    {
                        fullTextSearch = formatEnum(((DbObjectAttribute) attr).getFullTextSearch());
                    }

                    java.util.List<String> cells = new java.util.ArrayList<>(java.util.Arrays.asList(
                        attr.getName(),
                        getSynonym(attr.getSynonym(), language),
                        formatType(attr.getType()),
                        indexing,
                        formatEnum(attr.getFillChecking()),
                        fullTextSearch,
                        passwordMode,
                        multiLine,
                        formatEnum(attr.getQuickChoice()),
                        formatEnum(attr.getCreateOnInput())));
                    if (showOrigin)
                    {
                        cells.add(originCell(attr));
                    }
                    addTableRow(sb, cells.toArray(new String[0]));
                }
            }
        }
        else
        {
            // Compact format - Name, Synonym, Type (+ Origin when adopted attributes are present)
            if (showOrigin)
            {
                startTable(sb, "Name", SYNONYM_TOKEN, "Type", ORIGIN_TOKEN); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else
            {
                startTable(sb, "Name", SYNONYM_TOKEN, "Type"); //$NON-NLS-1$ //$NON-NLS-2$
            }

            for (Object item : items)
            {
                if (item instanceof BasicFeature)
                {
                    BasicFeature attr = (BasicFeature) item;
                    if (showOrigin)
                    {
                        addTableRow(sb,
                            attr.getName(),
                            getSynonym(attr.getSynonym(), language),
                            formatType(attr.getType()),
                            originCell(attr));
                    }
                    else
                    {
                        addTableRow(sb,
                            attr.getName(),
                            getSynonym(attr.getSynonym(), language),
                            formatType(attr.getType()));
                    }
                }
            }
        }
    }
    
    /**
     * Format tabular sections with extended details.
     * For each tabular section:
     * 1. Section header with TS name
     * 2. Properties of the TS itself (Name, Synonym, Use, Fill Checking, etc.)
     * 3. Table of TS attributes
     */
    private void formatTabularSectionsExtended(StringBuilder sb, String name, Collection<?> items, boolean full, String language)
    {
        addSectionHeader(sb, name);
        
        for (Object item : items)
        {
            if (item instanceof BasicTabularSection)
            {
                BasicTabularSection ts = (BasicTabularSection) item;
                
                // Sub-header for this tabular section
                sb.append("\n#### ").append(ts.getName()).append("\n\n");
                
                // Properties table for the TS itself
                startTable(sb, "Property", VALUE_TOKEN);
                addPropertyRow(sb, "Name", ts.getName());
                addPropertyRow(sb, SYNONYM_TOKEN, getSynonym(ts.getSynonym(), language));
                
                String comment = ts.getComment();
                if (comment != null && !comment.isEmpty())
                {
                    addPropertyRow(sb, "Comment", comment);
                }
                
                // Tool Tip
                String toolTip = getSynonym(ts.getToolTip(), language);
                if (toolTip != null && !toolTip.isEmpty())
                {
                    addPropertyRow(sb, "Tool Tip", toolTip);
                }
                
                // Fill Checking
                addPropertyRow(sb, "Fill Checking", formatEnum(ts.getFillChecking()));
                
                // Use (via reflection, available in HierarchicalDbObjectTabularSection)
                try
                {
                    java.lang.reflect.Method method = ts.getClass().getMethod("getUse");
                    Object use = method.invoke(ts);
                    if (use != null)
                    {
                        addPropertyRow(sb, "Use", formatEnum(use));
                    }
                }
                catch (Exception e)
                {
                    // Method doesn't exist - skip
                }
                
                // LineNumberLength (via reflection)
                try
                {
                    java.lang.reflect.Method method = ts.getClass().getMethod("getLineNumberLength");
                    Object lineNumLen = method.invoke(ts);
                    if (lineNumLen != null)
                    {
                        addPropertyRow(sb, "Line Number Length", lineNumLen.toString());
                    }
                }
                catch (Exception e)
                {
                    // Method doesn't exist - skip
                }
                
                // Get attributes collection via EMF reflection
                try
                {
                    EObject eObj = (EObject) ts;
                    EStructuralFeature attrFeature = eObj.eClass().getEStructuralFeature("attributes");
                    if (attrFeature != null)
                    {
                        Object attrValue = eObj.eGet(attrFeature);
                        if (attrValue instanceof Collection && !((Collection<?>) attrValue).isEmpty())
                        {
                            Collection<?> attributes = (Collection<?>) attrValue;
                            
                            // Format attributes of this tabular section
                            sb.append("\n**Attributes:**\n\n");
                            formatAttributesCollection(sb, "", attributes, full, language);
                        }
                    }
                }
                catch (Exception e)
                {
                    // Error getting attributes - skip
                    System.err.println("Error formatting tabular section attributes: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Format a collection of characteristics descriptions as a single table.
     * Shows all properties of each CharacteristicsDescription using EMF reflection.
     */
    private void formatCharacteristicsCollection(StringBuilder sb, String name, Collection<?> items, String language)
    {
        addSectionHeader(sb, name);
        startTable(sb, "Index", "Characteristic Types", "Key Field", "Types Filter Field", "Types Filter Value", "Characteristic Values", "Object Field", "Type Field", "Value Field"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$
        
        int index = 0;
        for (Object item : items)
        {
            if (item instanceof CharacteristicsDescription)
            {
                CharacteristicsDescription charDesc = (CharacteristicsDescription) item;
                
                // CharacteristicTypes (MdObject)
                String typesSource = DASH;
                if (charDesc.getCharacteristicTypes() != null)
                {
                    typesSource = formatEObjectReference(charDesc.getCharacteristicTypes());
                }
                
                // KeyField (Field)
                String keyField = charDesc.getKeyField() != null ? formatEObjectReference(charDesc.getKeyField()) : DASH;
                
                // TypesFilterField (Field)
                String typesFilterField = charDesc.getTypesFilterField() != null ? formatEObjectReference(charDesc.getTypesFilterField()) : DASH;
                
                // TypesFilterValue (Value)
                String filterValue = DASH;
                if (charDesc.getTypesFilterValue() != null)
                {
                    filterValue = formatEObjectReference(charDesc.getTypesFilterValue());
                }
                
                // CharacteristicValues (MdObject) 
                String valuesSource = DASH;
                if (charDesc.getCharacteristicValues() != null)
                {
                    valuesSource = formatEObjectReference(charDesc.getCharacteristicValues());
                }
                
                // ObjectField (Field)
                String objectField = charDesc.getObjectField() != null ? formatEObjectReference(charDesc.getObjectField()) : DASH;
                
                // TypeField (Field)
                String typeField = charDesc.getTypeField() != null ? formatEObjectReference(charDesc.getTypeField()) : DASH;
                
                // ValueField (Field)
                String valueField = charDesc.getValueField() != null ? formatEObjectReference(charDesc.getValueField()) : DASH;
                
                addTableRow(sb, 
                    String.valueOf(index++),
                    typesSource,
                    keyField,
                    typesFilterField,
                    filterValue,
                    valuesSource,
                    objectField,
                    typeField,
                    valueField
                );
            }
        }
    }
    
    /**
     * Format a collection of Map.Entry items (EMap entries like Synonym, ObjectPresentation).
     * Displays as Language/Value table.
     */
    @SuppressWarnings("rawtypes")
    private void formatMapEntryCollection(StringBuilder sb, String name, Collection<?> items, String language)
    {
        addSectionHeader(sb, name);
        startTable(sb, "Language", VALUE_TOKEN); //$NON-NLS-1$
        
        for (Object item : items)
        {
            if (item instanceof java.util.Map.Entry)
            {
                java.util.Map.Entry entry = (java.util.Map.Entry) item;
                String key = entry.getKey() != null ? entry.getKey().toString() : DASH;
                String value = entry.getValue() != null ? entry.getValue().toString() : DASH;
                addTableRow(sb, key, value);
            }
        }
    }
    
    /**
     * Format command group to a readable string.
     * Uses EObjectInspector to extract the category enum from StandardCommandGroup.
     */
    private String formatCommandGroup(Object groupObj)
    {
        if (groupObj == null)
        {
            return DASH;
        }
        
        // If it's an EObject (StandardCommandGroup), use EObjectInspector
        if (groupObj instanceof EObject)
        {
            // EObjectInspector will properly extract the 'category' enum value
            return EObjectInspector.getPrimaryValueAsString((EObject) groupObj);
        }
        
        return groupObj.toString();
    }
    
    /**
     * Format a collection of MdObjects (attributes, tabular sections, dimensions, etc.)
     */
    private void formatMdObjectCollection(StringBuilder sb, String name, Collection<?> items, 
            boolean full, String language)
    {
        addSectionHeader(sb, name);
        
        boolean first = true;
        for (Object item : items)
        {
            if (item instanceof MdObject)
            {
                MdObject mdObj = (MdObject) item;
                
                if (first)
                {
                    // Build table headers based on first item
                    if (full)
                    {
                        startTable(sb, "Name", SYNONYM_TOKEN, "Type"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    else
                    {
                        startTable(sb, "Name", SYNONYM_TOKEN); //$NON-NLS-1$
                    }
                    first = false;
                }
                
                String typeName = getTypeFromMdObject(mdObj);
                if (full)
                {
                    addTableRow(sb, mdObj.getName(), getSynonym(mdObj.getSynonym(), language), typeName);
                }
                else
                {
                    addTableRow(sb, mdObj.getName(), getSynonym(mdObj.getSynonym(), language));
                }
            }
        }
    }
    
    /**
     * Format a collection of EObjects that are not MdObjects.
     */
    private void formatEObjectCollection(StringBuilder sb, String name, Collection<?> items, 
            boolean full, String language)
    {
        addSectionHeader(sb, name);
        
        boolean first = true;
        for (Object item : items)
        {
            if (item instanceof EObject)
            {
                EObject eObj = (EObject) item;
                
                if (first)
                {
                    // Get available feature names for headers
                    startTable(sb, "Name", VALUE_TOKEN); //$NON-NLS-1$
                    first = false;
                }
                
                String itemName = formatEObjectReference(eObj);
                addTableRow(sb, itemName, eObj.eClass().getName());
            }
        }
    }
    
    /**
     * Try to get type information from an MdObject (e.g., for attributes).
     */
    private String getTypeFromMdObject(MdObject mdObj)
    {
        // Try to find a "type" feature
        EStructuralFeature typeFeature = mdObj.eClass().getEStructuralFeature("type"); //$NON-NLS-1$
        if (typeFeature != null)
        {
            Object typeValue = mdObj.eGet(typeFeature);
            if (typeValue instanceof com._1c.g5.v8.dt.mcore.TypeDescription)
            {
                return formatType((com._1c.g5.v8.dt.mcore.TypeDescription) typeValue);
            }
        }
        return DASH;
    }
}
