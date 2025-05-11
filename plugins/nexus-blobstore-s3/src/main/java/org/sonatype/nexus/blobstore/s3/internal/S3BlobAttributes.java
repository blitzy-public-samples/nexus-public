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

import java.io.IOException;
import java.util.Map;

import org.sonatype.nexus.blobstore.BlobAttributesSupport;
import org.sonatype.nexus.blobstore.api.BlobAttributes;
import org.sonatype.nexus.blobstore.api.BlobMetrics;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.AmazonS3Exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * {@link BlobAttributes} backed by {@link S3PropertiesFile}
 * 
 * This implementation is compatible with Java 21 and leverages modern language features
 * such as pattern matching for instanceof and string templates. The class handles
 * loading and storing blob attributes in Amazon S3 storage.
 * 
 * This class is designed to work with Java 21 and AWS SDK for Java.
 *
 * @since 3.6.1
 */
public class S3BlobAttributes
    extends BlobAttributesSupport<S3PropertiesFile>
{
  private static final Logger log = LoggerFactory.getLogger(S3BlobAttributes.class);
  /**
   * Constructs a new S3BlobAttributes with no initial headers or metrics.
   * 
   * @param s3 The AmazonS3 client
   * @param bucket The S3 bucket name
   * @param key The S3 object key
   */
  public S3BlobAttributes(final AmazonS3 s3, final String bucket, final String key) {
    super(new S3PropertiesFile(s3, bucket, key), null, null);
  }

  /**
   * Constructs a new S3BlobAttributes with the specified headers and metrics.
   * 
   * @param s3 The AmazonS3 client
   * @param bucket The S3 bucket name
   * @param key The S3 object key
   * @param headers The blob headers
   * @param metrics The blob metrics
   */
  public S3BlobAttributes(
      final AmazonS3 s3,
      final String bucket,
      final String key,
      final Map<String, String> headers,
      final BlobMetrics metrics)
  {
    super(new S3PropertiesFile(s3, bucket, key), checkNotNull(headers), checkNotNull(metrics));
  }

  /**
   * Loads the blob attributes from S3.
   * Uses Java 21 pattern matching for instanceof to check for 404 status code in exceptions.
   * 
   * @return true if the attributes were loaded successfully, false if the object doesn't exist
   * @throws IOException if an I/O error occurs during loading
   */
  public boolean load() throws IOException {
    try {
      propertiesFile.load();
      readFrom(propertiesFile);
      log.debug(STR."Loaded blob attributes: \{propertiesFile}");
      return true;
    }
    catch (AmazonS3Exception e) {
      // Using pattern matching to check for 404 status code
      if (e instanceof AmazonS3Exception exception && exception.getStatusCode() != 404) {
        log.error(STR."Failed to load blob attributes: \{propertiesFile}", e);
        throw e;
      }
      log.debug(STR."Blob attributes not found: \{propertiesFile}");
      return false;
    }
  }

  /**
   * Stores the blob attributes to S3.
   * 
   * @throws IOException if an I/O error occurs during storage
   */
  @Override
  public void store() throws IOException {
    writeTo(propertiesFile);
    propertiesFile.store();
    // Using Java 21 string template for logging
    log.debug(STR."Stored blob attributes: \{propertiesFile}");
  }

  /**
   * Writes properties to the underlying properties file without storing them.
   */
  @Override
  public void writeProperties() {
    writeTo(propertiesFile);
    log.debug(STR."Updated properties for blob attributes: \{propertiesFile}");
  }
}