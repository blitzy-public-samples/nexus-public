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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModelProperty;

/**
 * REST API model which describes replication capabilities for a repository.
 */
public record ReplicationAttributes(
    @ApiModelProperty(value = "Whether pre-emptive pull is enabled", example = "false")
    @NotNull
    @JsonProperty("preemptivePullEnabled") Boolean preemptivePullEnabled,
    
    @ApiModelProperty(value = "Regular Expression of Asset Paths to pull pre-emptively pull")
    @JsonProperty("assetPathRegex") String assetPathRegex)
{
    @JsonCreator
    public ReplicationAttributes(
        @JsonProperty("preemptivePullEnabled") Boolean preemptivePullEnabled,
        @JsonProperty("assetPathRegex") String assetPathRegex) {
        // The record automatically assigns parameters to components
    }
}