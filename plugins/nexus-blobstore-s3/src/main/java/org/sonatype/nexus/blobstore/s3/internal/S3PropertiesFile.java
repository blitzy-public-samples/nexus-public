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
package org.sonatype.nexus.blobstore.s3.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.sonatype.nexus.blobstore.CloudBlobPropertiesSupport;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.blobstore.api.BlobAttributesConstants.HEADER_PREFIX;
import static org.sonatype.nexus.blobstore.api.BlobStore.TEMPORARY_BLOB_HEADER;

/**
 * Persistent properties file stored in AWS S3.
 * 
 * This implementation is compatible with Java 21 and leverages modern language features
 * such as pattern matching for instanceof and string templates. The class handles
 * loading and storing property files in Amazon S3 storage.
 * 
 * This class is designed to work with Java 21 and AWS SDK for Java.
 *
 * @since 3.6.1
 */
public class S3PropertiesFile
    extends CloudBlobPropertiesSupport<ObjectMetadata>
{
  private static final Logger log = LoggerFactory.getLogger(S3PropertiesFile.class);

  private final AmazonS3 s3;

  private final String bucket;

  private final String key;

  /**
   * Constructs a new S3PropertiesFile.
   * 
   * @param s3 The AmazonS3 client
   * @param bucket The S3 bucket name
   * @param key The S3 object key
   */
  public S3PropertiesFile(final AmazonS3 s3, final String bucket, final String key) {
    this.s3 = checkNotNull(s3);
    this.bucket = checkNotNull(bucket);
    this.key = checkNotNull(key);
  }

  /**
   * Loads properties from S3 object.
   * 
   * @throws IOException if an I/O error occurs during loading
   */
  public void load() throws IOException {
    log.debug(STR."Loading: \{bucket}/\{key}");

    try (S3Object object = s3.getObject(bucket, key)) {
      try (InputStream inputStream = object.getObjectContent()) {
        load(inputStream);
      }
    }
  }

  /**
   * Creates and returns metadata for the S3 object.
   * 
   * @return The object metadata
   */
  @Override
  public ObjectMetadata getMetadata() {
    ObjectMetadata metadata = new ObjectMetadata();
    maybePutTempBlobUserMetadata(metadata);
    return metadata;
  }

  /**
   * Gets the data as a ByteArrayOutputStream.
   * 
   * @return The data stream
   * @throws IOException if an I/O error occurs during data retrieval
   */
  @Override
  public ByteArrayOutputStream getData() throws IOException {
    ByteArrayOutputStream bufferStream = new ByteArrayOutputStream();
    store(bufferStream, null);
    return bufferStream;
  }

  /**
   * Writes data to S3 with the provided metadata.
   * 
   * @param data The data to write
   * @param metadata The metadata to associate with the S3 object
   */
  @Override
  protected void write(final ByteArrayOutputStream data, final ObjectMetadata metadata) {
    log.debug(STR."Storing: \{bucket}/\{key}");
    byte[] buffer = data.toByteArray();
    metadata.setContentLength(buffer.length);
    s3.putObject(bucket, key, new ByteArrayInputStream(buffer), metadata);
  }

  /**
   * Adds temporary blob metadata if applicable.
   * 
   * @param metadata The metadata to potentially modify
   */
  private void maybePutTempBlobUserMetadata(final ObjectMetadata metadata) {
    if (containsKey(HEADER_PREFIX + TEMPORARY_BLOB_HEADER)) {
      metadata.addUserMetadata(TEMPORARY_BLOB_HEADER, "true");
    }
  }

  /**
   * Checks if the S3 object exists.
   * 
   * @return true if the object exists, false otherwise
   * @throws IOException if an I/O error occurs during the check
   */
  public boolean exists() throws IOException {
    return s3.doesObjectExist(bucket, key);
  }

  /**
   * Removes the S3 object.
   * 
   * @throws IOException if an I/O error occurs during removal
   */
  public void remove() throws IOException {
    s3.deleteObject(bucket, key);
  }

  public String toString() {
    return STR."s3://\{bucket}/\{key} \{super.toString()}";
  }

  /**
   * Compares this object with another for equality.
   * Uses Java 21 pattern matching for instanceof to simplify the code.  
   * 
   * @param object The object to compare with
   * @return true if the objects are equal, false otherwise
   */
  @Override
  public boolean equals(Object object) {
    if (object instanceof S3PropertiesFile other) {
      return
          s3.equals(other.s3) &&
              bucket.equals(other.bucket) &&
              key.equals(other.key) &&
              super.equals(object);
    }
    else {
      return false;
    }
  }

  /**
   * Generates a hash code for this object.
   * 
   * @return The hash code value
   */
  @Override
  public int hashCode() {
    return
        s3.hashCode() +
            bucket.hashCode() +
            key.hashCode() +
            super.hashCode();
  }
}