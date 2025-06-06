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
package org.sonatype.nexus.blobstore.s3.internal;

import java.util.Optional;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.goodies.common.Time;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.s3.S3BlobStoreConfigurationHelper;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.common.text.Strings2;
import org.sonatype.nexus.crypto.secrets.SecretsFactory;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static org.sonatype.nexus.blobstore.s3.S3BlobStoreConfigurationHelper.CONFIG_KEY;
import static org.sonatype.nexus.blobstore.s3.internal.S3BlobStore.*;

/**
 * Creates configured AmazonS3 clients.
 *
 * @since 3.6.1
 * @note Compatible with Java 21 and AWS SDK for Java 2.x
 */
@Named
public class AmazonS3Factory extends ComponentSupport
{
  public static final String DEFAULT = "DEFAULT";

  private final int defaultConnectionPoolSize;

  private final boolean cloudWatchMetricsEnabled;

  private final String cloudWatchMetricsNamespace;

  private final Time connectionTtl;

  private final SecretsFactory secretsFactory;

  @Inject
  public AmazonS3Factory(
          @Named("${nexus.s3.connection.pool:--1}") final int connectionPoolSize,
          @Nullable @Named("${nexus.s3.connection.ttl:-null}") final Time connectionTtl,
          @Named("${nexus.s3.cloudwatchmetrics.enabled:-false}") final boolean cloudWatchMetricsEnabled,
          @Named("${nexus.s3.cloudwatchmetrics.namespace:-nexus-blobstore-s3}") final String cloudWatchMetricsNamespace,
          final SecretsFactory secretsFactory)
  {
    this.defaultConnectionPoolSize = connectionPoolSize;
    this.cloudWatchMetricsEnabled = cloudWatchMetricsEnabled;
    this.cloudWatchMetricsNamespace = cloudWatchMetricsNamespace;
    this.connectionTtl = connectionTtl;
    this.secretsFactory = checkNotNull(secretsFactory);
  }

  /**
   * Creates an Amazon S3 client configured with the provided blob store configuration.
   *
   * @param blobStoreConfiguration the blob store configuration
   * @return a configured AmazonS3 client
   */
  public S3Client create(final BlobStoreConfiguration blobStoreConfiguration) {
    NestedAttributesMap s3Config = blobStoreConfiguration.attributes(CONFIG_KEY);

    String accessKeyId = s3Config.get(ACCESS_KEY_ID_KEY, String.class);
    String secretAccessKey = s3Config.get(SECRET_ACCESS_KEY_KEY, String.class);
    String sessionToken = getSessionToken(s3Config);
    String regionStr = S3BlobStoreConfigurationHelper.getConfiguredRegion(blobStoreConfiguration);
    String assumeRole = s3Config.get(ASSUME_ROLE_KEY, String.class);
    String endpoint = s3Config.get(ENDPOINT_KEY, String.class);
    String forcePathStyle = s3Config.get(FORCE_PATH_STYLE_KEY, String.class);

    Region region = Region.of(!isNullOrEmpty(regionStr) ? regionStr : defaultRegion());

    AwsCredentialsProvider credentialsProvider = null;
    if (!isNullOrEmpty(accessKeyId) && !isNullOrEmpty(secretAccessKey)) {
      AwsCredentials baseCredentials = buildCredentials(accessKeyId, secretAccessKey, sessionToken);
      credentialsProvider = StaticCredentialsProvider.create(baseCredentials);

      if (!isNullOrEmpty(assumeRole)) {
        StsClient stsClient = StsClient.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .build();

        credentialsProvider = StsAssumeRoleCredentialsProvider.builder()
                .refreshRequest(r -> r.roleArn(assumeRole).roleSessionName("nexus-s3-session"))
                .stsClient(stsClient)
                .build();
      }
    }

    S3ClientBuilder builder = S3Client.builder()
            .region(region)
            .credentialsProvider(credentialsProvider)
            .serviceConfiguration(S3Configuration.builder()
                    .pathStyleAccessEnabled(Boolean.parseBoolean(forcePathStyle))
                    .build());

    if (!isNullOrEmpty(endpoint)) {
      builder.endpointOverride(java.net.URI.create(endpoint));
    }

    return builder.build();
  }

  /**
   * Builds AWS credentials based on the provided parameters.
   */
  private AwsCredentials buildCredentials(String accessKeyId, String secretAccessKeyEncrypted, String sessionToken) {
    String secretAccessKey = new String(secretsFactory.from(secretAccessKeyEncrypted).decrypt());
    if (isNullOrEmpty(sessionToken)) {
      return AwsBasicCredentials.create(accessKeyId, secretAccessKey);
    } else {
      return AwsSessionCredentials.create(accessKeyId, secretAccessKey, sessionToken);
    }
  }

  /**
   * Gets the session token from the S3 configuration.
   */
  private String getSessionToken(final NestedAttributesMap s3Configuration) {
    if (s3Configuration.contains(SESSION_TOKEN_KEY)) {
      return new String(secretsFactory.from(s3Configuration.get(SESSION_TOKEN_KEY, String.class)).decrypt());
    }
    return null;
  }

  /**
   * Gets the default AWS region.
   */
  private String defaultRegion() {
    return "us-east-1"; // Fallback region
  }
}
