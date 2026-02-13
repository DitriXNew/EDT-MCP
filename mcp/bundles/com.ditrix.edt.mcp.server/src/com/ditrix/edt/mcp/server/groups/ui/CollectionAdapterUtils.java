/*******************************************************************************
 * Copyright (c) 2025 DITRIX
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 * 
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/
package com.ditrix.edt.mcp.server.groups.ui;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.ui.model.IWorkbenchAdapter;

/**
 * Utility class for working with Navigator collection adapters.
 * Uses MethodHandle for better performance than reflection.
 * 
 * <p>This class caches MethodHandles for frequently used methods to avoid
 * repeated reflection lookups.</p>
 */
public final class CollectionAdapterUtils {
    
    private static final String COLLECTION_ADAPTER_CLASS_NAME = 
        "com._1c.g5.v8.dt.navigator.adapters.CollectionNavigatorAdapterBase";
    
    /**
     * Cache for getModelObjectName MethodHandles by class.
     */
    private static final Map<Class<?>, MethodHandle> MODEL_OBJECT_NAME_CACHE = new ConcurrentHashMap<>();
    
    /**
     * Cache for getParent MethodHandles by class.
     */
    private static final Map<Class<?>, MethodHandle> GET_PARENT_CACHE = new ConcurrentHashMap<>();
    
    /**
     * Marker for classes without the method.
     */
    private static final MethodHandle NO_METHOD = null;
    
    private CollectionAdapterUtils() {
        // Utility class
    }
    
    /**
     * Checks if the element is a collection adapter.
     * 
     * @param element the element to check
     * @return true if it's a collection adapter
     */
    public static boolean isCollectionAdapter(Object element) {
        if (element == null) {
            return false;
        }
        Class<?> clazz = element.getClass();
        while (clazz != null) {
            if (COLLECTION_ADAPTER_CLASS_NAME.equals(clazz.getName())) {
                return true;
            }
            clazz = clazz.getSuperclass();
        }
        return false;
    }
    
    /**
     * Gets the project from a navigator adapter.
     * 
     * @param adapter the adapter
     * @return the project or null
     */
    public static IProject getProjectFromAdapter(Object adapter) {
        if (adapter instanceof IAdaptable adaptable) {
            return adaptable.getAdapter(IProject.class);
        }
        return null;
    }
    
    /**
     * Gets the model object name (collection type) from an adapter.
     * Uses cached MethodHandle for performance.
     * 
     * @param adapter the adapter
     * @return the model object name or null
     */
    public static String getModelObjectName(Object adapter) {
        if (adapter == null) {
            return null;
        }
        
        Class<?> clazz = adapter.getClass();
        
        // Check if we've already cached this class (including negative results)
        if (MODEL_OBJECT_NAME_CACHE.containsKey(clazz)) {
            MethodHandle cached = MODEL_OBJECT_NAME_CACHE.get(clazz);
            if (cached != null) {
                try {
                    return (String) cached.invoke(adapter);
                } catch (Throwable e) {
                    // Fall through to fallback
                }
            }
            // cached is null - means we tried before and there's no method
        } else {
            // Cache miss - try to find and cache the method
            MethodHandle mh = findGetModelObjectNameMethod(clazz);
            if (mh != null) {
                MODEL_OBJECT_NAME_CACHE.put(clazz, mh);
                try {
                    return (String) mh.invoke(adapter);
                } catch (Throwable e) {
                    // Fall through to fallback
                }
            }
            // Don't cache null - ConcurrentHashMap doesn't allow null values
            // We'll just repeat the lookup for classes without the method
        }
        
        // Fallback: try IWorkbenchAdapter label
        if (adapter instanceof IWorkbenchAdapter workbenchAdapter) {
            String label = workbenchAdapter.getLabel(adapter);
            if (label != null) {
                return label.replace(" ", "");
            }
        }
        
        return null;
    }
    
    /**
     * Gets the collection path for a collection adapter.
     * Only returns top-level collection types.
     * Returns null for nested collections.
     * 
     * @param adapter the adapter
     * @return the collection path or null
     */
    public static String getCollectionPath(Object adapter) {
        String modelObjectName = getModelObjectName(adapter);
        if (modelObjectName == null) {
            return null;
        }
        
        // Check if this is a nested collection
        if (hasEObjectParent(adapter)) {
            return null;
        }
        
        return modelObjectName;
    }
    
    /**
     * Gets the full collection path including parent FQN for nested collections.
     * 
     * @param adapter the adapter
     * @param parentFqnExtractor function to extract FQN from parent EObject
     * @return the full path or null
     */
    public static String getFullCollectionPath(Object adapter, 
            java.util.function.Function<EObject, String> parentFqnExtractor) {
        String modelObjectName = getModelObjectName(adapter);
        if (modelObjectName == null) {
            return null;
        }
        
        EObject parentEObject = getParentEObject(adapter);
        if (parentEObject != null && parentFqnExtractor != null) {
            String parentFqn = parentFqnExtractor.apply(parentEObject);
            if (parentFqn != null) {
                return parentFqn + "." + modelObjectName;
            }
        }
        
        return modelObjectName;
    }
    
    /**
     * Checks if the adapter has an EObject parent (is a nested collection).
     */
    private static boolean hasEObjectParent(Object adapter) {
        return getParentEObject(adapter) != null;
    }
    
    /**
     * Gets the parent EObject if this is a nested collection.
     */
    private static EObject getParentEObject(Object adapter) {
        if (adapter == null) {
            return null;
        }
        
        Class<?> clazz = adapter.getClass();
        
        // Try cached MethodHandle first
        MethodHandle mh = GET_PARENT_CACHE.get(clazz);
        
        if (mh == null && !GET_PARENT_CACHE.containsKey(clazz)) {
            mh = findGetParentMethod(clazz);
            GET_PARENT_CACHE.put(clazz, mh);
        }
        
        if (mh != null) {
            try {
                Object parent = mh.invoke(adapter, adapter);
                if (parent instanceof EObject) {
                    return (EObject) parent;
                }
            } catch (Throwable e) {
                // Ignore
            }
        }
        
        return null;
    }
    
    /**
     * Finds the getModelObjectName method and creates a MethodHandle.
     */
    private static MethodHandle findGetModelObjectNameMethod(Class<?> clazz) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            return lookup.findVirtual(clazz, "getModelObjectName", MethodType.methodType(String.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            return NO_METHOD;
        }
    }
    
    /**
     * Finds the getParent method and creates a MethodHandle.
     */
    private static MethodHandle findGetParentMethod(Class<?> clazz) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            return lookup.findVirtual(clazz, "getParent", 
                MethodType.methodType(Object.class, Object.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            return NO_METHOD;
        }
    }
    
    /**
     * Clears the method caches. Useful for testing.
     */
    public static void clearCaches() {
        MODEL_OBJECT_NAME_CACHE.clear();
        GET_PARENT_CACHE.clear();
    }
}
