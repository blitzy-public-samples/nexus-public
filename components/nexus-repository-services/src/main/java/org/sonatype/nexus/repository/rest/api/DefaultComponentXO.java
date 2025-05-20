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
 */
public class DefaultComponentXO
    implements ComponentXO
{
  /**
   * Immutable record representing component data.
   */
  private record ComponentData(
      String id,
      String group,
      String name,
      String version,
      String repository,
      String format,
      List<AssetXO> assets
  ) {}

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
    return data.id();
  }

  @Override
  public void setId(String id) {
    this.data = new ComponentData(id, data.group(), data.name(), data.version(), 
        data.repository(), data.format(), data.assets());
  }

  @Override
  public String getGroup() {
    return data.group();
  }

  @Override
  public void setGroup(String group) {
    this.data = new ComponentData(data.id(), group, data.name(), data.version(), 
        data.repository(), data.format(), data.assets());
  }

  @Override
  public String getName() {
    return data.name();
  }

  @Override
  public void setName(String name) {
    this.data = new ComponentData(data.id(), data.group(), name, data.version(), 
        data.repository(), data.format(), data.assets());
  }

  @Override
  public String getVersion() {
    return data.version();
  }

  @Override
  public void setVersion(String version) {
    this.data = new ComponentData(data.id(), data.group(), data.name(), version, 
        data.repository(), data.format(), data.assets());
  }

  @Override
  public String getRepository() {
    return data.repository();
  }

  @Override
  public void setRepository(String repository) {
    this.data = new ComponentData(data.id(), data.group(), data.name(), data.version(), 
        repository, data.format(), data.assets());
  }

  @Override
  public String getFormat() {
    return data.format();
  }

  @Override
  public void setFormat(String format) {
    this.data = new ComponentData(data.id(), data.group(), data.name(), data.version(), 
        data.repository(), format, data.assets());
  }

  @Override
  public List<AssetXO> getAssets() {
    return data.assets();
  }

  @Override
  public void setAssets(List<AssetXO> assets) {
    this.data = new ComponentData(data.id(), data.group(), data.name(), data.version(), 
        data.repository(), data.format(), assets);
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
    
    // Using record pattern matching for type-safe comparison
    if (o instanceof DefaultComponentXO other) {
      return Objects.equals(data.id(), other.data.id());
    }
    return false;
  }

  @Override
  public int hashCode() {
    return Objects.hash(data.id());
  }

  @Override
  public String toString() {
    // Using record pattern matching for string representation
    ComponentData(String id, String group, String name, String version, 
                 String repository, String format, List<AssetXO> assets) = data;
    
    return "DefaultComponentXO{" +
        "id='" + id + '\'' +
        ", group='" + group + '\'' +
        ", name='" + name + '\'' +
        ", version='" + version + '\'' +
        ", repository='" + repository + '\'' +
        ", format='" + format + '\'' +
        ", assets=" + assets +
        '}';
  }

  public static DefaultComponentXOBuilder builder() {
    return new DefaultComponentXOBuilder();
  }

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
      // Using record pattern for creating the final object
      ComponentData data = new ComponentData(id, group, name, version, repository, format, assets);
      DefaultComponentXO component = new DefaultComponentXO();
      component.data = data;
      return component;
    }
  }
}
