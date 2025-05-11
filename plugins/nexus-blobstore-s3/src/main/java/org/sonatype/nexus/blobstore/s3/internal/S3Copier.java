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

import software.amazon.awssdk.services.s3.S3Client;

/**
 * Copies a file in S3.
 * 
 * <p>Updated for Java 21 compatibility using AWS SDK for Java v2.x.</p>
 * 
 * @since 3.15
 */
public interface S3Copier {

  /**
   * Copies a file in S3.
   * 
   * <p>This method leverages AWS SDK for Java v2.x and is compatible with Java 21 features
   * such as virtual threads for improved I/O performance.</p>
   *
   * @param s3Client the S3Client instance used to interact with the AWS S3 service
   * @param bucket the source bucket name
   * @param sourcePath the source object key path
   * @param destinationPath the destination object key path
   */
  void copy(S3Client s3Client, String bucket, String sourcePath, String destinationPath);
}