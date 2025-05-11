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

import java.io.File;
import java.io.InputStream;

/**
 * Request object for uploading a part in a multipart upload.
 * This class is designed to maintain compatibility with AWS SDK for Java 1.x
 * while using AWS SDK for Java 2.x internally.
 *
 * @since 3.19
 */
public class UploadPartRequest
{
  private String bucketName;
  private String key;
  private String uploadId;
  private int partNumber;
  private long partSize;
  private File file;
  private InputStream inputStream;

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
   * Gets the upload ID.
   *
   * @return the upload ID
   */
  public String getUploadId() {
    return uploadId;
  }

  /**
   * Sets the upload ID.
   *
   * @param uploadId the upload ID
   */
  public void setUploadId(final String uploadId) {
    this.uploadId = uploadId;
  }

  /**
   * Gets the part number.
   *
   * @return the part number
   */
  public int getPartNumber() {
    return partNumber;
  }

  /**
   * Sets the part number.
   *
   * @param partNumber the part number
   */
  public void setPartNumber(final int partNumber) {
    this.partNumber = partNumber;
  }

  /**
   * Gets the part size.
   *
   * @return the part size
   */
  public long getPartSize() {
    return partSize;
  }

  /**
   * Sets the part size.
   *
   * @param partSize the part size
   */
  public void setPartSize(final long partSize) {
    this.partSize = partSize;
  }

  /**
   * Gets the file to upload.
   *
   * @return the file
   */
  public File getFile() {
    return file;
  }

  /**
   * Sets the file to upload.
   *
   * @param file the file
   */
  public void setFile(final File file) {
    this.file = file;
  }

  /**
   * Gets the input stream.
   *
   * @return the input stream
   */
  public InputStream getInputStream() {
    return inputStream;
  }

  /**
   * Sets the input stream.
   *
   * @param inputStream the input stream
   */
  public void setInputStream(final InputStream inputStream) {
    this.inputStream = inputStream;
  }
}