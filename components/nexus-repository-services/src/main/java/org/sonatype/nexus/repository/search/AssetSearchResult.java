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

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Result of an Asset search
 *
 * @since 3.38
 */
public class AssetSearchResult
{
  private String path;

  private String id;

  private String repository;

  private String format;

  private Map<String, String> checksum;

  private String contentType;

  private Date lastModified;

  private Date lastDownloaded;

  private Date blobCreated;

  private Long fileSize;

  private String uploader;

  private String uploaderIp;

  private Map<String, Object> attributes;

  /**
   * Default constructor
   */
  public AssetSearchResult() {
    // Default constructor
  }

  /**
   * Constructor that uses record patterns to efficiently extract data from search results
   * 
   * @param searchData A record containing asset search data
   * @since Java 21
   */
  public <T> AssetSearchResult(record AssetData(String path, String id, String repository, String format,
      Map<String, String> checksum, String contentType, Date lastModified, Date lastDownloaded,
      Date blobCreated, Long fileSize, String uploader, String uploaderIp, Map<String, Object> attributes) searchData) {
    this.path = searchData.path();
    this.id = searchData.id();
    this.repository = searchData.repository();
    this.format = searchData.format();
    this.checksum = searchData.checksum();
    this.contentType = searchData.contentType();
    this.lastModified = searchData.lastModified();
    this.lastDownloaded = searchData.lastDownloaded();
    this.blobCreated = searchData.blobCreated();
    this.fileSize = searchData.fileSize();
    this.uploader = searchData.uploader();
    this.uploaderIp = searchData.uploaderIp();
    this.attributes = searchData.attributes();
  }

  /**
   * Static factory method that uses record patterns to efficiently map search result data
   * 
   * @param searchResult The search result object to extract data from
   * @return A new AssetSearchResult populated with data from the search result
   * @since Java 21
   */
  public static <T> AssetSearchResult fromSearchResult(Object searchResult) {
    if (searchResult instanceof record AssetSearchData(String path, String id, String repository, String format,
        Map<String, String> checksum, String contentType, Date lastModified, Date lastDownloaded,
        Date blobCreated, Long fileSize, String uploader, String uploaderIp, Map<String, Object> attributes)) {
      
      AssetSearchResult result = new AssetSearchResult();
      result.setPath(path);
      result.setId(id);
      result.setRepository(repository);
      result.setFormat(format);
      result.setChecksum(checksum);
      result.setContentType(contentType);
      result.setLastModified(lastModified);
      result.setLastDownloaded(lastDownloaded);
      result.setBlobCreated(blobCreated);
      result.setFileSize(fileSize);
      result.setUploader(uploader);
      result.setUploaderIp(uploaderIp);
      result.setAttributes(attributes);
      
      return result;
    }
    
    throw new IllegalArgumentException("Search result object does not match expected pattern");
  }
  
  /**
   * Processes nested asset data using Java 21 record patterns for efficient data extraction
   * 
   * @param assetData The asset data object to process
   * @return A new AssetSearchResult populated with data from the nested structure
   * @since Java 21
   */
  public static AssetSearchResult processNestedAssetData(Object assetData) {
    // Using nested record patterns to extract data from complex structures
    if (assetData instanceof record NestedAssetData(
        record AssetInfo(String path, String id) info,
        record RepositoryInfo(String name, String format) repo,
        record ContentInfo(String contentType, Map<String, String> checksums) content,
        record TimestampInfo(Date lastModified, Date lastDownloaded, Date blobCreated) timestamps,
        record UploaderInfo(String uploader, String uploaderIp) uploaderData,
        Long fileSize,
        Map<String, Object> attributes)) {
      
      AssetSearchResult result = new AssetSearchResult();
      
      // Extract data from nested records using pattern variables
      result.setPath(info.path());
      result.setId(info.id());
      result.setRepository(repo.name());
      result.setFormat(repo.format());
      result.setContentType(content.contentType());
      result.setChecksum(content.checksums());
      result.setLastModified(timestamps.lastModified());
      result.setLastDownloaded(timestamps.lastDownloaded());
      result.setBlobCreated(timestamps.blobCreated());
      result.setFileSize(fileSize);
      result.setUploader(uploaderData.uploader());
      result.setUploaderIp(uploaderData.uploaderIp());
      result.setAttributes(attributes);
      
      return result;
    }
    
    throw new IllegalArgumentException("Asset data does not match expected nested pattern");
  }
  
  /**
   * Processes asset data using Java 21's pattern matching in switch statements
   * for more efficient data handling from search results
   * 
   * @param data The data object to process
   * @return A new AssetSearchResult populated with data based on the input type
   * @since Java 21
   */
  public static AssetSearchResult processAssetData(Object data) {
    return switch (data) {
      // Using record patterns in switch cases for type-safe data extraction
      case record SimpleAsset(String path, String id, String repository, String format) simple -> {
        var result = new AssetSearchResult();
        result.setPath(path);
        result.setId(id);
        result.setRepository(repository);
        result.setFormat(format);
        yield result;
      }
      
      // Nested record pattern with asset and content information
      case record DetailedAsset(
          record AssetDetail(String path, String id, String repository) asset,
          record ContentDetail(String format, String contentType, Map<String, String> checksums) content,
          Date modified,
          Long size) detailed -> {
        
        var result = new AssetSearchResult();
        result.setPath(asset.path());
        result.setId(asset.id());
        result.setRepository(asset.repository());
        result.setFormat(content.format());
        result.setContentType(content.contentType());
        result.setChecksum(content.checksums());
        result.setLastModified(modified);
        result.setFileSize(size);
        yield result;
      }
      
      // Using var for type inference in pattern variables
      case record AssetWithAttributes(var path, var id, var repository, var attributes) withAttrs -> {
        var result = new AssetSearchResult();
        result.setPath(path);
        result.setId(id);
        result.setRepository(repository);
        
        // Process attributes if they match expected type
        if (attributes instanceof Map<?, ?> attrMap) {
          Map<String, Object> convertedMap = new HashMap<>();
          attrMap.forEach((key, value) -> {
            if (key instanceof String keyStr) {
              convertedMap.put(keyStr, value);
            }
          });
          result.setAttributes(convertedMap);
        }
        
        yield result;
      }
      
      // Default case for unrecognized data types
      default -> throw new IllegalArgumentException("Unrecognized asset data format");
    };
  }

  /**
   * Creates a new builder for AssetSearchResult
   * 
   * @return a new builder instance
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Creates a new builder initialized with values from the provided AssetSearchResult
   * 
   * @param result the AssetSearchResult to copy values from
   * @return a new builder instance with copied values
   */
  public static Builder builder(AssetSearchResult result) {
    return new Builder()
        .path(result.getPath())
        .id(result.getId())
        .repository(result.getRepository())
        .format(result.getFormat())
        .checksum(result.getChecksum())
        .contentType(result.getContentType())
        .lastModified(result.getLastModified())
        .lastDownloaded(result.getLastDownloaded())
        .blobCreated(result.getBlobCreated())
        .fileSize(result.getFileSize())
        .uploader(result.getUploader())
        .uploaderIp(result.getUploaderIp())
        .attributes(result.getAttributes());
  }
  
  /**
   * Creates a new builder with type inference from the provided parameters
   * Leverages Java 21's improved type inference for more concise code
   * 
   * @param path the asset path
   * @param id the asset ID
   * @return a new builder instance with the provided values
   * @since Java 21
   */
  public static <T> Builder builderOf(String path, String id) {
    return new Builder().path(path).id(id);
  }
  
  /**
   * Creates a new builder with type inference from the provided parameters
   * Leverages Java 21's improved type inference for more concise code
   * 
   * @param path the asset path
   * @param id the asset ID
   * @param repository the repository name
   * @param format the format
   * @return a new builder instance with the provided values
   * @since Java 21
   */
  public static <T> Builder builderOf(String path, String id, String repository, String format) {
    return new Builder()
        .path(path)
        .id(id)
        .repository(repository)
        .format(format);
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

  public Long getFileSize() {
    return fileSize;
  }

  public void setFileSize(final Long fileSize) {
    this.fileSize = fileSize;
  }

  public Date getLastDownloaded() {
    return lastDownloaded;
  }

  public void setLastDownloaded(final Date lastDownloaded) {
    this.lastDownloaded = lastDownloaded;
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

  public Date getBlobCreated() {
    return blobCreated;
  }

  public void setBlobCreated(final Date blobCreated) {
    this.blobCreated = blobCreated;
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

  public Map<String, Object> getAttributes() {
    return attributes;
  }

  public void setAttributes(final Map<String, Object> attributes) {
    this.attributes = attributes;
  }

  @Override
  public String toString() {
    return "AssetSearchResult [path=" + path + ", id=" + id + ", repository=" + repository + ", format=" + format
        + ", checksum=" + checksum + ", contentType=" + contentType + ", lastModified=" + lastModified
        + ", lastDownloaded=" + lastDownloaded + ", blobCreated=" + blobCreated + ", fileSize=" + fileSize 
        + ", uploader=" + uploader + ", uploaderIp=" + uploaderIp + ", attributes=" + attributes + "]";
  }
  
  /**
   * Builder for AssetSearchResult that leverages Java 21's improved type inference
   */
  public static class Builder {
    private final AssetSearchResult result;

    /**
     * Creates a new builder with an empty AssetSearchResult
     */
    public Builder() {
      this.result = new AssetSearchResult();
    }

    /**
     * Sets the asset path
     * 
     * @param path the asset path
     * @return this builder for method chaining
     */
    public Builder path(String path) {
      result.setPath(path);
      return this;
    }

    /**
     * Sets the asset ID
     * 
     * @param id the asset ID
     * @return this builder for method chaining
     */
    public Builder id(String id) {
      result.setId(id);
      return this;
    }

    /**
     * Sets the repository name
     * 
     * @param repository the repository name
     * @return this builder for method chaining
     */
    public Builder repository(String repository) {
      result.setRepository(repository);
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
     * Sets the checksum map
     * 
     * @param checksum the checksum map
     * @return this builder for method chaining
     */
    public Builder checksum(Map<String, String> checksum) {
      result.setChecksum(checksum);
      return this;
    }

    /**
     * Sets the content type
     * 
     * @param contentType the content type
     * @return this builder for method chaining
     */
    public Builder contentType(String contentType) {
      result.setContentType(contentType);
      return this;
    }

    /**
     * Sets the last modified date
     * 
     * @param lastModified the last modified date
     * @return this builder for method chaining
     */
    public Builder lastModified(Date lastModified) {
      result.setLastModified(lastModified);
      return this;
    }

    /**
     * Sets the last downloaded date
     * 
     * @param lastDownloaded the last downloaded date
     * @return this builder for method chaining
     */
    public Builder lastDownloaded(Date lastDownloaded) {
      result.setLastDownloaded(lastDownloaded);
      return this;
    }

    /**
     * Sets the blob created date
     * 
     * @param blobCreated the blob created date
     * @return this builder for method chaining
     */
    public Builder blobCreated(Date blobCreated) {
      result.setBlobCreated(blobCreated);
      return this;
    }

    /**
     * Sets the file size
     * 
     * @param fileSize the file size
     * @return this builder for method chaining
     */
    public Builder fileSize(Long fileSize) {
      result.setFileSize(fileSize);
      return this;
    }

    /**
     * Sets the uploader
     * 
     * @param uploader the uploader
     * @return this builder for method chaining
     */
    public Builder uploader(String uploader) {
      result.setUploader(uploader);
      return this;
    }

    /**
     * Sets the uploader IP
     * 
     * @param uploaderIp the uploader IP
     * @return this builder for method chaining
     */
    public Builder uploaderIp(String uploaderIp) {
      result.setUploaderIp(uploaderIp);
      return this;
    }

    /**
     * Sets the attributes map
     * 
     * @param attributes the attributes map
     * @return this builder for method chaining
     */
    public Builder attributes(Map<String, Object> attributes) {
      result.setAttributes(attributes);
      return this;
    }

    /**
     * Builds the AssetSearchResult
     * 
     * @return the built AssetSearchResult
     */
    public AssetSearchResult build() {
      return result;
    }
  }
}