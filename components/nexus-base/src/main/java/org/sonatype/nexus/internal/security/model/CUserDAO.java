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
package org.sonatype.nexus.internal.security.model;

import java.util.Optional;

import org.apache.ibatis.annotations.Param;
import org.sonatype.nexus.datastore.api.IdentifiedDataAccess;

/**
 * {@link CUserData} access.
 *
 * <p>This interface is compatible with Java 21 Virtual Threads for database operations,
 * allowing for high-concurrency database access with minimal resource usage.</p>
 *
 * @since 3.21
 */
public interface CUserDAO
    extends IdentifiedDataAccess<CUserData>
{
  /**
   * Browse existing user entities.
   *
   * @return an iterable of all user entities
   */
  @Override
  Iterable<CUserData> browse();

  /**
   * Create a new user entity.
   *
   * @param entity the user entity to create
   */
  @Override
  void create(@Param("entity") CUserData entity);

  /**
   * Retrieve the user entity with the given id.
   *
   * @param id the id of the user entity to retrieve
   * @return an optional containing the user entity if found
   */
  @Override
  Optional<CUserData> read(@Param("id") String id);

  /**
   * Update an existing user entity.
   *
   * @param entity the user entity to update
   * @return true if the entity was updated, false otherwise
   */
  @Override
  boolean update(@Param("entity") CUserData entity);

  /**
   * Delete the user entity with the given id.
   *
   * @param id the id of the user entity to delete
   * @return true if the entity was deleted, false otherwise
   */
  @Override
  boolean delete(@Param("id") String id);
}