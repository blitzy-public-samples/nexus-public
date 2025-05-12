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
package org.sonatype.nexus.repository.apt.rest;

import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModelProperty;
import javax.validation.constraints.NotEmpty;

/**
 * Data Transfer Object for APT proxy repository attributes.
 * 
 * @since 3.20
 * @see java.lang.Record
 * @see java.lang.Record#components()
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AptProxyRepositoriesAttributes(
    @ApiModelProperty(value = "Distribution to fetch", example = "bionic")
    @JsonProperty("distribution")
    @NotEmpty
    String distribution,
    
    @ApiModelProperty(value = "Whether this repository is flat", example = "false")
    @JsonProperty("flat")
    @NotNull
    Boolean flat
) {
  // Java 21 Record provides automatic implementations of:
  // - Constructor
  // - Accessor methods (distribution() and flat())
  // - equals(), hashCode(), and toString()
}
