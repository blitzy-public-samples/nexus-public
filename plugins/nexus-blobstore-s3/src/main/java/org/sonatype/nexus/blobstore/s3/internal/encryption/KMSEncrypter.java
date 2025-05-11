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

import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import static java.util.Optional.empty;

/**
 * An {@link S3Encrypter} that adds KMS server-side encryption to requests.
 * Updated to work with AWS SDK for Java 2.x.
 *
 * @since 3.19
 */
public class KMSEncrypter
    implements S3Encrypter
{
  public static final String ID = "kms";

  private final Optional<String> key;

  /**
   * Creates a new KMSEncrypter with the specified key.
   *
   * @param key the KMS key to use for encryption
   */
  public KMSEncrypter(final Optional<String> key) {
    this.key = key;
  }

  @Override
  public void addEncryption(final PutObjectRequest.Builder request) {
    request.serverSideEncryption(ServerSideEncryption.AWS_KMS);
    key.ifPresent(request::ssekmsKeyId);
  }

  @Override
  public void addEncryption(final CopyObjectRequest.Builder request) {
    request.serverSideEncryption(ServerSideEncryption.AWS_KMS);
    key.ifPresent(request::ssekmsKeyId);
  }

  @Override
  public void addEncryption(final CreateMultipartUploadRequest.Builder request) {
    request.serverSideEncryption(ServerSideEncryption.AWS_KMS);
    key.ifPresent(request::ssekmsKeyId);
  }
}