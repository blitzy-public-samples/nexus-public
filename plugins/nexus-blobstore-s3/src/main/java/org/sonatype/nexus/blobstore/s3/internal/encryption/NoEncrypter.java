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
 * An {@link S3Encrypter} that does not add any encryption to requests.
 * Updated to work with AWS SDK for Java 2.x.
 *
 * @since 3.19
 */
public class NoEncrypter
    implements S3Encrypter
{
  public static final String ID = "none";

  public static final NoEncrypter INSTANCE = new NoEncrypter();

  @Override
  public void addEncryption(final PutObjectRequest.Builder request) {
    // No encryption to add
  }

  @Override
  public void addEncryption(final CopyObjectRequest.Builder request) {
    // No encryption to add
  }

  @Override
  public void addEncryption(final CreateMultipartUploadRequest.Builder request) {
    // No encryption to add
  }
}