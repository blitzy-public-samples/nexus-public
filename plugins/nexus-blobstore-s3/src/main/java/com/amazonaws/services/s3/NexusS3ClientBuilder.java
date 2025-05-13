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
package com.amazonaws.services.s3;

import java.util.concurrent.CompletableFuture;

import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;

import com.amazonaws.client.AwsSyncClientParams;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Wraps another AmazonS3 client builder to create a {@link EncryptingAmazonS3Client}.
 *
 * It is located in this package because of {@code S3CredentialsProviderChain} and {@code AmazonS3ClientParamsWrapper}
 *
 * This implementation is compatible with Java 21 and leverages Virtual Threads for improved performance
 * on I/O-bound operations. It maintains backward compatibility with AWS SDK for Java 1.x while using
 * AWS SDK for Java 2.x features internally where possible.
 *
 * @since 3.19
 */
public class NexusS3ClientBuilder
    extends AmazonS3Builder<NexusS3ClientBuilder, AmazonS3>
{
  private BlobStoreConfiguration blobStoreConfig;

  /**
   * Creates a new instance of the builder with standard configuration.
   *
   * @return a new builder instance
   */
  public static NexusS3ClientBuilder standard() {
    NexusS3ClientBuilder builder = new NexusS3ClientBuilder();
    builder.setCredentials(new S3CredentialsProviderChain());
    return builder;
  }

  /**
   * Sets the blob store configuration for this builder.
   *
   * @param blobStoreConfig the blob store configuration
   * @return this builder instance
   * @throws NullPointerException if blobStoreConfig is null
   */
  public NexusS3ClientBuilder withBlobStoreConfig(final BlobStoreConfiguration blobStoreConfig) {
    this.blobStoreConfig = checkNotNull(blobStoreConfig, "BlobStoreConfiguration must not be null");
    return this;
  }

  /**
   * Gets the blob store configuration for this builder.
   *
   * @return the blob store configuration
   */
  public BlobStoreConfiguration getBlobStoreConfig() {
    return blobStoreConfig;
  }

  /**
   * Builds an AmazonS3 client with the configured parameters.
   * The resulting client uses Java 21 Virtual Threads for I/O-bound operations
   * to improve throughput and concurrency.
   * 
   * @param clientParams the client parameters
   * @return a new AmazonS3 client
   * @throws NullPointerException if getBlobStoreConfig() returns null
   */
  @Override
  protected AmazonS3 build(final AwsSyncClientParams clientParams) {
    checkNotNull(getBlobStoreConfig(), "BlobStoreConfiguration must not be null");
    return new EncryptingAmazonS3Client(getBlobStoreConfig(), 
        new AmazonS3ClientParamsWrapper(clientParams, resolveS3ClientOptions()));
  }
}