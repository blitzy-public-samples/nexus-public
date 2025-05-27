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
package org.sonatype.nexus.blobstore.quota;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Holds result for the evaluation of {@link BlobStoreQuota}.
 * 
 * This class is immutable and thread-safe, making it suitable for use with Virtual Threads
 * in high-concurrency environments. All fields are final and the object's state cannot be
 * modified after construction.
 *
 * @since 3.14
 */
public record BlobStoreQuotaResult(
    boolean isViolation,
    String blobStoreName,
    String humanReadableMessage)
{
  /**
   * Constructs a new BlobStoreQuotaResult with the specified parameters.
   * 
   * @param isViolation whether the quota is violated
   * @param blobStoreName name of the blob store (must not be null)
   * @param humanReadableMessage human-readable message describing the result (must not be null)
   * @throws NullPointerException if blobStoreName or humanReadableMessage is null
   */
  public BlobStoreQuotaResult {
    checkNotNull(blobStoreName, "Blob store name cannot be null");
    checkNotNull(humanReadableMessage, "Human readable message cannot be null");
  }

  /**
   * Returns whether the quota is violated.
   * 
   * @return true if the quota is violated, false otherwise
   */
  public boolean isViolation() {
    return isViolation;
  }

  /**
   * Returns the human-readable message describing the result.
   * 
   * @return the human-readable message
   */
  public String getMessage() {
    return humanReadableMessage;
  }

  /**
   * Returns the name of the blob store.
   * 
   * @return the blob store name
   */
  public String getBlobStoreName() {
    return blobStoreName;
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "{"
        + "isViolation='" + isViolation + '\''
        + ", message='" + humanReadableMessage + '\''
        + ", blobStoreName='" + blobStoreName + '\''
        + '}';
  }
}