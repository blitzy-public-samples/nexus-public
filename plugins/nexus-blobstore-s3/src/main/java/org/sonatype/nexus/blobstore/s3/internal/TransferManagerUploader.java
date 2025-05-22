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

import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import javax.inject.Named;

import org.sonatype.nexus.blobstore.api.BlobStoreException;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.TransferManagerBuilder;
import com.amazonaws.services.s3.transfer.Upload;

/**
 * Uploads a file with the TransferManager using Java 21 Virtual Threads for improved I/O performance.
 * @since 3.7
 * @deprecated replaced with {@link MultipartUploader}
 */
@Deprecated
@Named("transfer-manager-uploader")
public class TransferManagerUploader
    implements S3Uploader
{

  public void upload(final AmazonS3 s3, final String bucket, final String key, final InputStream contents) {
    TransferManager transferManager = TransferManagerBuilder.standard().withS3Client(s3).build();
    
    try {
      // Create a CompletableFuture that will be completed by a Virtual Thread
      CompletableFuture<Void> uploadFuture = CompletableFuture.runAsync(() -> {
        try {
          // Perform the upload operation
          Upload upload = transferManager.upload(bucket, key, contents, new ObjectMetadata());
          upload.waitForCompletion();
        } 
        catch (InterruptedException e) {
          // Restore the interrupted status as per best practices
          Thread.currentThread().interrupt();
          throw new BlobStoreException("Virtual thread interrupted during S3 upload", e, null);
        } 
        catch (Exception e) {
          throw new BlobStoreException("Error during S3 upload operation", e, null);
        }
      }, Executors.newVirtualThreadPerTaskExecutor());
      
      // Wait for the upload to complete
      uploadFuture.join();
    } 
    catch (Exception e) {
      // Handle any exceptions from the CompletableFuture execution
      Throwable cause = e.getCause();
      if (cause instanceof BlobStoreException) {
        throw (BlobStoreException) cause;
      }
      if (e instanceof InterruptedException || (cause instanceof InterruptedException)) {
        // Restore the interrupted status
        Thread.currentThread().interrupt();
        throw new BlobStoreException("Thread interrupted while uploading to S3", cause != null ? cause : e, null);
      }
      throw new BlobStoreException("Error uploading blob to S3", cause != null ? cause : e, null);
    }
    finally {
      // Ensure the TransferManager is properly shut down
      transferManager.shutdownNow(false);
    }
  }
}