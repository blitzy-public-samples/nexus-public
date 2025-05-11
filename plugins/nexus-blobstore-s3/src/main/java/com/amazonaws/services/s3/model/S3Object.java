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

import java.io.InputStream;

/**
 * Represents an object stored in S3.
 * This class is designed to maintain compatibility with AWS SDK for Java 1.x
 * while using AWS SDK for Java 2.x internally.
 *
 * @since 3.19
 */
public class S3Object
{
  private String bucketName;
  private String key;
  private InputStream objectContent;
  private ObjectMetadata objectMetadata;

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
   * Gets the object key.
   *
   * @return the object key
   */
  public String getKey() {
    return key;
  }

  /**
   * Sets the object key.
   *
   * @param key the object key
   */
  public void setKey(final String key) {
    this.key = key;
  }

  /**
   * Gets the object content as an input stream.
   *
   * @return the object content
   */
  public InputStream getObjectContent() {
    return objectContent;
  }

  /**
   * Sets the object content as an input stream.
   *
   * @param objectContent the object content
   */
  public void setObjectContent(final InputStream objectContent) {
    this.objectContent = objectContent;
  }

  /**
   * Gets the object metadata.
   *
   * @return the object metadata
   */
  public ObjectMetadata getObjectMetadata() {
    return objectMetadata;
  }

  /**
   * Sets the object metadata.
   *
   * @param objectMetadata the object metadata
   */
  public void setObjectMetadata(final ObjectMetadata objectMetadata) {
    this.objectMetadata = objectMetadata;
  }
}