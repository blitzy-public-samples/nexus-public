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

import org.sonatype.nexus.repository.types.GroupType;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModelProperty;

/**
 * API Group Repository for simple formats which do not have custom attributes for groups.
 *
 * @since 3.20
 */
@JsonIgnoreProperties(value = {"format", "type", "url"}, allowGetters = true)
public class SimpleApiGroupRepository
    extends AbstractApiRepository
{
  @ApiModelProperty
  @NotNull
  protected final StorageAttributes storage;

  @ApiModelProperty
  @NotNull
  protected final GroupAttributes group;

  @JsonCreator
  public SimpleApiGroupRepository(
      @JsonProperty("name") final String name,
      @JsonProperty("format") final String format,
      @JsonProperty("url") final String url,
      @JsonProperty("online") final Boolean online,
      @JsonProperty("storage") final StorageAttributes storage,
      @JsonProperty("group") final GroupAttributes group)
  {
    super(name, format, GroupType.NAME, url, online);
    this.storage = storage;
    this.group = group;
  }

  /**
   * Gets the storage attributes using record pattern matching in Java 21.
   *
   * @return the storage attributes
   */
  public StorageAttributes getStorage() {
    return storage;
  }

  /**
   * Gets the group attributes using record pattern matching in Java 21.
   *
   * @return the group attributes
   */
  public GroupAttributes getGroup() {
    return group;
  }
  
  /**
   * Utility method to extract storage blob store name using record pattern matching.
   * 
   * @return the blob store name from storage attributes
   */
  public String getBlobStoreName() {
    if (storage instanceof StorageAttributes(String blobStoreName, var _)) {
      return blobStoreName;
    }
    return storage.blobStoreName();
  }
  
  /**
   * Utility method to extract member names using record pattern matching.
   * 
   * @return the member names from group attributes
   */
  public java.util.Collection<String> getMemberNames() {
    if (group instanceof GroupAttributes(var memberNames)) {
      return memberNames;
    }
    return group.memberNames();
  }
}
