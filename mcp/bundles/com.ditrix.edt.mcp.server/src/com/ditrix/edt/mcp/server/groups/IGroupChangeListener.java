/*******************************************************************************
 * Copyright (c) 2025 DITRIX
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 * 
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/
package com.ditrix.edt.mcp.server.groups;

import org.eclipse.core.resources.IProject;

/**
 * Listener interface for group change events.
 */
public interface IGroupChangeListener {
    
    /**
     * Called when groups have changed for a project.
     * 
     * @param project the project whose groups changed
     */
    void onGroupsChanged(IProject project);
}
