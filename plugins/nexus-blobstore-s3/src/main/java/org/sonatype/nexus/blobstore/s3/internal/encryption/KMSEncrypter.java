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

import com.google.common.annotations.VisibleForTesting;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Optional.empty;

/**
 * Adds KMS encryption to S3 requests.
 * The keyID is optional and in the params
 *
 * Updated for AWS SDK v2.x and Java 21 Virtual Threads.
 *
 * @since 3.19
 */
@Named(KMSEncrypter.ID)
public class KMSEncrypter
    implements S3Encrypter
{
  public static final String ID = "kmsManagedEncryption";

  public static final String NAME = "KMS Managed Encryption";

  private final String kmsKeyId;

  public KMSEncrypter() {
    this(empty());
  }

  public KMSEncrypter(final Optional<String> kmsId) {
    this.kmsKeyId = checkNotNull(kmsId)
        .map(String::trim)
        .filter(id -> !id.isEmpty())
        .orElse(null);
  }

  @VisibleForTesting
  String getKmsKeyId() {
    return kmsKeyId;
  }

  @Override
  public <T extends CreateMultipartUploadRequest> T addEncryption(final T request) {
    CreateMultipartUploadRequest.Builder builder = request.toBuilder()
        .serverSideEncryption(ServerSideEncryption.AWS_KMS);
    
    if (kmsKeyId != null) {
      builder.ssekmsKeyId(kmsKeyId);
    }
    
    return (T) builder.build();
  }

  @Override
  public <T extends PutObjectRequest> T addEncryption(final T request) {
    PutObjectRequest.Builder builder = request.toBuilder()
        .serverSideEncryption(ServerSideEncryption.AWS_KMS);
    
    if (kmsKeyId != null) {
      builder.ssekmsKeyId(kmsKeyId);
    }
    
    return (T) builder.build();
  }

  @Override
  public <T extends CopyObjectRequest> T addEncryption(final T request) {
    CopyObjectRequest.Builder builder = request.toBuilder()
        .serverSideEncryption(ServerSideEncryption.AWS_KMS);
    
    if (kmsKeyId != null) {
      builder.ssekmsKeyId(kmsKeyId);
    }
    
    return (T) builder.build();
  }
}