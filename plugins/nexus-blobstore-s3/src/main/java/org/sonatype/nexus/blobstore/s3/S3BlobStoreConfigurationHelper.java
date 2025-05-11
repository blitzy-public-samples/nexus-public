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
package org.sonatype.nexus.blobstore.s3;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Nullable;

import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.common.collect.NestedAttributesMap;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.StringTemplate.STR;
import static org.sonatype.nexus.blobstore.s3.internal.S3BlobStore.DEFAULT_EXPIRATION_IN_DAYS;
import static org.sonatype.nexus.blobstore.s3.internal.S3BlobStore.EXPIRATION_KEY;
import static org.sonatype.nexus.blobstore.s3.internal.S3BlobStore.REGION_KEY;

/**
 * Helper for retrieving S3 specific settings from a {@link BlobStoreConfiguration}.
 * 
 * This class has been updated for Java 21 compatibility with the following enhancements:
 * <ul>
 *   <li>Uses Java 21 String Templates for improved logging</li>
 *   <li>Uses Java 21 Pattern Matching for switch expressions</li>
 *   <li>Uses Java 21 Map.of() for immutable map creation</li>
 * </ul>
 *
 * @since 3.16
 */
public class S3BlobStoreConfigurationHelper
{
  private static final Logger log = LoggerFactory.getLogger(S3BlobStoreConfigurationHelper.class);

  public static final String BUCKET_PREFIX_KEY = "prefix";

  public static final String BUCKET_KEY = "bucket";

  /**
   * A map of buckets to use in other regions
   */
  public static final String FAILOVER_BUCKETS_KEY = "failover-buckets";

  public static final String CONFIG_KEY = "s3";

  private S3BlobStoreConfigurationHelper() {
    // empty
  }

  /**
   * Sets the configured bucket in the blob store configuration.
   *
   * @param blobStoreConfiguration The blob store configuration to update
   * @param bucket The bucket name to set
   */
  public static void setConfiguredBucket(final BlobStoreConfiguration blobStoreConfiguration, final String bucket) {
    blobStoreConfiguration.attributes(CONFIG_KEY).set(BUCKET_KEY, bucket);
  }

  /**
   * Returns the configured bucket, if failover buckets are configured then the choice will depend on the EC2 region.
   * 
   * @param blobStoreConfiguration The blob store configuration to retrieve the bucket from
   * @return The configured bucket name
   */
  public static String getConfiguredBucket(final BlobStoreConfiguration blobStoreConfiguration) {
    return getBucketConfiguration(blobStoreConfiguration).values().iterator().next();
  }

  /**
   * Returns the configured region for the bucket, if failover buckets are configured then the choice will depend on the EC2 region.
   * 
   * @param blobStoreConfiguration The blob store configuration to retrieve the region from
   * @return The configured region name
   */
  public static String getConfiguredRegion(final BlobStoreConfiguration blobStoreConfiguration) {
    return getBucketConfiguration(blobStoreConfiguration).keySet().iterator().next();
  }

  /**
   * Determines the appropriate bucket configuration based on the current region.
   * Uses failover buckets if configured and the current region is available.
   *
   * @param blobStoreConfiguration The blob store configuration to retrieve bucket information from
   * @return A map containing a single entry with region as key and bucket name as value
   */
  private static Map<String, String> getBucketConfiguration(final BlobStoreConfiguration blobStoreConfiguration) {
    NestedAttributesMap config = blobStoreConfiguration.attributes(CONFIG_KEY);
    String primaryBucket = config.get(BUCKET_KEY, String.class);
    String primaryRegion = config.get(REGION_KEY, String.class);
    String currentRegion = getCurrentRegion();

    // Use pattern matching to check for failover configuration availability
    if (switch(config) {
          case var c when !c.contains(FAILOVER_BUCKETS_KEY) -> true;
          case var _ when currentRegion == null -> true;
          default -> false;
        }) {
      log.trace(STR."No failover configuration possible");
      return Map.of(primaryRegion, primaryBucket);
    }

    Map<String, Object> regionMapping = new LinkedHashMap<>(config.get(FAILOVER_BUCKETS_KEY, Map.class));

    // Add the primary last so we always prefer it
    regionMapping.put(primaryRegion, primaryBucket);

    log.debug(STR."Detected region \{currentRegion} choosing from \{regionMapping}");

    return Optional.ofNullable(regionMapping.get(currentRegion))
      .map(Object::toString)
      .map(bucketName -> Map.of(currentRegion, bucketName))
      .orElse(Map.of(primaryRegion, primaryBucket));
  }

  /**
   * Gets the configured expiration in days from the blob store configuration.
   *
   * @param blobStoreConfiguration The blob store configuration to retrieve the expiration from
   * @return The configured expiration in days
   */
  public static int getConfiguredExpirationInDays(final BlobStoreConfiguration blobStoreConfiguration) {
    Object expirationValue = blobStoreConfiguration.attributes(CONFIG_KEY).get(EXPIRATION_KEY, DEFAULT_EXPIRATION_IN_DAYS);
    return switch(expirationValue) {
      case Integer i -> i;
      case Number n -> n.intValue();
      case String s -> Integer.parseInt(s);
      default -> Integer.parseInt(expirationValue.toString());
    };
  }

  /**
   * Gets the bucket prefix from the configuration, ensuring it ends with a trailing slash if not empty.
   * 
   * @param blobStoreConfiguration The blob store configuration to retrieve the prefix from
   * @return The bucket prefix with a trailing slash, or an empty string if no prefix is configured
   */
  public static String getBucketPrefix(final BlobStoreConfiguration blobStoreConfiguration) {
    return Optional.ofNullable(blobStoreConfiguration.attributes(CONFIG_KEY).get(BUCKET_PREFIX_KEY, String.class))
        .filter(s -> !s.isBlank())
        .map(s -> s.replaceFirst("/$", "") + "/")
        .orElse("");
  }

  @VisibleForTesting
  static boolean regionLoaded;

  @VisibleForTesting
  static String region;

  /**
   * Retrieves the current AWS region from the environment.
   * The result is cached after the first call for performance.
   *
   * @return The current region name or null if it cannot be determined
   */
  @Nullable
  private static String getCurrentRegion() {
    if (!regionLoaded) {
      regionLoaded = true;
      try {
        // Using pattern matching with Optional to improve readability
        region = switch(Optional.ofNullable(Regions.getCurrentRegion())) {
          case Optional<Region> opt when opt.isPresent() -> opt.get().getName();
          default -> null;
        };
      }
      catch (Exception e) {
        log.debug(STR."Failed to retrieve region", e);
      }
    }
    log.trace(STR."Current region \{region}");
    return region;
  }
}