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
package com.amazonaws.services.s3.model;

/**
 * Exception thrown when a bucket does not exist in S3.
 * This class is designed to maintain compatibility with AWS SDK for Java 1.x
 * while using AWS SDK for Java 2.x internally.
 *
 * @since 3.19
 */
public class NoSuchBucketException
    extends AmazonS3Exception
{
  /**
   * Constructs a new NoSuchBucketException with the specified message.
   *
   * @param message the error message
   */
  public NoSuchBucketException(final String message) {
    super(message);
    setErrorCode("NoSuchBucket");
  }

  /**
   * Constructs a new NoSuchBucketException with the specified message and cause.
   *
   * @param message the error message
   * @param cause the cause of the exception
   */
  public NoSuchBucketException(final String message, final Throwable cause) {
    super(message, cause);
    setErrorCode("NoSuchBucket");
  }
}