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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonAnyGetter;

/**
 * Component transfer object for REST APIs.
 * 
 * Leverages Java 21's Record Patterns for type-safe and memory-efficient data representation.
 */
public class DefaultComponentXO
    implements ComponentXO
{
  /**
   * Internal record to hold component data in a type-safe and memory-efficient way.
   * This enables the use of Record Patterns for data manipulation.
   */
  private record ComponentData(
      String id,
      String group,
      String name,
      String version,
      String repository,
      String format,
      List<AssetXO> assets) {}

  private ComponentData data;

  public DefaultComponentXO() {
    this.data = new ComponentData(null, null, null, null, null, null, null);
  }

  public DefaultComponentXO(
      String id,
      String group,
      String name,
      String version,
      String repository,
      String format,
      List<AssetXO> assets)
  {
    this.data = new ComponentData(id, group, name, version, repository, format, assets);
  }

  @Override
  public String getId() {
    if (data instanceof ComponentData(var id, var _, var _, var _, var _, var _, var _)) {
      return id;
    }
    return null;
  }

  @Override
  public void setId(String id) {
    if (data instanceof ComponentData(var _, var group, var name, var version, var repository, var format, var assets)) {
      this.data = new ComponentData(id, group, name, version, repository, format, assets);
    }
  }

  @Override
  public String getGroup() {
    if (data instanceof ComponentData(var _, var group, var _, var _, var _, var _, var _)) {
      return group;
    }
    return null;
  }

  @Override
  public void setGroup(String group) {
    if (data instanceof ComponentData(var id, var _, var name, var version, var repository, var format, var assets)) {
      this.data = new ComponentData(id, group, name, version, repository, format, assets);
    }
  }

  @Override
  public String getName() {
    if (data instanceof ComponentData(var _, var _, var name, var _, var _, var _, var _)) {
      return name;
    }
    return null;
  }

  @Override
  public void setName(String name) {
    if (data instanceof ComponentData(var id, var group, var _, var version, var repository, var format, var assets)) {
      this.data = new ComponentData(id, group, name, version, repository, format, assets);
    }
  }

  @Override
  public String getVersion() {
    if (data instanceof ComponentData(var _, var _, var _, var version, var _, var _, var _)) {
      return version;
    }
    return null;
  }

  @Override
  public void setVersion(String version) {
    if (data instanceof ComponentData(var id, var group, var name, var _, var repository, var format, var assets)) {
      this.data = new ComponentData(id, group, name, version, repository, format, assets);
    }
  }

  @Override
  public String getRepository() {
    if (data instanceof ComponentData(var _, var _, var _, var _, var repository, var _, var _)) {
      return repository;
    }
    return null;
  }

  @Override
  public void setRepository(String repository) {
    if (data instanceof ComponentData(var id, var group, var name, var version, var _, var format, var assets)) {
      this.data = new ComponentData(id, group, name, version, repository, format, assets);
    }
  }

  @Override
  public String getFormat() {
    if (data instanceof ComponentData(var _, var _, var _, var _, var _, var format, var _)) {
      return format;
    }
    return null;
  }

  @Override
  public void setFormat(String format) {
    if (data instanceof ComponentData(var id, var group, var name, var version, var repository, var _, var assets)) {
      this.data = new ComponentData(id, group, name, version, repository, format, assets);
    }
  }

  @Override
  public List<AssetXO> getAssets() {
    if (data instanceof ComponentData(var _, var _, var _, var _, var _, var _, var assets)) {
      return assets;
    }
    return null;
  }

  @Override
  public void setAssets(List<AssetXO> assets) {
    if (data instanceof ComponentData(var id, var group, var name, var version, var repository, var format, var _)) {
      this.data = new ComponentData(id, group, name, version, repository, format, assets);
    }
  }

  @Override
  @JsonAnyGetter
  public Map<String, Object> getExtraJsonAttributes() {
    return Collections.emptyMap();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    
    // Use record pattern to extract id for comparison
    DefaultComponentXO that = (DefaultComponentXO) o;
    if (this.data instanceof ComponentData(var thisId, var _, var _, var _, var _, var _, var _) &&
        that.data instanceof ComponentData(var thatId, var _, var _, var _, var _, var _, var _)) {
      return Objects.equals(thisId, thatId);
    }
    return false;
  }

  @Override
  public int hashCode() {
    // Use record pattern to extract id for hash code calculation
    if (data instanceof ComponentData(var id, var _, var _, var _, var _, var _, var _)) {
      return Objects.hash(id);
    }
    return 0;
  }

  @Override
  public String toString() {
    // Use record pattern to extract all fields for string representation
    if (data instanceof ComponentData(var id, var group, var name, var version, var repository, var format, var assets)) {
      return "DefaultComponentXO{" +
          "id='" + id + '\'' +
          ", group='" + group + '\'' +
          ", name='" + name + '\'' +
          ", version='" + version + '\'' +
          ", repository='" + repository + '\'' +
          ", format='" + format + '\'' +
          ", assets=" + assets +
          "}";
    }
    return "DefaultComponentXO{data=null}";
  }

  public static DefaultComponentXOBuilder builder() {
    return new DefaultComponentXOBuilder();
  }

  /**
   * Builder pattern implementation updated to work with Record Patterns.
   * Uses the ComponentData record internally for type-safe data representation.
   */
  public static class DefaultComponentXOBuilder
  {
    private String id;
    private String group;
    private String name;
    private String version;
    private String repository;
    private String format;
    private List<AssetXO> assets;

    public DefaultComponentXOBuilder id(String id) {
      this.id = id;
      return this;
    }

    public DefaultComponentXOBuilder group(String group) {
      this.group = group;
      return this;
    }

    public DefaultComponentXOBuilder name(String name) {
      this.name = name;
      return this;
    }

    public DefaultComponentXOBuilder version(String version) {
      this.version = version;
      return this;
    }

    public DefaultComponentXOBuilder repository(String repository) {
      this.repository = repository;
      return this;
    }

    public DefaultComponentXOBuilder format(String format) {
      this.format = format;
      return this;
    }

    public DefaultComponentXOBuilder assets(List<AssetXO> assets) {
      this.assets = assets;
      return this;
    }

    public DefaultComponentXO build() {
      return new DefaultComponentXO(id, group, name, version, repository, format, assets);
    }
  }
}