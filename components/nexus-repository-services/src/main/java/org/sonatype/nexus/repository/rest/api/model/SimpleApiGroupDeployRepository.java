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

import jakarta.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * API Group Deploy Repository for simple formats which do not have custom attributes for groups.
 *
 * @since 3.28
 */
@JsonIgnoreProperties(value = {"format", "type", "url"}, allowGetters = true)
public class SimpleApiGroupDeployRepository
    extends SimpleApiGroupRepository
{
  @Schema(description = "Group deploy attributes")
  protected final GroupDeployAttributesRecord groupDeploy;
  
  @JsonCreator
  public SimpleApiGroupDeployRepository(
      @JsonProperty("name") final String name,
      @JsonProperty("format") final String format,
      @JsonProperty("url") final String url,
      @JsonProperty("online") final Boolean online,
      @JsonProperty("storage") final StorageAttributesRecord storage,
      @JsonProperty("group") final GroupDeployAttributesRecord group)
  {
    super(name, format, url, online, storage, group);
    this.groupDeploy = group;
  }

  /**
   * Constructor that accepts legacy attribute types and converts them to records
   */
  public SimpleApiGroupDeployRepository(
      final String name,
      final String format,
      final String url,
      final Boolean online,
      final StorageAttributes storage,
      final GroupDeployAttributes group)
  {
    super(name, format, url, online, storage, group);
    this.groupDeploy = GroupDeployAttributesRecord.from(group);
  }

  @Override
  public GroupDeployAttributesRecord getGroup() {
    return groupDeploy;
  }
  
  /**
   * Gets the writable member using Record Pattern matching
   * 
   * @return the writable member name or null if not set
   */
  @Nullable
  public String getWritableMember() {
    if (groupDeploy instanceof GroupDeployAttributesRecord(var ignored, String writableMember)) {
      return writableMember;
    }
    return null;
  }
}