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
 * <p>This class is immutable and thread-safe. All fields are final and the class provides no
 * methods that can modify its state. This makes it safe to share instances across threads without
 * synchronization, including Java 21 Virtual Threads.</p>
 *
 * @since 3.14
 */
public class BlobStoreQuotaResult
{
  private final boolean isViolation;

  private final String humanReadableMessage;

  private final String blobStoreName;

  /**
   * Constructs a new immutable quota result.
   *
   * @param isViolation whether the quota is violated
   * @param blobStoreName name of the blob store (must not be null)
   * @param humanReadableMessage human-readable message describing the result (must not be null)
   */
  public BlobStoreQuotaResult(
      final boolean isViolation,
      final String blobStoreName,
      final String humanReadableMessage)
  {
    this.isViolation = isViolation;
    this.blobStoreName = checkNotNull(blobStoreName);
    this.humanReadableMessage = checkNotNull(humanReadableMessage);
  }

  /**
   * Returns whether the quota is violated.
   *
   * @return true if the quota is violated, false otherwise
   */
  public boolean isViolation() {
    return this.isViolation;
  }

  /**
   * Returns the human-readable message describing the result.
   *
   * @return the message
   */
  public String getMessage() {
    return this.humanReadableMessage;
  }

  /**
   * Returns the name of the blob store.
   *
   * @return the blob store name
   */
  public String getBlobStoreName() {
    return this.blobStoreName;
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "{" +
        "isViolation='" + isViolation + '\'' +
        ", message='" + humanReadableMessage + '\'' +
        ", blobStoreName='" + blobStoreName + '\'' +
        '}';
  }
}