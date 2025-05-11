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
package com.amazonaws.services.s3.iterable;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.S3ObjectSummary;

/**
 * Utility class for iterating over S3 objects.
 * This class is designed to maintain compatibility with AWS SDK for Java 1.x
 * while using AWS SDK for Java 2.x internally.
 *
 * @since 3.19
 */
public class S3Objects
{
  /**
   * Returns an iterable of S3 object summaries for the specified bucket and prefix.
   *
   * @param s3 the S3 client
   * @param bucketName the bucket name
   * @param prefix the prefix
   * @return an iterable of S3 object summaries
   */
  public static Iterable<S3ObjectSummary> withPrefix(final AmazonS3 s3, final String bucketName, final String prefix) {
    return com.amazonaws.services.s3.model.S3Objects.withPrefix(s3, bucketName, prefix);
  }
}