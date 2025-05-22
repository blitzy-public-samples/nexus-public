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
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.s3.internal.S3BlobStore;
import org.sonatype.nexus.blobstore.s3.internal.encryption.KMSEncrypter;
import org.sonatype.nexus.blobstore.s3.internal.encryption.NoEncrypter;
import org.sonatype.nexus.blobstore.s3.internal.encryption.S3Encrypter;
import org.sonatype.nexus.blobstore.s3.internal.encryption.S3ManagedEncrypter;
import org.sonatype.nexus.common.thread.VirtualThreads;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.codahale.metrics.Timer;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingResponse;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import static java.util.Optional.ofNullable;
import static org.sonatype.nexus.blobstore.s3.S3BlobStoreConfigurationHelper.CONFIG_KEY;
import static org.sonatype.nexus.blobstore.s3.internal.S3BlobStore.ENCRYPTION_KEY;
import static org.sonatype.nexus.blobstore.s3.internal.S3BlobStore.ENCRYPTION_TYPE;

/**
 * Provides S3 client functionality with custom behavior that adds server side encryption to requests.
 * This implementation uses AWS SDK for Java 2.x and leverages Virtual Threads for improved I/O performance.
 * 
 * This class replaces the previous {@code AmazonS3Client} implementation with the AWS SDK v2.x {@link S3Client}
 * and adds support for Java 21 Virtual Threads to improve I/O performance.
 *
 * @since 3.19
 */
public class EncryptingAmazonS3Client
    implements AutoCloseable
{
  private static final String METRIC_NAME = "encryptingS3Client";

  private final S3Client s3Client;
  private final S3Encrypter encrypter;
  private final Executor virtualThreadExecutor;

  private final Timer getTimer;
  private final Timer putTimer;
  private final Timer copyTimer;
  private final Timer uploadPartTimer;
  private final Timer deleteTimer;
  private final Timer setTaggingTimer;

  /**
   * Creates a new EncryptingAmazonS3Client with the specified configuration and credentials.
   * This constructor replaces the previous AWS SDK v1.x constructor with a v2.x compatible version.
   *
   * @param blobStoreConfig the blob store configuration
   * @param s3ClientBuilder the S3 client builder with credentials and region already configured
   */
  public EncryptingAmazonS3Client(
      final BlobStoreConfiguration blobStoreConfig,
      final S3ClientBuilder s3ClientBuilder)
  {
    this.s3Client = s3ClientBuilder.build();
    this.encrypter = getEncrypter(blobStoreConfig);
    
    // Create a virtual thread executor for I/O operations
    this.virtualThreadExecutor = VirtualThreads.isEnabled() 
        ? Executors.newVirtualThreadPerTaskExecutor()
        : Executors.newCachedThreadPool();

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
   * @return the copy object response
   */
  public CopyObjectResponse copyObject(final CopyObjectRequest request) {
    CopyObjectRequest encryptedRequest = encrypter.addEncryption(request);

    try (final Timer.Context copyContext = copyTimer.time()) {
      if (VirtualThreads.isEnabled()) {
        CompletableFuture<CopyObjectResponse> future = CompletableFuture.supplyAsync(
            () -> s3Client.copyObject(encryptedRequest),
            virtualThreadExecutor
        );
        return future.join();
      } else {
        return s3Client.copyObject(encryptedRequest);
      }
    }
  }

  /**
   * Copies an object from one location to another in S3.
   *
   * @param sourceBucketName      the source bucket name
   * @param sourceKey             the source key
   * @param destinationBucketName the destination bucket name
   * @param destinationKey        the destination key
   * @return the copy object response
   */
  public CopyObjectResponse copyObject(
      final String sourceBucketName, final String sourceKey,
      final String destinationBucketName, final String destinationKey)
  {
    CopyObjectRequest request = CopyObjectRequest.builder()
        .sourceBucket(sourceBucketName)
        .sourceKey(sourceKey)
        .destinationBucket(destinationBucketName)
        .destinationKey(destinationKey)
        .build();
    return copyObject(request);
  }

  /**
   * Initiates a multipart upload and returns an InitiateMultipartUploadResult which
   * contains an upload ID. This upload ID associates all the parts in the specific
   * upload and is used in each of your subsequent uploadPart requests.
   *
   * @param request the create multipart upload request
   * @return the create multipart upload response
   */
  public CreateMultipartUploadResponse createMultipartUpload(final CreateMultipartUploadRequest request) {
    CreateMultipartUploadRequest encryptedRequest = encrypter.addEncryption(request);
    if (VirtualThreads.isEnabled()) {
      CompletableFuture<CreateMultipartUploadResponse> future = CompletableFuture.supplyAsync(
          () -> s3Client.createMultipartUpload(encryptedRequest),
          virtualThreadExecutor
      );
      return future.join();
    } else {
      return s3Client.createMultipartUpload(encryptedRequest);
    }
  }

  /**
   * Puts an object into S3.
   *
   * @param bucketName the bucket name
   * @param key        the key
   * @param file       the file to upload
   * @return the put object response
   */
  public PutObjectResponse putObject(final String bucketName, final String key, final File file) {
    PutObjectRequest request = PutObjectRequest.builder()
        .bucket(bucketName)
        .key(key)
        .build();
    return putObject(request, RequestBody.fromFile(file));
  }

  /**
   * Puts an object into S3.
   *
   * @param bucketName the bucket name
   * @param key        the key
   * @param path       the path to the file to upload
   * @return the put object response
   */
  public PutObjectResponse putObject(final String bucketName, final String key, final Path path) {
    PutObjectRequest request = PutObjectRequest.builder()
        .bucket(bucketName)
        .key(key)
        .build();
    return putObject(request, RequestBody.fromFile(path));
  }

  /**
   * Puts an object into S3 with a redirect location.
   *
   * @param bucketName       the bucket name
   * @param key              the key
   * @param redirectLocation the redirect location
   * @return the put object response
   */
  public PutObjectResponse putObject(final String bucketName, final String key, final String redirectLocation) {
    PutObjectRequest request = PutObjectRequest.builder()
        .bucket(bucketName)
        .key(key)
        .websiteRedirectLocation(redirectLocation)
        .build();
    return putObject(request, RequestBody.empty());
  }

  /**
   * Puts an object into S3 from an input stream.
   *
   * @param bucketName the bucket name
   * @param key        the key
   * @param input      the input stream
   * @param length     the content length
   * @return the put object response
   */
  public PutObjectResponse putObject(
      final String bucketName, final String key,
      final InputStream input, final long length)
  {
    PutObjectRequest request = PutObjectRequest.builder()
        .bucket(bucketName)
        .key(key)
        .build();
    return putObject(request, RequestBody.fromInputStream(input, length));
  }

  /**
   * Puts an object into S3.
   *
   * @param request the put object request
   * @param requestBody the request body containing the object content
   * @return the put object response
   */
  public PutObjectResponse putObject(final PutObjectRequest request, final RequestBody requestBody) {
    PutObjectRequest encryptedRequest = encrypter.addEncryption(request);
    try (final Timer.Context putContext = putTimer.time()) {
      if (VirtualThreads.isEnabled()) {
        CompletableFuture<PutObjectResponse> future = CompletableFuture.supplyAsync(
            () -> s3Client.putObject(encryptedRequest, requestBody),
            virtualThreadExecutor
        );
        return future.join();
      } else {
        return s3Client.putObject(encryptedRequest, requestBody);
      }
    }
  }

  /**
   * Gets an object from S3.
   *
   * @param request the get object request
   * @return the S3 object
   */
  public ResponseBytes<GetObjectResponse> getObject(GetObjectRequest request) {
    try (final Timer.Context getContext = getTimer.time()) {
      if (VirtualThreads.isEnabled()) {
        CompletableFuture<ResponseBytes<GetObjectResponse>> future = CompletableFuture.supplyAsync(
            () -> s3Client.getObjectAsBytes(request),
            virtualThreadExecutor
        );
        return future.join();
      } else {
        return s3Client.getObjectAsBytes(request);
      }
    }
  }

  /**
   * Gets an object from S3.
   *
   * @param bucketName the bucket name
   * @param key        the key
   * @return the S3 object
   */
  public ResponseBytes<GetObjectResponse> getObject(String bucketName, String key)
  {
    GetObjectRequest request = GetObjectRequest.builder()
        .bucket(bucketName)
        .key(key)
        .build();
    return getObject(request);
  }

  /**
   * Uploads a part in a multipart upload.
   *
   * @param request     the upload part request
   * @param requestBody the request body containing the part content
   * @return the upload part response
   */
  public UploadPartResponse uploadPart(UploadPartRequest request, RequestBody requestBody) {
    try (final Timer.Context uploadPartContext = uploadPartTimer.time()) {
      if (VirtualThreads.isEnabled()) {
        CompletableFuture<UploadPartResponse> future = CompletableFuture.supplyAsync(
            () -> s3Client.uploadPart(request, requestBody),
            virtualThreadExecutor
        );
        return future.join();
      } else {
        return s3Client.uploadPart(request, requestBody);
      }
    }
  }

  /**
   * Deletes an object from S3.
   *
   * @param bucketName the bucket name
   * @param key        the key
   * @return the delete object response
   */
  public DeleteObjectResponse deleteObject(String bucketName, String key)
  {
    DeleteObjectRequest request = DeleteObjectRequest.builder()
        .bucket(bucketName)
        .key(key)
        .build();
    return deleteObject(request);
  }

  /**
   * Deletes an object from S3.
   *
   * @param request the delete object request
   * @return the delete object response
   */
  public DeleteObjectResponse deleteObject(DeleteObjectRequest request) {
    try (final Timer.Context deleteContext = deleteTimer.time()) {
      if (VirtualThreads.isEnabled()) {
        CompletableFuture<DeleteObjectResponse> future = CompletableFuture.supplyAsync(
            () -> s3Client.deleteObject(request),
            virtualThreadExecutor
        );
        return future.join();
      } else {
        return s3Client.deleteObject(request);
      }
    }
  }

  /**
   * Sets the tagging for an object in S3.
   *
   * @param request the put object tagging request
   * @return the put object tagging response
   */
  public PutObjectTaggingResponse putObjectTagging(PutObjectTaggingRequest request) {
    try (final Timer.Context setTaggingContext = setTaggingTimer.time()) {
      if (VirtualThreads.isEnabled()) {
        CompletableFuture<PutObjectTaggingResponse> future = CompletableFuture.supplyAsync(
            () -> s3Client.putObjectTagging(request),
            virtualThreadExecutor
        );
        return future.join();
      } else {
        return s3Client.putObjectTagging(request);
      }
    }
  }
  
  /**
   * Closes this client and releases any system resources associated with it.
   */
  @Override
  public void close() {
    s3Client.close();
    if (virtualThreadExecutor instanceof AutoCloseable) {
      try {
        ((AutoCloseable) virtualThreadExecutor).close();
      } catch (Exception e) {
        // Log and ignore
      }
    }
  }
}