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

/**
 * Base class for metadata formatters with common utility methods.
 */
public abstract class AbstractMetadataFormatter implements IMetadataFormatter
{
    protected static final String YES = "Yes"; //$NON-NLS-1$
    protected static final String NO = "No"; //$NON-NLS-1$
    protected static final String DASH = "-"; //$NON-NLS-1$
    
    /**
     * Gets synonym for the specified language with fallback.
     */
    protected String getSynonym(EMap<String, String> synonymMap, String language)
    {
        if (synonymMap == null || synonymMap.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        
        // Try the requested language first
        String synonym = synonymMap.get(language);
        if (synonym != null && !synonym.isEmpty())
        {
            return synonym;
        }
        
        // Fallback: try to find any available synonym
        for (String val : synonymMap.values())
        {
            if (val != null && !val.isEmpty())
            {
                return val;
            }
        }
        
        return ""; //$NON-NLS-1$
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
        return value.replace("|", "\\|").replace("\n", " "); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
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
        startTable(sb, "Property", "Value"); //$NON-NLS-1$ //$NON-NLS-2$
        addPropertyRow(sb, "Name", mdObject.getName()); //$NON-NLS-1$
        addPropertyRow(sb, "Synonym", getSynonym(mdObject.getSynonym(), language)); //$NON-NLS-1$
        
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
                startTable(sb, "Name", "Synonym", "Fill Checking", "Full Text Search", "Password Mode", "Multi Line", "Quick Choice", "Create On Input", "Data History"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$
                
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
        startTable(sb, "Property", "Value"); //$NON-NLS-1$ //$NON-NLS-2$
        
        List<EStructuralFeature> features = eObject.eClass().getEAllStructuralFeatures();
        
        for (EStructuralFeature feature : features)
        {
            // Skip derived, transient, and volatile features (they're computed, not stored)
            if (feature.isDerived() || feature.isTransient() || feature.isVolatile())
            {
                continue;
            }
            
            // Skip containment references (handled separately as collections)
            if (feature instanceof EReference && ((EReference) feature).isContainment())
            {
                continue;
            }
            
            Object value = eObject.eGet(feature);
            String valueStr = formatDynamicValue(value, feature, language);
            
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
            
            addPropertyRow(sb, formatFeatureName(feature.getName()), valueStr);
        }
    }
    
    /**
     * Dynamically format properties, separating simple attributes from references.
     */
    protected void formatDynamicPropertiesSeparated(StringBuilder sb, EObject eObject, String language)
    {
        List<EStructuralFeature> features = eObject.eClass().getEAllStructuralFeatures();
        
        // Collect simple attributes and references separately
        List<EAttribute> simpleAttributes = new ArrayList<>();
        List<EReference> singleReferences = new ArrayList<>();
        List<EReference> containmentReferences = new ArrayList<>();
        List<EReference> crossReferences = new ArrayList<>();
        
        for (EStructuralFeature feature : features)
        {
            if (feature.isDerived() || feature.isTransient() || feature.isVolatile())
            {
                continue;
            }
            if (!eObject.eIsSet(feature))
            {
                continue;
            }
            
            if (feature instanceof EAttribute)
            {
                simpleAttributes.add((EAttribute) feature);
            }
            else if (feature instanceof EReference)
            {
                EReference ref = (EReference) feature;
                if (ref.isContainment())
                {
                    containmentReferences.add(ref);
                }
                else if (!ref.isMany())
                {
                    singleReferences.add(ref);
                }
                else
                {
                    crossReferences.add(ref);
                }
            }
        }
        
        // Format simple attributes
        if (!simpleAttributes.isEmpty())
        {
            addSectionHeader(sb, "Properties"); //$NON-NLS-1$
            startTable(sb, "Property", "Value"); //$NON-NLS-1$ //$NON-NLS-2$
            for (EAttribute attr : simpleAttributes)
            {
                Object value = eObject.eGet(attr);
                String valueStr = formatDynamicValue(value, attr, language);
                if (valueStr != null && !valueStr.isEmpty() && !valueStr.equals(DASH))
                {
                    addPropertyRow(sb, formatFeatureName(attr.getName()), valueStr);
                }
            }
        }
        
        // Format single references (forms, modules, etc.)
        if (!singleReferences.isEmpty())
        {
            addSectionHeader(sb, "References"); //$NON-NLS-1$
            startTable(sb, "Property", "Value"); //$NON-NLS-1$ //$NON-NLS-2$
            for (EReference ref : singleReferences)
            {
                Object value = eObject.eGet(ref);
                String valueStr = formatDynamicValue(value, ref, language);
                if (valueStr != null && !valueStr.isEmpty() && !valueStr.equals(DASH))
                {
                    addPropertyRow(sb, formatFeatureName(ref.getName()), valueStr);
                }
            }
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
     * Format a collection of references.
     */
    @SuppressWarnings("rawtypes")
    protected void formatReferenceCollection(StringBuilder sb, String name, Collection<?> collection, String language)
    {
        // Check if this is an EMap collection (contains Map.Entry items)
        Object firstItem = collection.iterator().next();
        if (firstItem instanceof java.util.Map.Entry)
        {
            // For EMap collections like Synonym, ObjectPresentation - format as key-value table
            addSectionHeader(sb, formatFeatureName(name) + " (" + collection.size() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            startTable(sb, "Language", "Value"); //$NON-NLS-1$ //$NON-NLS-2$
            for (Object item : collection)
            {
                java.util.Map.Entry entry = (java.util.Map.Entry) item;
                String key = entry.getKey() != null ? entry.getKey().toString() : DASH;
                String value = entry.getValue() != null ? entry.getValue().toString() : DASH;
                addTableRow(sb, key, value);
            }
            return;
        }
        
        addSectionHeader(sb, formatFeatureName(name) + " (" + collection.size() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        
        boolean first = true;
        for (Object item : collection)
        {
            if (item instanceof MdObject)
            {
                MdObject mdObj = (MdObject) item;
                if (first)
                {
                    startTable(sb, "FQN", "Synonym"); //$NON-NLS-1$ //$NON-NLS-2$
                    first = false;
                }
                // Show full FQN: Type.Name (e.g. Catalog.Products)
                String fqn = mdObj.eClass().getName() + "." + mdObj.getName(); //$NON-NLS-1$
                addTableRow(sb, fqn, getSynonym(mdObj.getSynonym(), language));
            }
            else if (item instanceof EObject)
            {
                if (first)
                {
                    sb.append("- "); //$NON-NLS-1$
                    first = false;
                }
                else
                {
                    sb.append(", "); //$NON-NLS-1$
                }
                sb.append(formatEObjectReference((EObject) item));
            }
            else if (item != null)
            {
                if (first)
                {
                    sb.append("- "); //$NON-NLS-1$
                    first = false;
                }
                else
                {
                    sb.append(", "); //$NON-NLS-1$
                }
                sb.append(item.toString());
            }
        }
        if (!first && !(collection.iterator().next() instanceof MdObject))
        {
            sb.append("\n\n"); //$NON-NLS-1$
        }
    }
    
    /**
     * Format a dynamic value based on its type.
     */
    protected String formatDynamicValue(Object value, EStructuralFeature feature, String language)
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
            EObject eObj = (EObject) value;
            
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
        
        // Handle collections
        if (value instanceof Collection)
        {
            Collection<?> coll = (Collection<?>) value;
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
                return sb.toString();
            }
            // For larger collections, just show count
            return "[" + coll.size() + " items]"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        
        // Default: toString
        return value.toString();
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
