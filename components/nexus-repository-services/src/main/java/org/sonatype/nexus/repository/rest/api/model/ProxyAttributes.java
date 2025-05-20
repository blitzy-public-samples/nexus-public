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

import org.sonatype.nexus.validation.constraint.UriString;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModelProperty;

/**
 * REST API model describing a proxy repository.
 *
 * @since 3.20
 */
public record ProxyAttributes(
    @ApiModelProperty(value = "Location of the remote repository being proxied", example = "https://remote.repository.com")
    @UriString
    @NotEmpty
    @JsonProperty("remoteUrl")
    String remoteUrl,

    @ApiModelProperty(value = "How long to cache artifacts before rechecking the remote repository (in minutes)",
        example = "1440")
    @NotNull
    @JsonProperty("contentMaxAge")
    Integer contentMaxAge,

    @ApiModelProperty(value = "How long to cache metadata before rechecking the remote repository (in minutes)",
        example = "1440")
    @NotNull
    @JsonProperty("metadataMaxAge")
    Integer metadataMaxAge
) {
    @JsonCreator
    public ProxyAttributes {
        // The canonical constructor is automatically generated with validation
    }
}