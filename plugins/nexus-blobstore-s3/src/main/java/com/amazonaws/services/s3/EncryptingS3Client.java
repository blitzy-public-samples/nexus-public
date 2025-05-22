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
import java.util.Optional;

import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.s3.internal.S3BlobStore;
import org.sonatype.nexus.blobstore.s3.internal.encryption.KMSEncrypter;
import org.sonatype.nexus.blobstore.s3.internal.encryption.NoEncrypter;
import org.sonatype.nexus.blobstore.s3.internal.encryption.S3Encrypter;
import org.sonatype.nexus.blobstore.s3.internal.encryption.S3ManagedEncrypter;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.codahale.metrics.Timer;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
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
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingResponse;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;
import software.amazon.awssdk.core.ResponseInputStream;

import static java.util.Optional.ofNullable;
import static org.sonatype.nexus.blobstore.s3.S3BlobStoreConfigurationHelper.CONFIG_KEY;
import static org.sonatype.nexus.blobstore.s3.internal.S3BlobStore.ENCRYPTION_KEY;
import static org.sonatype.nexus.blobstore.s3.internal.S3BlobStore.ENCRYPTION_TYPE;

/**
 * Wraps an {@link S3Client} with custom behavior that adds server side encryption to requests.
 *
 * This class is designed to work with AWS SDK v2.x and supports Java 21 Virtual Threads for improved I/O performance.
 *
 * @since 3.19
 */
public class EncryptingS3Client
    implements S3Client
{
  private static final String METRIC_NAME = "encryptingS3Client";

  private final S3Client delegate;
  
  private final S3Encrypter encrypter;

  private final Timer getTimer;

  private final Timer putTimer;

  private final Timer copyTimer;

  private final Timer uploadPartTimer;

  private final Timer deleteTimer;

  private final Timer setTaggingTimer;

  /**
   * Creates a new EncryptingS3Client that wraps the provided S3Client.
   *
   * @param blobStoreConfig The BlobStoreConfiguration containing encryption settings
   * @param delegate The S3Client to delegate operations to
   */
  public EncryptingS3Client(
      final BlobStoreConfiguration blobStoreConfig,
      final S3Client delegate)
  {
    this.delegate = delegate;
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

  @Override
  public CopyObjectResponse copyObject(final CopyObjectRequest request) {
    CopyObjectRequest encryptedRequest = encrypter.addEncryption(request);

    try (final Timer.Context copyContext = copyTimer.time()) {
      return delegate.copyObject(encryptedRequest);
    }
  }

  /**
   * Convenience method to copy an object from one location to another.
   *
   * @param sourceBucketName The name of the bucket containing the source object
   * @param sourceKey The key in the source bucket under which the source object is stored
   * @param destinationBucketName The name of the bucket to which the source object will be copied
   * @param destinationKey The key in the destination bucket under which the source object will be copied
   * @return The response from the copy operation
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

  @Override
  public CreateMultipartUploadResponse createMultipartUpload(final CreateMultipartUploadRequest request) {
    CreateMultipartUploadRequest encryptedRequest = encrypter.addEncryption(request);
    return delegate.createMultipartUpload(encryptedRequest);
  }

  /**
   * Convenience method to put an object from a file.
   *
   * @param bucketName The name of the bucket to put the object into
   * @param key The key under which to store the object
   * @param file The file containing the data to upload
   * @return The response from the put operation
   */
  public PutObjectResponse putObject(final String bucketName, final String key, final File file) {
    PutObjectRequest request = PutObjectRequest.builder()
        .bucket(bucketName)
        .key(key)
        .build();
    return putObject(request, RequestBody.fromFile(file.toPath()));
  }

  /**
   * Convenience method to put an object from a path.
   *
   * @param bucketName The name of the bucket to put the object into
   * @param key The key under which to store the object
   * @param path The path to the file containing the data to upload
   * @return The response from the put operation
   */
  public PutObjectResponse putObject(final String bucketName, final String key, final Path path) {
    PutObjectRequest request = PutObjectRequest.builder()
        .bucket(bucketName)
        .key(key)
        .build();
    return putObject(request, RequestBody.fromFile(path));
  }

  /**
   * Convenience method to put an object from an input stream.
   *
   * @param bucketName The name of the bucket to put the object into
   * @param key The key under which to store the object
   * @param input The input stream containing the data to upload
   * @param contentLength The length of the data in the input stream
   * @return The response from the put operation
   */
  public PutObjectResponse putObject(
      final String bucketName, final String key,
      final InputStream input, final long contentLength)
  {
    PutObjectRequest request = PutObjectRequest.builder()
        .bucket(bucketName)
        .key(key)
        .build();
    return putObject(request, RequestBody.fromInputStream(input, contentLength));
  }

  @Override
  public PutObjectResponse putObject(final PutObjectRequest request, final RequestBody requestBody) {
    PutObjectRequest encryptedRequest = encrypter.addEncryption(request);
    try (final Timer.Context putContext = putTimer.time()) {
      return delegate.putObject(encryptedRequest, requestBody);
    }
  }

  @Override
  public ResponseInputStream<GetObjectResponse> getObject(GetObjectRequest request) {
    try (final Timer.Context getContext = getTimer.time()) {
      return delegate.getObject(request);
    }
  }

  /**
   * Convenience method to get an object.
   *
   * @param bucketName The name of the bucket containing the object
   * @param key The key under which the object is stored
   * @return The response input stream containing the object data
   */
  public ResponseInputStream<GetObjectResponse> getObject(String bucketName, String key) {
    GetObjectRequest request = GetObjectRequest.builder()
        .bucket(bucketName)
        .key(key)
        .build();
    return getObject(request);
  }

  @Override
  public UploadPartResponse uploadPart(UploadPartRequest request, RequestBody requestBody) {
    try (final Timer.Context uploadPartContext = uploadPartTimer.time()) {
      return delegate.uploadPart(request, requestBody);
    }
  }

  /**
   * Convenience method to delete an object.
   *
   * @param bucketName The name of the bucket containing the object
   * @param key The key under which the object is stored
   * @return The response from the delete operation
   */
  public DeleteObjectResponse deleteObject(String bucketName, String key) {
    DeleteObjectRequest request = DeleteObjectRequest.builder()
        .bucket(bucketName)
        .key(key)
        .build();
    return deleteObject(request);
  }

  @Override
  public DeleteObjectResponse deleteObject(DeleteObjectRequest request) {
    try (final Timer.Context deleteContext = deleteTimer.time()) {
      return delegate.deleteObject(request);
    }
  }

  @Override
  public PutObjectTaggingResponse putObjectTagging(PutObjectTaggingRequest request) {
    try (final Timer.Context setTaggingContext = setTaggingTimer.time()) {
      return delegate.putObjectTagging(request);
    }
  }

  /**
   * Closes the underlying S3Client.
   */
  @Override
  public void close() {
    delegate.close();
  }

  /**
   * Delegates all other S3Client methods to the underlying client.
   * This is a catch-all for methods not explicitly overridden above.
   */
  @Override
  public String serviceName() {
    return delegate.serviceName();
  }
}