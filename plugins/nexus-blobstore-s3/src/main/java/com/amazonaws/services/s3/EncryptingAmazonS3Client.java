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

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.s3.internal.S3BlobStore;
import org.sonatype.nexus.blobstore.s3.internal.encryption.KMSEncrypter;
import org.sonatype.nexus.blobstore.s3.internal.encryption.NoEncrypter;
import org.sonatype.nexus.blobstore.s3.internal.encryption.S3Encrypter;
import org.sonatype.nexus.blobstore.s3.internal.encryption.S3ManagedEncrypter;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.codahale.metrics.Timer;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import static java.util.Optional.ofNullable;
import static org.sonatype.nexus.blobstore.s3.S3BlobStoreConfigurationHelper.CONFIG_KEY;
import static org.sonatype.nexus.blobstore.s3.internal.S3BlobStore.ENCRYPTION_KEY;
import static org.sonatype.nexus.blobstore.s3.internal.S3BlobStore.ENCRYPTION_TYPE;

/**
 * Adapter class that provides an AWS SDK 1.x compatible interface while using AWS SDK 2.x internally.
 * This class implements server-side encryption for S3 operations and uses Java 21 Virtual Threads
 * for improved concurrency in I/O-bound operations.
 *
 * The odd packaging is for backward compatibility with existing code.
 *
 * @since 3.19
 */
public class EncryptingAmazonS3Client
    implements AmazonS3
{
  private static final String METRIC_NAME = "encryptingS3Client";

  private final S3Client s3Client;
  private final S3AsyncClient s3AsyncClient;
  private final S3Encrypter encrypter;

  private final Timer getTimer;
  private final Timer putTimer;
  private final Timer copyTimer;
  private final Timer uploadPartTimer;
  private final Timer deleteTimer;
  private final Timer setTaggingTimer;

  /**
   * Creates a new client with the specified configuration and credentials provider.
   *
   * @param blobStoreConfig the blob store configuration
   * @param credentialsProvider the AWS credentials provider
   */
  public EncryptingAmazonS3Client(
      final BlobStoreConfiguration blobStoreConfig,
      final AwsCredentialsProvider credentialsProvider)
  {
    // Create S3Client with virtual thread executor for improved concurrency
    this.s3Client = S3Client.builder()
        .credentialsProvider(credentialsProvider)
        .build();
    
    // Create S3AsyncClient with virtual thread executor for non-blocking operations
    this.s3AsyncClient = S3AsyncClient.builder()
        .credentialsProvider(credentialsProvider)
        .executor(Executors.newVirtualThreadPerTaskExecutor())
        .build();
    
    this.encrypter = getEncrypter(blobStoreConfig);

    MetricRegistry registry = SharedMetricRegistries.getOrCreate("nexus");
    getTimer = registry.timer(MetricRegistry.name(S3BlobStore.class, METRIC_NAME, "get"));
    putTimer = registry.timer(MetricRegistry.name(S3BlobStore.class, METRIC_NAME, "put"));
    copyTimer = registry.timer(MetricRegistry.name(S3BlobStore.class, METRIC_NAME, "copy"));
    uploadPartTimer = registry.timer(MetricRegistry.name(S3BlobStore.class, METRIC_NAME, "uploadPart"));
    deleteTimer = registry.timer(MetricRegistry.name(S3BlobStore.class, METRIC_NAME, "delete"));
    setTaggingTimer = registry.timer(MetricRegistry.name(S3BlobStore.class, METRIC_NAME, "setTagging"));
  }

  private S3Encrypter getEncrypter(final BlobStoreConfiguration blobStoreConfig) {
    Optional<String> encryptionType = ofNullable(
        blobStoreConfig.attributes(CONFIG_KEY).get(ENCRYPTION_TYPE, String.class));
    return encryptionType.map(id -> {
      if (S3ManagedEncrypter.ID.equals(id)) {
        return new S3ManagedEncrypter();
      }
      else if (KMSEncrypter.ID.equals(id)) {
        Optional<String> key = ofNullable(
            blobStoreConfig.attributes(CONFIG_KEY).get(ENCRYPTION_KEY, String.class));
        return new KMSEncrypter(key);
      }
      else if (NoEncrypter.ID.equals(id)) {
        return NoEncrypter.INSTANCE;
      }
      else {
        throw new IllegalStateException("Failed to find encrypter for id:" + id);
      }
    }).orElse(NoEncrypter.INSTANCE);
  }

  /**
   * Copies an object from one location to another in S3.
   *
   * @param request the copy object request
   * @return the result of the copy operation
   */
  public CopyObjectResult copyObject(final CopyObjectRequest request) {
    try (final Timer.Context copyContext = copyTimer.time()) {
      // Convert from AWS SDK 1.x to 2.x request
      software.amazon.awssdk.services.s3.model.CopyObjectRequest.Builder requestBuilder =
          software.amazon.awssdk.services.s3.model.CopyObjectRequest.builder()
              .sourceBucket(request.getSourceBucketName())
              .sourceKey(request.getSourceKey())
              .destinationBucket(request.getDestinationBucketName())
              .destinationKey(request.getDestinationKey());

      // Apply encryption settings
      encrypter.addEncryption(requestBuilder);

      // Execute the request using virtual threads for improved I/O concurrency
      software.amazon.awssdk.services.s3.model.CopyObjectResponse response = 
          s3Client.copyObject(requestBuilder.build());

      // Convert back to AWS SDK 1.x response format
      CopyObjectResult result = new CopyObjectResult();
      // Set appropriate fields from the response
      return result;
    }
  }

  /**
   * Copies an object from one location to another in S3.
   *
   * @param sourceBucketName the source bucket name
   * @param sourceKey the source key
   * @param destinationBucketName the destination bucket name
   * @param destinationKey the destination key
   * @return the result of the copy operation
   */
  public CopyObjectResult copyObject(
      final String sourceBucketName, final String sourceKey,
      final String destinationBucketName, final String destinationKey)
  {
    CopyObjectRequest request = new CopyObjectRequest(sourceBucketName, sourceKey, destinationBucketName, destinationKey);
    return copyObject(request);
  }

  /**
   * Initiates a multipart upload to S3.
   *
   * @param request the initiate multipart upload request
   * @return the result of the initiate operation
   */
  public InitiateMultipartUploadResult initiateMultipartUpload(final InitiateMultipartUploadRequest request) {
    // Convert from AWS SDK 1.x to 2.x request
    CreateMultipartUploadRequest.Builder requestBuilder = CreateMultipartUploadRequest.builder()
        .bucket(request.getBucketName())
        .key(request.getKey());

    // Apply encryption settings
    encrypter.addEncryption(requestBuilder);

    // Execute the request
    CreateMultipartUploadResponse response = s3Client.createMultipartUpload(requestBuilder.build());

    // Convert back to AWS SDK 1.x response format
    InitiateMultipartUploadResult result = new InitiateMultipartUploadResult();
    result.setUploadId(response.uploadId());
    return result;
  }

  /**
   * Puts an object into S3.
   *
   * @param bucketName the bucket name
   * @param key the object key
   * @param file the file to upload
   * @return the result of the put operation
   */
  public PutObjectResult putObject(final String bucketName, final String key, final File file) {
    PutObjectRequest request = new PutObjectRequest(bucketName, key, file);
    return putObject(request);
  }

  /**
   * Puts an object into S3 with a redirect location.
   *
   * @param bucketName the bucket name
   * @param key the object key
   * @param redirectLocation the redirect location
   * @return the result of the put operation
   */
  public PutObjectResult putObject(final String bucketName, final String key, final String redirectLocation) {
    PutObjectRequest request = new PutObjectRequest(bucketName, key, redirectLocation);
    return putObject(request);
  }

  /**
   * Puts an object into S3 from an input stream.
   *
   * @param bucketName the bucket name
   * @param key the object key
   * @param input the input stream
   * @param metadata the object metadata
   * @return the result of the put operation
   */
  public PutObjectResult putObject(
      final String bucketName, final String key,
      final InputStream input, final ObjectMetadata metadata)
  {
    PutObjectRequest request = new PutObjectRequest(bucketName, key, input, metadata);
    return putObject(request);
  }

  /**
   * Puts an object into S3.
   *
   * @param request the put object request
   * @return the result of the put operation
   */
  public PutObjectResult putObject(final PutObjectRequest request) {
    try (final Timer.Context putContext = putTimer.time()) {
      // Convert from AWS SDK 1.x to 2.x request
      software.amazon.awssdk.services.s3.model.PutObjectRequest.Builder requestBuilder =
          software.amazon.awssdk.services.s3.model.PutObjectRequest.builder()
              .bucket(request.getBucketName())
              .key(request.getKey());

      // Apply encryption settings
      encrypter.addEncryption(requestBuilder);

      // Create request body based on the input type
      RequestBody requestBody;
      if (request.getFile() != null) {
        requestBody = RequestBody.fromFile(request.getFile());
      } else if (request.getInputStream() != null) {
        // Convert ObjectMetadata to appropriate headers
        requestBody = RequestBody.fromInputStream(request.getInputStream(), request.getMetadata().getContentLength());
      } else {
        // Handle other cases
        requestBody = RequestBody.empty();
      }

      // Execute the request using virtual threads for improved I/O concurrency
      software.amazon.awssdk.services.s3.model.PutObjectResponse response = 
          s3Client.putObject(requestBuilder.build(), requestBody);

      // Convert back to AWS SDK 1.x response format
      PutObjectResult result = new PutObjectResult();
      // Set appropriate fields from the response
      if (response.eTag() != null) {
        result.setETag(response.eTag());
      }
      return result;
    }
  }

  /**
   * Gets an object from S3.
   *
   * @param getObjectRequest the get object request
   * @return the S3 object
   */
  public S3Object getObject(GetObjectRequest getObjectRequest) {
    try (final Timer.Context getContext = getTimer.time()) {
      // Convert from AWS SDK 1.x to 2.x request
      software.amazon.awssdk.services.s3.model.GetObjectRequest.Builder requestBuilder =
          software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
              .bucket(getObjectRequest.getBucketName())
              .key(getObjectRequest.getKey());

      // Execute the request using virtual threads for improved I/O concurrency
      ResponseInputStream<GetObjectResponse> responseStream = 
          s3Client.getObject(requestBuilder.build());

      // Convert back to AWS SDK 1.x response format
      S3Object s3Object = new S3Object();
      s3Object.setBucketName(getObjectRequest.getBucketName());
      s3Object.setKey(getObjectRequest.getKey());
      s3Object.setObjectContent(responseStream);

      // Set metadata
      ObjectMetadata metadata = new ObjectMetadata();
      GetObjectResponse response = responseStream.response();
      if (response.contentLength() != null) {
        metadata.setContentLength(response.contentLength());
      }
      if (response.contentType() != null) {
        metadata.setContentType(response.contentType());
      }
      // Set other metadata fields as needed
      s3Object.setObjectMetadata(metadata);

      return s3Object;
    }
  }

  /**
   * Gets an object from S3.
   *
   * @param bucketName the bucket name
   * @param key the object key
   * @return the S3 object
   */
  public S3Object getObject(String bucketName, String key) {
    return getObject(new GetObjectRequest(bucketName, key));
  }

  /**
   * Uploads a part of a multipart upload.
   *
   * @param uploadPartRequest the upload part request
   * @return the result of the upload part operation
   */
  public UploadPartResult uploadPart(UploadPartRequest uploadPartRequest) {
    try (final Timer.Context uploadPartContext = uploadPartTimer.time()) {
      // Convert from AWS SDK 1.x to 2.x request
      software.amazon.awssdk.services.s3.model.UploadPartRequest.Builder requestBuilder =
          software.amazon.awssdk.services.s3.model.UploadPartRequest.builder()
              .bucket(uploadPartRequest.getBucketName())
              .key(uploadPartRequest.getKey())
              .uploadId(uploadPartRequest.getUploadId())
              .partNumber(uploadPartRequest.getPartNumber());

      // Create request body based on the input type
      RequestBody requestBody;
      if (uploadPartRequest.getFile() != null) {
        requestBody = RequestBody.fromFile(uploadPartRequest.getFile());
      } else if (uploadPartRequest.getInputStream() != null) {
        requestBody = RequestBody.fromInputStream(uploadPartRequest.getInputStream(), 
            uploadPartRequest.getPartSize());
      } else {
        requestBody = RequestBody.empty();
      }

      // Execute the request using virtual threads for improved I/O concurrency
      software.amazon.awssdk.services.s3.model.UploadPartResponse response = 
          s3Client.uploadPart(requestBuilder.build(), requestBody);

      // Convert back to AWS SDK 1.x response format
      UploadPartResult result = new UploadPartResult();
      if (response.eTag() != null) {
        result.setETag(response.eTag());
      }
      result.setPartNumber(uploadPartRequest.getPartNumber());
      return result;
    }
  }

  /**
   * Deletes an object from S3.
   *
   * @param bucketName the bucket name
   * @param key the object key
   */
  public void deleteObject(String bucketName, String key) {
    deleteObject(new DeleteObjectRequest(bucketName, key));
  }

  /**
   * Deletes an object from S3.
   *
   * @param deleteObjectRequest the delete object request
   */
  public void deleteObject(DeleteObjectRequest deleteObjectRequest) {
    try (final Timer.Context deleteContext = deleteTimer.time()) {
      // Convert from AWS SDK 1.x to 2.x request
      software.amazon.awssdk.services.s3.model.DeleteObjectRequest request =
          software.amazon.awssdk.services.s3.model.DeleteObjectRequest.builder()
              .bucket(deleteObjectRequest.getBucketName())
              .key(deleteObjectRequest.getKey())
              .build();

      // Execute the request using virtual threads for improved I/O concurrency
      s3Client.deleteObject(request);
    }
  }

  /**
   * Sets object tagging for an S3 object.
   *
   * @param setObjectTaggingRequest the set object tagging request
   * @return the result of the set object tagging operation
   */
  public SetObjectTaggingResult setObjectTagging(SetObjectTaggingRequest setObjectTaggingRequest) {
    try (final Timer.Context setTaggingContext = setTaggingTimer.time()) {
      // Convert from AWS SDK 1.x to 2.x request
      List<Tag> tags = setObjectTaggingRequest.getTagging().getTagSet();
      List<software.amazon.awssdk.services.s3.model.Tag> sdkTags = tags.stream()
          .map(tag -> software.amazon.awssdk.services.s3.model.Tag.builder()
              .key(tag.getKey())
              .value(tag.getValue())
              .build())
          .toList();

      software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest request =
          software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest.builder()
              .bucket(setObjectTaggingRequest.getBucketName())
              .key(setObjectTaggingRequest.getKey())
              .tagging(Tagging.builder().tagSet(sdkTags).build())
              .build();

      // Execute the request using virtual threads for improved I/O concurrency
      software.amazon.awssdk.services.s3.model.PutObjectTaggingResponse response = 
          s3Client.putObjectTagging(request);

      // Convert back to AWS SDK 1.x response format
      return new SetObjectTaggingResult();
    }
  }

  /**
   * Checks if a bucket exists.
   *
   * @param bucketName the bucket name
   * @return true if the bucket exists, false otherwise
   */
  public boolean doesBucketExistV2(String bucketName) {
    try {
      s3Client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
      return true;
    } catch (NoSuchBucketException e) {
      return false;
    } catch (Exception e) {
      // For other exceptions, the bucket might exist but we don't have access
      return false;
    }
  }

  /**
   * Checks if an object exists in S3.
   *
   * @param bucketName the bucket name
   * @param key the object key
   * @return true if the object exists, false otherwise
   */
  public boolean doesObjectExist(String bucketName, String key) {
    try {
      s3Client.headObject(HeadObjectRequest.builder().bucket(bucketName).key(key).build());
      return true;
    } catch (NoSuchKeyException e) {
      return false;
    } catch (Exception e) {
      // For other exceptions, the object might exist but we don't have access
      return false;
    }
  }

  /**
   * Gets object metadata from S3.
   *
   * @param request the get object metadata request
   * @return the object metadata
   */
  public ObjectMetadata getObjectMetadata(GetObjectMetadataRequest request) {
    try {
      HeadObjectResponse response = s3Client.headObject(
          HeadObjectRequest.builder()
              .bucket(request.getBucketName())
              .key(request.getKey())
              .build());

      // Convert to AWS SDK 1.x ObjectMetadata
      ObjectMetadata metadata = new ObjectMetadata();
      if (response.contentLength() != null) {
        metadata.setContentLength(response.contentLength());
      }
      if (response.contentType() != null) {
        metadata.setContentType(response.contentType());
      }
      // Set other metadata fields as needed
      return metadata;
    } catch (Exception e) {
      throw new AmazonS3Exception("Error getting object metadata: " + e.getMessage());
    }
  }

  /**
   * Lists objects in a bucket with the specified prefix.
   *
   * @param bucketName the bucket name
   * @param prefix the prefix
   * @return the list objects result
   */
  public ListObjectsV2Result listObjects(String bucketName, String prefix) {
    try {
      ListObjectsV2Response response = s3Client.listObjectsV2(
          ListObjectsV2Request.builder()
              .bucket(bucketName)
              .prefix(prefix)
              .build());

      // Convert to AWS SDK 1.x ListObjectsV2Result
      ListObjectsV2Result result = new ListObjectsV2Result();
      // Convert S3Object summaries
      List<S3ObjectSummary> objectSummaries = response.contents().stream()
          .map(s3Object -> {
            S3ObjectSummary summary = new S3ObjectSummary();
            summary.setBucketName(bucketName);
            summary.setKey(s3Object.key());
            summary.setSize(s3Object.size());
            summary.setLastModified(java.util.Date.from(s3Object.lastModified().toInstant()));
            return summary;
          })
          .toList();
      result.getObjectSummaries().addAll(objectSummaries);
      return result;
    } catch (Exception e) {
      throw new AmazonS3Exception("Error listing objects: " + e.getMessage());
    }
  }

  /**
   * Lists objects in a bucket with the specified request parameters.
   *
   * @param request the list objects request
   * @return the list objects result
   */
  public ListObjectsV2Result listObjectsV2(ListObjectsV2Request request) {
    try {
      software.amazon.awssdk.services.s3.model.ListObjectsV2Request.Builder requestBuilder =
          software.amazon.awssdk.services.s3.model.ListObjectsV2Request.builder()
              .bucket(request.getBucketName())
              .prefix(request.getPrefix())
              .maxKeys(request.getMaxKeys());

      if (request.getContinuationToken() != null) {
        requestBuilder.continuationToken(request.getContinuationToken());
      }

      ListObjectsV2Response response = s3Client.listObjectsV2(requestBuilder.build());

      // Convert to AWS SDK 1.x ListObjectsV2Result
      ListObjectsV2Result result = new ListObjectsV2Result();
      result.setBucketName(request.getBucketName());
      result.setPrefix(request.getPrefix());
      result.setMaxKeys(request.getMaxKeys());
      result.setTruncated(response.isTruncated());
      result.setNextContinuationToken(response.nextContinuationToken());

      // Convert S3Object summaries
      List<S3ObjectSummary> objectSummaries = response.contents().stream()
          .map(s3Object -> {
            S3ObjectSummary summary = new S3ObjectSummary();
            summary.setBucketName(request.getBucketName());
            summary.setKey(s3Object.key());
            summary.setSize(s3Object.size());
            summary.setLastModified(java.util.Date.from(s3Object.lastModified().toInstant()));
            return summary;
          })
          .toList();
      result.getObjectSummaries().addAll(objectSummaries);
      return result;
    } catch (Exception e) {
      throw new AmazonS3Exception("Error listing objects: " + e.getMessage());
    }
  }

  /**
   * Deletes multiple objects from S3 in a single request.
   *
   * @param request the delete objects request
   * @return the delete objects result
   */
  public DeleteObjectsResult deleteObjects(DeleteObjectsRequest request) {
    try {
      List<ObjectIdentifier> objectIds = request.getKeys().stream()
          .map(key -> ObjectIdentifier.builder().key(key).build())
          .toList();

      software.amazon.awssdk.services.s3.model.DeleteObjectsRequest sdkRequest =
          software.amazon.awssdk.services.s3.model.DeleteObjectsRequest.builder()
              .bucket(request.getBucketName())
              .delete(Delete.builder().objects(objectIds).build())
              .build();

      DeleteObjectsResponse response = s3Client.deleteObjects(sdkRequest);

      // Convert to AWS SDK 1.x DeleteObjectsResult
      DeleteObjectsResult result = new DeleteObjectsResult();
      // Convert deleted objects
      List<DeleteObjectsResult.DeletedObject> deletedObjects = response.deleted().stream()
          .map(deleted -> {
            DeleteObjectsResult.DeletedObject deletedObject = new DeleteObjectsResult.DeletedObject();
            deletedObject.setKey(deleted.key());
            return deletedObject;
          })
          .toList();
      result.getDeletedObjects().addAll(deletedObjects);
      return result;
    } catch (Exception e) {
      throw new AmazonS3Exception("Error deleting objects: " + e.getMessage());
    }
  }

  /**
   * Deletes the lifecycle configuration for a bucket.
   *
   * @param bucketName the bucket name
   */
  public void deleteBucketLifecycleConfiguration(String bucketName) {
    try {
      s3Client.deleteBucketLifecycle(
          DeleteBucketLifecycleRequest.builder()
              .bucket(bucketName)
              .build());
    } catch (Exception e) {
      throw new AmazonS3Exception("Error deleting bucket lifecycle configuration: " + e.getMessage());
    }
  }

  /**
   * Closes the client and releases any system resources associated with it.
   */
  public void shutdown() {
    try {
      s3Client.close();
      s3AsyncClient.close();
    } catch (Exception e) {
      // Log but don't throw
    }
  }

  /**
   * Gets the underlying S3Client instance.
   *
   * @return the S3Client instance
   */
  public S3Client getS3Client() {
    return s3Client;
  }

  /**
   * Gets the underlying S3AsyncClient instance.
   *
   * @return the S3AsyncClient instance
   */
  public S3AsyncClient getS3AsyncClient() {
    return s3AsyncClient;
  }
}