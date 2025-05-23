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
package org.sonatype.nexus.repository.rest.api.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotEmpty;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * REST API record describing a repository's storage settings.
 *
 * @since 3.60
 */
public record StorageAttributesRecord(
    @Schema(description = "Blob store used to store repository contents", example = "default", required = true)
    @NotEmpty
    String blobStoreName,

    @Schema(description = "Whether to validate uploaded content's MIME type appropriate for the repository format", example = "true")
    @NotNull
    Boolean strictContentTypeValidation
) {
  @JsonCreator
  public StorageAttributesRecord(
      @JsonProperty("blobStoreName") final String blobStoreName,
      @JsonProperty("strictContentTypeValidation") final Boolean strictContentTypeValidation) {
    this.blobStoreName = blobStoreName;
    this.strictContentTypeValidation = strictContentTypeValidation;
  }

  /**
   * Convert from legacy StorageAttributes to StorageAttributesRecord
   *
   * @param attributes the legacy storage attributes
   * @return a new StorageAttributesRecord with the same values
   */
  public static StorageAttributesRecord from(StorageAttributes attributes) {
    return new StorageAttributesRecord(
        attributes.getBlobStoreName(),
        attributes.getStrictContentTypeValidation());
  }
}