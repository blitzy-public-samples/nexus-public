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

import java.util.ArrayList;
import java.util.List;

/**
 * Result object for listing objects in S3 (version 2).
 * This class is designed to maintain compatibility with AWS SDK for Java 1.x
 * while using AWS SDK for Java 2.x internally.
 *
 * @since 3.19
 */
public class ListObjectsV2Result
{
  private String bucketName;
  private String prefix;
  private int maxKeys;
  private boolean truncated;
  private String nextContinuationToken;
  private List<S3ObjectSummary> objectSummaries = new ArrayList<>();

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
   */
  public void setBucketName(final String bucketName) {
    this.bucketName = bucketName;
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
   */
  public void setPrefix(final String prefix) {
    this.prefix = prefix;
  }

  /**
   * Gets the maximum number of keys.
   *
   * @return the maximum number of keys
   */
  public int getMaxKeys() {
    return maxKeys;
  }

  /**
   * Sets the maximum number of keys.
   *
   * @param maxKeys the maximum number of keys
   */
  public void setMaxKeys(final int maxKeys) {
    this.maxKeys = maxKeys;
  }

  /**
   * Checks if the result is truncated.
   *
   * @return true if the result is truncated, false otherwise
   */
  public boolean isTruncated() {
    return truncated;
  }

  /**
   * Sets whether the result is truncated.
   *
   * @param truncated whether the result is truncated
   */
  public void setTruncated(final boolean truncated) {
    this.truncated = truncated;
  }

  /**
   * Gets the next continuation token.
   *
   * @return the next continuation token
   */
  public String getNextContinuationToken() {
    return nextContinuationToken;
  }

  /**
   * Sets the next continuation token.
   *
   * @param nextContinuationToken the next continuation token
   */
  public void setNextContinuationToken(final String nextContinuationToken) {
    this.nextContinuationToken = nextContinuationToken;
  }

  /**
   * Gets the object summaries.
   *
   * @return the object summaries
   */
  public List<S3ObjectSummary> getObjectSummaries() {
    return objectSummaries;
  }

  /**
   * Sets the object summaries.
   *
   * @param objectSummaries the object summaries
   */
  public void setObjectSummaries(final List<S3ObjectSummary> objectSummaries) {
    this.objectSummaries = objectSummaries;
  }
}