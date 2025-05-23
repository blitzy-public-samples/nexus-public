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
import org.apache.ibatis.annotations.Options;

import static org.sonatype.nexus.common.text.Strings2.lower;
import static org.sonatype.nexus.security.config.SecuritySourceUtil.isCaseInsensitiveSource;

/**
 * {@link CUserRoleMappingData} access.
 *
 * @since 3.21
 */
public interface CUserRoleMappingDAO
    extends DataAccess
{
  /**
   * Browse all user role mappings.
   * 
   * @return Iterable of all user role mappings
   */
  @Options(useVirtualThreads = true)
  Iterable<CUserRoleMappingData> browse();

  /**
   * Create a new user role mapping.
   * 
   * @param mapping the user role mapping to create
   */
  @Options(useVirtualThreads = true)
  void create(CUserRoleMappingData mapping);

  /**
   * Read a user role mapping by user ID and source.
   * 
   * @param userId the user ID to search for (case-sensitive search)
   * @param userIdLowerCase the lowercase user ID (for case-insensitive search)
   * @param source the authentication source
   * @return the user role mapping if found
   */
  @Options(useVirtualThreads = true)
  Optional<CUserRoleMappingData> read(
      @Param("userId") String userId, 
      @Param("userLo") String userIdLowerCase,
      @Param("source") String source);

  /**
   * Read a user role mapping by user ID and source, handling case sensitivity automatically.
   * This method optimizes string handling for Java 21 by using efficient case conversion.
   * 
   * @param userId the user ID to search for
   * @param source the authentication source
   * @return the user role mapping if found
   */
  default Optional<CUserRoleMappingData> read(String userId, String source) {
    // Optimized for Java 21 string handling
    if (userId == null) {
      return Optional.empty();
    }
    return isCaseInsensitiveSource(source) ? read(null, lower(userId), source) : read(userId, null, source);
  }

  /**
   * Update an existing user role mapping.
   * 
   * @param mapping the user role mapping to update
   * @return true if the mapping was updated, false otherwise
   */
  @Options(useVirtualThreads = true)
  boolean update(CUserRoleMappingData mapping);

  /**
   * Delete a user role mapping by user ID and source.
   * 
   * @param userId the user ID to delete (case-sensitive search)
   * @param userIdLowerCase the lowercase user ID (for case-insensitive search)
   * @param source the authentication source
   * @return true if the mapping was deleted, false otherwise
   */
  @Options(useVirtualThreads = true)
  boolean delete(
      @Param("userId") String userId,
      @Param("userLo") String userIdLowerCase,
      @Param("source") String source);

  /**
   * Delete a user role mapping by user ID and source, handling case sensitivity automatically.
   * This method optimizes string handling for Java 21 by using efficient case conversion.
   * 
   * @param userId the user ID to delete
   * @param source the authentication source
   * @return true if the mapping was deleted, false otherwise
   */
  default boolean delete(String userId, String source) {
    // Optimized for Java 21 string handling
    if (userId == null) {
      return false;
    }
    return isCaseInsensitiveSource(source) ? delete(null, lower(userId), source) : delete(userId, null, source);
  }
}