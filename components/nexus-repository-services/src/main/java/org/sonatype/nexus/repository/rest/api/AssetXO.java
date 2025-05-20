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
 * Implemented as a record for immutability and pattern matching support in Java 21.
 **/
public record AssetXO(
    String downloadUrl,
    String path,
    String id,
    String repository,
    String format,
    Map<String, String> checksum,
    String contentType,
    Date lastModified,
    Date lastDownloaded,
    String uploader,
    String uploaderIp,
    Long fileSize,
    Date blobCreated,
    String blobStoreName,
    @JsonIgnore Map<String, Object> attributes
) {
  /**
   * Constructor with validation to ensure non-null maps.
   */
  public AssetXO {
    // Ensure maps are never null
    if (checksum == null) {
      checksum = Map.of();
    }
    if (attributes == null) {
      attributes = Map.of();
    }
  }

  /**
   * Creates a new AssetXO from an AssetSearchResult and Repository.
   *
   * @param asset the asset search result
   * @param repository the repository
   * @param assetDescriptors the asset descriptors map, may be null
   * @return a new AssetXO instance
   */
  public static AssetXO from(
      AssetSearchResult asset,
      Repository repository,
      @Nullable Map<String, AssetXODescriptor> assetDescriptors)
  {
    // Using pattern matching to extract data from AssetSearchResult
    return switch (asset) {
      case AssetSearchResult result when result != null -> {
        String path = result.getPath();
        yield new AssetXO(
            repository.getUrl() + '/' + StringUtils.removeStart(path, "/"),
            path,
            new RepositoryItemIDXO(repository.getName(), result.getId()).getValue(),
            repository.getName(),
            result.getFormat(),
            result.getChecksum(),
            result.getContentType(),
            result.getLastModifiedDate(),
            result.getLastDownloadedDate(),
            result.getUploader(),
            result.getUploaderIp(),
            result.getFileSize(),
            result.getBlobCreatedDate(),
            null, // blobStoreName not available in AssetSearchResult
            getExpandedAttributes(result.getAttributes(), result.getFormat(), assetDescriptors)
        );
      }
      default -> throw new IllegalArgumentException("Asset search result cannot be null");
    };
  }

  /**
   * Creates a new AssetXO from an ElasticSearch map and Repository.
   *
   * @param map the ElasticSearch map
   * @param repository the repository
   * @param assetDescriptors the asset descriptors map, may be null
   * @return a new AssetXO instance
   */
  public static AssetXO fromElasticSearchMap(
      Map<String, Object> map,
      Repository repository,
      @Nullable Map<String, AssetXODescriptor> assetDescriptors)
  {
    // Using pattern matching with records to extract data from the map
    record ElasticSearchData(String path, String id, Map<String, Object> attributes, 
                            Map<String, String> checksum, String format, String contentType) {}
    
    // Extract data from the map using pattern matching
    ElasticSearchData data = new ElasticSearchData(
        (String) map.get("name"),
        (String) map.get("id"),
        (Map<String, Object>) map.getOrDefault("attributes", Map.of()),
        (Map<String, String>) ((Map<String, Object>) map.getOrDefault("attributes", Map.of())).get("checksum"),
        repository.getFormat().getValue(),
        (String) map.get("contentType")
    );
    
    // Use pattern matching to create the AssetXO
    return switch (data) {
      case ElasticSearchData(var path, var id, var attributes, var checksum, var format, var contentType) -> 
          new AssetXO(
              repository.getUrl() + '/' + path,
              path,
              new RepositoryItemIDXO(repository.getName(), id).getValue(),
              repository.getName(),
              format,
              checksum,
              contentType,
              calculateLastModified(attributes),
              null, // lastDownloaded not available in ElasticSearch map
              null, // uploader not available in ElasticSearch map
              null, // uploaderIp not available in ElasticSearch map
              null, // fileSize not available in ElasticSearch map
              null, // blobCreated not available in ElasticSearch map
              null, // blobStoreName not available in ElasticSearch map
              getExpandedAttributes(attributes, format, assetDescriptors)
          );
    };
  }

  /**
   * Expands the attributes map based on the asset descriptors.
   *
   * @param attributes the attributes map
   * @param format the format
   * @param assetDescriptors the asset descriptors map, may be null
   * @return the expanded attributes map
   */
  @VisibleForTesting
  static Map<String, Object> getExpandedAttributes(
      Map<String, Object> attributes,
      String format,
      @Nullable Map<String, AssetXODescriptor> assetDescriptors)
  {
    Set<String> exposedAttributeKeys = Optional.ofNullable(assetDescriptors)
        .map(ad -> ad.get(format))
        .map(AssetXODescriptor::listExposedAttributeKeys)
        .orElse(Set.of());

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
   * Calculates the last modified date from the attributes map.
   *
   * @param attributes the attributes map
   * @return the last modified date, or null if not available
   */
  private static Date calculateLastModified(Map<String, Object> attributes) {
    String lastModifiedString =
        (String) ((Map<String, Object>) attributes.getOrDefault("content", Map.of()))
            .getOrDefault("last_modified", null);
    Date lastModified = null;
    if (lastModifiedString != null) {
      try {
        lastModified = new Date(Long.parseLong(lastModifiedString.trim()));
      }
      catch (Exception ignored) {
        // Nothing we can do here for invalid data. It shouldn't happen but date parsing will blow out the results.
      }
    }
    return lastModified;
  }

  /**
   * Creates a new builder for AssetXO.
   *
   * @return a new builder instance
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Gets the attributes map for JSON serialization.
   *
   * @return the attributes map
   */
  @JsonAnyGetter
  public Map<String, Object> getAttributes() {
    return attributes;
  }

  /**
   * Builder for {@link AssetXO}.
   * Adapted to work with the record-based implementation.
   */
  public static class Builder {
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
    private Map<String, Object> attributes;

    public Builder downloadUrl(String downloadUrl) {
      this.downloadUrl = downloadUrl;
      return this;
    }

    public Builder path(String path) {
      this.path = path;
      return this;
    }

    public Builder id(String id) {
      this.id = id;
      return this;
    }

    public Builder repository(String repository) {
      this.repository = repository;
      return this;
    }

    public Builder format(String format) {
      this.format = format;
      return this;
    }

    public Builder checksum(Map<String, String> checksum) {
      this.checksum = checksum;
      return this;
    }

    public Builder contentType(String contentType) {
      this.contentType = contentType;
      return this;
    }

    public Builder lastModified(Date lastModified) {
      this.lastModified = lastModified;
      return this;
    }

    public Builder lastDownloaded(Date lastDownloaded) {
      this.lastDownloaded = lastDownloaded;
      return this;
    }

    public Builder uploader(String uploader) {
      this.uploader = uploader;
      return this;
    }

    public Builder uploaderIp(String uploaderIp) {
      this.uploaderIp = uploaderIp;
      return this;
    }

    public Builder fileSize(Long fileSize) {
      this.fileSize = fileSize;
      return this;
    }

    public Builder blobCreated(Date blobCreated) {
      this.blobCreated = blobCreated;
      return this;
    }

    public Builder blobStoreName(String blobStoreName) {
      this.blobStoreName = blobStoreName;
      return this;
    }

    public Builder attributes(Map<String, Object> attributes) {
      this.attributes = attributes;
      return this;
    }

    public AssetXO build() {
      return new AssetXO(
          downloadUrl,
          path,
          id,
          repository,
          format,
          checksum,
          contentType,
          lastModified,
          lastDownloaded,
          uploader,
          uploaderIp,
          fileSize,
          blobCreated,
          blobStoreName,
          attributes
      );
    }
  }
}
