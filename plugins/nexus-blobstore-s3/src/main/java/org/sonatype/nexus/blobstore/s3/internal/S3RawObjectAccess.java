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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.lang.StringTemplate;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.sonatype.nexus.blobstore.PerformanceLogger;
import org.sonatype.nexus.blobstore.api.RawObjectAccess;

// AWS SDK for Java 2.x imports
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import static java.util.Objects.requireNonNull;

import static java.util.stream.Collectors.toList;

/**
 * Implementation of {@link RawObjectAccess} for the {@link S3BlobStore}.
 * <p>
 * This implementation has been updated for Java 21 compatibility, designed to run efficiently
 * in Virtual Threads for I/O-bound operations to improve performance and scalability. The implementation
 * uses AWS SDK for Java 2.x and modern Java language features including pattern matching and string templates.
 * </p>
 * <p>
 * When running in a Virtual Thread, this implementation automatically benefits from the improved
 * concurrency model without blocking platform threads during I/O operations. This is particularly
 * beneficial for S3 operations which are primarily I/O-bound.
 * </p>
 *
 * @since 3.31
 */
public class S3RawObjectAccess
    implements RawObjectAccess
{
  /* How many keys to fetch in one request (maximum value is 1000) */
  private static final int LIST_RAW_OBJECTS_MAX_KEYS = 1000;

  private final String bucket;

  private final String bucketPrefix;

  private final S3Client s3;

  private final PerformanceLogger performanceLogger;

  private final S3Uploader uploader;
  


  public S3RawObjectAccess(
      final String bucket,
      final String bucketPrefix,
      final S3Client s3,
      final PerformanceLogger performanceLogger,
      final S3Uploader uploader)
  {
    this.bucket = requireNonNull(bucket);
    this.bucketPrefix = requireNonNull(bucketPrefix);
    this.s3 = requireNonNull(s3);
    this.performanceLogger = requireNonNull(performanceLogger);
    this.uploader = requireNonNull(uploader);
  }

  /**
   * List raw objects at this path in the blobstore.
   * <p>
   * This implementation returns a maximum of 10,000 results and is designed to run
   * efficiently in a Virtual Thread for improved I/O concurrency.
   * </p>
   */
  @Override
  public Stream<String> listRawObjects(@Nullable final Path path) {
    final String prefix = bucketPrefix + normalizeS3Path(path, true);

    ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
        .bucket(bucket)
        .prefix(prefix)
        .delimiter("/")
        .maxKeys(LIST_RAW_OBJECTS_MAX_KEYS)
        .build();

    ListObjectsV2Response listing = s3.listObjectsV2(listRequest);

    List<String> rawObjects = new ArrayList<>(listingToFilenames(listing));

    while (listing.isTruncated()) {
      listRequest = ListObjectsV2Request.builder()
          .bucket(bucket)
          .prefix(prefix)
          .delimiter("/")
          .maxKeys(LIST_RAW_OBJECTS_MAX_KEYS)
          .continuationToken(listing.nextContinuationToken())
          .build();
      
      listing = s3.listObjectsV2(listRequest);
      rawObjects.addAll(listingToFilenames(listing));
    }

    return rawObjects.stream().sorted();
  }

  private List<String> listingToFilenames(final ListObjectsV2Response listing) {
    return listing.contents().stream().map(s3Object -> {
      String key = s3Object.key();
      return key.substring(key.lastIndexOf('/') + 1);
    }).collect(toList());
  }

  @Override
  @Nullable
  public InputStream getRawObject(final Path path) {
    try {
      String key = bucketPrefix + normalizeS3Path(path);
      GetObjectRequest getObjectRequest = GetObjectRequest.builder()
          .bucket(bucket)
          .key(key)
          .build();
      
      // Log thread information for debugging if needed
      if (performanceLogger.isVirtualThread()) {
        String threadInfo = performanceLogger.getThreadInfo();
        // Using Java 21 String Templates for improved readability
        System.out.println(STR."Getting S3 object from \{bucket}/\{key} using \{threadInfo}");
      }
          
      // The S3Client's getObject method already runs in the current thread,
      // so we can leverage virtual threads by having the caller run this method in a virtual thread
      try {
        InputStream objectContent = s3.getObject(getObjectRequest);
        return performanceLogger.maybeWrapForPerformanceLogging(objectContent);
      } catch (S3Exception e) {
        if (e.statusCode() == 404) {
          return null;
        }
        throw e;
      }
    } catch (Exception e) {
      if (e instanceof S3Exception s3Exception && s3Exception.statusCode() == 404) {
        return null;
      }
      throw new RuntimeException("Error getting raw object", e);
    }
  }

  @Override
  public void putRawObject(final Path path, final InputStream input) {
    try (InputStream in = input) {
      // The S3Uploader is responsible for the actual upload and should be configured
      // to leverage virtual threads when appropriate
      String key = bucketPrefix + normalizeS3Path(path);
      long startTime = System.nanoTime();
      
      uploader.upload(s3, bucket, key, in);
      
      // Log performance metrics if debug is enabled
      if (performanceLogger.isVirtualThread()) {
        long endTime = System.nanoTime();
        performanceLogger.logRead(0, endTime - startTime); // Use 0 bytes since we don't know the size here
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public boolean hasRawObject(final Path path) {
    try {
      // The S3Client's headObject method already runs in the current thread,
      // so we can leverage virtual threads by having the caller run this method in a virtual thread
      try {
        s3.headObject(request -> request
            .bucket(bucket)
            .key(bucketPrefix + normalizeS3Path(path))
            .build());
        return true;
      } catch (S3Exception e) {
        if (e.statusCode() == 404) {
          return false;
        }
        throw e;
      }
    } catch (Exception e) {
      if (e instanceof S3Exception s3Exception && s3Exception.statusCode() == 404) {
        return false;
      }
      throw new RuntimeException("Error checking if raw object exists", e);
    }
  }

  @Override
  public void deleteRawObject(final Path path) {
    // The S3Client's deleteObject method already runs in the current thread,
    // so we can leverage virtual threads by having the caller run this method in a virtual thread
    long startTime = System.nanoTime();
    
    s3.deleteObject(request -> request
        .bucket(bucket)
        .key(bucketPrefix + normalizeS3Path(path))
        .build());
    
    // Log performance metrics if debug is enabled and running in a virtual thread
    if (performanceLogger.isVirtualThread()) {
      long endTime = System.nanoTime();
      performanceLogger.logDelete(endTime - startTime);
    }
  }

  @Override
  public void deleteRawObjectsInPath(final Path path) {
    final String prefix = bucketPrefix + normalizeS3Path(path, true);
    
    ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
        .bucket(bucket)
        .prefix(prefix)
        .delimiter("/")
        .maxKeys(LIST_RAW_OBJECTS_MAX_KEYS)
        .build();
        
    ListObjectsV2Response listing = s3.listObjectsV2(listRequest);
    deleteObjectsInListing(listing);

    while (listing.isTruncated()) {
      listRequest = ListObjectsV2Request.builder()
          .bucket(bucket)
          .prefix(prefix)
          .delimiter("/")
          .maxKeys(LIST_RAW_OBJECTS_MAX_KEYS)
          .continuationToken(listing.nextContinuationToken())
          .build();
          
      listing = s3.listObjectsV2(listRequest);
      deleteObjectsInListing(listing);
    }
  }

  private void deleteObjectsInListing(final ListObjectsV2Response listing) {
    if (listing.contents().isEmpty()) {
      return;
    }
    
    List<ObjectIdentifier> keys = listing.contents().stream()
        .map(s3Object -> ObjectIdentifier.builder().key(s3Object.key()).build())
        .collect(toList());
    
    // The S3Client's deleteObjects method already runs in the current thread,
    // so we can leverage virtual threads by having the caller run this method in a virtual thread
    DeleteObjectsRequest deleteRequest = DeleteObjectsRequest.builder()
        .bucket(bucket)
        .delete(Delete.builder().objects(keys).build())
        .build();
        
    s3.deleteObjects(deleteRequest);
  }

  private String normalizeS3Path(final Path path) {
    return normalizeS3Path(path, false);
  }

  private String normalizeS3Path(final Path path, final boolean requireTrailingSlash) {
    if (path == null) {
      return "/";
    }

    String normalized = path.toString().replace("\\", "/");
    if (requireTrailingSlash && !normalized.endsWith("/")) {
      return normalized + "/";
    }
    return normalized;
  }
}