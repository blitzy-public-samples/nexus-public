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
package org.sonatype.nexus.blobstore.s3.rest.internal.model;

import java.util.List;

import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModelProperty;
import io.swagger.annotations.ApiModelProperty.AccessMode;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;

/**
 * Encapsulates the objects used to specify the configuration for an s3 blob store.
 *
 * @since 3.20
 * @since 3.40 Updated for Java 21 compatibility with Jakarta EE validation
 */
@JsonInclude(NON_NULL)
public class S3BlobStoreApiBucketConfiguration
{
  public static final String FAILOVER_BUCKETS = "failoverBuckets";

  public static final String ACTIVE_REGION = "activeRegion";

  @Valid
  @NotNull
  @ApiModelProperty(value = "Details of the S3 bucket such as name and region", required = true)
  private final S3BlobStoreApiBucket bucket;

  @ApiModelProperty("Security details for granting access the S3 API")
  private final S3BlobStoreApiBucketSecurity bucketSecurity;

  @ApiModelProperty("The type of encryption to use if any")
  private final S3BlobStoreApiEncryption encryption;

  @ApiModelProperty("A custom endpoint URL, signer type and whether path style access is enabled")
  private final S3BlobStoreApiAdvancedBucketConnection advancedBucketConnection;

  @Valid
  @Nullable
  @ApiModelProperty(value = "A list of secondary buckets which have bidirectional replication enabled and should be used when Nexus is running in the region", accessMode = AccessMode.READ_WRITE)
  private final List<S3BlobStoreApiFailoverBucket> failoverBuckets;

  @Nullable
  @ApiModelProperty(value = "The active region based on bucket configuration, failover buckets, and EC2 region Nexus is running.", accessMode = AccessMode.READ_ONLY)
  private final String activeRegion;

  /**
   * Constructor for S3BlobStoreApiBucketConfiguration.
   * 
   * @param bucket The S3 bucket details
   * @param bucketSecurity Security details for S3 API access
   * @param encryption Encryption settings
   * @param advancedBucketConnection Advanced connection settings
   * @param failoverBuckets List of failover buckets
   * @param activeRegion The active region
   */
  public S3BlobStoreApiBucketConfiguration(
      @JsonProperty("bucket") final S3BlobStoreApiBucket bucket,
      @JsonProperty("security") final S3BlobStoreApiBucketSecurity bucketSecurity,
      @JsonProperty("encryption") final S3BlobStoreApiEncryption encryption,
      @JsonProperty("advancedConnection") final S3BlobStoreApiAdvancedBucketConnection advancedBucketConnection,
      @Nullable @JsonProperty(FAILOVER_BUCKETS) final List<S3BlobStoreApiFailoverBucket> failoverBuckets,
      @Nullable @JsonProperty(ACTIVE_REGION) final String activeRegion)
  {
    this.bucket = bucket;
    this.bucketSecurity = bucketSecurity;
    this.encryption = encryption;
    this.advancedBucketConnection = advancedBucketConnection;
    this.failoverBuckets = failoverBuckets;
    this.activeRegion = activeRegion;
  }

  /**
   * @return the S3 bucket details
   */
  public S3BlobStoreApiBucket getBucket() {
    return bucket;
  }

  /**
   * @return security details for S3 API access
   */
  public S3BlobStoreApiBucketSecurity getBucketSecurity() {
    return bucketSecurity;
  }

  /**
   * @return encryption settings
   */
  public S3BlobStoreApiEncryption getEncryption() {
    return encryption;
  }

  /**
   * @return advanced connection settings
   */
  public S3BlobStoreApiAdvancedBucketConnection getAdvancedBucketConnection() {
    return advancedBucketConnection;
  }

  /**
   * @return list of failover buckets
   */
  @Nullable
  public List<S3BlobStoreApiFailoverBucket> getFailoverBuckets() {
    return failoverBuckets;
  }

  /**
   * @return the active region
   */
  @Nullable
  public String getActiveRegion() {
    return activeRegion;
  }
}