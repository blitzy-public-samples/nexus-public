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
 * Encapsulates the encryption type and key to use for encrypting an s3 blob store at rest i.e AWS S3 server side
 * encryption.
 *
 * @since 3.20
 * @since Java 21 - Converted to record for improved immutability and conciseness
 */
@JsonInclude(NON_NULL)
public record S3BlobStoreApiEncryption(
    @Schema(description = "The type of S3 server side encryption to use.",
        allowableValues = "s3ManagedEncryption,kmsManagedEncryption")
    @JsonProperty("encryptionType")
    String encryptionType,

    @Schema(description = "The encryption key.")
    @JsonProperty("encryptionKey")
    String encryptionKey
) {
  /**
   * Constructor with explicit property names for Jackson deserialization.
   * 
   * @param encryptionType The type of S3 server side encryption to use
   * @param encryptionKey The encryption key
   */
  @JsonCreator
  public S3BlobStoreApiEncryption {
    // Record compact constructor - validation could be added here if needed
  }
}