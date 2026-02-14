/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.metadata;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.mcore.ReferenceValue;

/**
 * Utility class for inspecting and analyzing EObject types in EDT EMF model.
 * 
 * Provides methods to determine:
 * - If an EObject is a "simple value holder" (like StandardCommandGroup that wraps an enum)
 * - If an EObject needs to be "expanded" (like CharacteristicsDescription with multiple properties)
 * - How to extract the primary/meaningful value from wrapper objects
 * 
 * This enables proper formatting without hardcoding class names.
 */
public final class EObjectInspector
{
    /** Maximum number of attributes for a class to be considered a "simple wrapper" */
    private static final int MAX_SIMPLE_WRAPPER_ATTRIBUTES = 5;
    
    /** Maximum number of attributes for showing as simple value vs expand */
    private static final int MAX_ATTRIBUTES_FOR_SIMPLE_VALUE = 3;
    
    /** Feature names that typically hold primary values in wrapper classes */
    private static final List<String> PRIMARY_VALUE_FEATURE_NAMES = 
        Arrays.asList("category", "group", "type", "value"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    
    private EObjectInspector()
    {
        // Utility class
    }
    
    /**
     * How an EObject value should be formatted.
     */
    public enum FormatStyle
    {
        /** Show as simple text value (e.g., enum name, string) */
        SIMPLE_VALUE,
        
        /** Show as reference (e.g., "Catalog.Products") */
        REFERENCE,
        
        /** Expand and show all properties in detail */
        EXPAND
    }
    
    /**
     * Determine how to format an EObject value.
     * 
     * @param eObj the EObject to analyze
     * @return the recommended format style
     */
    public static FormatStyle getFormatStyle(EObject eObj)
    {
        if (eObj == null)
        {
            return FormatStyle.SIMPLE_VALUE;
        }
        
        // 1. MdObject references should be shown as references
        if (eObj instanceof MdObject)
        {
            return FormatStyle.REFERENCE;
        }
        
        // 2. Check for containment references (complex objects that need expansion)
        if (hasNonEmptyContainment(eObj))
        {
            return FormatStyle.EXPAND;
        }
        
        // 3. Count meaningful (set) attributes
        int meaningfulAttrs = countMeaningfulAttributes(eObj);
        
        // Simple wrappers have few attributes
        if (meaningfulAttrs <= MAX_ATTRIBUTES_FOR_SIMPLE_VALUE)
        {
            return FormatStyle.SIMPLE_VALUE;
        }
        
        // Complex objects need expansion
        return FormatStyle.EXPAND;
    }
    
    /**
     * Check if an EClass is a "simple value holder" wrapper class.
     * 
     * Simple value holders:
     * - Have NO containment references (don't own complex child objects)
     * - Have at most a few attributes
     * 
     * Examples: StandardCommandGroup, Color, Picture
     * 
     * @param eClass the EClass to check
     * @return true if it's a simple value holder
     */
    public static boolean isSimpleValueHolder(EClass eClass)
    {
        if (eClass == null)
        {
            return false;
        }
        
        // Check for containment references
        for (EReference ref : eClass.getEAllReferences())
        {
            if (ref.isContainment() && !ref.isDerived() && !ref.isTransient())
            {
                return false; // Has containment - not a simple wrapper
            }
        }
        
        // Count non-derived attributes
        int attrCount = 0;
        for (EAttribute attr : eClass.getEAllAttributes())
        {
            if (!attr.isDerived() && !attr.isTransient())
            {
                attrCount++;
            }
        }
        
        // Simple wrappers typically have few attributes
        return attrCount <= MAX_SIMPLE_WRAPPER_ATTRIBUTES;
    }
    
    /**
     * Check if an EObject instance is a simple value holder with actual values set.
     * 
     * @param eObj the EObject to check
     * @return true if it's a simple value holder
     */
    public static boolean isSimpleValueHolder(EObject eObj)
    {
        if (eObj == null)
        {
            return false;
        }
        return isSimpleValueHolder(eObj.eClass()) && !hasNonEmptyContainment(eObj);
    }
    
    /**
     * Check if an EObject has non-empty containment references.
     * 
     * @param eObj the EObject to check
     * @return true if it has non-empty containment references
     */
    public static boolean hasNonEmptyContainment(EObject eObj)
    {
        if (eObj == null)
        {
            return false;
        }
        
        for (EReference ref : eObj.eClass().getEAllReferences())
        {
            if (ref.isContainment() && !ref.isDerived() && !ref.isTransient())
            {
                Object value = eObj.eGet(ref);
                if (value != null)
                {
                    if (ref.isMany())
                    {
                        if (!((Collection<?>) value).isEmpty())
                        {
                            return true;
                        }
                    }
                    else
                    {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    
    /**
     * Get the "primary value" from a wrapper EObject.
     * 
     * Priority order:
     * 1. Enum attribute (like 'category' in StandardCommandGroup)
     * 2. Known primary value feature names (category, group, type, value)
     * 3. 'name' feature
     * 4. First non-derived, non-name attribute
     * 5. Class name as fallback
     * 
     * @param eObj the EObject to extract value from
     * @return the primary value, or class name as fallback
     */
    public static Object getPrimaryValue(EObject eObj)
    {
        if (eObj == null)
        {
            return null;
        }
        
        EClass eClass = eObj.eClass();
        
        // 1. Look for enum attributes first (highest priority)
        for (EAttribute attr : eClass.getEAllAttributes())
        {
            if (attr.isDerived() || attr.isTransient())
            {
                continue;
            }
            
            Object value = eObj.eGet(attr);
            if (value != null && value.getClass().isEnum())
            {
                return value;
            }
        }
        
        // 2. Look for known primary value feature names
        for (String featureName : PRIMARY_VALUE_FEATURE_NAMES)
        {
            EStructuralFeature f = eClass.getEStructuralFeature(featureName);
            if (f != null && eObj.eIsSet(f))
            {
                Object value = eObj.eGet(f);
                if (value != null)
                {
                    return value;
                }
            }
        }
        
        // 3. Try 'name' feature
        EStructuralFeature nameFeature = eClass.getEStructuralFeature("name"); //$NON-NLS-1$
        if (nameFeature != null && eObj.eIsSet(nameFeature))
        {
            Object name = eObj.eGet(nameFeature);
            if (name != null && !name.toString().isEmpty())
            {
                return name;
            }
        }
        
        // 4. First non-derived, non-name attribute that is set
        for (EAttribute attr : eClass.getEAllAttributes())
        {
            if (attr.isDerived() || attr.isTransient())
            {
                continue;
            }
            String attrName = attr.getName();
            if ("name".equalsIgnoreCase(attrName) || "nameRu".equalsIgnoreCase(attrName)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                continue;
            }
            if (eObj.eIsSet(attr))
            {
                Object value = eObj.eGet(attr);
                if (value != null)
                {
                    return value;
                }
            }
        }
        
        // 5. Fallback to class name
        return eClass.getName();
    }
    
    /**
     * Get the primary value formatted as a string.
     * 
     * @param eObj the EObject to extract value from
     * @return the primary value as string
     */
    public static String getPrimaryValueAsString(EObject eObj)
    {
        Object value = getPrimaryValue(eObj);
        if (value == null)
        {
            return ""; //$NON-NLS-1$
        }
        return value.toString();
    }
    
    /**
     * Count the number of "meaningful" attributes that are set on an EObject.
     * Excludes derived, transient, and volatile attributes.
     * 
     * @param eObj the EObject to analyze
     * @return the count of meaningful set attributes
     */
    public static int countMeaningfulAttributes(EObject eObj)
    {
        if (eObj == null)
        {
            return 0;
        }
        
        int count = 0;
        for (EAttribute attr : eObj.eClass().getEAllAttributes())
        {
            if (attr.isDerived() || attr.isTransient() || attr.isVolatile())
            {
                continue;
            }
            if (eObj.eIsSet(attr))
            {
                count++;
            }
        }
        return count;
    }
    
    /**
     * Check if an EObject represents a reference to an MdObject.
     * 
     * @param eObj the EObject to check
     * @return true if it's an MdObject reference
     */
    public static boolean isMdObjectReference(EObject eObj)
    {
        return eObj instanceof MdObject;
    }
    
    /**
     * Format an EObject reference as a string.
     * For MdObject: returns "TypeName.ObjectName"
     * For BmObject with name: returns "TypeName.ObjectName"
     * For ReferenceValue: unwraps and formats the contained value
     * For simple wrappers: returns the primary value
     * For complex objects: returns the class name
     * 
     * @param eObj the EObject to format
     * @return formatted string representation
     */
    public static String formatReference(EObject eObj)
    {
        if (eObj == null)
        {
            return ""; //$NON-NLS-1$
        }
        
        // Handle ReferenceValue wrapper - unwrap and format the contained value
        if (eObj instanceof ReferenceValue)
        {
            ReferenceValue refValue = (ReferenceValue) eObj;
            EObject containedValue = refValue.getValue();
            if (containedValue != null)
            {
                // Recursively format the contained object
                return formatReference(containedValue);
            }
            return ""; //$NON-NLS-1$
        }
        
        // MdObject: show as Type.Name
        if (eObj instanceof MdObject)
        {
            MdObject mdObj = (MdObject) eObj;
            return mdObj.eClass().getName() + "." + mdObj.getName(); //$NON-NLS-1$
        }
        
        // Simple wrapper: show primary value
        if (isSimpleValueHolder(eObj))
        {
            return getPrimaryValueAsString(eObj);
        }
        
        // For BmObject with name property (PredefinedItem, etc.) - show as Type.Name format
        EStructuralFeature nameFeature = eObj.eClass().getEStructuralFeature("name"); //$NON-NLS-1$
        if (nameFeature != null)
        {
            Object name = eObj.eGet(nameFeature);
            if (name != null && !name.toString().isEmpty())
            {
                // If it's a BmObject with a name, use Type.Name format (like MdObject)
                String typeName = getCleanClassName(eObj);
                return typeName + "." + name; //$NON-NLS-1$
            }
        }
        
        // Try alternative name features (some objects use different naming)
        for (String altName : Arrays.asList("id", "identifier", "code")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            EStructuralFeature altFeature = eObj.eClass().getEStructuralFeature(altName);
            if (altFeature != null)
            {
                Object value = eObj.eGet(altFeature);
                if (value != null && !value.toString().isEmpty())
                {
                    return getCleanClassName(eObj) + "." + value; //$NON-NLS-1$
                }
            }
        }
        
        // Fallback: clean class name without Impl suffix
        return getCleanClassName(eObj);
    }
    
    /**
     * Get a simple class name without "Impl" suffix.
     * 
     * @param eObj the EObject
     * @return clean class name
     */
    public static String getCleanClassName(EObject eObj)
    {
        if (eObj == null)
        {
            return ""; //$NON-NLS-1$
        }
        String name = eObj.eClass().getName();
        if (name.endsWith("Impl")) //$NON-NLS-1$
        {
            name = name.substring(0, name.length() - 4);
        }
        return name;
    }
}
