/*******************************************************************************
 * Copyright (c) 2025 DITRIX
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 * 
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/
package com.ditrix.edt.mcp.server.groups.repository;

import org.eclipse.core.resources.IProject;

import com.ditrix.edt.mcp.server.groups.model.GroupStorage;

/**
 * Repository interface for loading and saving group storage.
 * Abstracts the persistence mechanism (YAML, JSON, database, etc.).
 */
public interface IGroupRepository {
    
    /**
     * Loads group storage for a project.
     * 
     * @param project the project to load groups for
     * @return the group storage, never null (returns empty storage if none exists)
     */
    GroupStorage load(IProject project);
    
    /**
     * Saves group storage for a project.
     * 
     * @param project the project
     * @param storage the storage to save
     * @return true if saved successfully
     */
    boolean save(IProject project, GroupStorage storage);
    
    /**
     * Checks if group storage file exists for a project.
     * 
     * @param project the project
     * @return true if storage exists
     */
    boolean exists(IProject project);
    
    /**
     * Deletes group storage for a project.
     * 
     * @param project the project
     * @return true if deleted
     */
    boolean delete(IProject project);
}
