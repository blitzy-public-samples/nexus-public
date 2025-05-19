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
 * Record for asset reconciliation data, leveraging Java 21 record patterns for efficient data encapsulation.
 * 
 * @since 3.20
 */
public record AssetReconcileData(BlobRef blobRef, String repository, String path, Integer assetBlobId)
    implements ContinuationAware
{
  /**
   * Creates a continuation token using Java 21 String Templates for improved token generation.
   * 
   * @return the token to use when requesting the next set of results
   */
  @Override
  public String nextContinuationToken() {
    return STR."asset_blob_\{assetBlobId}";
  }
  
  /**
   * Pattern matching utility method to extract the asset blob ID from a token.
   * 
   * @param token the continuation token
   * @return the extracted asset blob ID, or null if the token doesn't match the expected pattern
   */
  public static Integer extractAssetBlobId(String token) {
    if (token != null && token.startsWith("asset_blob_")) {
      try {
        return Integer.parseInt(token.substring(11));
      }
      catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }
}
