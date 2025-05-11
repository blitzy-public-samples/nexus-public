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

package org.sonatype.nexus.content.maven.store;

import javax.annotation.Nullable;

import org.sonatype.nexus.common.entity.Continuation;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.store.AssetDAO;

import org.apache.ibatis.annotations.Param;

/**
 * Maven 2 specific {@link AssetDAO} that provides specialized queries for Maven plugin assets.
 * <p>
 * This interface is compatible with Java 21 and works with the updated MyBatis 3.5.15 dependency.
 * The implementation of this interface benefits from Java 21's Virtual Threads when performing
 * I/O-bound database operations, allowing for higher concurrency with minimal resource overhead.
 * </p>
 *
 * @since 3.25
 */
public interface Maven2AssetDAO
    extends AssetDAO
{
  /**
   * Find jar assets associated with Components in the namespace of kind maven-plugin.
   * <p>
   * This query is optimized for database operations and benefits from Java 21's Virtual Threads
   * infrastructure when executed within a transactional context. The implementation automatically
   * leverages the platform's thread management system for I/O-bound operations.
   * </p>
   *
   * @param repositoryId the repository to search
   * @param limit maximum number of assets to return
   * @param continuationToken optional token to continue from a previous request
   * @param namespace the namespace to find plugins for
   * @return collection of assets and the next continuation token
   *
   * @see Continuation#nextContinuationToken()
   */
  Continuation<Asset> findMavenPluginAssetsForNamespace(
      @Param("repositoryId") int repositoryId,
      @Param("limit") int limit,
      @Nullable @Param("continuationToken") String continuationToken,
      @Param("namespace") String namespace);
}