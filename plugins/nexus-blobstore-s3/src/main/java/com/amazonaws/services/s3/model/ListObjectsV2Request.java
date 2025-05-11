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
 * Request object for listing objects in S3 (version 2).
 * This class is designed to maintain compatibility with AWS SDK for Java 1.x
 * while using AWS SDK for Java 2.x internally.
 *
 * @since 3.19
 */
public class ListObjectsV2Request
{
  private String bucketName;
  private String prefix;
  private int maxKeys = 1000; // Default value
  private String continuationToken;

  /**
   * Gets the bucket name.
   *
   * @return the bucket name
   */
  public String getBucketName() {
    return bucketName;
  }

  /**
   * Sets the bucket name.
   *
   * @param bucketName the bucket name
   * @return this request for method chaining
   */
  public ListObjectsV2Request withBucketName(final String bucketName) {
    this.bucketName = bucketName;
    return this;
  }

  /**
   * Gets the prefix.
   *
   * @return the prefix
   */
  public String getPrefix() {
    return prefix;
  }

  /**
   * Sets the prefix.
   *
   * @param prefix the prefix
   * @return this request for method chaining
   */
  public ListObjectsV2Request withPrefix(final String prefix) {
    this.prefix = prefix;
    return this;
  }

  /**
   * Gets the maximum number of keys to return.
   *
   * @return the maximum number of keys
   */
  public int getMaxKeys() {
    return maxKeys;
  }

  /**
   * Sets the maximum number of keys to return.
   *
   * @param maxKeys the maximum number of keys
   * @return this request for method chaining
   */
  public ListObjectsV2Request withMaxKeys(final int maxKeys) {
    this.maxKeys = maxKeys;
    return this;
  }

  /**
   * Gets the continuation token.
   *
   * @return the continuation token
   */
  public String getContinuationToken() {
    return continuationToken;
  }

  /**
   * Sets the continuation token.
   *
   * @param continuationToken the continuation token
   * @return this request for method chaining
   */
  public ListObjectsV2Request withContinuationToken(final String continuationToken) {
    this.continuationToken = continuationToken;
    return this;
  }
}