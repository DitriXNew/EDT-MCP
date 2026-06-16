/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

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

        // A successful MethodHandle invocation is authoritative even when it
        // returns null - only a missing handle or a failed invocation falls
        // through to the IWorkbenchAdapter fallback.
        HandleResult handleResult = invokeGetModelObjectName(adapter, adapter.getClass());
        if (handleResult.invoked) {
            return handleResult.value;
        }

        return getModelObjectNameFallback(adapter);
    }

    /**
     * Outcome of attempting the getModelObjectName MethodHandle invocation.
     * {@link #invoked} is true only when a handle existed and ran without throwing,
     * in which case {@link #value} (possibly null) is authoritative; otherwise the
     * caller must apply the fallback.
     */
    private static final class HandleResult {
        final boolean invoked;
        final String value;

        private HandleResult(boolean invoked, String value) {
            this.invoked = invoked;
            this.value = value;
        }

        static HandleResult fallThrough() {
            return new HandleResult(false, null);
        }

        static HandleResult of(String value) {
            return new HandleResult(true, value);
        }
    }

    /**
     * Resolves the model object name via the cached/looked-up getModelObjectName
     * MethodHandle. Returns a {@link HandleResult} whose {@code invoked} flag is
     * false when there is no such method or the invocation throws, so the caller
     * can apply the IWorkbenchAdapter fallback; otherwise the (possibly null)
     * invocation result is authoritative.
     *
     * @param adapter the adapter to invoke the method on
     * @param clazz the adapter's runtime class (cache key)
     * @return the invocation outcome
     */
    private static HandleResult invokeGetModelObjectName(Object adapter, Class<?> clazz) {
        MethodHandle mh = resolveModelObjectNameHandle(clazz);
        if (mh == null) {
            return HandleResult.fallThrough();
        }
        try {
            return HandleResult.of((String) mh.invoke(adapter));
        } catch (Throwable e) {
            // Fall through to fallback
            return HandleResult.fallThrough();
        }
    }

    /**
     * Returns the getModelObjectName MethodHandle for the given class, using the
     * cache. Negative results (no such method) are cached as null keys; positive
     * results are stored. ConcurrentHashMap forbids null values, so classes without
     * the method are simply re-looked-up rather than negatively cached.
     *
     * @param clazz the adapter class
     * @return the MethodHandle, or null if the class has no such method
     */
    private static MethodHandle resolveModelObjectNameHandle(Class<?> clazz) {
        // Check if we've already cached this class (including negative results)
        if (MODEL_OBJECT_NAME_CACHE.containsKey(clazz)) {
            // A non-null value is a usable handle; null means we tried before and
            // there's no method.
            return MODEL_OBJECT_NAME_CACHE.get(clazz);
        }
        // Cache miss - try to find and cache the method
        MethodHandle mh = findGetModelObjectNameMethod(clazz);
        if (mh != null) {
            MODEL_OBJECT_NAME_CACHE.put(clazz, mh);
        }
        // Don't cache null - ConcurrentHashMap doesn't allow null values
        // We'll just repeat the lookup for classes without the method
        return mh;
    }

    /**
     * Fallback model-object-name resolution via the IWorkbenchAdapter label
     * (spaces stripped), used when the getModelObjectName MethodHandle is
     * unavailable or fails.
     *
     * @param adapter the adapter
     * @return the label-derived name, or null
     */
    private static String getModelObjectNameFallback(Object adapter) {
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
