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
import software.amazon.awssdk.auth.credentials.ContainerCredentialsProvider;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.SystemPropertyCredentialsProvider;
import software.amazon.awssdk.auth.credentials.WebIdentityTokenFileCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;

import java.util.ArrayList;
import java.util.List;

/**
 * AWS credentials provider chain for S3 that looks for credentials in the following order:
 * <ol>
 *   <li>Environment Variables - AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY</li>
 *   <li>Java System Properties - aws.accessKeyId and aws.secretKey</li>
 *   <li>Web Identity Token credentials from the environment or container</li>
 *   <li>Credential profiles file at the default location (~/.aws/credentials) shared by all AWS SDKs</li>
 *   <li>Amazon ECS container credentials - loaded from the Amazon ECS if the environment variable AWS_CONTAINER_CREDENTIALS_RELATIVE_URI is set</li>
 *   <li>Instance profile credentials - used on EC2 instances, and delivered through the EC2 metadata service</li>
 * </ol>
 *
 * This class is designed to work with AWS SDK v2.x and supports Java 21 Virtual Threads.
 *
 * @since 3.19
 */
public class S3CredentialsProviderChain implements AwsCredentialsProvider {

  private final List<AwsCredentialsProvider> credentialsProviders;

  /**
   * Creates a new credentials provider chain with the default providers.
   */
  public S3CredentialsProviderChain() {
    this(new ArrayList<>());
    credentialsProviders.add(EnvironmentVariableCredentialsProvider.create());
    credentialsProviders.add(SystemPropertyCredentialsProvider.create());
    credentialsProviders.add(WebIdentityTokenFileCredentialsProvider.create());
    credentialsProviders.add(ProfileCredentialsProvider.create());
    credentialsProviders.add(ContainerCredentialsProvider.builder().build());
    credentialsProviders.add(InstanceProfileCredentialsProvider.create());
  }

  /**
   * Creates a new credentials provider chain with the specified list of providers.
   *
   * @param credentialsProviders The list of credentials providers to use
   */
  public S3CredentialsProviderChain(List<AwsCredentialsProvider> credentialsProviders) {
    this.credentialsProviders = credentialsProviders;
  }

  /**
   * Returns the first set of credentials that can be loaded from the available providers.
   *
   * @return The first available set of credentials
   * @throws SdkClientException If no credentials can be found
   */
  @Override
  public AwsCredentials resolveCredentials() {
    for (AwsCredentialsProvider provider : credentialsProviders) {
      try {
        return provider.resolveCredentials();
      } catch (Exception e) {
        // Ignore and try the next provider
      }
    }

    throw SdkClientException.create(
        "Unable to load AWS credentials from any provider in the chain");
  }

  /**
   * Adds a new credentials provider to the chain.
   *
   * @param provider The credentials provider to add
   */
  public void addCredentialsProvider(AwsCredentialsProvider provider) {
    credentialsProviders.add(provider);
  }
}