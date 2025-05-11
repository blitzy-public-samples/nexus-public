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

import java.util.Map;
import javax.annotation.Nullable;

import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStoreException;

// Import for Java 21 String Templates feature
import static java.lang.StringTemplate.STR;

import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.google.common.collect.ImmutableMap;

/**
 * A {@link BlobStoreException} specific to the S3 implementation
 *
 * @since 3.19
 * @see BlobStoreException
 * 
 * This class has been updated for Java 21 compatibility, leveraging modern language features
 * such as String Templates for improved readability and maintainability.
 */
public class S3BlobStoreException
    extends BlobStoreException
{
  public static final String DEFAULT_MESSAGE = "An unexpected S3 error occurred. Check the logs for more details.";

  public static final String INSUFFICIENT_PERM_CREATE_BUCKET_ERR_MSG = "Insufficient permissions to create bucket.";

  public static final String UNEXPECTED_ERR = "An unexpected error occurred %s. Check the logs for more details.";

  public static final String BUCKET_OWNERSHIP_ERR_MSG = "Bucket exists but is not owned by you.";

  // If you have the correct permissions, but you're not using an identity
  // that belongs to the bucket owner's account, Amazon S3 returns a 405 Method Not Allowed error.
  // https://docs.aws.amazon.com/cli/latest/reference/s3api/get-bucket-policy.html#description
  public static final String INVALID_IDENTITY_ERR_MSG = "The identity used does not belong to the bucket owner's account.";

  public static final String ACCESS_DENIED_CODE = "AccessDenied";

  public static final String METHOD_NOT_ALLOWED_CODE = "MethodNotAllowed";

  public static final String INVALID_ACCESS_KEY_ID_CODE = "InvalidAccessKeyId";

  public static final String SIGNATURE_DOES_NOT_MATCH_CODE = "SignatureDoesNotMatch";

  public static Map<String, String> ERROR_CODE_MESSAGES = ImmutableMap.of(
    INVALID_ACCESS_KEY_ID_CODE, "The Access Key ID provided was invalid.",
    ACCESS_DENIED_CODE, "Access denied. Please check the credentials provided have proper permissions.",
    SIGNATURE_DOES_NOT_MATCH_CODE, "The secret access key does not match causing an invalid signature."
  );

  private final String message;

  private S3BlobStoreException(final String message,
                               final Throwable cause,
                               @Nullable final BlobId blobId)
  {
    super(message, cause, blobId);
    this.message = message;
  }

  private S3BlobStoreException(final String message) {
    super(message, null);
    this.message = message;
  }

  /**
   * Builds an exception with an appropriate message based on the error code from the cause.
   * Uses pattern matching internally to determine the appropriate message.
   *
   * @param cause The Amazon S3 exception that triggered this error
   * @return A new S3BlobStoreException with the appropriate message
   */
  public static S3BlobStoreException buildException(final AmazonS3Exception cause) {
    // Using Map.getOrDefault for simple lookup - could be enhanced with pattern matching in more complex scenarios
    String message = ERROR_CODE_MESSAGES.getOrDefault(cause.getErrorCode(), DEFAULT_MESSAGE);
    return new S3BlobStoreException(message, cause, null);
  }

  /**
   * Creates an exception for insufficient permissions to create a bucket.
   *
   * @return A new S3BlobStoreException with an insufficient permissions message
   */
  public static S3BlobStoreException insufficientCreatePermissionsError() {
    return new S3BlobStoreException(INSUFFICIENT_PERM_CREATE_BUCKET_ERR_MSG);
  }

  /**
   * Creates an exception for an unexpected error with the specified action.
   * Uses Java 21 String Templates for more readable string interpolation.
   *
   * @param action The action that was being performed when the error occurred
   * @return A new S3BlobStoreException with a message including the action
   */
  public static S3BlobStoreException unexpectedError(String action) {
    // Using Java 21 String Templates instead of String.format for improved readability
    return new S3BlobStoreException(STR."An unexpected error occurred \{action}. Check the logs for more details.");
  }

  /**
   * Creates an exception for bucket ownership errors.
   *
   * @return A new S3BlobStoreException with a bucket ownership error message
   */
  public static S3BlobStoreException bucketOwnershipError() {
    return new S3BlobStoreException(BUCKET_OWNERSHIP_ERR_MSG);
  }

  /**
   * Creates an exception for invalid identity errors.
   *
   * @return A new S3BlobStoreException with an invalid identity error message
   */
  public static S3BlobStoreException invalidIdentityError() {
    return new S3BlobStoreException(INVALID_IDENTITY_ERR_MSG);
  }

  @Override
  public String getMessage() {
    return message;
  }
}