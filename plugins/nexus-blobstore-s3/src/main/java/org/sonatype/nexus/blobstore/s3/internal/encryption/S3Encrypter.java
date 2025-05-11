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

import com.amazonaws.services.s3.model.AbstractPutObjectRequest;
import com.amazonaws.services.s3.model.CopyObjectRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;

/**
 * Adds any encryption necessary to S3 requests.
 *
 * <p>This interface uses AWS SDK for Java 1.x which is compatible with Java 21 but
 * is in maintenance mode (as of July 31, 2024) with end-of-support on December 31, 2025.
 * Future implementations should consider migrating to AWS SDK for Java 2.x.</p>
 *
 * @since 3.19
 */
public interface S3Encrypter
{
  /**
   * Adds encryption to a multipart upload initiation request.
   *
   * @param <T> the type of request
   * @param request the request to add encryption to
   * @return the modified request with encryption added
   */
  <T extends InitiateMultipartUploadRequest> T addEncryption(T request);
  
  /**
   * Adds encryption to a put object request.
   *
   * @param <T> the type of request
   * @param request the request to add encryption to
   * @return the modified request with encryption added
   */
  <T extends AbstractPutObjectRequest> T addEncryption(T request);
  
  /**
   * Adds encryption to a copy object request.
   *
   * @param <T> the type of request
   * @param request the request to add encryption to
   * @return the modified request with encryption added
   */
  <T extends CopyObjectRequest> T addEncryption(T request);
}