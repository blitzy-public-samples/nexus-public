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
 * Encapsulates S3 endpoint url, signer type and whether path-style access should be enabled for the specified S3
 * endpoint url.
 *
 * This class has been updated to use Java 21 record patterns for improved data handling and immutability.
 * Pattern matching can be used with this record for more concise code when extracting component values.
 *
 * @since 3.20
 */
@JsonInclude(NON_NULL)
public record S3BlobStoreApiAdvancedBucketConnection(
    @Schema(description = "A custom endpoint URL for third party object stores using the S3 API.")
    @JsonProperty("endpoint")
    String endpoint,

    @Schema(description = "An API signature version which may be required for third party object stores using the S3 API.")
    @JsonProperty("signerType")
    String signerType,

    @Schema(description = "Setting this flag will result in path-style access being used for all requests.")
    @JsonProperty("forcePathStyle")
    Boolean forcePathStyle,

    @Schema(description = "Setting this value will override the default connection pool size of Nexus of the s3 client for this blobstore.")
    @JsonProperty("maxConnectionPoolSize")
    Integer maxConnectionPoolSize
) {
    /**
     * Constructor for Jackson deserialization.
     * 
     * @param endpoint The custom endpoint URL
     * @param signerType The API signature version
     * @param forcePathStyle Whether to use path-style access
     * @param maxConnectionPoolSize The maximum connection pool size
     */
    @JsonCreator
    public S3BlobStoreApiAdvancedBucketConnection {
        // Compact constructor for validation if needed in the future
    }
}