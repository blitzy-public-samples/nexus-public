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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.s3.rest.internal.model.S3BlobStoreApiBucketConfiguration;
import org.sonatype.nexus.blobstore.s3.rest.internal.model.S3BlobStoreApiFailoverBucket;
import org.sonatype.nexus.blobstore.s3.rest.internal.model.S3BlobStoreApiModel;
import org.sonatype.nexus.rest.ValidationErrorXO;
import org.sonatype.nexus.rest.ValidationErrorsException;

import org.apache.commons.lang3.StringUtils;

import static java.lang.String.format;
import static java.util.Optional.ofNullable;
import static org.apache.commons.lang3.StringUtils.equalsIgnoreCase;
import static org.sonatype.nexus.blobstore.s3.internal.S3BlobStore.TYPE;
import static org.sonatype.nexus.blobstore.s3.rest.internal.S3BlobStoreApiConstants.BLOB_STORE_NAME_UPDATE_ERROR_MESSAGE;
import static org.sonatype.nexus.blobstore.s3.rest.internal.S3BlobStoreApiConstants.BLOB_STORE_TYPE_MISMATCH_ERROR_FORMAT;
import static org.sonatype.nexus.blobstore.s3.rest.internal.S3BlobStoreApiConstants.DUPLICATE_REGIONS_ERROR_MESSAGE;
import static org.sonatype.nexus.blobstore.s3.rest.internal.S3BlobStoreApiConstants.FAILOVER_DEFAULT_ERROR_MESSAGE;
import static org.sonatype.nexus.blobstore.s3.rest.internal.S3BlobStoreApiConstants.MATCHES_PRIMARY_ERROR_MESSAGE;
import static org.sonatype.nexus.blobstore.s3.rest.internal.S3BlobStoreApiConstants.NON_EXISTENT_BLOB_STORE_ERROR_MESSAGE_FORMAT;
import static org.sonatype.nexus.blobstore.s3.rest.internal.model.S3BlobStoreApiBucketConfiguration.FAILOVER_BUCKETS;

/**
 * Performs validation checks on specified {@link S3BlobStoreApiModel} object containing updates to an S3 blob store.
 * 
 * This class has been updated for Java 21 compatibility, leveraging pattern matching for instanceof
 * and other Java 21 features for improved code clarity and performance.
 *
 * @since 3.20
 */
@Named
@Singleton
public class S3BlobStoreApiUpdateValidation
{
  private static final String BLOB_STORE_NAME = "name";

  private final BlobStoreManager blobStoreManager;

  @Inject
  public S3BlobStoreApiUpdateValidation(final BlobStoreManager blobStoreManager) {
    this.blobStoreManager = blobStoreManager;
  }

  /**
   * Validates a request to create a new S3 blob store.
   *
   * @param s3BlobStoreApiModel the model containing the blob store configuration to validate
   * @throws ValidationErrorsException if validation fails
   */
  void validateCreateRequest(final S3BlobStoreApiModel s3BlobStoreApiModel) {
    List<ValidationErrorXO> errors = new ArrayList<>();
    checkBlobStoreNameNotEmpty(s3BlobStoreApiModel.getName(), errors);
    checkFailoverBuckets(s3BlobStoreApiModel, errors);

    if (!errors.isEmpty()) {
      throw new ValidationErrorsException().withErrors(errors);
    }
  }

  /**
   * Validates a request to update an existing S3 blob store.
   *
   * @param s3BlobStoreApiModel the model containing the updated blob store configuration to validate
   * @param blobStoreName the name of the blob store to update
   * @throws ValidationErrorsException if validation fails
   */
  void validateUpdateRequest(final S3BlobStoreApiModel s3BlobStoreApiModel, final String blobStoreName) {
    List<ValidationErrorXO> errors = new ArrayList<>();
    final boolean blobStoreExists = checkBlobStoreExists(blobStoreName, errors);
    checkBlobStoreNamesMatch(s3BlobStoreApiModel, blobStoreName, errors);
    if (blobStoreExists) {
      checkBlobStoreTypeIsS3(blobStoreName, errors);
    }
    checkFailoverBuckets(s3BlobStoreApiModel, errors);

    if (!errors.isEmpty()) {
      throw new ValidationErrorsException().withErrors(errors);
    }
  }

  /**
   * Checks if the blob store name is not empty.
   *
   * @param blobStoreName the name of the blob store to check
   * @param errors the list of validation errors to add to if validation fails
   */
  private static void checkBlobStoreNameNotEmpty(final String blobStoreName, final List<ValidationErrorXO> errors) {
    if (StringUtils.isBlank(blobStoreName)) {
      errors.add(new ValidationErrorXO(BLOB_STORE_NAME, "Blob store name cannot be empty"));
    }
  }

  /**
   * Checks if the blob store exists.
   *
   * @param blobStoreName the name of the blob store to check
   * @param errors the list of validation errors to add to if validation fails
   * @return true if the blob store exists, false otherwise
   */
  private boolean checkBlobStoreExists(final String blobStoreName, final List<ValidationErrorXO> errors) {
    if (!blobStoreManager.exists(blobStoreName)) {
      errors.add(
          new ValidationErrorXO(BLOB_STORE_NAME, format(NON_EXISTENT_BLOB_STORE_ERROR_MESSAGE_FORMAT, blobStoreName)));
      return false;
    }
    return true;
  }

  /**
   * Checks if the blob store names match.
   * S3 blob store names cannot be changed after creation.
   *
   * @param s3BlobStoreApiModel the model containing the blob store configuration to validate
   * @param blobStoreName the name of the existing blob store
   * @param errors the list of validation errors to add to if validation fails
   */
  private static void checkBlobStoreNamesMatch(
      final S3BlobStoreApiModel s3BlobStoreApiModel,
      final String blobStoreName, final List<ValidationErrorXO> errors)
  {
    if (!equalsIgnoreCase(s3BlobStoreApiModel.getName(), blobStoreName)) {
      errors.add(new ValidationErrorXO(BLOB_STORE_NAME, BLOB_STORE_NAME_UPDATE_ERROR_MESSAGE));
    }
  }

  /**
   * Checks if the blob store is an S3 blob store.
   *
   * @param blobStoreName the name of the blob store to check
   * @param errors the list of validation errors to add to if validation fails
   */
  private void checkBlobStoreTypeIsS3(final String blobStoreName, final List<ValidationErrorXO> errors) {
    if (existingBlobStoreIsNotS3(blobStoreName)) {
      errors.add(new ValidationErrorXO(format(BLOB_STORE_TYPE_MISMATCH_ERROR_FORMAT, blobStoreName)));
    }
  }

  /**
   * Checks if the existing blob store is not an S3 blob store.
   * Uses Java 21 pattern matching for instanceof to simplify the code.
   *
   * @param blobStoreName the name of the blob store to check
   * @return true if the blob store exists but is not an S3 blob store, false otherwise
   */
  private boolean existingBlobStoreIsNotS3(final String blobStoreName) {
    BlobStore blobStore = blobStoreManager.get(blobStoreName);
    if (blobStore instanceof BlobStore store) {
      BlobStoreConfiguration config = store.getBlobStoreConfiguration();
      if (config instanceof BlobStoreConfiguration cfg) {
        String type = cfg.getType();
        return !equalsIgnoreCase(TYPE, type);
      }
    }
    return true; // If we can't determine the type, assume it's not an S3 blob store
  }

  /**
   * Validates the failover buckets configuration.
   * Uses Java 21 pattern matching and sequenced collections features where appropriate.
   *
   * @param s3BlobStoreApiModel the model containing the bucket configuration to validate
   * @param errors the list of validation errors to add to if validation fails
   */
  private static void checkFailoverBuckets(
      final S3BlobStoreApiModel s3BlobStoreApiModel,
      final List<ValidationErrorXO> errors)
  {
    // Use pattern matching to simplify the null check and property access
    if (s3BlobStoreApiModel.getBucketConfiguration() instanceof S3BlobStoreApiBucketConfiguration bucketConfig) {
      List<S3BlobStoreApiFailoverBucket> failoverBuckets = bucketConfig.getFailoverBuckets();
      
      if (failoverBuckets == null) {
        return;
      }
      
      // Convert regions to lowercase for case-insensitive comparison
      Set<String> regions = failoverBuckets.stream()
          .map(S3BlobStoreApiFailoverBucket::getRegion)
          .map(String::toLowerCase)
          .collect(Collectors.toSet());
      
      // Check for duplicate regions
      if (regions.size() != failoverBuckets.size()) {
        errors.add(new ValidationErrorXO(FAILOVER_BUCKETS, DUPLICATE_REGIONS_ERROR_MESSAGE));
      }
      
      // Check if any failover region matches the primary region
      String primaryRegion = bucketConfig.getBucket().getRegion().toLowerCase();
      if (regions.contains(primaryRegion)) {
        errors.add(new ValidationErrorXO(FAILOVER_BUCKETS, MATCHES_PRIMARY_ERROR_MESSAGE));
      }
      
      // Check if any region is "default"
      if (regions.contains("default")) {
        errors.add(new ValidationErrorXO(FAILOVER_BUCKETS, FAILOVER_DEFAULT_ERROR_MESSAGE));
      }
    }
  }
}
