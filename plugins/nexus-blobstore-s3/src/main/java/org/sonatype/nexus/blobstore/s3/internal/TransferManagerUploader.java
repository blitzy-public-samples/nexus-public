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

import javax.inject.Named;

import org.sonatype.nexus.blobstore.api.BlobStoreException;

// AWS SDK for Java v1.x imports - compatible with Java 21 but scheduled for end-of-support on December 31, 2025
// In a full migration, these would be replaced with software.amazon.awssdk.* imports from AWS SDK v2.x
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.TransferManagerBuilder;

/**
 * Uploads a file with the TransferManager.
 * @since 3.7
 * @deprecated This implementation uses AWS SDK for Java v1.x which is scheduled for end-of-support on December 31, 2025.
 *             It has been replaced with {@link MultipartUploader} which provides better performance and reliability.
 *             This class is maintained for backward compatibility with Java 21 but should not be used in new code.
 *             
 *             Note: In a full migration to AWS SDK for Java v2.x, this would be replaced with S3TransferManager
 *             and could leverage Java 21 Virtual Threads for improved I/O performance.
 */
@Deprecated
@Named("transfer-manager-uploader")
public class TransferManagerUploader
    implements S3Uploader
{
  /**
   * Uploads content to S3 using the AWS SDK v1.x TransferManager.
   * 
   * @param s3 The AmazonS3 client
   * @param bucket The S3 bucket name
   * @param key The S3 object key
   * @param contents The input stream containing the data to upload
   * @throws BlobStoreException if the upload fails
   */
  public void upload(final AmazonS3 s3, final String bucket, final String key, final InputStream contents) {
    try {
      // Create a TransferManager with the provided S3 client
      TransferManager transferManager = TransferManagerBuilder.standard().withS3Client(s3).build();
      
      // Upload the content and wait for completion
      transferManager.upload(bucket, key, contents, new ObjectMetadata())
          .waitForCompletion();
    } 
    // Using Java 21 pattern matching for exception handling
    catch (InterruptedException e) {
      // Restore the interrupted status as per best practices for handling InterruptedException
      Thread.currentThread().interrupt();
      
      // Use Java 21 string template for error message
      String errorMessage = STR."Error uploading blob to S3 bucket \{bucket} with key \{key}";
      throw new BlobStoreException(errorMessage, e, null);
    }
  }
}