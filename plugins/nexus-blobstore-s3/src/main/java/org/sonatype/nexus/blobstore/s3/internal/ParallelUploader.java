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
import static java.lang.StringTemplate.STR;
import static java.lang.String.format;
import static java.util.Optional.empty;
import static java.util.Optional.of;

/**
 * Uploads an InputStream, using multipart upload in parallel if the file is larger or equal to the chunk size.
 * A normal putObject request is used instead if only a single chunk would be sent.
 * 
 * This implementation leverages Java 21 Virtual Threads for I/O-bound operations to improve concurrency and throughput
 * without increasing resource consumption.
 *
 * @since 3.19
 * @requires Java 21
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
      Chunk chunk = chunkReader.readChunk(chunkSize).orElse(EMPTY_CHUNK);
      input.reset();

      if (chunk.dataLength < chunkSize) {
        // For small files, use a simple putObject request
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(chunk.dataLength);
        s3.putObject(bucket, key, new ByteArrayInputStream(chunk.data, 0, chunk.dataLength), metadata);
      }
      else {
        // For larger files, use parallel multipart upload with Virtual Threads
        ChunkReader parallelReader = new ChunkReader(input);
        parallelRequests(s3, bucket, key,
            () -> (uploadId -> uploadChunks(s3, bucket, key, uploadId, parallelReader)));
      }
      log.debug(STR."Finished upload to key \{key} in bucket \{bucket}");
    }
    catch (IOException | SdkClientException e) { // NOSONAR
      throw new BlobStoreException(STR."Error uploading blob to bucket:\{bucket} key:\{key}", e, null);
    }
  }

  private List<PartETag> uploadChunks(final AmazonS3 s3,
                                      final String bucket,
                                      final String key,
                                      final String uploadId,
                                      final ChunkReader chunkReader)
      throws IOException
  {
    List<PartETag> tags = new ArrayList<>();
    Optional<Chunk> chunk;

    while ((chunk = chunkReader.readChunk(chunkSize)).isPresent()) {
      Chunk chunkData = chunk.get();
      UploadPartRequest request = new UploadPartRequest()
          .withBucketName(bucket)
          .withKey(key)
          .withUploadId(uploadId)
          .withPartNumber(chunkData.chunkNumber)
          .withInputStream(new ByteArrayInputStream(chunkData.data, 0, chunkData.dataLength))
          .withPartSize(chunkData.dataLength);

      tags.add(s3.uploadPart(request).getPartETag());
    }

    return tags;
  }

  /**
   * Reads chunks of data from an input stream.
   * Designed to be used with Virtual Threads for efficient I/O operations.
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