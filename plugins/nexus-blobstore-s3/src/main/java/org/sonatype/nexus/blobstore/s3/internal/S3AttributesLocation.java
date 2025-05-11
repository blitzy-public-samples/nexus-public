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
package org.sonatype.nexus.blobstore.s3.internal;

import org.sonatype.nexus.blobstore.AttributesLocation;

import com.amazonaws.services.s3.model.S3ObjectSummary;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Location for S3BlobStore attributes files.
 * 
 * Implemented as a Java Record for immutability and concise data representation.
 *
 * @since 3.15
 * @see AttributesLocation
 * @see S3ObjectSummary
 * 
 * @note Requires Java 21 or higher
 */
public record S3AttributesLocation(String key) implements AttributesLocation {

  /**
   * Creates a new S3AttributesLocation from an S3ObjectSummary.
   * Uses pattern matching to extract the key from the summary.
   *
   * @param summary the S3ObjectSummary containing the key
   * @throws NullPointerException if summary is null
   */
  public S3AttributesLocation(final S3ObjectSummary summary) {
    this(extractKey(summary));
  }
  
  /**
   * Extracts the key from an S3ObjectSummary, with null check.
   *
   * @param summary the S3ObjectSummary to extract the key from
   * @return the extracted key
   * @throws NullPointerException if summary is null
   */
  private static String extractKey(final S3ObjectSummary summary) {
    checkNotNull(summary);
    return summary.getKey();
  }

  @Override
  public String getFileName() {
    return key.substring(key.lastIndexOf('/') + 1);
  }

  @Override
  public String getFullPath() {
    return key;
  }
}