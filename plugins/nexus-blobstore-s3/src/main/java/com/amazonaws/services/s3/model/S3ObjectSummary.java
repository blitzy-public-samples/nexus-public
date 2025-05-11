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

import java.util.Date;

/**
 * Represents a summary of an object stored in S3.
 * This class is designed to maintain compatibility with AWS SDK for Java 1.x
 * while using AWS SDK for Java 2.x internally.
 *
 * @since 3.19
 */
public class S3ObjectSummary
{
  private String bucketName;
  private String key;
  private long size;
  private Date lastModified;

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
   * Gets the object size.
   *
   * @return the object size
   */
  public long getSize() {
    return size;
  }

  /**
   * Sets the object size.
   *
   * @param size the object size
   */
  public void setSize(final long size) {
    this.size = size;
  }

  /**
   * Gets the last modified date.
   *
   * @return the last modified date
   */
  public Date getLastModified() {
    return lastModified;
  }

  /**
   * Sets the last modified date.
   *
   * @param lastModified the last modified date
   */
  public void setLastModified(final Date lastModified) {
    this.lastModified = lastModified;
  }
}