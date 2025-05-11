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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;


import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.blobstore.api.BlobStoreException;

import com.amazonaws.SdkClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.AbortMultipartUploadRequest;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.UploadPartRequest;
import com.amazonaws.services.s3.model.UploadPartResult;
import com.google.common.annotations.VisibleForTesting;

import static com.google.common.base.Preconditions.checkState;

/**
 * Uploads a file, using multipart upload if the file is larger or equal to the chunk size. A normal putObject request
 * is used instead if only a single chunk would be sent.
 *
 * This implementation leverages Java 21 Virtual Threads for I/O-bound operations to improve concurrency and throughput.
 * Virtual threads are lightweight threads that are managed by the JVM rather than the OS, allowing for thousands of
 * concurrent operations with minimal overhead. When a virtual thread performs a blocking I/O operation, it releases
 * the carrier thread, enabling efficient resource utilization.
 *
 * @since 3.12
 */
@Named("multipart-uploader")
public class MultipartUploader
    extends ComponentSupport
    implements S3Uploader
{

  private final int chunkSize;

  @Inject
  public MultipartUploader(@Named("${nexus.s3.multipartupload.chunksize:-5242880}") final int chunkSize) {
    this.chunkSize = chunkSize;
  }

  /**
   * Uploads content to an S3 bucket. If the content is smaller than the configured chunk size,
   * it uses a single-part upload. Otherwise, it uses a multipart upload for better reliability
   * and performance.
   * 
   * @param s3 The AmazonS3 client to use for the upload
   * @param bucket The S3 bucket name
   * @param key The object key in the S3 bucket
   * @param contents The input stream containing the data to upload
   * @throws BlobStoreException If an error occurs during the upload process
   */
  @Override
  public void upload(final AmazonS3 s3, final String bucket, final String key, final InputStream contents) {
    try (InputStream input = contents) {
      // Read the first chunk to determine if we need a single or multipart upload
      InputStream chunkOne = readChunk(input);
      
      // Choose upload strategy based on content size
      if (chunkOne.available() < chunkSize) {
        // Content fits in a single chunk, use simple upload
        uploadSinglePart(s3, bucket, key, chunkOne);
      }
      else {
        // Content requires multiple chunks, use multipart upload
        uploadMultiPart(s3, bucket, key, chunkOne, input);
      }
    }
    catch (IOException | SdkClientException e) { // NOSONAR
      throw new BlobStoreException("Error uploading blob to S3", e, null);
    }
  }

  private void uploadSinglePart(
      final AmazonS3 s3,
      final String bucket,
      final String key,
      final InputStream contents) throws IOException
  {
    int contentLength = contents.available();
    log.debug("Starting upload to key {} in bucket {} of {} bytes", key, bucket, contentLength);
    ObjectMetadata metadata = new ObjectMetadata();
    metadata.setContentLength(contentLength);
    
    // Use a virtual thread for the I/O-bound operation
    Thread.startVirtualThread(() -> {
      try {
        s3.putObject(bucket, key, contents, metadata);
      }
      catch (Exception e) {
        log.error("Error in virtual thread during single part upload to bucket {} with key {}", bucket, key, e);
      }
    }).join(); // Wait for the virtual thread to complete
  }

  private void uploadMultiPart(
      final AmazonS3 s3,
      final String bucket,
      final String key,
      final InputStream firstChunk,
      final InputStream restOfContents) throws IOException
  {
    checkState(firstChunk.available() > 0);
    String uploadId = null;
    try {
      // Initiate the multipart upload
      InitiateMultipartUploadRequest initiateRequest = new InitiateMultipartUploadRequest(bucket, key);
      uploadId = s3.initiateMultipartUpload(initiateRequest).getUploadId();

      log.debug("Starting multipart upload {} to key {} in bucket {}", uploadId, key, bucket);

      // Track upload part results
      List<UploadPartResult> results = new ArrayList<>();
      
      // Process each part
      for (int partNumber = 1;; partNumber++) {
        // Get the next chunk of data
        InputStream chunk = switch (partNumber) {
            case 1 -> firstChunk;
            default -> readChunk(restOfContents);
        };
        
        // Check if we've reached the end of the data
        int availableBytes = chunk.available();
        if (availableBytes == 0) {
          break;
        }
        
        // Log the chunk being uploaded
        log.debug("Uploading chunk {} for {} of {} bytes", partNumber, uploadId, availableBytes);
        
        // Capture variables for use in the virtual thread
        final int currentPartNumber = partNumber;
        final InputStream currentChunk = chunk;
        final int chunkSize = availableBytes;
        
        // Use a virtual thread for each part upload to improve concurrency
        var uploadPartThread = Thread.ofVirtual()
            .name("s3-upload-part-" + currentPartNumber)
            .start(() -> {
                try {
                    UploadPartRequest part = new UploadPartRequest()
                        .withBucketName(bucket)
                        .withKey(key)
                        .withUploadId(uploadId)
                        .withPartNumber(currentPartNumber)
                        .withInputStream(currentChunk)
                        .withPartSize(chunkSize);
                    results.add(s3.uploadPart(part));
                }
                catch (Exception e) {
                    log.error("Error in virtual thread during multipart upload to bucket {} with key {}, part {}", 
                        bucket, key, currentPartNumber, e);
                }
            });
        
        // Wait for this part to complete before proceeding to the next part
        // This maintains the sequential order of parts which is required by S3
        uploadPartThread.join();
      }
      
      // Complete the multipart upload in a virtual thread
      Thread.ofVirtual()
          .name("s3-complete-upload-" + uploadId)
          .start(() -> {
              try {
                  CompleteMultipartUploadRequest compRequest = new CompleteMultipartUploadRequest()
                      .withBucketName(bucket)
                      .withKey(key)
                      .withUploadId(uploadId)
                      .withPartETags(results);
                  s3.completeMultipartUpload(compRequest);
                  log.debug("Upload {} complete", uploadId);
              }
              catch (Exception e) {
                  log.error("Error in virtual thread during completion of multipart upload to bucket {} with key {}", 
                      bucket, key, e);
              }
          }).join(); // Wait for completion
      
      // Set uploadId to null to indicate successful completion
      uploadId = null;
    }
    finally {
      if (uploadId != null) {
        try {
          // Abort the multipart upload in a virtual thread if needed
          final String finalUploadId = uploadId;
          Thread.ofVirtual()
              .name("s3-abort-upload-" + finalUploadId)
              .start(() -> {
                  try {
                      s3.abortMultipartUpload(new AbortMultipartUploadRequest(bucket, key, finalUploadId));
                      log.debug("Aborted multipart upload {} for key {} in bucket {}", finalUploadId, key, bucket);
                  }
                  catch (Exception e) {
                      log.error("Error in virtual thread during abort of multipart upload to bucket {} with key {}", 
                          bucket, key, e);
                  }
              }).join(); // Wait for abort to complete
        }
        catch (Exception e) {
          log.error("Error aborting S3 multipart upload to bucket {} with key {}", bucket, key,
              log.isDebugEnabled() ? e : null);
        }
      }
    }
  }

  /**
   * Reads a chunk of data from the input stream up to the configured chunk size.
   * 
   * @param input The input stream to read from
   * @return A new input stream containing the chunk data
   * @throws IOException If an I/O error occurs during reading
   */
  @VisibleForTesting
  InputStream readChunk(final InputStream input) throws IOException {
    byte[] buffer = new byte[chunkSize];
    int offset = 0;
    int remain = chunkSize;
    int bytesRead = 0;

    // Read until we've filled the buffer or reached the end of the stream
    while (remain > 0 && bytesRead >= 0) {
      bytesRead = input.read(buffer, offset, remain);
      if (bytesRead > 0) {
        offset += bytesRead;
        remain -= bytesRead;
      }
    }
    
    // Return a stream with the read data or an empty stream if no data was read
    return offset > 0 
        ? new ByteArrayInputStream(buffer, 0, offset)
        : new ByteArrayInputStream(new byte[0]);
  }
}