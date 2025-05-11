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

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

/**
 * Abstract builder for AmazonS3 clients.
 * This class is designed to maintain compatibility with AWS SDK for Java 1.x
 * while using AWS SDK for Java 2.x internally.
 *
 * @param <T> the builder type
 * @param <U> the client type
 * @since 3.19
 */
public abstract class AmazonS3Builder<T extends AmazonS3Builder<T, U>, U>
{
  private AwsCredentialsProvider credentialsProvider;
  private S3ClientOptions clientOptions = new S3ClientOptions();

  /**
   * Sets the credentials provider to use for authentication with AWS.
   *
   * @param credentialsProvider the credentials provider
   * @return this builder for method chaining
   */
  @SuppressWarnings("unchecked")
  public T setCredentials(final AwsCredentialsProvider credentialsProvider) {
    this.credentialsProvider = credentialsProvider;
    return (T) this;
  }

  /**
   * Gets the credentials provider.
   *
   * @return the credentials provider
   */
  public AwsCredentialsProvider getCredentialsProvider() {
    return credentialsProvider;
  }

  /**
   * Gets the client options.
   *
   * @return the client options
   */
  public S3ClientOptions resolveS3ClientOptions() {
    return clientOptions;
  }

  /**
   * Builds an AmazonS3 client with the configured settings.
   *
   * @return a new AmazonS3 client
   */
  public U build() {
    return build(null);
  }

  /**
   * Builds an AmazonS3 client with the configured settings and client parameters.
   *
   * @param clientParams the client parameters
   * @return a new AmazonS3 client
   */
  protected abstract U build(AwsSyncClientParams clientParams);
}