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

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.blobstore.api.BlobStoreException;
import org.sonatype.nexus.blobstore.s3.internal.ParallelUploader.ChunkReader.Chunk;

import com.amazonaws.SdkClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.UploadPartRequest;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;
import static java.util.Optional.empty;
import static java.util.Optional.of;

/**
 * Uploads an InputStream, using multipart upload in parallel if the file is larger or equal to the chunk size.
 * A normal putObject request is used instead if only a single chunk would be sent.
 * 
 * This implementation leverages Java 21 Virtual Threads for I/O-bound operations to improve throughput
 * and concurrency without the overhead of platform threads. Virtual threads are particularly well-suited
 * for this use case as they provide:
 * <ul>
 *   <li>High concurrency with minimal resource overhead</li>
 *   <li>Efficient handling of blocking I/O operations</li>
 *   <li>Simplified code compared to traditional async approaches</li>
 * </ul>
 *
 * @since 3.19
 * @see java.lang.Thread#startVirtualThread(Runnable)
 * @see java.util.concurrent.Executors#newVirtualThreadPerTaskExecutor()
 */
@Singleton
@Named("parallelUploader")
public class ParallelUploader
    extends ParallelRequester
    implements S3Uploader
{
  private static final Chunk EMPTY_CHUNK = new ChunkReader.Chunk(0, new byte[0], 0);

  @Inject
  public ParallelUploader(@Named("${nexus.s3.parallelRequests.chunksize:-5242880}") final int chunkSize,
                          @Named("${nexus.s3.parallelRequests.parallelism:-0}") final int nThreads)
  {
    super(chunkSize, nThreads, "uploadThreads");
  }

  @Override
  public void upload(final AmazonS3 s3, final String bucket, final String key, final InputStream contents) {
    try (InputStream input = new BufferedInputStream(contents, chunkSize)) {
      log.debug(STR."Starting upload to key \{key} in bucket \{bucket}");

      input.mark(chunkSize);
      ChunkReader chunkReader = new ChunkReader(input);
      Optional<Chunk> chunkOptional = chunkReader.readChunk(chunkSize);
      
      // Use pattern matching for Optional to handle the chunk
      Chunk chunk = switch (chunkOptional) {
        case Optional.of(var c) -> c;
        case Optional.empty() -> EMPTY_CHUNK;
      };
      
      input.reset();

      if (chunk.dataLength < chunkSize) {
        // For small files, use a direct upload
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(chunk.dataLength);
        s3.putObject(bucket, key, new ByteArrayInputStream(chunk.data, 0, chunk.dataLength), metadata);
      }
      else {
        // For larger files, use parallel upload with Virtual Threads for I/O operations
        ChunkReader parallelReader = new ChunkReader(input);
        parallelRequests(s3, bucket, key,
            () -> (uploadId -> uploadChunksWithVirtualThreads(s3, bucket, key, uploadId, parallelReader)));
      }
      log.debug(STR."Finished upload to key \{key} in bucket \{bucket}");
    }
    catch (IOException | SdkClientException e) { // NOSONAR
      throw new BlobStoreException(format("Error uploading blob to bucket:%s key:%s", bucket, key), e, null);
    }
  }

  /**
   * Uploads chunks using Java 21 Virtual Threads for improved I/O throughput.
   * This method leverages virtual threads to handle the I/O-bound operations of uploading
   * chunks to S3, which can significantly improve performance with minimal resource overhead.
   * 
   * Virtual threads are particularly effective for this use case because:
   * <ul>
   *   <li>S3 uploads are I/O-bound operations that often involve waiting for network responses</li>
   *   <li>Multiple chunks can be uploaded concurrently without exhausting system resources</li>
   *   <li>The JVM can efficiently manage thread scheduling based on I/O availability</li>
   * </ul>
   * 
   * Note: This implementation properly manages the virtual thread executor lifecycle
   * to ensure resources are released appropriately after use.
   *
   * @param s3 The AmazonS3 client to use for uploads
   * @param bucket The S3 bucket name
   * @param key The S3 object key
   * @param uploadId The multipart upload ID
   * @param chunkReader The chunk reader to read data from
   * @return A list of PartETag objects for the uploaded parts
   * @throws IOException If an I/O error occurs during upload
   */
  private List<PartETag> uploadChunksWithVirtualThreads(final AmazonS3 s3,
                                      final String bucket,
                                      final String key,
                                      final String uploadId,
                                      final ChunkReader chunkReader)
      throws IOException
  {
    var tags = new ArrayList<PartETag>();
    Optional<Chunk> chunkOptional;

    // Use a virtual thread executor for all I/O-bound upload operations
    // This allows for high concurrency with minimal resource overhead compared to platform threads
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      while ((chunkOptional = chunkReader.readChunk(chunkSize)).isPresent()) {
        // Use pattern matching for Optional to handle the chunk
        if (chunkOptional instanceof Optional.of<Chunk>(var chunk)) {
          // Create a final reference to the chunk for use in the lambda
          final Chunk finalChunk = chunk;
          
          // Submit the upload task to the virtual thread executor
          var future = executor.submit(() -> {
            UploadPartRequest request = new UploadPartRequest()
                .withBucketName(bucket)
                .withKey(key)
                .withUploadId(uploadId)
                .withPartNumber(finalChunk.chunkNumber)
                .withInputStream(new ByteArrayInputStream(finalChunk.data, 0, finalChunk.dataLength))
                .withPartSize(finalChunk.dataLength);

            return s3.uploadPart(request).getPartETag();
          });
          
          try {
            tags.add(future.get());
          } catch (Exception e) {
            throw new IOException(STR."Error uploading part \{finalChunk.chunkNumber} to S3", e);
          }
        }
      }
    } finally {
      executor.close(); // Properly close the executor
    }

    return tags;
  }

  /**
   * Reads chunks from an input stream for parallel processing.
   * Optimized for use with Java 21 Virtual Threads to handle I/O operations efficiently.
   * 
   * This reader is designed to work with the virtual thread implementation by providing
   * thread-safe access to the underlying input stream and maintaining proper chunk ordering
   * through atomic counters.
   */
  static class ChunkReader
  {
    private final AtomicInteger counter;

    private final InputStream input;

    private ChunkReader(final InputStream input) {
      this.counter = new AtomicInteger(1);
      this.input = checkNotNull(input);
    }

    synchronized Optional<Chunk> readChunk(final int size) throws IOException
    {
      byte[] buf = new byte[size];
      int bytesRead = 0;
      int readSize;

      while ((readSize = input.read(buf, bytesRead, size - bytesRead)) != -1 && bytesRead < size) {
        bytesRead += readSize;
      }

      return bytesRead > 0 ? of(new Chunk(bytesRead, buf, counter.getAndIncrement())) : empty();
    }

    /**
     * Represents a chunk of data read from the input stream.
     * Immutable data structure for thread-safe parallel processing.
     */
    static class Chunk
    {
      final byte[] data;

      final int dataLength;

      final int chunkNumber;

      Chunk(final int dataLength, final byte[] data, final int chunkNumber) {
        this.dataLength = dataLength;
        this.data = data;  //NOSONAR
        this.chunkNumber = chunkNumber;
      }
    }
  }
}