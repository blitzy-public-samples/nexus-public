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
package org.sonatype.nexus.repository.rest.api;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Interface for component transfer objects in the REST API.
 * 
 * Implementations use Java 21's Record Patterns for more memory-efficient and type-safe data handling
 * while maintaining backward compatibility with existing code.
 *
 * @since 3.8
 */
@JsonPropertyOrder({"id", "repository", "format", "group", "name", "version", "assets"})
public interface ComponentXO
{
  /**
   * @return the component id
   */
  String getId();

  /**
   * @param id the component id to set
   */
  void setId(String id);

  /**
   * @return the component group
   */
  String getGroup();

  /**
   * @param group the component group to set
   */
  void setGroup(String group);

  /**
   * @return the component name
   */
  String getName();

  /**
   * @param name the component name to set
   */
  void setName(String name);

  /**
   * @return the component version
   */
  String getVersion();

  /**
   * @param version the component version to set
   */
  void setVersion(String version);

  /**
   * @return the repository name
   */
  String getRepository();

  /**
   * @param repository the repository name to set
   */
  void setRepository(String repository);

  /**
   * @return the component format
   */
  String getFormat();

  /**
   * @param format the component format to set
   */
  void setFormat(String format);

  /**
   * @return the list of assets associated with this component
   */
  List<AssetXO> getAssets();

  /**
   * @param assets the list of assets to set
   */
  void setAssets(List<AssetXO> assets);

  /**
   * Attributes to add to the JSON payload.
   * Implementations may use Record Patterns to efficiently extract these attributes.
   *
   * @return a map of additional attributes to include in the JSON
   */
  @JsonAnyGetter
  Map<String, Object> getExtraJsonAttributes();
}