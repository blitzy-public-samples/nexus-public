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

import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

/**
 * A credentials provider chain for S3 that uses the default AWS SDK for Java 2.x credentials provider chain.
 * This class is located in the com.amazonaws.services.s3 package for backward compatibility
 * but uses AWS SDK for Java 2.x internally.
 *
 * @since 3.19
 */
public class S3CredentialsProviderChain
    implements AwsCredentialsProvider
{
  private final DefaultCredentialsProvider defaultCredentialsProvider;

  /**
   * Creates a new credentials provider chain that uses the default AWS SDK for Java 2.x credentials provider chain.
   */
  public S3CredentialsProviderChain() {
    this.defaultCredentialsProvider = DefaultCredentialsProvider.create();
  }

  @Override
  public AwsCredentials resolveCredentials() {
    return defaultCredentialsProvider.resolveCredentials();
  }
}