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
  private String id;

  private String repositoryName;

  private String group;

  private String name;

  private String version;

  private String format;

  private OffsetDateTime lastDownloaded;

  private OffsetDateTime lastModified;

  private List<AssetSearchResult> assets;

  private Map<String, Object> annotations = new HashMap<>();

  /**
   * Default constructor
   */
  public ComponentSearchResult() {
    // Default constructor
  }

  /**
   * Constructor that uses record patterns to efficiently extract data from search results
   * 
   * @param searchData A record containing component search data
   * @since Java 21
   */
  public <T> ComponentSearchResult(record ComponentData(String id, String repositoryName, String group, 
      String name, String version, String format, OffsetDateTime lastDownloaded, 
      OffsetDateTime lastModified, List<AssetSearchResult> assets) searchData) {
    this.id = searchData.id();
    this.repositoryName = searchData.repositoryName();
    this.group = searchData.group();
    this.name = searchData.name();
    this.version = searchData.version();
    this.format = searchData.format();
    this.lastDownloaded = searchData.lastDownloaded();
    this.lastModified = searchData.lastModified();
    this.assets = searchData.assets() != null ? new ArrayList<>(searchData.assets()) : null;
  }
  
  /**
   * Static factory method that uses record patterns to efficiently map search result data
   * 
   * @param searchResult The search result object to extract data from
   * @return A new ComponentSearchResult populated with data from the search result
   * @since Java 21
   */
  public static <T> ComponentSearchResult fromSearchResult(Object searchResult) {
    if (searchResult instanceof record SearchResultData(String id, String repository, String group,
        String name, String version, String format, OffsetDateTime lastDownloaded,
        OffsetDateTime lastModified, var assets, var attributes)) {
      
      ComponentSearchResult result = new ComponentSearchResult();
      result.setId(id);
      result.setRepositoryName(repository);
      result.setGroup(group);
      result.setName(name);
      result.setVersion(version);
      result.setFormat(format);
      result.setLastDownloaded(lastDownloaded);
      result.setLastModified(lastModified);
      
      // Process assets if available
      if (assets instanceof List<?> assetList) {
        assetList.forEach(asset -> {
          if (asset instanceof AssetSearchResult assetResult) {
            result.addAsset(assetResult);
          }
        });
      }
      
      // Process attributes if available
      if (attributes instanceof Map<?, ?> attrMap) {
        attrMap.forEach((key, value) -> {
          if (key instanceof String keyStr) {
            result.addAnnotation(keyStr, value);
          }
        });
      }
      
      return result;
    }
    
    throw new IllegalArgumentException("Search result object does not match expected pattern");
  }
  
  /**
   * Processes nested component data using Java 21 record patterns for efficient data extraction
   * This method demonstrates the power of nested record patterns for complex data structures
   * 
   * @param componentData The component data object to process
   * @return A new ComponentSearchResult populated with data from the nested structure
   * @since Java 21
   */
  public static ComponentSearchResult processNestedComponentData(Object componentData) {
    // Using nested record patterns to extract data from complex structures
    if (componentData instanceof record NestedComponentData(
        record ComponentInfo(String id, String name, String version) info,
        record RepositoryInfo(String name, String format) repo,
        record GroupInfo(String groupId) groupData,
        List<record AssetInfo(String path, String id, Map<String, String> checksums)> assetInfoList,
        OffsetDateTime lastModified,
        OffsetDateTime lastDownloaded)) {
      
      ComponentSearchResult result = new ComponentSearchResult();
      
      // Extract data from nested records using pattern variables
      result.setId(info.id());
      result.setName(info.name());
      result.setVersion(info.version());
      result.setRepositoryName(repo.name());
      result.setFormat(repo.format());
      result.setGroup(groupData.groupId());
      result.setLastModified(lastModified);
      result.setLastDownloaded(lastDownloaded);
      
      // Process asset information
      for (var assetInfo : assetInfoList) {
        AssetSearchResult asset = new AssetSearchResult();
        asset.setPath(assetInfo.path());
        asset.setId(assetInfo.id());
        asset.setChecksum(assetInfo.checksums());
        result.addAsset(asset);
      }
      
      return result;
    }
    
    throw new IllegalArgumentException("Component data does not match expected nested pattern");
  }
  
  /**
   * Processes component data using Java 21's pattern matching in switch statements
   * for more efficient data handling from search results
   * 
   * @param data The data object to process
   * @return A new ComponentSearchResult populated with data based on the input type
   * @since Java 21
   */
  public static ComponentSearchResult processComponentData(Object data) {
    return switch (data) {
      // Using record patterns in switch cases for type-safe data extraction
      case record SimpleComponent(String id, String name, String version, String format) simple -> {
        var result = new ComponentSearchResult();
        result.setId(id);
        result.setName(name);
        result.setVersion(version);
        result.setFormat(format);
        yield result;
      }
      
      // Nested record pattern with component and repository information
      case record DetailedComponent(
          record ComponentDetail(String id, String name, String version) component,
          record RepositoryDetail(String repoName, String format) repository,
          OffsetDateTime modified,
          OffsetDateTime downloaded) detailed -> {
        
        var result = new ComponentSearchResult();
        result.setId(component.id());
        result.setName(component.name());
        result.setVersion(component.version());
        result.setRepositoryName(repository.repoName());
        result.setFormat(repository.format());
        result.setLastModified(modified);
        result.setLastDownloaded(downloaded);
        yield result;
      }
      
      // Using var for type inference in pattern variables
      case record ComponentWithAssets(var id, var name, var version, var assets) withAssets -> {
        var result = new ComponentSearchResult();
        result.setId(id);
        result.setName(name);
        result.setVersion(version);
        
        // Process assets if they match expected type
        if (assets instanceof List<?> assetList) {
          assetList.forEach(asset -> {
            if (asset instanceof AssetSearchResult assetResult) {
              result.addAsset(assetResult);
            }
          });
        }
        
        yield result;
      }
      
      // Default case for unrecognized data types
      default -> throw new IllegalArgumentException("Unrecognized component data format");
    };
  }

  /**
   * Creates a new builder for ComponentSearchResult
   * 
   * @return a new builder instance
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Creates a new builder initialized with values from the provided ComponentSearchResult
   * 
   * @param result the ComponentSearchResult to copy values from
   * @return a new builder instance with copied values
   */
  public static Builder builder(ComponentSearchResult result) {
    return new Builder()
        .id(result.getId())
        .repositoryName(result.getRepositoryName())
        .group(result.getGroup())
        .name(result.getName())
        .version(result.getVersion())
        .format(result.getFormat())
        .lastDownloaded(result.getLastDownloaded())
        .lastModified(result.getLastModified())
        .assets(result.getAssets());
  }
  
  /**
   * Creates a new builder with type inference from the provided parameters
   * Leverages Java 21's improved type inference for more concise code
   * 
   * @param id the component ID
   * @param name the component name
   * @return a new builder instance with the provided values
   * @since Java 21
   */
  public static <T> Builder builderOf(String id, String name) {
    return new Builder().id(id).name(name);
  }
  
  /**
   * Creates a new builder with type inference from the provided parameters
   * Leverages Java 21's improved type inference for more concise code
   * 
   * @param id the component ID
   * @param repositoryName the repository name
   * @param group the group
   * @param name the component name
   * @param version the version
   * @return a new builder instance with the provided values
   * @since Java 21
   */
  public static <T> Builder builderOf(String id, String repositoryName, String group, String name, String version) {
    return new Builder()
        .id(id)
        .repositoryName(repositoryName)
        .group(group)
        .name(name)
        .version(version);
  }

  /**
   * Adds an annotation to the search result, this is an extension point for plugins. The ID must be unique.
   */
  public void addAnnotation(final String id, final Object annotation) {
    checkArgument(!annotations.containsKey(id), "Annotation " + id + " already exists on the component.");
    annotations.put(id, annotation);
  }

  /**
   * Returns the requested annotation if it has been set.
   * 
   * @param id the annotation ID
   * @return the annotation value, or null if not found
   * @param <T> the expected type of the annotation value
   */
  @SuppressWarnings("unchecked")
  public <T> T getAnnotation(final String id) {
    Object value = annotations.get(id);
    if (value == null) {
      return null;
    }
    
    // Using pattern matching for instanceof with Java 21
    if (value instanceof T typedValue) {
      return typedValue;
    }
    
    // Fallback to traditional cast if pattern matching doesn't work
    return (T) value;
  }

  public String getId() {
    return id;
  }

  public void setId(final String id) {
    this.id = id;
  }

  public String getRepositoryName() {
    return repositoryName;
  }

  public void setRepositoryName(final String repositoryName) {
    this.repositoryName = repositoryName;
  }

  public String getGroup() {
    return group;
  }

  public void setGroup(final String group) {
    this.group = group;
  }

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public String getVersion() {
    return version;
  }

  public void setVersion(final String version) {
    this.version = version;
  }

  public String getFormat() {
    return format;
  }

  public void setFormat(final String format) {
    this.format = format;
  }

  public List<AssetSearchResult> getAssets() {
    return assets != null ? assets : List.of();
  }

  public void setAssets(final List<AssetSearchResult> assets) {
    this.assets = assets;
  }

  /**
   * Represents the latest date a blob from any asset associated with the component was changed.
   */
  public OffsetDateTime getLastModified() {
    return lastModified;
  }

  public void setLastModified(final OffsetDateTime lastModified) {
    this.lastModified = lastModified;
  }

  /**
   * Represents the most recent time any asset associated with this component was downloaded.
   */
  public OffsetDateTime getLastDownloaded() {
    return lastDownloaded;
  }

  public void setLastDownloaded(final OffsetDateTime lastDownloaded) {
    this.lastDownloaded = lastDownloaded;
  }

  public void addAsset(final AssetSearchResult asset) {
    if (assets == null) {
      assets = new ArrayList<>();
    }
    assets.add(asset);
  }

  @Override
  public String toString() {
    return "ComponentSearchResult [id=" + id + ", repositoryName=" + repositoryName + ", group=" + group + ", name="
        + name + ", version=" + version + ", format=" + format + ", lastDownloaded=" + lastDownloaded
        + ", lastModified=" + lastModified + ", assets=" + assets + ", annotations=" + annotations + "]";
  }

  /**
   * Builder for ComponentSearchResult that leverages Java 21's improved type inference
   */
  public static class Builder {
    private final ComponentSearchResult result;

    /**
     * Creates a new builder with an empty ComponentSearchResult
     */
    public Builder() {
      this.result = new ComponentSearchResult();
    }

    /**
     * Sets the component ID
     * 
     * @param id the component ID
     * @return this builder for method chaining
     */
    public Builder id(String id) {
      result.setId(id);
      return this;
    }

    /**
     * Sets the repository name
     * 
     * @param repositoryName the repository name
     * @return this builder for method chaining
     */
    public Builder repositoryName(String repositoryName) {
      result.setRepositoryName(repositoryName);
      return this;
    }

    /**
     * Sets the group
     * 
     * @param group the group
     * @return this builder for method chaining
     */
    public Builder group(String group) {
      result.setGroup(group);
      return this;
    }

    /**
     * Sets the name
     * 
     * @param name the name
     * @return this builder for method chaining
     */
    public Builder name(String name) {
      result.setName(name);
      return this;
    }

    /**
     * Sets the version
     * 
     * @param version the version
     * @return this builder for method chaining
     */
    public Builder version(String version) {
      result.setVersion(version);
      return this;
    }

    /**
     * Sets the format
     * 
     * @param format the format
     * @return this builder for method chaining
     */
    public Builder format(String format) {
      result.setFormat(format);
      return this;
    }

    /**
     * Sets the last downloaded time
     * 
     * @param lastDownloaded the last downloaded time
     * @return this builder for method chaining
     */
    public Builder lastDownloaded(OffsetDateTime lastDownloaded) {
      result.setLastDownloaded(lastDownloaded);
      return this;
    }

    /**
     * Sets the last modified time
     * 
     * @param lastModified the last modified time
     * @return this builder for method chaining
     */
    public Builder lastModified(OffsetDateTime lastModified) {
      result.setLastModified(lastModified);
      return this;
    }

    /**
     * Sets the assets
     * 
     * @param assets the assets
     * @return this builder for method chaining
     */
    public Builder assets(List<AssetSearchResult> assets) {
      result.setAssets(assets);
      return this;
    }

    /**
     * Adds an asset to the component
     * 
     * @param asset the asset to add
     * @return this builder for method chaining
     */
    public Builder addAsset(AssetSearchResult asset) {
      result.addAsset(asset);
      return this;
    }

    /**
     * Adds an annotation to the component
     * 
     * @param id the annotation ID
     * @param annotation the annotation object
     * @return this builder for method chaining
     */
    public <T> Builder addAnnotation(String id, T annotation) {
      result.addAnnotation(id, annotation);
      return this;
    }

    /**
     * Builds the ComponentSearchResult
     * 
     * @return the built ComponentSearchResult
     */
    public ComponentSearchResult build() {
      return result;
    }
  }
}