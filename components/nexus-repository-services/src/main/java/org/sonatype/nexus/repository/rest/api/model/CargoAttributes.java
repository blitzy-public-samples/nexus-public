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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModelProperty;

/**
 * Record representing Cargo repository attributes.
 */
public record CargoAttributes(
    @ApiModelProperty(value = "Indicates if this repository requires authentication overriding anonymous access.",
        example = "false")
    @JsonProperty(REQUIRE_AUTHENTICATION)
    Boolean requireAuthentication)
{
  public static final String REQUIRE_AUTHENTICATION = "requireAuthentication";

  /**
   * Constructor for Jackson deserialization.
   */
  @JsonCreator
  public CargoAttributes(@JsonProperty(REQUIRE_AUTHENTICATION) Boolean requireAuthentication) {
    this.requireAuthentication = requireAuthentication;
  }
}