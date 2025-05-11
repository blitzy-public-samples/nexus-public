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
package org.sonatype.nexus.blobstore.s3.rest.internal.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;

/**
 * Encapsulates the IAM settings to use for accessing an s3 blob store.
 * 
 * This class is designed to be compatible with Java 21 record pattern matching while
 * maintaining backward compatibility for serialization. It is not implemented as a full
 * record due to the need for mutable fields (secretAccessKey and sessionToken).
 *
 * @since 3.20
 */
@JsonInclude(NON_NULL)
public class S3BlobStoreApiBucketSecurity
{
  @Schema(description = "An IAM access key ID for granting access to the S3 bucket")
  private final String accessKeyId;

  @Schema(description = "The secret access key associated with the specified IAM access key ID")
  private String secretAccessKey;

  @Schema(description = "An IAM role to assume in order to access the S3 bucket")
  private final String role;

  @Schema(description = "An AWS STS session token associated with temporary security credentials which grant access to the S3 bucket")
  private String sessionToken;

  /**
   * Creates a new instance with the specified IAM settings.
   *
   * @param accessKeyId The IAM access key ID for granting access to the S3 bucket
   * @param secretAccessKey The secret access key associated with the specified IAM access key ID
   * @param role An IAM role to assume in order to access the S3 bucket
   * @param sessionToken An AWS STS session token associated with temporary security credentials
   */
  @JsonCreator
  public S3BlobStoreApiBucketSecurity(
      @JsonProperty("accessKeyId") final String accessKeyId,
      @JsonProperty("secretAccessKey") final String secretAccessKey,
      @JsonProperty("role") final String role,
      @JsonProperty("sessionToken") final String sessionToken)
  {
    this.accessKeyId = accessKeyId;
    this.secretAccessKey = secretAccessKey;
    this.role = role;
    this.sessionToken = sessionToken;
  }

  /**
   * @return The IAM access key ID for granting access to the S3 bucket
   */
  public String getAccessKeyId() {
    return accessKeyId;
  }

  /**
   * @return The secret access key associated with the specified IAM access key ID
   */
  public String getSecretAccessKey() {
    return secretAccessKey;
  }

  /**
   * @return The IAM role to assume in order to access the S3 bucket
   */
  public String getRole() {
    return role;
  }

  /**
   * @return The AWS STS session token associated with temporary security credentials
   */
  public String getSessionToken() {
    return sessionToken;
  }

  /**
   * Sets the secret access key associated with the specified IAM access key ID.
   * 
   * @param secretAccessKey The secret access key to set
   */
  public void setSecretAccessKey(final String secretAccessKey) {
    this.secretAccessKey = secretAccessKey;
  }

  /**
   * Sets the AWS STS session token associated with temporary security credentials.
   * 
   * @param sessionToken The session token to set
   */
  public void setSessionToken(final String sessionToken) {
    this.sessionToken = sessionToken;
  }
  
  /**
   * Pattern matching helper method for use with Java 21 record patterns.
   * This method allows this class to be used with pattern matching constructs
   * even though it's not a true record type.
   *
   * @param obj The object to match against this class pattern
   * @param accessKeyIdConsumer Consumer for the accessKeyId field
   * @param secretAccessKeyConsumer Consumer for the secretAccessKey field
   * @param roleConsumer Consumer for the role field
   * @param sessionTokenConsumer Consumer for the sessionToken field
   * @return true if the object matches this class pattern, false otherwise
   * @since Java 21
   */
  public static boolean patternMatch(
      Object obj,
      java.util.function.Consumer<String> accessKeyIdConsumer,
      java.util.function.Consumer<String> secretAccessKeyConsumer,
      java.util.function.Consumer<String> roleConsumer,
      java.util.function.Consumer<String> sessionTokenConsumer) {
    
    if (!(obj instanceof S3BlobStoreApiBucketSecurity security)) {
      return false;
    }
    
    accessKeyIdConsumer.accept(security.getAccessKeyId());
    secretAccessKeyConsumer.accept(security.getSecretAccessKey());
    roleConsumer.accept(security.getRole());
    sessionTokenConsumer.accept(security.getSessionToken());
    
    return true;
  }
}