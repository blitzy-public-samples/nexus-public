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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * REST API model for group deploy repository requests.
 * 
 * @since 3.28
 */
@JsonIgnoreProperties({"type"})
public class GroupDeployRepositoryApiRequest
    extends GroupRepositoryApiRequest
{
  /**
   * Creates a new GroupDeployRepositoryApiRequest.
   *
   * @param name the repository name
   * @param format the repository format
   * @param online whether the repository is online
   * @param storage the storage attributes
   * @param group the group deploy attributes
   */
  public GroupDeployRepositoryApiRequest(
      @JsonProperty("name") final String name,
      @JsonProperty("format") final String format,
      @JsonProperty("online") final Boolean online,
      @JsonProperty("storage") final StorageAttributes storage,
      @JsonProperty("group") final GroupDeployAttributes group)
  {
    super(name, format, online, storage, group);
  }

  /**
   * Returns the group deploy attributes for this repository.
   *
   * @return the group deploy attributes record
   */
  @Override
  public GroupDeployAttributes getGroup() {
    return (GroupDeployAttributes) super.getGroup();
  }
  
  /**
   * Extracts the writable member from the group deploy attributes using record pattern matching.
   * 
   * @return the writable member name or null if not set
   */
  @Nullable
  public String getWritableMember() {
    if (getGroup() instanceof GroupDeployAttributes(var memberNames, var writableMember)) {
      return writableMember;
    }
    return null;
  }
  
  /**
   * Extracts the member names from the group deploy attributes using record pattern matching.
   * 
   * @return the collection of member names
   */
  public Collection<String> getMemberNames() {
    if (getGroup() instanceof GroupDeployAttributes(var memberNames, var _)) {
      return memberNames;
    }
    return getGroup().getMemberNames();
  }
  
  /**
   * Checks if this repository has a specific writable member using record pattern matching.
   * 
   * @param memberName the member name to check
   * @return true if this repository has the specified writable member
   */
  public boolean hasWritableMember(String memberName) {
    return getGroup() instanceof GroupDeployAttributes(var _, var writableMember) && 
           memberName.equals(writableMember);
  }
}