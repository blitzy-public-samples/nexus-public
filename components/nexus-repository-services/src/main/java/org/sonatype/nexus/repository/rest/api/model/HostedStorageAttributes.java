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

import javax.validation.constraints.NotNull;
import javax.validation.constraints.NotEmpty;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModelProperty;

/**
 * REST API model for describing storage of hosted repositories.
 *
 * @since 3.20
 */
public record HostedStorageAttributes(
    @ApiModelProperty(value = "Blob store used to store repository contents", example = "default", required = true)
    @NotEmpty
    @JsonProperty("blobStoreName")
    String blobStoreName,

    @ApiModelProperty(value = "Whether to validate uploaded content's MIME type appropriate for the repository format",
        example = "true")
    @NotNull
    @JsonProperty("strictContentTypeValidation")
    Boolean strictContentTypeValidation,

    @ApiModelProperty(value = "Controls if deployments of and updates to assets are allowed",
        allowableValues = "allow,allow_once,deny",
        example = "allow_once")
    @NotNull
    @JsonProperty("writePolicy")
    String writePolicy
) {
  @JsonCreator
  public HostedStorageAttributes {
    // The canonical constructor is automatically generated with validation
  }
}