/*******************************************************************************
 * Copyright (c) 2025 DITRIX
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 * 
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/
package com.ditrix.edt.mcp.server.tags.ui;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Tag search manager.
 * 
 * NOTE: The #tag search functionality has been removed in favor of the
 * "Filter by Tag" dialog (similar to EDT's "Filter by Subsystem").
 * 
 * This class is kept as a stub for compatibility with startup code.
 * Use {@link FilterByTagManager} for tag-based filtering.
 * 
 * @deprecated Use {@link FilterByTagManager} instead
 */
@Deprecated
public class TagSearchManager {
    
    private static TagSearchManager instance;
    
    private TagSearchManager() {
    }
    
    /**
     * Gets the singleton instance.
     */
    public static synchronized TagSearchManager getInstance() {
        if (instance == null) {
            instance = new TagSearchManager();
        }
        return instance;
    }
    
    /**
     * Starts the manager.
     * This is a no-op - kept for compatibility.
     */
    public void start() {
        Activator.logInfo("TagSearchManager: #tag search disabled, use 'Filter by Tag' dialog instead");
    }
    
    /**
     * Stops the manager.
     * This is a no-op - kept for compatibility.
     */
    public void stop() {
        // No-op
    }
}
