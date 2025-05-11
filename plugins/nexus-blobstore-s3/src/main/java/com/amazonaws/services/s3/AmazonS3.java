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

import com.amazonaws.services.s3.model.CopyObjectRequest;
import com.amazonaws.services.s3.model.CopyObjectResult;
import com.amazonaws.services.s3.model.DeleteObjectRequest;
import com.amazonaws.services.s3.model.DeleteObjectsRequest;
import com.amazonaws.services.s3.model.DeleteObjectsResult;
import com.amazonaws.services.s3.model.GetObjectMetadataRequest;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.PutObjectResult;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.SetObjectTaggingRequest;
import com.amazonaws.services.s3.model.SetObjectTaggingResult;
import com.amazonaws.services.s3.model.UploadPartRequest;
import com.amazonaws.services.s3.model.UploadPartResult;

/**
 * Interface for Amazon S3 client operations.
 * This interface is designed to maintain compatibility with AWS SDK for Java 1.x
 * while using AWS SDK for Java 2.x internally.
 *
 * @since 3.19
 */
public interface AmazonS3 {

  /**
   * Copies an object from one location to another in S3.
   *
   * @param request the copy object request
   * @return the result of the copy operation
   */
  CopyObjectResult copyObject(CopyObjectRequest request);

  /**
   * Copies an object from one location to another in S3.
   *
   * @param sourceBucketName the source bucket name
   * @param sourceKey the source key
   * @param destinationBucketName the destination bucket name
   * @param destinationKey the destination key
   * @return the result of the copy operation
   */
  CopyObjectResult copyObject(
      String sourceBucketName, String sourceKey,
      String destinationBucketName, String destinationKey);

  /**
   * Initiates a multipart upload to S3.
   *
   * @param request the initiate multipart upload request
   * @return the result of the initiate operation
   */
  InitiateMultipartUploadResult initiateMultipartUpload(InitiateMultipartUploadRequest request);

  /**
   * Puts an object into S3.
   *
   * @param bucketName the bucket name
   * @param key the object key
   * @param file the file to upload
   * @return the result of the put operation
   */
  PutObjectResult putObject(String bucketName, String key, File file);

  /**
   * Puts an object into S3 with a redirect location.
   *
   * @param bucketName the bucket name
   * @param key the object key
   * @param redirectLocation the redirect location
   * @return the result of the put operation
   */
  PutObjectResult putObject(String bucketName, String key, String redirectLocation);

  /**
   * Puts an object into S3 from an input stream.
   *
   * @param bucketName the bucket name
   * @param key the object key
   * @param input the input stream
   * @param metadata the object metadata
   * @return the result of the put operation
   */
  PutObjectResult putObject(
      String bucketName, String key,
      InputStream input, ObjectMetadata metadata);

  /**
   * Puts an object into S3.
   *
   * @param request the put object request
   * @return the result of the put operation
   */
  PutObjectResult putObject(PutObjectRequest request);

  /**
   * Gets an object from S3.
   *
   * @param getObjectRequest the get object request
   * @return the S3 object
   */
  S3Object getObject(GetObjectRequest getObjectRequest);

  /**
   * Gets an object from S3.
   *
   * @param bucketName the bucket name
   * @param key the object key
   * @return the S3 object
   */
  S3Object getObject(String bucketName, String key);

  /**
   * Uploads a part of a multipart upload.
   *
   * @param uploadPartRequest the upload part request
   * @return the result of the upload part operation
   */
  UploadPartResult uploadPart(UploadPartRequest uploadPartRequest);

  /**
   * Deletes an object from S3.
   *
   * @param bucketName the bucket name
   * @param key the object key
   */
  void deleteObject(String bucketName, String key);

  /**
   * Deletes an object from S3.
   *
   * @param deleteObjectRequest the delete object request
   */
  void deleteObject(DeleteObjectRequest deleteObjectRequest);

  /**
   * Sets object tagging for an S3 object.
   *
   * @param setObjectTaggingRequest the set object tagging request
   * @return the result of the set object tagging operation
   */
  SetObjectTaggingResult setObjectTagging(SetObjectTaggingRequest setObjectTaggingRequest);

  /**
   * Checks if a bucket exists.
   *
   * @param bucketName the bucket name
   * @return true if the bucket exists, false otherwise
   */
  boolean doesBucketExistV2(String bucketName);

  /**
   * Checks if an object exists in S3.
   *
   * @param bucketName the bucket name
   * @param key the object key
   * @return true if the object exists, false otherwise
   */
  boolean doesObjectExist(String bucketName, String key);

  /**
   * Gets object metadata from S3.
   *
   * @param request the get object metadata request
   * @return the object metadata
   */
  ObjectMetadata getObjectMetadata(GetObjectMetadataRequest request);

  /**
   * Lists objects in a bucket with the specified prefix.
   *
   * @param bucketName the bucket name
   * @param prefix the prefix
   * @return the list objects result
   */
  ListObjectsV2Result listObjects(String bucketName, String prefix);

  /**
   * Lists objects in a bucket with the specified request parameters.
   *
   * @param request the list objects request
   * @return the list objects result
   */
  ListObjectsV2Result listObjectsV2(ListObjectsV2Request request);

  /**
   * Deletes multiple objects from S3 in a single request.
   *
   * @param request the delete objects request
   * @return the delete objects result
   */
  DeleteObjectsResult deleteObjects(DeleteObjectsRequest request);

  /**
   * Deletes the lifecycle configuration for a bucket.
   *
   * @param bucketName the bucket name
   */
  void deleteBucketLifecycleConfiguration(String bucketName);

  /**
   * Closes the client and releases any system resources associated with it.
   */
  void shutdown();
}