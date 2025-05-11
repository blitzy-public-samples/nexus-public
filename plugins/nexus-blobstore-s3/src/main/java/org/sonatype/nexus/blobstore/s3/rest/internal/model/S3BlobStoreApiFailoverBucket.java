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

import jakarta.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Represents a failover bucket configuration for S3 blob stores.
 * 
 * This class is implemented as a Java Record for immutability and concise data handling.
 * Compatible with Java 21 record patterns for efficient destructuring.
 * 
 * Example usage with Java 21 record patterns:
 * <pre>
 * {@code
 * // Destructuring with record pattern in instanceof
 * if (bucket instanceof S3BlobStoreApiFailoverBucket(var region, var bucketName)) {
 *     // Direct access to components without getter calls
 *     processRegion(region);
 *     processBucketName(bucketName);
 * }
 * 
 * // Destructuring with record pattern in switch
 * String result = switch(bucket) {
 *     case S3BlobStoreApiFailoverBucket(String region, String name) ->
 *         STR."Failover bucket: \{name} in region \{region}";
 *     default -> "Unknown bucket";
 * };
 * }
 * </pre>
 *
 * @since 3.20
 */
public record S3BlobStoreApiFailoverBucket(
    @NotNull
    @Schema(description = "The region containing the bucket", requiredMode = Schema.RequiredMode.REQUIRED)
    String region,

    @NotNull
    @Schema(description = "The name of the bucket in the region", requiredMode = Schema.RequiredMode.REQUIRED)
    String bucketName
) {
  /**
   * Constructor with JsonCreator annotation for Jackson deserialization.
   * 
   * @param region The region containing the bucket
   * @param bucketName The name of the bucket in the region
   */
  @JsonCreator
  public S3BlobStoreApiFailoverBucket(
      @JsonProperty("region") final String region,
      @JsonProperty("bucketName") final String bucketName) {
    this(region, bucketName);
  }
}
