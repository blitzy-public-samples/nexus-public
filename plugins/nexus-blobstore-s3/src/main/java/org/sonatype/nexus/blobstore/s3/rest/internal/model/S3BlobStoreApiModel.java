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

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.sonatype.nexus.blobstore.rest.BlobStoreApiSoftQuota;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.AccessMode;

import static org.sonatype.nexus.blobstore.s3.internal.S3BlobStore.TYPE;

/**
 * Encapsulates the API payload for creating, reading and updating an S3 blob store.
 * 
 * This class is implemented as a Java Record for immutability and concise data handling.
 * Compatible with Java 21 record patterns for efficient destructuring.
 * 
 * Example usage with Java 21 record patterns:
 * <pre>
 * {@code
 * // Destructuring with record pattern in instanceof
 * if (model instanceof S3BlobStoreApiModel(var name, var quota, var config)) {
 *     // Direct access to components without getter calls
 *     processName(name);
 *     processQuota(quota);
 *     processConfig(config);
 * }
 * 
 * // Destructuring with record pattern in switch
 * String result = switch(model) {
 *     case S3BlobStoreApiModel(String name, BlobStoreApiSoftQuota quota, var config) ->
 *         STR."S3 Blob Store: \{name} with quota type \{quota.getType()}";
 *     default -> "Unknown model";
 * };
 * }
 * </pre>
 *
 * @since 3.20
 */
public record S3BlobStoreApiModel(
    @NotNull
    @Schema(description = "The name of the S3 blob store.", example = "s3", requiredMode = Schema.RequiredMode.REQUIRED)
    String name,

    @Schema(description = "Settings to control the soft quota.")
    BlobStoreApiSoftQuota softQuota,

    @Valid
    @NotNull
    @Schema(description = "The S3 specific configuration details for the S3 object that'll contain the blob store.", 
           requiredMode = Schema.RequiredMode.REQUIRED)
    S3BlobStoreApiBucketConfiguration bucketConfiguration
) {
    /**
     * Constructor with JsonCreator annotation for Jackson deserialization.
     * 
     * @param name The name of the S3 blob store
     * @param softQuota Settings to control the soft quota
     * @param bucketConfiguration The S3 specific configuration details
     */
    @JsonCreator
    public S3BlobStoreApiModel(
        @JsonProperty("name") final String name,
        @JsonProperty("softQuota") final BlobStoreApiSoftQuota softQuota,
        @JsonProperty("bucketConfiguration") final S3BlobStoreApiBucketConfiguration bucketConfiguration) {
        this(name, softQuota, bucketConfiguration);
    }

    /**
     * Returns the blob store type.
     * 
     * @return The blob store type, always "s3"
     */
    @Schema(description = STR."The blob store type (always \{TYPE}).", accessMode = AccessMode.READ_ONLY, example = TYPE)
    public String getType() {
        return TYPE;
    }
}