/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.script.plugin.internal;

import java.util.List;

import javax.annotation.Nullable;

import org.sonatype.goodies.lifecycle.Lifecycle;
import org.sonatype.nexus.script.Script;


/**
 * Store for managing {@link Script} entities.
 * 
 * @since 3.0
 */
public interface ScriptStore
    extends Lifecycle
{

  /**
   * Create a new, unpopulated Script
   * @since 3.20
   */
  Script newScript();

  /**
   * Returns all stored {@link Script} entities.
   * 
   * @return an immutable list of all stored scripts
   */
  List<Script> list();

  /**
   * Retrieves a {@link Script} by name.
   * 
   * @param name the name of the script to retrieve
   * @return the script with matching name, or null if not found
   */
  @Nullable
  Script get(String name);

  /**
   * Persists a new {@link Script}.
   * 
   * @param script the script to create
   */
  void create(Script script);

  /**
   * Updates an existing {@link Script}.
   * 
   * @param script the script to update
   */
  void update(Script script);

  /**
   * Deletes an existing {@link Script}.
   * 
   * @param script the script to delete
   */
  void delete(Script script);
}