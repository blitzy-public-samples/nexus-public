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
package org.sonatype.nexus.blobstore.s3.rest.internal;

import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.rest.BlobStoreApiSoftQuota;
import org.sonatype.nexus.blobstore.s3.S3BlobStoreConfigurationHelper;
import org.sonatype.nexus.blobstore.s3.rest.internal.model.S3BlobStoreApiAdvancedBucketConnection;
import org.sonatype.nexus.blobstore.s3.rest.internal.model.S3BlobStoreApiBucket;
import org.sonatype.nexus.blobstore.s3.rest.internal.model.S3BlobStoreApiBucketConfiguration;
import org.sonatype.nexus.blobstore.s3.rest.internal.model.S3BlobStoreApiBucketSecurity;
import org.sonatype.nexus.blobstore.s3.rest.internal.model.S3BlobStoreApiEncryption;
import org.sonatype.nexus.blobstore.s3.rest.internal.model.S3BlobStoreApiFailoverBucket;
import org.sonatype.nexus.blobstore.s3.rest.internal.model.S3BlobStoreApiModel;
import org.sonatype.nexus.common.collect.NestedAttributesMap;

import static java.lang.Long.parseLong;
import static java.util.Objects.nonNull;
import static org.sonatype.nexus.blobstore.quota.BlobStoreQuotaSupport.LIMIT_KEY;
import static org.sonatype.nexus.blobstore.quota.BlobStoreQuotaSupport.ROOT_KEY;
import static org.sonatype.nexus.blobstore.quota.BlobStoreQuotaSupport.TYPE_KEY;
import static org.sonatype.nexus.blobstore.s3.S3BlobStoreConfigurationHelper.BUCKET_KEY;
import static org.sonatype.nexus.blobstore.s3.S3BlobStoreConfigurationHelper.BUCKET_PREFIX_KEY;
import static org.sonatype.nexus.blobstore.s3.S3BlobStoreConfigurationHelper.CONFIG_KEY;
import static org.sonatype.nexus.blobstore.s3.S3BlobStoreConfigurationHelper.FAILOVER_BUCKETS_KEY;
import static org.sonatype.nexus.blobstore.s3.internal.S3BlobStore.*;

/**
 * Transforms a {@link BlobStoreConfiguration} to an {@link S3BlobStoreApiModel}.
 *
 * @since 3.20
 * @see <a href="https://openjdk.org/jeps/440">JEP 440: Record Patterns</a>
 * @see <a href="https://openjdk.org/jeps/441">JEP 441: Pattern Matching for switch</a>
 * @see <a href="https://openjdk.org/jeps/430">JEP 430: String Templates</a>
 */
public final class S3BlobStoreApiConfigurationMapper
{
  /**
   * Maps a {@link BlobStoreConfiguration} to an {@link S3BlobStoreApiModel}.
   *
   * @param configuration the blob store configuration to map
   * @return the mapped S3 blob store API model
   */
  public static S3BlobStoreApiModel map(final BlobStoreConfiguration configuration) {
    return new S3BlobStoreApiModel(
        configuration.getName(),
        createSoftQuota(configuration),
        createS3BlobStoreBucketConfiguration(configuration)
    );
  }

  /**
   * Creates a {@link BlobStoreApiSoftQuota} from the given configuration.
   *
   * @param configuration the blob store configuration
   * @return the soft quota configuration or null if not configured
   */
  private static BlobStoreApiSoftQuota createSoftQuota(final BlobStoreConfiguration configuration) {
    final NestedAttributesMap softQuotaAttributes = configuration.attributes(ROOT_KEY);
    if (!softQuotaAttributes.isEmpty()) {
      final String quotaType = getValue(softQuotaAttributes, TYPE_KEY);
      final String quotaLimit = getValue(softQuotaAttributes, LIMIT_KEY);
      
      // Using pattern matching to simplify null checks
      if (quotaType != null && quotaLimit != null) {
        var blobStoreApiSoftQuota = new BlobStoreApiSoftQuota();
        blobStoreApiSoftQuota.setType(quotaType);
        blobStoreApiSoftQuota.setLimit(parseLong(quotaLimit));
        return blobStoreApiSoftQuota;
      }
    }
    return null;
  }

  /**
   * Gets a value from the attributes map, returning null if the value is not present.
   *
   * @param attributes the attributes map
   * @param key the key to look up
   * @return the value as a string, or null if not present
   */
  private static String getValue(final NestedAttributesMap attributes, final String key) {
    return Objects.toString(attributes.get(key), null);
  }

  /**
   * Creates an {@link S3BlobStoreApiBucketConfiguration} from the given configuration.
   *
   * @param configuration the blob store configuration
   * @return the S3 bucket configuration
   */
  private static S3BlobStoreApiBucketConfiguration createS3BlobStoreBucketConfiguration(
      final BlobStoreConfiguration configuration) 
  {
    final NestedAttributesMap s3BucketAttributes = configuration.attributes(CONFIG_KEY);
    return new S3BlobStoreApiBucketConfiguration(
        buildS3BlobStoreBucket(s3BucketAttributes),
        buildS3BlobStoreBucketSecurity(s3BucketAttributes),
        buildS3BlobStoreEncryption(s3BucketAttributes),
        buildS3BlobStoreAdvancedBucketConnection(s3BucketAttributes),
        buildS3BlobStoreFailoverBuckets(s3BucketAttributes),
        buildS3BlobStoreActiveRegion(configuration));
  }

  /**
   * Builds an {@link S3BlobStoreApiBucket} from the given attributes.
   *
   * @param attributes the attributes map
   * @return the S3 bucket configuration
   */
  private static S3BlobStoreApiBucket buildS3BlobStoreBucket(final NestedAttributesMap attributes) {
    final String expiration = getValue(attributes, EXPIRATION_KEY);
    return new S3BlobStoreApiBucket(
        getValue(attributes, REGION_KEY),
        getValue(attributes, BUCKET_KEY),
        getValue(attributes, BUCKET_PREFIX_KEY),
        // Using pattern matching for switch to handle expiration value
        switch (expiration) {
          case null -> 0;
          case String s -> Integer.parseInt(s);
        }
    );
  }

  /**
   * Builds an {@link S3BlobStoreApiBucketSecurity} from the given attributes.
   *
   * @param attributes the attributes map
   * @return the S3 bucket security configuration or null if not configured
   */
  private static S3BlobStoreApiBucketSecurity buildS3BlobStoreBucketSecurity(final NestedAttributesMap attributes) {
    final String accessKeyId = getValue(attributes, ACCESS_KEY_ID_KEY);
    final String roleToAssume = getValue(attributes, ASSUME_ROLE_KEY);
    final String sessionToken = getValue(attributes, SESSION_TOKEN_KEY);
    
    // Using pattern matching to check if any credentials are provided
    return switch (accessKeyId, roleToAssume, sessionToken) {
      case (String a, _, _) when a != null -> 
          new S3BlobStoreApiBucketSecurity(accessKeyId, null, roleToAssume, sessionToken);
      case (_, String r, _) when r != null -> 
          new S3BlobStoreApiBucketSecurity(accessKeyId, null, roleToAssume, sessionToken);
      case (_, _, String s) when s != null -> 
          new S3BlobStoreApiBucketSecurity(accessKeyId, null, roleToAssume, sessionToken);
      default -> null;
    };
  }

  /**
   * Builds an {@link S3BlobStoreApiEncryption} from the given attributes.
   *
   * @param s3BucketAttributes the attributes map
   * @return the S3 encryption configuration or null if not configured
   */
  private static S3BlobStoreApiEncryption buildS3BlobStoreEncryption(final NestedAttributesMap s3BucketAttributes) {
    final String encryptionType = getValue(s3BucketAttributes, ENCRYPTION_TYPE);
    final String encryptionKey = getValue(s3BucketAttributes, ENCRYPTION_KEY);

    // Using pattern matching to check if encryption is configured
    return switch (encryptionType, encryptionKey) {
      case (String t, _) when t != null -> new S3BlobStoreApiEncryption(encryptionType, encryptionKey);
      case (_, String k) when k != null -> new S3BlobStoreApiEncryption(encryptionType, encryptionKey);
      default -> null;
    };
  }

  /**
   * Builds an {@link S3BlobStoreApiAdvancedBucketConnection} from the given attributes.
   *
   * @param attributes the attributes map
   * @return the S3 advanced bucket connection configuration or null if not configured
   */
  private static S3BlobStoreApiAdvancedBucketConnection buildS3BlobStoreAdvancedBucketConnection(
      final NestedAttributesMap attributes) 
  {
    final String endpoint = getValue(attributes, ENDPOINT_KEY);
    final String signerType = getValue(attributes, SIGNERTYPE_KEY);
    final String forcePathStyle = getValue(attributes, FORCE_PATH_STYLE_KEY);
    
    // Using Optional with pattern matching for cleaner null handling
    Integer maxConnectionPoolSize = Optional.ofNullable(getValue(attributes, MAX_CONNECTION_POOL_KEY))
        .filter(val -> !val.isEmpty())
        .map(Integer::valueOf)
        .orElse(null);

    // Using pattern matching to check if any advanced connection settings are configured
    if (endpoint != null || signerType != null || forcePathStyle != null || maxConnectionPoolSize != null) {
      return new S3BlobStoreApiAdvancedBucketConnection(
          endpoint, 
          signerType, 
          Boolean.valueOf(forcePathStyle),
          maxConnectionPoolSize);
    }
    return null;
  }

  /**
   * Builds a list of {@link S3BlobStoreApiFailoverBucket} from the given attributes.
   *
   * @param attributes the attributes map
   * @return the list of S3 failover buckets or an empty list if none are configured
   */
  private static List<S3BlobStoreApiFailoverBucket> buildS3BlobStoreFailoverBuckets(
      final NestedAttributesMap attributes)
  {
    if (attributes.contains(FAILOVER_BUCKETS_KEY)) {
      return attributes.child(FAILOVER_BUCKETS_KEY).entries().stream()
          .map(entry -> new S3BlobStoreApiFailoverBucket(entry.getKey(), entry.getValue().toString()))
          .collect(Collectors.toList());
    }
    return Collections.emptyList();
  }

  /**
   * Builds the active region for the S3 blob store.
   *
   * @param configuration the blob store configuration
   * @return the active region
   */
  private static String buildS3BlobStoreActiveRegion(final BlobStoreConfiguration configuration) {
    return S3BlobStoreConfigurationHelper.getConfiguredRegion(configuration);
  }
}
