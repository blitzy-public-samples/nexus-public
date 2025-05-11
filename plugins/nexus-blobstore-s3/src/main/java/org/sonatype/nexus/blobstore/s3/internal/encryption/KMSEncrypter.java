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

import java.util.Optional;

import javax.inject.Named;

import com.amazonaws.services.s3.model.AbstractPutObjectRequest;
import com.amazonaws.services.s3.model.CopyObjectRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.SSEAwsKeyManagementParams;
import com.google.common.annotations.VisibleForTesting;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Optional.empty;

/**
 * Adds KMS encryption to S3 requests.
 * The keyID is optional and in the params.
 * 
 * This implementation is compatible with Java 21 and uses AWS SDK for S3 KMS encryption.
 * It ensures secure key management through AWS KMS service while maintaining compatibility
 * with Java 21's enhanced security model.
 *
 * @since 3.19
 */
@Named(KMSEncrypter.ID)
public class KMSEncrypter
    implements S3Encrypter
{
  public static final String ID = "kmsManagedEncryption";

  public static final String NAME = "KMS Managed Encryption";

  /**
   * Returns the KMS parameters used for encryption.
   * Exposed for testing purposes only.
   * 
   * @return The SSEAwsKeyManagementParams instance used for encryption
   */
  @VisibleForTesting
  SSEAwsKeyManagementParams getKmsParameters() {
    return kmsParameters;
  }

  private final SSEAwsKeyManagementParams kmsParameters;

  /**
   * Creates a KMSEncrypter with default parameters (no specific KMS key ID).
   */
  public KMSEncrypter() {
    this(empty());
  }

  /**
   * Creates a KMSEncrypter with the specified KMS ID.
   * 
   * @param kmsId Optional KMS key ID to use for encryption. If empty or contains only whitespace,
   *              a default SSEAwsKeyManagementParams instance will be used.
   */
  public KMSEncrypter(final Optional<String> kmsId) {
    this.kmsParameters = checkNotNull(kmsId)
        .map(String::trim)
        .filter(id -> !id.isEmpty())
        .map(SSEAwsKeyManagementParams::new)
        .orElse(new SSEAwsKeyManagementParams());
  }

  /**
   * Adds KMS encryption parameters to a multipart upload request.
   * 
   * @param request The multipart upload request to add encryption to
   * @param <T> The type of request
   * @return The modified request with encryption parameters
   */
  @Override
  public <T extends InitiateMultipartUploadRequest> T addEncryption(final T request) {
    request.setSSEAwsKeyManagementParams(kmsParameters);
    return request;
  }

  /**
   * Adds KMS encryption parameters to a put object request.
   * 
   * @param request The put object request to add encryption to
   * @param <T> The type of request
   * @return The modified request with encryption parameters
   */
  @Override
  public <T extends AbstractPutObjectRequest> T addEncryption(final T request) {
    request.setSSEAwsKeyManagementParams(kmsParameters);
    return request;
  }

  /**
   * Adds KMS encryption parameters to a copy object request.
   * 
   * @param request The copy object request to add encryption to
   * @param <T> The type of request
   * @return The modified request with encryption parameters
   */
  @Override
  public <T extends CopyObjectRequest> T addEncryption(final T request) {
    request.setSSEAwsKeyManagementParams(kmsParameters);
    return request;
  }
}