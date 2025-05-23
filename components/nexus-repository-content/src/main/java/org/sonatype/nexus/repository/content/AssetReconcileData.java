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
package org.sonatype.nexus.repository.content;

import org.sonatype.nexus.blobstore.api.BlobRef;
import org.sonatype.nexus.common.entity.ContinuationAware;

/**
 * Record for asset reconciliation data that encapsulates information needed for blob reconciliation.
 * Implements {@link ContinuationAware} to support pagination in queries.
 *
 * @since 3.21
 */
public record AssetReconcileData(
    BlobRef blobRef,
    String repository,
    String path,
    Integer assetBlobId
) implements ContinuationAware
{
  /**
   * Returns the continuation token for pagination, using the assetBlobId as the token.
   * Leverages Java 21 String templates for more efficient string handling.
   *
   * @return the continuation token as a string
   */
  @Override
  public String nextContinuationToken() {
    return STR."{assetBlobId}";
  }
  
  /**
   * Factory method to create an instance with null values, useful for MyBatis.
   *
   * @return a new instance with null values
   */
  public static AssetReconcileData empty() {
    return new AssetReconcileData(null, null, null, null);
  }
}