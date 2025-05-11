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

import javax.annotation.Nullable;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;

/**
 * Encapsulates the objects used to specify the configuration for an s3 blob store.
 *
 * @since 3.20
 * @since Java 21 - Converted to record for improved immutability and conciseness
 */
@JsonInclude(NON_NULL)
public record S3BlobStoreApiBucketConfiguration(
    @Valid
    @NotNull
    @Schema(description = "Details of the S3 bucket such as name and region", required = true)
    @JsonProperty("bucket")
    S3BlobStoreApiBucket bucket,

    @Schema(description = "Security details for granting access the S3 API")
    @JsonProperty("security")
    S3BlobStoreApiBucketSecurity bucketSecurity,

    @Schema(description = "The type of encryption to use if any")
    @JsonProperty("encryption")
    S3BlobStoreApiEncryption encryption,

    @Schema(description = "A custom endpoint URL, signer type and whether path style access is enabled")
    @JsonProperty("advancedConnection")
    S3BlobStoreApiAdvancedBucketConnection advancedBucketConnection,

    @Valid
    @Nullable
    @Schema(description = "A list of secondary buckets which have bidirectional replication enabled and should be used when Nexus is running in the region", accessMode = Schema.AccessMode.READ_WRITE)
    @JsonProperty(FAILOVER_BUCKETS)
    List<S3BlobStoreApiFailoverBucket> failoverBuckets,

    @Nullable
    @Schema(description = "The active region based on bucket configuration, failover buckets, and EC2 region Nexus is running.", accessMode = Schema.AccessMode.READ_ONLY)
    @JsonProperty(ACTIVE_REGION)
    String activeRegion
) {
  public static final String FAILOVER_BUCKETS = "failoverBuckets";
  public static final String ACTIVE_REGION = "activeRegion";

  /**
   * Constructor with explicit property names for Jackson deserialization.
   * 
   * @param bucket The S3 bucket details
   * @param bucketSecurity Security details for S3 API access
   * @param encryption Encryption configuration
   * @param advancedBucketConnection Advanced connection settings
   * @param failoverBuckets List of failover buckets
   * @param activeRegion The active region
   */
  @JsonCreator
  public S3BlobStoreApiBucketConfiguration {
    // Record compact constructor - validation could be added here if needed
  }
}
