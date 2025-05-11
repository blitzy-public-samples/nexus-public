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

import java.io.InputStream;

import software.amazon.awssdk.services.s3.S3Client;

/**
 * Uploads a file to S3.
 * <p>
 * This interface has been updated for Java 21 compatibility using AWS SDK for Java 2.x.
 * Implementations should leverage Java 21 virtual threads for I/O operations to improve
 * performance and scalability when handling concurrent uploads.
 * </p>
 * 
 * @since 3.12
 * @see <a href="https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/home.html">AWS SDK for Java 2.x</a>
 */
public interface S3Uploader {

  /**
   * Upload a file to S3.
   * <p>
   * When implemented with Java 21, this method can benefit from running in a virtual thread
   * to improve I/O throughput without consuming platform thread resources.
   * </p>
   * 
   * @param s3 the S3 client
   * @param bucket the S3 bucket name
   * @param key the object key in the bucket
   * @param contents the input stream containing the file contents
   */
  void upload(S3Client s3, String bucket, String key, InputStream contents);
}
