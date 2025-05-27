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

import java.util.Collection;

import jakarta.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * REST API record used to describe a group repository with deployment capabilities.
 *
 * @since 3.60
 */
public record GroupDeployAttributesRecord(
    @Schema(description = "Member repositories' names")
    Collection<String> memberNames,
    
    @Schema(description = "Pro-only: This field is for the Group Deployment feature available in NXRM Pro.")
    @Nullable
    @JsonInclude(Include.NON_NULL)
    String writableMember
) {
  @JsonCreator
  public GroupDeployAttributesRecord(
      @JsonProperty("memberNames") final Collection<String> memberNames,
      @JsonProperty("writableMember") @Nullable final String writableMember) {
    this.memberNames = memberNames;
    this.writableMember = writableMember;
  }

  /**
   * Convert from legacy GroupDeployAttributes to GroupDeployAttributesRecord
   *
   * @param attributes the legacy group deploy attributes
   * @return a new GroupDeployAttributesRecord with the same values
   */
  public static GroupDeployAttributesRecord from(GroupDeployAttributes attributes) {
    return new GroupDeployAttributesRecord(
        attributes.getMemberNames(),
        attributes.getWritableMember());
  }
}