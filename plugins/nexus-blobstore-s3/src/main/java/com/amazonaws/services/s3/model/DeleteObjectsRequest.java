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
import java.util.Arrays;
import java.util.List;

/**
 * Request object for deleting multiple objects from S3 in a single request.
 * This class is designed to maintain compatibility with AWS SDK for Java 1.x
 * while using AWS SDK for Java 2.x internally.
 *
 * @since 3.19
 */
public class DeleteObjectsRequest
{
  private String bucketName;
  private List<String> keys = new ArrayList<>();

  /**
   * Constructs a new DeleteObjectsRequest with the specified bucket name.
   *
   * @param bucketName the bucket name
   */
  public DeleteObjectsRequest(final String bucketName) {
    this.bucketName = bucketName;
  }

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
   * Gets the keys of the objects to delete.
   *
   * @return the keys
   */
  public List<String> getKeys() {
    return keys;
  }

  /**
   * Sets the keys of the objects to delete.
   *
   * @param keys the keys
   */
  public void setKeys(final List<String> keys) {
    this.keys = keys;
  }

  /**
   * Sets the keys of the objects to delete.
   *
   * @param keys the keys
   * @return this request for method chaining
   */
  public DeleteObjectsRequest withKeys(final String... keys) {
    this.keys = Arrays.asList(keys);
    return this;
  }
}