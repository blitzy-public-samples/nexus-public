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
 * Request object for copying an object in S3.
 * This class is designed to maintain compatibility with AWS SDK for Java 1.x
 * while using AWS SDK for Java 2.x internally.
 *
 * @since 3.19
 */
public class CopyObjectRequest
{
  private String sourceBucketName;
  private String sourceKey;
  private String destinationBucketName;
  private String destinationKey;

  /**
   * Constructs a new CopyObjectRequest with the specified parameters.
   *
   * @param sourceBucketName the source bucket name
   * @param sourceKey the source key
   * @param destinationBucketName the destination bucket name
   * @param destinationKey the destination key
   */
  public CopyObjectRequest(
      final String sourceBucketName,
      final String sourceKey,
      final String destinationBucketName,
      final String destinationKey)
  {
    this.sourceBucketName = sourceBucketName;
    this.sourceKey = sourceKey;
    this.destinationBucketName = destinationBucketName;
    this.destinationKey = destinationKey;
  }

  /**
   * Gets the source bucket name.
   *
   * @return the source bucket name
   */
  public String getSourceBucketName() {
    return sourceBucketName;
  }

  /**
   * Sets the source bucket name.
   *
   * @param sourceBucketName the source bucket name
   */
  public void setSourceBucketName(final String sourceBucketName) {
    this.sourceBucketName = sourceBucketName;
  }

  /**
   * Gets the source key.
   *
   * @return the source key
   */
  public String getSourceKey() {
    return sourceKey;
  }

  /**
   * Sets the source key.
   *
   * @param sourceKey the source key
   */
  public void setSourceKey(final String sourceKey) {
    this.sourceKey = sourceKey;
  }

  /**
   * Gets the destination bucket name.
   *
   * @return the destination bucket name
   */
  public String getDestinationBucketName() {
    return destinationBucketName;
  }

  /**
   * Sets the destination bucket name.
   *
   * @param destinationBucketName the destination bucket name
   */
  public void setDestinationBucketName(final String destinationBucketName) {
    this.destinationBucketName = destinationBucketName;
  }

  /**
   * Gets the destination key.
   *
   * @return the destination key
   */
  public String getDestinationKey() {
    return destinationKey;
  }

  /**
   * Sets the destination key.
   *
   * @param destinationKey the destination key
   */
  public void setDestinationKey(final String destinationKey) {
    this.destinationKey = destinationKey;
  }
}