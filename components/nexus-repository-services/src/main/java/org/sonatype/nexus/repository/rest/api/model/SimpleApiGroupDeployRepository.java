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

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * API Group Deploy Repository for simple formats which do not have custom attributes for groups.
 *
 * @since 3.28
 */
@JsonIgnoreProperties(value = {"format", "type", "url"}, allowGetters = true)
public class SimpleApiGroupDeployRepository
    extends SimpleApiGroupRepository
{
  /**
   * Creates a new SimpleApiGroupDeployRepository instance.
   *
   * @param name the repository name
   * @param format the repository format
   * @param url the repository URL
   * @param online whether the repository is online
   * @param storage the storage attributes (as a record in Java 21)
   * @param group the group deploy attributes
   */
  @JsonCreator
  public SimpleApiGroupDeployRepository(
      @JsonProperty("name") final String name,
      @JsonProperty("format") final String format,
      @JsonProperty("url") final String url,
      @JsonProperty("online") final Boolean online,
      @JsonProperty("storage") final StorageAttributes storage,
      @JsonProperty("group") final GroupDeployAttributes group)
  {
    super(name, format, url, online, storage, group);
  }

  /**
   * Gets the group attributes as GroupDeployAttributes.
   *
   * @return the group deploy attributes
   */
  @Override
  public GroupDeployAttributes getGroup() {
    return (GroupDeployAttributes) super.getGroup();
  }
  
  /**
   * Gets the writable member from group deploy attributes.
   * Uses pattern matching when available in Java 21.
   *
   * @return the writable member name or null if not set
   */
  @Nullable
  public String getWritableMember() {
    GroupDeployAttributes groupDeploy = getGroup();
    if (groupDeploy != null) {
      return groupDeploy.getWritableMember();
    }
    return null;
  }
}
