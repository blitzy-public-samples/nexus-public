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
package org.sonatype.nexus.blobstore.s3.internal.encryption;

import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Interface for adding encryption to S3 requests.
 * Updated to work with AWS SDK for Java 2.x.
 *
 * @since 3.19
 */
public interface S3Encrypter
{
  /**
   * Adds encryption settings to a PutObjectRequest.
   *
   * @param request the request to add encryption to
   */
  void addEncryption(PutObjectRequest.Builder request);

  /**
   * Adds encryption settings to a CopyObjectRequest.
   *
   * @param request the request to add encryption to
   */
  void addEncryption(CopyObjectRequest.Builder request);

  /**
   * Adds encryption settings to a CreateMultipartUploadRequest.
   *
   * @param request the request to add encryption to
   */
  void addEncryption(CreateMultipartUploadRequest.Builder request);
}