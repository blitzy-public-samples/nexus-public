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

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.search.AssetSearchResult;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.lang.StringUtils;

/**
 * Asset transfer object for REST APIs.
 **/
public class AssetXO
{
  private String downloadUrl;

  private String path;

  private String id;

  private String repository;

  private String format;

  private Map<String, String> checksum;

  private String contentType;

  private Date lastModified;

  private Date lastDownloaded;

  private String uploader;

  private String uploaderIp;

  private Long fileSize;

  private Date blobCreated;

  private String blobStoreName;

  @JsonIgnore
  private Map<String, Object> attributes;

  public AssetXO() {
    // empty constructor
  }

  /**
   * Constructor that uses record patterns to efficiently extract data from asset data
   * 
   * @param assetData A record containing asset data
   * @since Java 21
   */
  public <T> AssetXO(record AssetData(String downloadUrl, String path, String id, String repository, String format,
      Map<String, String> checksum, String contentType, Date lastModified, Date lastDownloaded,
      String uploader, String uploaderIp, Long fileSize, Date blobCreated, String blobStoreName,
      Map<String, Object> attributes) assetData) {
    this.downloadUrl = assetData.downloadUrl();
    this.path = assetData.path();
    this.id = assetData.id();
    this.repository = assetData.repository();
    this.format = assetData.format();
    this.checksum = assetData.checksum();
    this.contentType = assetData.contentType();
    this.lastModified = assetData.lastModified();
    this.lastDownloaded = assetData.lastDownloaded();
    this.uploader = assetData.uploader();
    this.uploaderIp = assetData.uploaderIp();
    this.fileSize = assetData.fileSize();
    this.blobCreated = assetData.blobCreated();
    this.blobStoreName = assetData.blobStoreName();
    this.attributes = assetData.attributes();
  }

  public String getDownloadUrl() {
    return downloadUrl;
  }

  public void setDownloadUrl(final String downloadUrl) {
    this.downloadUrl = downloadUrl;
  }

  public String getPath() {
    return path;
  }

  public void setPath(final String path) {
    this.path = path;
  }

  public String getId() {
    return id;
  }

  public void setId(final String id) {
    this.id = id;
  }

  public String getRepository() {
    return repository;
  }

  public void setRepository(final String repository) {
    this.repository = repository;
  }

  public String getFormat() {
    return format;
  }

  public void setFormat(final String format) {
    this.format = format;
  }

  public Map<String, String> getChecksum() {
    return checksum;
  }

  public void setChecksum(final Map<String, String> checksum) {
    this.checksum = checksum;
  }

  public String getContentType() {
    return contentType;
  }

  public void setContentType(final String contentType) {
    this.contentType = contentType;
  }

  public Date getLastModified() {
    return lastModified;
  }

  public void setLastModified(final Date lastModified) {
    this.lastModified = lastModified;
  }

  public Date getLastDownloaded() {
    return lastDownloaded;
  }

  public void setLastDownloaded(final Date lastDownloaded) {
    this.lastDownloaded = lastDownloaded;
  }

  public String getUploader() {
    return uploader;
  }

  public void setUploader(final String uploader) {
    this.uploader = uploader;
  }

  public String getUploaderIp() {
    return uploaderIp;
  }

  public void setUploaderIp(final String uploaderIp) {
    this.uploaderIp = uploaderIp;
  }

  public Long getFileSize() {
    return fileSize;
  }

  public void setFileSize(final Long fileSize) {
    this.fileSize = fileSize;
  }

  public Date getBlobCreated() {
    return blobCreated;
  }

  public void setBlobCreated(final Date blobCreated) {
    this.blobCreated = blobCreated;
  }

  public String getBlobStoreName() {
    return blobStoreName;
  }

  public void setBlobStoreName(final String blobStoreName) {
    this.blobStoreName = blobStoreName;
  }

  public void setAttributes(final Map<String, Object> attributes) {
    this.attributes = attributes;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    AssetXO assetXO = (AssetXO) o;
    return Objects.equals(id, assetXO.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }

  /**
   * Creates an AssetXO from an AssetSearchResult using Record Patterns for efficient data handling.
   * 
   * @param asset The asset search result
   * @param repository The repository
   * @param assetDescriptors Optional map of asset descriptors
   * @return A new AssetXO populated with data from the asset search result
   * @since Java 21
   */
  public static AssetXO from(
      AssetSearchResult asset,
      Repository repository,
      @Nullable Map<String, AssetXODescriptor> assetDescriptors)
  {
    // Using pattern matching to extract data from the repository
    String repoName = repository.getName();
    String repoUrl = repository.getUrl();
    
    // Using record patterns to create a structured view of the asset data
    record AssetData(String path, String id, String format, Map<String, String> checksum, 
                    String contentType, Map<String, Object> attributes, Date lastModified, 
                    Date lastDownloaded, Long fileSize, Date blobCreated, 
                    String uploader, String uploaderIp) {}
    
    // Creating a record instance with the asset data
    var assetData = new AssetData(
        asset.getPath(),
        asset.getId(),
        asset.getFormat(),
        asset.getChecksum(),
        asset.getContentType(),
        asset.getAttributes(),
        asset.getLastModified(),
        asset.getLastDownloaded(),
        asset.getFileSize(),
        asset.getBlobCreated(),
        asset.getUploader(),
        asset.getUploaderIp()
    );
    
    // Using pattern matching to extract data from the record
    if (assetData instanceof AssetData(var path, var id, var format, var checksum, 
                                     var contentType, var attributes, var lastModified, 
                                     var lastDownloaded, var fileSize, var blobCreated, 
                                     var uploader, var uploaderIp)) {
      return builder()
          .path(path)
          .downloadUrl(repoUrl + '/' + StringUtils.removeStart(path, "/"))
          .id(new RepositoryItemIDXO(repoName, id).getValue())
          .repository(repoName)
          .checksum(checksum)
          .format(format)
          .contentType(contentType)
          .attributes(getExpandedAttributes(attributes, format, assetDescriptors))
          .lastModified(lastModified)
          .lastDownloaded(lastDownloaded)
          .fileSize(fileSize)
          .blobCreated(blobCreated)
          .uploader(uploader)
          .uploaderIp(uploaderIp)
          .build();
    }
    
    // Fallback to the traditional approach if pattern matching fails
    return builder()
        .path(asset.getPath())
        .downloadUrl(repoUrl + '/' + StringUtils.removeStart(asset.getPath(), "/"))
        .id(new RepositoryItemIDXO(repoName, asset.getId()).getValue())
        .repository(repoName)
        .checksum(asset.getChecksum())
        .format(asset.getFormat())
        .contentType(asset.getContentType())
        .attributes(getExpandedAttributes(asset.getAttributes(), asset.getFormat(), assetDescriptors))
        .lastModified(asset.getLastModified())
        .lastDownloaded(asset.getLastDownloaded())
        .fileSize(asset.getFileSize())
        .blobCreated(asset.getBlobCreated())
        .uploader(asset.getUploader())
        .uploaderIp(asset.getUploaderIp())
        .build();
  }

  /**
   * Creates an AssetXO from an ElasticSearch map using Record Patterns for efficient data handling.
   * 
   * @param map The ElasticSearch map
   * @param repository The repository
   * @param assetDescriptors Optional map of asset descriptors
   * @return A new AssetXO populated with data from the ElasticSearch map
   * @since Java 21
   */
  public static AssetXO fromElasticSearchMap(
      Map<String, Object> map,
      Repository repository,
      @Nullable Map<String, AssetXODescriptor> assetDescriptors)
  {
    // Using pattern matching to extract data from the repository
    String repoName = repository.getName();
    String repoUrl = repository.getUrl();
    String format = repository.getFormat().getValue();
    
    // Using record patterns to create a structured view of the map data
    record ElasticSearchData(String path, String id, String contentType, 
                            Map<String, Object> attributes) {}
    
    // Creating a record instance with the map data
    var esData = new ElasticSearchData(
        (String) map.get("name"),
        (String) map.get("id"),
        (String) map.get("contentType"),
        (Map<String, Object>) map.getOrDefault("attributes", Map.of())
    );
    
    // Using pattern matching to extract data from the record
    if (esData instanceof ElasticSearchData(var path, var id, var contentType, var attributes)) {
      // Extract checksum from attributes
      Map<String, String> checksum = (Map<String, String>) attributes.get("checksum");
      
      return builder()
          .path(path)
          .downloadUrl(repoUrl + '/' + path)
          .id(new RepositoryItemIDXO(repoName, id).getValue())
          .repository(repoName)
          .checksum(checksum)
          .format(format)
          .contentType(contentType)
          .attributes(getExpandedAttributes(attributes, format, assetDescriptors))
          .lastModified(calculateLastModified(attributes))
          .build();
    }
    
    // Fallback to the traditional approach if pattern matching fails
    String path = (String) map.get("name");
    String id = (String) map.get("id");
    Map<String, Object> attributes = (Map<String, Object>) map.getOrDefault("attributes", Map.of());
    Map<String, String> checksum = (Map<String, String>) attributes.get("checksum");
    String contentType = (String) map.get("contentType");

    return builder()
        .path(path)
        .downloadUrl(repoUrl + '/' + path)
        .id(new RepositoryItemIDXO(repoName, id).getValue())
        .repository(repoName)
        .checksum(checksum)
        .format(format)
        .contentType(contentType)
        .attributes(getExpandedAttributes(attributes, format, assetDescriptors))
        .lastModified(calculateLastModified(attributes))
        .build();
  }

  /**
   * Gets expanded attributes using Record Patterns for efficient data handling.
   * 
   * @param attributes The attributes map
   * @param format The format
   * @param assetDescriptors Optional map of asset descriptors
   * @return A map of expanded attributes
   * @since Java 21
   */
  @VisibleForTesting
  static Map<String, Object> getExpandedAttributes(
      Map<String, Object> attributes,
      String format,
      @Nullable Map<String, AssetXODescriptor> assetDescriptors)
  {
    // Using record patterns to create a structured view of the descriptor data
    record DescriptorData(String format, Set<String> exposedKeys) {}
    
    // Extract exposed attribute keys using pattern matching
    Set<String> exposedAttributeKeys = Optional.ofNullable(assetDescriptors)
        .map(ad -> ad.get(format))
        .map(descriptor -> {
          if (descriptor != null) {
            return new DescriptorData(format, descriptor.listExposedAttributeKeys());
          }
          return null;
        })
        .map(data -> {
          if (data instanceof DescriptorData(var fmt, var keys)) {
            return keys;
          }
          return Set.<String>of();
        })
        .orElse(Set.of());

    // Extract format attributes using pattern matching
    Map<String, Object> formatAttributes = (Map<String, Object>) attributes.get(format);
    Map<String, Object> exposedAttributes = new HashMap<>();
    
    if (formatAttributes != null) {
      exposedAttributes.putAll(formatAttributes.entrySet()
          .stream()
          .filter(e -> exposedAttributeKeys.contains(e.getKey()))
          .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    return Map.of(format, exposedAttributes);
  }

  /**
   * Calculates the last modified date from attributes using Record Patterns for efficient data handling.
   * 
   * @param attributes The attributes map
   * @return The calculated last modified date, or null if not available
   * @since Java 21
   */
  private static Date calculateLastModified(Map<String, Object> attributes) {
    // Using record patterns to create a structured view of the content data
    record ContentData(String lastModified) {}
    
    // Extract content data using pattern matching
    Map<String, Object> contentMap = (Map<String, Object>) attributes.getOrDefault("content", Map.of());
    String lastModifiedString = (String) contentMap.getOrDefault("last_modified", null);
    
    // Create a record instance with the content data
    var contentData = lastModifiedString != null ? new ContentData(lastModifiedString) : null;
    
    // Using pattern matching to extract and process the last modified date
    if (contentData instanceof ContentData(var lastModifiedStr)) {
      try {
        return new Date(Long.parseLong(lastModifiedStr.trim()));
      }
      catch (Exception ignored) {
        // Nothing we can do here for invalid data. It shouldn't happen but date parsing will blow out the results.
      }
    }
    
    return null;
  }

  /**
   * Creates a new builder for AssetXO.
   * 
   * @return A new builder instance
   */
  public static AssetXOBuilder builder() {
    return new AssetXOBuilder();
  }

  /**
   * Creates a new builder initialized with values from the provided AssetXO.
   * 
   * @param asset The AssetXO to copy values from
   * @return A new builder instance with copied values
   * @since Java 21
   */
  public static AssetXOBuilder builder(AssetXO asset) {
    return new AssetXOBuilder()
        .downloadUrl(asset.getDownloadUrl())
        .path(asset.getPath())
        .id(asset.getId())
        .repository(asset.getRepository())
        .format(asset.getFormat())
        .checksum(asset.getChecksum())
        .contentType(asset.getContentType())
        .lastModified(asset.getLastModified())
        .lastDownloaded(asset.getLastDownloaded())
        .uploader(asset.getUploader())
        .uploaderIp(asset.getUploaderIp())
        .fileSize(asset.getFileSize())
        .blobCreated(asset.getBlobCreated())
        .blobStoreName(asset.getBlobStoreName())
        .attributes(asset.getAttributes());
  }
  
  /**
   * Creates a new builder with type inference from the provided parameters.
   * Leverages Java 21's improved type inference for more concise code.
   * 
   * @param path The asset path
   * @param id The asset ID
   * @return A new builder instance with the provided values
   * @since Java 21
   */
  public static <T> AssetXOBuilder builderOf(String path, String id) {
    return new AssetXOBuilder().path(path).id(id);
  }
  
  /**
   * Creates a new builder with type inference from the provided parameters.
   * Leverages Java 21's improved type inference for more concise code.
   * 
   * @param path The asset path
   * @param id The asset ID
   * @param repository The repository name
   * @param format The format
   * @return A new builder instance with the provided values
   * @since Java 21
   */
  public static <T> AssetXOBuilder builderOf(String path, String id, String repository, String format) {
    return new AssetXOBuilder()
        .path(path)
        .id(id)
        .repository(repository)
        .format(format);
  }

  @JsonAnyGetter
  public Map<String, Object> getAttributes() {
    return attributes;
  }

  @Override
  public String toString() {
    return "AssetXO{" +
        "downloadUrl='" + downloadUrl + '\'' +
        ", path='" + path + '\'' +
        ", id='" + id + '\'' +
        ", repository='" + repository + '\'' +
        ", format='" + format + '\'' +
        ", checksum=" + checksum +
        ", contentType='" + contentType + '\'' +
        ", lastModified=" + lastModified +
        ", lastDownloaded=" + lastDownloaded +
        ", uploader='" + uploader + '\'' +
        ", uploaderIp='" + uploaderIp + '\'' +
        ", fileSize=" + fileSize +
        ", blobCreated=" + blobCreated +
        ", blobStoreName='" + blobStoreName + '\'' +
        ", attributes=" + attributes +
        '}';
  }

  /**
   * Builder class for AssetXO that leverages Java 21's improved type inference.
   */
  public static class AssetXOBuilder
  {
    private final AssetXO asset;

    /**
     * Creates a new builder with an empty AssetXO.
     */
    public AssetXOBuilder() {
      this.asset = new AssetXO();
    }

    /**
     * Sets the download URL.
     * 
     * @param downloadUrl The download URL
     * @return This builder for method chaining
     */
    public AssetXOBuilder downloadUrl(String downloadUrl) {
      asset.setDownloadUrl(downloadUrl);
      return this;
    }

    /**
     * Sets the asset path.
     * 
     * @param path The asset path
     * @return This builder for method chaining
     */
    public AssetXOBuilder path(String path) {
      asset.setPath(path);
      return this;
    }

    /**
     * Sets the asset ID.
     * 
     * @param id The asset ID
     * @return This builder for method chaining
     */
    public AssetXOBuilder id(String id) {
      asset.setId(id);
      return this;
    }

    /**
     * Sets the repository name.
     * 
     * @param repository The repository name
     * @return This builder for method chaining
     */
    public AssetXOBuilder repository(String repository) {
      asset.setRepository(repository);
      return this;
    }

    /**
     * Sets the format.
     * 
     * @param format The format
     * @return This builder for method chaining
     */
    public AssetXOBuilder format(String format) {
      asset.setFormat(format);
      return this;
    }

    /**
     * Sets the checksum map.
     * 
     * @param checksum The checksum map
     * @return This builder for method chaining
     */
    public AssetXOBuilder checksum(Map<String, String> checksum) {
      asset.setChecksum(checksum);
      return this;
    }

    /**
     * Sets the content type.
     * 
     * @param contentType The content type
     * @return This builder for method chaining
     */
    public AssetXOBuilder contentType(String contentType) {
      asset.setContentType(contentType);
      return this;
    }

    /**
     * Sets the last modified date.
     * 
     * @param lastModified The last modified date
     * @return This builder for method chaining
     */
    public AssetXOBuilder lastModified(Date lastModified) {
      asset.setLastModified(lastModified);
      return this;
    }

    /**
     * Sets the last downloaded date.
     * 
     * @param lastDownloaded The last downloaded date
     * @return This builder for method chaining
     */
    public AssetXOBuilder lastDownloaded(Date lastDownloaded) {
      asset.setLastDownloaded(lastDownloaded);
      return this;
    }

    /**
     * Sets the uploader.
     * 
     * @param uploader The uploader
     * @return This builder for method chaining
     */
    public AssetXOBuilder uploader(String uploader) {
      asset.setUploader(uploader);
      return this;
    }

    /**
     * Sets the uploader IP.
     * 
     * @param uploaderIp The uploader IP
     * @return This builder for method chaining
     */
    public AssetXOBuilder uploaderIp(String uploaderIp) {
      asset.setUploaderIp(uploaderIp);
      return this;
    }

    /**
     * Sets the file size.
     * 
     * @param fileSize The file size
     * @return This builder for method chaining
     */
    public AssetXOBuilder fileSize(Long fileSize) {
      asset.setFileSize(fileSize);
      return this;
    }

    /**
     * Sets the blob created date.
     * 
     * @param blobCreated The blob created date
     * @return This builder for method chaining
     */
    public AssetXOBuilder blobCreated(Date blobCreated) {
      asset.setBlobCreated(blobCreated);
      return this;
    }

    /**
     * Sets the blob store name.
     * 
     * @param blobStoreName The blob store name
     * @return This builder for method chaining
     */
    public AssetXOBuilder blobStoreName(String blobStoreName) {
      asset.setBlobStoreName(blobStoreName);
      return this;
    }

    /**
     * Sets the attributes map.
     * 
     * @param attributes The attributes map
     * @return This builder for method chaining
     */
    public AssetXOBuilder attributes(Map<String, Object> attributes) {
      asset.setAttributes(attributes);
      return this;
    }

    /**
     * Builds the AssetXO.
     * 
     * @return The built AssetXO
     */
    public AssetXO build() {
      return asset;
    }
    
    /**
     * Builds the AssetXO using Record Patterns for efficient data handling.
     * 
     * @param data A record containing asset data
     * @return The built AssetXO
     * @since Java 21
     */
    public <T> AssetXO buildFromRecord(Object data) {
      // Using pattern matching to extract data from different record types
      return switch (data) {
        // Basic asset data pattern
        case record BasicAssetData(var path, var id, var repository, var format) basic -> {
          asset.setPath(path);
          asset.setId(id);
          asset.setRepository(repository);
          asset.setFormat(format);
          yield asset;
        }
        
        // Detailed asset data pattern with nested records
        case record DetailedAssetData(
            record AssetInfo(var path, var id) info,
            record RepositoryInfo(var name, var format) repo,
            Map<String, String> checksum,
            String contentType,
            Date lastModified) detailed -> {
          
          asset.setPath(info.path());
          asset.setId(info.id());
          asset.setRepository(repo.name());
          asset.setFormat(repo.format());
          asset.setChecksum(checksum);
          asset.setContentType(contentType);
          asset.setLastModified(lastModified);
          yield asset;
        }
        
        // Complete asset data pattern
        case record CompleteAssetData(
            String downloadUrl, String path, String id, String repository, String format,
            Map<String, String> checksum, String contentType, Date lastModified, Date lastDownloaded,
            String uploader, String uploaderIp, Long fileSize, Date blobCreated, String blobStoreName,
            Map<String, Object> attributes) complete -> {
          
          asset.setDownloadUrl(downloadUrl);
          asset.setPath(path);
          asset.setId(id);
          asset.setRepository(repository);
          asset.setFormat(format);
          asset.setChecksum(checksum);
          asset.setContentType(contentType);
          asset.setLastModified(lastModified);
          asset.setLastDownloaded(lastDownloaded);
          asset.setUploader(uploader);
          asset.setUploaderIp(uploaderIp);
          asset.setFileSize(fileSize);
          asset.setBlobCreated(blobCreated);
          asset.setBlobStoreName(blobStoreName);
          asset.setAttributes(attributes);
          yield asset;
        }
        
        // Default case for unrecognized data types
        default -> asset;
      };
    }
  }
}