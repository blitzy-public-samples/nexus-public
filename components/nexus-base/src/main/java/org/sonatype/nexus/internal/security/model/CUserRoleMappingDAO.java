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

import org.sonatype.nexus.datastore.api.DataAccess;

import org.apache.ibatis.annotations.Param;

import static org.sonatype.nexus.common.text.Strings2.lower;
import static org.sonatype.nexus.security.config.SecuritySourceUtil.isCaseInsensitiveSource;

/**
 * {@link CUserRoleMappingData} access.
 * <p>
 * This DAO interface is designed to be compatible with Java 21 Virtual Threads for improved
 * performance with I/O-bound database operations. All methods can be executed efficiently
 * within Virtual Threads without causing thread pinning.
 *
 * @since 3.21
 */
public interface CUserRoleMappingDAO
    extends DataAccess
{
  /**
   * Browse all user role mappings.
   * <p>
   * This operation is suitable for execution within a Virtual Thread as it performs
   * I/O-bound database operations.
   * 
   * @return all user role mappings
   */
  Iterable<CUserRoleMappingData> browse();

  /**
   * Create a new user role mapping.
   * <p>
   * This operation is suitable for execution within a Virtual Thread as it performs
   * I/O-bound database operations.
   * 
   * @param mapping the user role mapping to create
   */
  void create(CUserRoleMappingData mapping);

  /**
   * Read a user role mapping by user ID and source.
   * <p>
   * This operation is suitable for execution within a Virtual Thread as it performs
   * I/O-bound database operations.
   * 
   * @param userId usual case-sensitive userId, non-null when doing usual search
   * @param userLo lowercase userId, non-null when doing case-insensitive search
   * @param source the source of the user
   * @return the user role mapping if found
   */
  Optional<CUserRoleMappingData> read(
      @Param("userId") String userId,
      @Param("userLo") String userLo,
      @Param("source") String source);

  /**
   * Read a user role mapping by user ID and source, automatically handling case sensitivity.
   * <p>
   * This operation is suitable for execution within a Virtual Thread as it performs
   * I/O-bound database operations.
   * 
   * @param userId the user ID
   * @param source the source of the user
   * @return the user role mapping if found
   */
  default Optional<CUserRoleMappingData> read(String userId, String source) {
    return isCaseInsensitiveSource(source) ? read(null, lower(userId), source) : read(userId, null, source);
  }

  /**
   * Update an existing user role mapping.
   * <p>
   * This operation is suitable for execution within a Virtual Thread as it performs
   * I/O-bound database operations.
   * 
   * @param mapping the user role mapping to update
   * @return true if the mapping was updated
   */
  boolean update(CUserRoleMappingData mapping);

  /**
   * Delete a user role mapping by user ID and source.
   * <p>
   * This operation is suitable for execution within a Virtual Thread as it performs
   * I/O-bound database operations.
   * 
   * @param userId usual case-sensitive userId, non-null when doing usual search
   * @param userLo lowercase userId, non-null when doing case-insensitive search
   * @param source the source of the user
   * @return true if the mapping was deleted
   */
  boolean delete(
      @Param("userId") String userId,
      @Param("userLo") String userLo,
      @Param("source") String source);

  /**
   * Delete a user role mapping by user ID and source, automatically handling case sensitivity.
   * <p>
   * This operation is suitable for execution within a Virtual Thread as it performs
   * I/O-bound database operations.
   * 
   * @param userId the user ID
   * @param source the source of the user
   * @return true if the mapping was deleted
   */
  default boolean delete(String userId, String source) {
    return isCaseInsensitiveSource(source) ? delete(null, lower(userId), source) : delete(userId, null, source);
  }
}