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

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModelProperty;

/**
 * REST API model for describing HTTP connection properties for proxy repositories.
 *
 * @since 3.20
 */
public record HttpClientAttributes(
  @ApiModelProperty(value = "Whether to block outbound connections on the repository", example = "false")
  @NotNull
  @JsonProperty("blocked")
  Boolean blocked,

  @ApiModelProperty(
      value = "Whether to auto-block outbound connections if remote peer is detected as unreachable/unresponsive",
      example = "true")
  @NotNull
  @JsonProperty("autoBlock")
  Boolean autoBlock,

  @Valid
  @JsonProperty("connection")
  HttpClientConnectionAttributes connection,

  @Valid
  @JsonProperty("authentication")
  HttpClientConnectionAuthenticationAttributes authentication
) {
  @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
  public HttpClientAttributes {
    // The canonical constructor is automatically generated with validation
  }
}
