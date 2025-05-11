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
 * Request object for putting an object in S3.
 * This class is designed to maintain compatibility with AWS SDK for Java 1.x
 * while using AWS SDK for Java 2.x internally.
 *
 * @since 3.19
 */
public class PutObjectRequest
{
  private String bucketName;
  private String key;
  private File file;
  private InputStream inputStream;
  private ObjectMetadata metadata;
  private String redirectLocation;

  /**
   * Constructs a new PutObjectRequest with the specified parameters.
   *
   * @param bucketName the bucket name
   * @param key the object key
   * @param file the file to upload
   */
  public PutObjectRequest(final String bucketName, final String key, final File file) {
    this.bucketName = bucketName;
    this.key = key;
    this.file = file;
  }

  /**
   * Constructs a new PutObjectRequest with the specified parameters.
   *
   * @param bucketName the bucket name
   * @param key the object key
   * @param redirectLocation the redirect location
   */
  public PutObjectRequest(final String bucketName, final String key, final String redirectLocation) {
    this.bucketName = bucketName;
    this.key = key;
    this.redirectLocation = redirectLocation;
  }

  /**
   * Constructs a new PutObjectRequest with the specified parameters.
   *
   * @param bucketName the bucket name
   * @param key the object key
   * @param inputStream the input stream
   * @param metadata the object metadata
   */
  public PutObjectRequest(
      final String bucketName,
      final String key,
      final InputStream inputStream,
      final ObjectMetadata metadata)
  {
    this.bucketName = bucketName;
    this.key = key;
    this.inputStream = inputStream;
    this.metadata = metadata;
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

  /**
   * Gets the object metadata.
   *
   * @return the object metadata
   */
  public ObjectMetadata getMetadata() {
    return metadata;
  }

  /**
   * Sets the object metadata.
   *
   * @param metadata the object metadata
   */
  public void setMetadata(final ObjectMetadata metadata) {
    this.metadata = metadata;
  }

  /**
   * Gets the redirect location.
   *
   * @return the redirect location
   */
  public String getRedirectLocation() {
    return redirectLocation;
  }

  /**
   * Sets the redirect location.
   *
   * @param redirectLocation the redirect location
   */
  public void setRedirectLocation(final String redirectLocation) {
    this.redirectLocation = redirectLocation;
  }
}