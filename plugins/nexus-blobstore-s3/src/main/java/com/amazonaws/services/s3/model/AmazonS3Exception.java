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
package com.amazonaws.services.s3.model;

import com.amazonaws.SdkBaseException;

/**
 * Exception thrown when an error occurs in the Amazon S3 service.
 * This class is designed to maintain compatibility with AWS SDK for Java 1.x
 * while using AWS SDK for Java 2.x internally.
 *
 * @since 3.19
 */
public class AmazonS3Exception
    extends SdkBaseException
{
  private String errorCode;

  /**
   * Constructs a new AmazonS3Exception with the specified message.
   *
   * @param message the error message
   */
  public AmazonS3Exception(String message) {
    super(message);
  }

  /**
   * Constructs a new AmazonS3Exception with the specified message and cause.
   *
   * @param message the error message
   * @param cause the cause of the exception
   */
  public AmazonS3Exception(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Gets the error code associated with this exception.
   *
   * @return the error code
   */
  public String getErrorCode() {
    return errorCode;
  }

  /**
   * Sets the error code associated with this exception.
   *
   * @param errorCode the error code
   */
  public void setErrorCode(String errorCode) {
    this.errorCode = errorCode;
  }

  /**
   * Gets the AWS error details associated with this exception.
   *
   * @return the AWS error details
   */
  public AwsErrorDetails awsErrorDetails() {
    return new AwsErrorDetails(errorCode);
  }

  /**
   * Class representing AWS error details.
   */
  public static class AwsErrorDetails {
    private final String errorCode;

    /**
     * Constructs a new AwsErrorDetails with the specified error code.
     *
     * @param errorCode the error code
     */
    public AwsErrorDetails(String errorCode) {
      this.errorCode = errorCode;
    }

    /**
     * Gets the error code associated with this error.
     *
     * @return the error code
     */
    public String errorCode() {
      return errorCode;
    }
  }
}