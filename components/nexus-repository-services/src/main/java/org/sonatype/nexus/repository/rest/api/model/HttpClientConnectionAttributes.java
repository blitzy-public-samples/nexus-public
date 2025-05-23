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

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModelProperty;

/**
 * REST API model specifying the HTTP connection used by a proxy repository.
 *
 * @since 3.20
 */
public record HttpClientConnectionAttributes(
  @ApiModelProperty(value = "Total retries if the initial connection attempt suffers a timeout", example = "0",
      allowableValues = "range[0,10]")
  @Min(0L)
  @Max(10L)
  @JsonProperty("retries")
  Integer retries,

  @ApiModelProperty(value = "Custom fragment to append to User-Agent header in HTTP requests", example = "")
  @JsonProperty("userAgentSuffix")
  String userAgentSuffix,

  @ApiModelProperty(value = "Seconds to wait for activity before stopping and retrying the connection", example = "60",
      allowableValues = "range[1,3600]")
  @Min(1L)
  @Max(3600L)
  @JsonProperty("timeout")
  Integer timeout,

  @ApiModelProperty(value = "Whether to enable redirects to the same location (may be required by some servers)",
      example = "false")
  @JsonProperty("enableCircularRedirects")
  Boolean enableCircularRedirects,

  @ApiModelProperty(value = "Whether to allow cookies to be stored and used", example = "false")
  @JsonProperty("enableCookies")
  Boolean enableCookies,

  @ApiModelProperty(value = "Use certificates stored in the Nexus Repository Manager truststore to connect to external systems",
      example = "false")
  @JsonProperty("useTrustStore")
  Boolean useTrustStore
) {
  @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
  public HttpClientConnectionAttributes {
    // The canonical constructor is automatically generated with validation
  }
}
