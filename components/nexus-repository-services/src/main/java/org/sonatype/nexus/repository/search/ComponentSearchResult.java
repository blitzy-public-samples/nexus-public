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
package org.sonatype.nexus.repository.search;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * Result of a component search
 *
 * @since 3.38
 */
public class ComponentSearchResult
{
  private final String id;

  private final String repositoryName;

  private final String group;

  private final String name;

  private final String version;

  private final String format;

  private final OffsetDateTime lastDownloaded;

  private final OffsetDateTime lastModified;

  private final List<AssetSearchResult> assets;

  private final Map<String, Object> annotations;

  /**
   * Creates a new ComponentSearchResult from the provided builder.
   *
   * @param builder the builder containing the component search result data
   */
  private ComponentSearchResult(final Builder builder) {
    this.id = builder.id;
    this.repositoryName = builder.repositoryName;
    this.group = builder.group;
    this.name = builder.name;
    this.version = builder.version;
    this.format = builder.format;
    this.lastDownloaded = builder.lastDownloaded;
    this.lastModified = builder.lastModified;
    this.assets = builder.assets != null ? new ArrayList<>(builder.assets) : new ArrayList<>();
    this.annotations = builder.annotations != null ? new HashMap<>(builder.annotations) : new HashMap<>();
  }

  /**
   * Creates a new builder for ComponentSearchResult.
   *
   * @return a new builder instance
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Creates a new builder initialized with values from an existing ComponentSearchResult.
   *
   * @param result the ComponentSearchResult to copy values from
   * @return a new builder instance with copied values
   */
  public static Builder builderFrom(final ComponentSearchResult result) {
    return new Builder()
        .id(result.id)
        .repositoryName(result.repositoryName)
        .group(result.group)
        .name(result.name)
        .version(result.version)
        .format(result.format)
        .lastDownloaded(result.lastDownloaded)
        .lastModified(result.lastModified)
        .assets(result.assets)
        .annotations(result.annotations);
  }

  /**
   * Adds an annotation to the search result, this is an extension point for plugins. The ID must be unique.
   * 
   * @param id the annotation identifier
   * @param annotation the annotation object
   * @throws IllegalArgumentException if the annotation ID already exists
   */
  public void addAnnotation(final String id, final Object annotation) {
    checkArgument(!annotations.containsKey(id), "Annotation " + id + " already exists on the component.");
    annotations.put(id, annotation);
  }

  /**
   * Returns the requested annotation if it has been set.
   * 
   * @param id the annotation identifier
   * @return the annotation object or null if not found
   */
  @SuppressWarnings("unchecked")
  public <T> T getAnnotation(final String id) {
    return (T) annotations.get(id);
  }

  /**
   * Gets the component ID.
   * 
   * @return the component ID
   */
  public String getId() {
    return id;
  }

  /**
   * Gets the repository name.
   * 
   * @return the repository name
   */
  public String getRepositoryName() {
    return repositoryName;
  }

  /**
   * Gets the component group.
   * 
   * @return the component group
   */
  public String getGroup() {
    return group;
  }

  /**
   * Gets the component name.
   * 
   * @return the component name
   */
  public String getName() {
    return name;
  }

  /**
   * Gets the component version.
   * 
   * @return the component version
   */
  public String getVersion() {
    return version;
  }

  /**
   * Gets the component format.
   * 
   * @return the component format
   */
  public String getFormat() {
    return format;
  }

  /**
   * Gets the list of assets associated with this component.
   * 
   * @return the list of assets, never null
   */
  public List<AssetSearchResult> getAssets() {
    return assets != null ? List.copyOf(assets) : List.of();
  }

  /**
   * Represents the latest date a blob from any asset associated with the component was changed.
   * 
   * @return the last modified date
   */
  public OffsetDateTime getLastModified() {
    return lastModified;
  }

  /**
   * Represents the most recent time any asset associated with this component was downloaded.
   * 
   * @return the last downloaded date
   */
  public OffsetDateTime getLastDownloaded() {
    return lastDownloaded;
  }

  /**
   * Adds an asset to this component's asset list.
   * 
   * @param asset the asset to add
   */
  public void addAsset(final AssetSearchResult asset) {
    if (asset != null) {
      assets.add(asset);
    }
  }

  /**
   * Gets all annotations for this component.
   * 
   * @return the annotations map, never null
   */
  public Map<String, Object> getAnnotations() {
    return Map.copyOf(annotations);
  }
  
  /**
   * Checks if this component has the specified format.
   * 
   * @param formatName the format name to check
   * @return true if this component has the specified format, false otherwise
   */
  public boolean hasFormat(final String formatName) {
    return Objects.equals(format, formatName);
  }

  @Override
  public String toString() {
    return "ComponentSearchResult [id=" + id + ", repositoryName=" + repositoryName + ", group=" + group + ", name="
        + name + ", version=" + version + ", format=" + format + ", lastDownloaded=" + lastDownloaded
        + ", lastModified=" + lastModified + ", assets=" + assets + ", annotations=" + annotations + "]";
  }

  /**
   * Builder for {@link ComponentSearchResult}.
   */
  public static class Builder {
    private String id;
    private String repositoryName;
    private String group;
    private String name;
    private String version;
    private String format;
    private OffsetDateTime lastDownloaded;
    private OffsetDateTime lastModified;
    private List<AssetSearchResult> assets;
    private Map<String, Object> annotations;

    /**
     * Sets the component ID.
     * 
     * @param id the component ID
     * @return this builder
     */
    public Builder id(final String id) {
      this.id = id;
      return this;
    }

    /**
     * Sets the repository name.
     * 
     * @param repositoryName the repository name
     * @return this builder
     */
    public Builder repositoryName(final String repositoryName) {
      this.repositoryName = repositoryName;
      return this;
    }

    /**
     * Sets the component group.
     * 
     * @param group the component group
     * @return this builder
     */
    public Builder group(final String group) {
      this.group = group;
      return this;
    }

    /**
     * Sets the component name.
     * 
     * @param name the component name
     * @return this builder
     */
    public Builder name(final String name) {
      this.name = name;
      return this;
    }

    /**
     * Sets the component version.
     * 
     * @param version the component version
     * @return this builder
     */
    public Builder version(final String version) {
      this.version = version;
      return this;
    }

    /**
     * Sets the component format.
     * 
     * @param format the component format
     * @return this builder
     */
    public Builder format(final String format) {
      this.format = format;
      return this;
    }

    /**
     * Sets the last downloaded date.
     * 
     * @param lastDownloaded the last downloaded date
     * @return this builder
     */
    public Builder lastDownloaded(final OffsetDateTime lastDownloaded) {
      this.lastDownloaded = lastDownloaded;
      return this;
    }

    /**
     * Sets the last modified date.
     * 
     * @param lastModified the last modified date
     * @return this builder
     */
    public Builder lastModified(final OffsetDateTime lastModified) {
      this.lastModified = lastModified;
      return this;
    }

    /**
     * Sets the list of assets.
     * 
     * @param assets the list of assets
     * @return this builder
     */
    public Builder assets(final List<AssetSearchResult> assets) {
      this.assets = assets != null ? new ArrayList<>(assets) : null;
      return this;
    }

    /**
     * Sets the annotations map.
     * 
     * @param annotations the annotations map
     * @return this builder
     */
    public Builder annotations(final Map<String, Object> annotations) {
      this.annotations = annotations != null ? new HashMap<>(annotations) : new HashMap<>();
      return this;
    }

    /**
     * Adds an asset to the list of assets.
     * 
     * @param asset the asset to add
     * @return this builder
     */
    public Builder addAsset(final AssetSearchResult asset) {
      if (this.assets == null) {
        this.assets = new ArrayList<>();
      }
      if (asset != null) {
        this.assets.add(asset);
      }
      return this;
    }

    /**
     * Adds an annotation to the annotations map.
     * 
     * @param id the annotation ID
     * @param annotation the annotation object
     * @return this builder
     */
    public Builder addAnnotation(final String id, final Object annotation) {
      if (this.annotations == null) {
        this.annotations = new HashMap<>();
      }
      checkArgument(!this.annotations.containsKey(id), "Annotation " + id + " already exists on the component.");
      this.annotations.put(id, annotation);
      return this;
    }

    /**
     * Builds a new ComponentSearchResult instance.
     * 
     * @return a new ComponentSearchResult instance
     */
    public ComponentSearchResult build() {
      return new ComponentSearchResult(this);
    }
  }

  /**
   * Pattern matching method to extract component data using Java 21 Record Patterns.
   * This method allows for more efficient data extraction from search results.
   *
   * @param <R> the return type
   * @param mapper the function to map component data to the return type
   * @return the mapped result
   */
  public <R> R match(ComponentDataMapper<R> mapper) {
    return mapper.map(id, repositoryName, group, name, version, format, lastDownloaded, lastModified, assets, annotations);
  }

  /**
   * Functional interface for mapping component data using pattern matching.
   *
   * @param <R> the return type
   */
  @FunctionalInterface
  public interface ComponentDataMapper<R> {
    /**
     * Maps component data to the return type.
     *
     * @param id the component ID
     * @param repositoryName the repository name
     * @param group the component group
     * @param name the component name
     * @param version the component version
     * @param format the component format
     * @param lastDownloaded the last downloaded date
     * @param lastModified the last modified date
     * @param assets the list of assets
     * @param annotations the annotations map
     * @return the mapped result
     */
    R map(String id, String repositoryName, String group, String name, String version, String format,
          OffsetDateTime lastDownloaded, OffsetDateTime lastModified, List<AssetSearchResult> assets,
          Map<String, Object> annotations);
  }
  
  /**
   * Creates a record-like representation of this component for use with Java 21 Record Patterns.
   * This allows for pattern matching in switch expressions and instanceof checks.
   * 
   * @return a record containing the component data
   */
  public ComponentRecord toRecord() {
    return new ComponentRecord(id, repositoryName, group, name, version, format, 
                             lastDownloaded, lastModified, assets, annotations);
  }
  
  /**
   * Record representation of ComponentSearchResult for use with Java 21 Record Patterns.
   */
  public record ComponentRecord(String id, String repositoryName, String group, String name, 
                               String version, String format, OffsetDateTime lastDownloaded, 
                               OffsetDateTime lastModified, List<AssetSearchResult> assets,
                               Map<String, Object> annotations) {}
}
