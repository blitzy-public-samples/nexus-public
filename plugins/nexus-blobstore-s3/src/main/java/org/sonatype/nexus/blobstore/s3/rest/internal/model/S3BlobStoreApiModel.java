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

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.sonatype.nexus.blobstore.rest.BlobStoreApiSoftQuota;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import static org.sonatype.nexus.blobstore.s3.internal.S3BlobStore.TYPE;

/**
 * Encapsulates the API payload for creating, reading and updating an S3 blob store.
 * <p>
 * This class has been updated for Java 21 compatibility with the following changes:
 * <ul>
 *   <li>Migrated from javax.validation to jakarta.validation</li>
 *   <li>Updated from Swagger annotations to OpenAPI annotations</li>
 *   <li>Added internal record pattern support for Java 21 pattern matching</li>
 *   <li>Added utility methods demonstrating Java 21 record patterns and switch expressions</li>
 * </ul>
 * <p>
 * The class maintains backward compatibility with existing serialization while
 * providing modern Java 21 features for internal processing.
 *
 * @since 3.20
 * @since 3.60 Updated for Java 21 compatibility with jakarta.validation and OpenAPI annotations
 */
public class S3BlobStoreApiModel
{
  /**
   * Immutable data record for S3BlobStoreApiModel.
   * Used internally for pattern matching and data processing in Java 21.
   */
  private record S3BlobStoreData(
      String name,
      BlobStoreApiSoftQuota softQuota,
      S3BlobStoreApiBucketConfiguration bucketConfiguration) {}

  @NotNull
  @Schema(description = "The name of the S3 blob store.", example = "s3", requiredMode = Schema.RequiredMode.REQUIRED)
  private String name;

  @Schema(description = "Settings to control the soft quota.")
  private final BlobStoreApiSoftQuota softQuota;

  @Valid
  @NotNull
  @Schema(description = "The S3 specific configuration details for the S3 object that'll contain the blob store.", 
         requiredMode = Schema.RequiredMode.REQUIRED)
  private final S3BlobStoreApiBucketConfiguration bucketConfiguration;

  @JsonCreator
  public S3BlobStoreApiModel(
      @JsonProperty("name") final String name,
      @JsonProperty("softQuota") final BlobStoreApiSoftQuota softQuota,
      @JsonProperty("bucketConfiguration")
      final S3BlobStoreApiBucketConfiguration bucketConfiguration)
  {
    this.name = name;
    this.softQuota = softQuota;
    this.bucketConfiguration = bucketConfiguration;
  }

  /**
   * Creates an immutable data record from this model.
   * This facilitates pattern matching with Java 21 record patterns.
   *
   * @return An immutable record containing this model's data
   */
  public S3BlobStoreData toData() {
    return new S3BlobStoreData(name, softQuota, bucketConfiguration);
  }

  /**
   * Pattern matching helper method that checks if this model matches the given criteria.
   * Leverages Java 21 record patterns for concise data extraction.
   *
   * @param namePattern Optional name pattern to match against
   * @return true if the model matches the criteria
   */
  public boolean matches(String namePattern) {
    var data = toData();
    return data instanceof S3BlobStoreData(var n, var sq, var bc) && 
           (namePattern == null || namePattern.equals(n));
  }
  
  /**
   * Utility method to extract bucket name using Java 21 record patterns.
   * Demonstrates nested record pattern matching capabilities.
   *
   * @return The bucket name or null if not available
   */
  public String extractBucketName() {
    var data = toData();
    if (data instanceof S3BlobStoreData(var n, var sq, var bucketConfig) && 
        bucketConfig != null && 
        bucketConfig.getBucket() != null) {
      return bucketConfig.getBucket().getName();
    }
    return null;
  }
  
  /**
   * Utility method to check if this model represents an S3 bucket in a specific region.
   * Demonstrates the power of Java 21 pattern matching for complex condition checking.
   *
   * @param regionName The region name to check
   * @return true if this model represents a bucket in the specified region
   */
  public boolean isInRegion(String regionName) {
    if (regionName == null) {
      return false;
    }
    
    var data = toData();
    return data instanceof S3BlobStoreData(var n, var sq, var bucketConfig) && 
           bucketConfig != null && 
           bucketConfig.getBucket() != null &&
           regionName.equals(bucketConfig.getBucket().getRegion());
  }
  
  /**
   * Factory method that creates a model from a data record.
   * Useful when processing data with pattern matching.
   *
   * @param data The data record
   * @return A new model instance
   */
  public static S3BlobStoreApiModel fromData(S3BlobStoreData data) {
    if (data instanceof S3BlobStoreData(var name, var softQuota, var bucketConfig)) {
      return new S3BlobStoreApiModel(name, softQuota, bucketConfig);
    }
    throw new IllegalArgumentException("Invalid data record");
  }
  
  /**
   * Utility method that demonstrates Java 21 pattern matching in switch expressions.
   * This method categorizes S3 blob stores based on their configuration.
   *
   * @param model The model to categorize
   * @return A category string
   */
  public static String categorize(S3BlobStoreApiModel model) {
    if (model == null) {
      return "UNKNOWN";
    }
    
    var data = model.toData();
    return switch (data) {
      case S3BlobStoreData(var name, var sq, var bc) when bc != null && bc.getEncryption() != null -> "ENCRYPTED";
      case S3BlobStoreData(var name, var sq, var bc) when bc != null && bc.getFailoverBuckets() != null && !bc.getFailoverBuckets().isEmpty() -> "FAILOVER_ENABLED";
      case S3BlobStoreData(var name, var sq, var bc) when bc != null && bc.getBucket() != null && "us-east-1".equals(bc.getBucket().getRegion()) -> "US_EAST";
      case S3BlobStoreData(var name, var sq, var bc) -> "STANDARD";
      default -> "UNKNOWN";
    };
  }

  public String getName() {
    return name;
  }

  public BlobStoreApiSoftQuota getSoftQuota() {
    return softQuota;
  }

  public S3BlobStoreApiBucketConfiguration getBucketConfiguration() {
    return bucketConfiguration;
  }

  @Schema(description = "The blob store type.", accessMode = Schema.AccessMode.READ_ONLY, example = TYPE)
  public String getType() {return TYPE;}
}