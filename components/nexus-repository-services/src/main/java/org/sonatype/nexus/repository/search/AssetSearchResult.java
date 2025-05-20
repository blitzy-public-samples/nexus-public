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

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Result of an Asset search
 *
 * @since 3.38
 */
public class AssetSearchResult implements Serializable
{
  private static final long serialVersionUID = 1L;

  private final String path;

  private final String id;

  private final String repository;

  private final String format;

  private final Map<String, String> checksum;

  private final String contentType;

  private final OffsetDateTime lastModified;

  private final OffsetDateTime lastDownloaded;

  private final OffsetDateTime blobCreated;

  private final Long fileSize;

  private final String uploader;

  private final String uploaderIp;

  private final Map<String, Object> attributes;

  /**
   * Creates a new AssetSearchResult from the provided builder.
   *
   * @param builder the builder containing the asset search result data
   */
  private AssetSearchResult(final Builder builder) {
    this.path = builder.path;
    this.id = builder.id;
    this.repository = builder.repository;
    this.format = builder.format;
    this.checksum = builder.checksum != null ? Map.copyOf(builder.checksum) : Map.of();
    this.contentType = builder.contentType;
    this.lastModified = builder.lastModified;
    this.lastDownloaded = builder.lastDownloaded;
    this.blobCreated = builder.blobCreated;
    this.fileSize = builder.fileSize;
    this.uploader = builder.uploader;
    this.uploaderIp = builder.uploaderIp;
    this.attributes = builder.attributes != null ? Map.copyOf(builder.attributes) : Map.of();
  }

  /**
   * Creates a new builder for AssetSearchResult.
   *
   * @return a new builder instance
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Creates a new builder initialized with values from an existing AssetSearchResult.
   *
   * @param result the AssetSearchResult to copy values from
   * @return a new builder instance with copied values
   */
  public static Builder builderFrom(final AssetSearchResult result) {
    return new Builder()
        .path(result.path)
        .id(result.id)
        .repository(result.repository)
        .format(result.format)
        .checksum(result.checksum)
        .contentType(result.contentType)
        .lastModified(result.lastModified)
        .lastDownloaded(result.lastDownloaded)
        .blobCreated(result.blobCreated)
        .fileSize(result.fileSize)
        .uploader(result.uploader)
        .uploaderIp(result.uploaderIp)
        .attributes(result.attributes);
  }

  /**
   * Gets the asset path.
   * 
   * @return the asset path
   */
  public String getPath() {
    return path;
  }

  /**
   * Gets the asset ID.
   * 
   * @return the asset ID
   */
  public String getId() {
    return id;
  }

  /**
   * Gets the repository name.
   * 
   * @return the repository name
   */
  public String getRepository() {
    return repository;
  }

  /**
   * Gets the asset format.
   * 
   * @return the asset format
   */
  public String getFormat() {
    return format;
  }

  /**
   * Gets the asset checksums.
   * 
   * @return the asset checksums map, never null
   */
  public Map<String, String> getChecksum() {
    return checksum;
  }

  /**
   * Gets the asset content type.
   * 
   * @return the asset content type
   */
  public String getContentType() {
    return contentType;
  }

  /**
   * Gets the asset last modified date.
   * 
   * @return the asset last modified date
   */
  public OffsetDateTime getLastModified() {
    return lastModified;
  }

  /**
   * Gets the asset last downloaded date.
   * 
   * @return the asset last downloaded date
   */
  public OffsetDateTime getLastDownloaded() {
    return lastDownloaded;
  }

  /**
   * Gets the asset blob created date.
   * 
   * @return the asset blob created date
   */
  public OffsetDateTime getBlobCreated() {
    return blobCreated;
  }

  /**
   * Gets the asset file size.
   * 
   * @return the asset file size
   */
  public Long getFileSize() {
    return fileSize;
  }

  /**
   * Gets the asset uploader.
   * 
   * @return the asset uploader
   */
  public String getUploader() {
    return uploader;
  }

  /**
   * Gets the asset uploader IP.
   * 
   * @return the asset uploader IP
   */
  public String getUploaderIp() {
    return uploaderIp;
  }

  /**
   * Gets the asset attributes.
   * 
   * @return the asset attributes map, never null
   */
  public Map<String, Object> getAttributes() {
    return attributes;
  }

  /**
   * For backward compatibility with code that expects Date objects.
   * 
   * @return the last modified date as a java.util.Date, or null if lastModified is null
   * @deprecated Use {@link #getLastModified()} instead
   */
  @Deprecated
  public Date getLastModifiedDate() {
    return lastModified != null ? Date.from(lastModified.toInstant()) : null;
  }

  /**
   * For backward compatibility with code that expects Date objects.
   * 
   * @return the last downloaded date as a java.util.Date, or null if lastDownloaded is null
   * @deprecated Use {@link #getLastDownloaded()} instead
   */
  @Deprecated
  public Date getLastDownloadedDate() {
    return lastDownloaded != null ? Date.from(lastDownloaded.toInstant()) : null;
  }

  /**
   * For backward compatibility with code that expects Date objects.
   * 
   * @return the blob created date as a java.util.Date, or null if blobCreated is null
   * @deprecated Use {@link #getBlobCreated()} instead
   */
  @Deprecated
  public Date getBlobCreatedDate() {
    return blobCreated != null ? Date.from(blobCreated.toInstant()) : null;
  }

  @Override
  public String toString() {
    return "AssetSearchResult [path=" + path + ", id=" + id + ", repository=" + repository + ", format=" + format
        + ", checksum=" + checksum + ", contentType=" + contentType + ", lastModified=" + lastModified
        + ", lastDownloaded=" + lastDownloaded + ", blobCreated=" + blobCreated + ", fileSize=" + fileSize
        + ", uploader=" + uploader + ", uploaderIp=" + uploaderIp + ", attributes=" + attributes + "]";
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    AssetSearchResult that = (AssetSearchResult) o;
    return Objects.equals(id, that.id) &&
           Objects.equals(repository, that.repository) &&
           Objects.equals(path, that.path);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, repository, path);
  }

  /**
   * Pattern matching method to extract asset data using Java 21 Record Patterns.
   * This method allows for more efficient data extraction from search results.
   *
   * @param <R> the return type
   * @param mapper the function to map asset data to the return type
   * @return the mapped result
   */
  public <R> R match(AssetDataMapper<R> mapper) {
    return mapper.map(path, id, repository, format, checksum, contentType, lastModified, 
                      lastDownloaded, blobCreated, fileSize, uploader, uploaderIp, attributes);
  }

  /**
   * Functional interface for mapping asset data using pattern matching.
   *
   * @param <R> the return type
   */
  @FunctionalInterface
  public interface AssetDataMapper<R> {
    /**
     * Maps asset data to the return type.
     *
     * @param path the asset path
     * @param id the asset ID
     * @param repository the repository name
     * @param format the asset format
     * @param checksum the asset checksums map
     * @param contentType the asset content type
     * @param lastModified the asset last modified date
     * @param lastDownloaded the asset last downloaded date
     * @param blobCreated the asset blob created date
     * @param fileSize the asset file size
     * @param uploader the asset uploader
     * @param uploaderIp the asset uploader IP
     * @param attributes the asset attributes map
     * @return the mapped result
     */
    R map(String path, String id, String repository, String format, Map<String, String> checksum,
          String contentType, OffsetDateTime lastModified, OffsetDateTime lastDownloaded,
          OffsetDateTime blobCreated, Long fileSize, String uploader, String uploaderIp,
          Map<String, Object> attributes);
  }
  
  /**
   * Creates a record-like representation of this asset for use with Java 21 Record Patterns.
   * This allows for pattern matching in switch expressions and instanceof checks.
   * 
   * @return a record containing the asset data
   */
  public AssetRecord toRecord() {
    return new AssetRecord(path, id, repository, format, checksum, contentType, lastModified,
                         lastDownloaded, blobCreated, fileSize, uploader, uploaderIp, attributes);
  }
  
  /**
   * Record representation of AssetSearchResult for use with Java 21 Record Patterns.
   */
  public record AssetRecord(String path, String id, String repository, String format, 
                           Map<String, String> checksum, String contentType, OffsetDateTime lastModified,
                           OffsetDateTime lastDownloaded, OffsetDateTime blobCreated, Long fileSize,
                           String uploader, String uploaderIp, Map<String, Object> attributes) {}

  /**
   * Builder for {@link AssetSearchResult}.
   */
  public static class Builder {
    private String path;
    private String id;
    private String repository;
    private String format;
    private Map<String, String> checksum;
    private String contentType;
    private OffsetDateTime lastModified;
    private OffsetDateTime lastDownloaded;
    private OffsetDateTime blobCreated;
    private Long fileSize;
    private String uploader;
    private String uploaderIp;
    private Map<String, Object> attributes;

    /**
     * Sets the asset path.
     * 
     * @param path the asset path
     * @return this builder
     */
    public Builder path(final String path) {
      this.path = path;
      return this;
    }

    /**
     * Sets the asset ID.
     * 
     * @param id the asset ID
     * @return this builder
     */
    public Builder id(final String id) {
      this.id = id;
      return this;
    }

    /**
     * Sets the repository name.
     * 
     * @param repository the repository name
     * @return this builder
     */
    public Builder repository(final String repository) {
      this.repository = repository;
      return this;
    }

    /**
     * Sets the asset format.
     * 
     * @param format the asset format
     * @return this builder
     */
    public Builder format(final String format) {
      this.format = format;
      return this;
    }

    /**
     * Sets the asset checksums map.
     * 
     * @param checksum the asset checksums map
     * @return this builder
     */
    public Builder checksum(final Map<String, String> checksum) {
      this.checksum = checksum != null ? new HashMap<>(checksum) : null;
      return this;
    }

    /**
     * Sets the asset content type.
     * 
     * @param contentType the asset content type
     * @return this builder
     */
    public Builder contentType(final String contentType) {
      this.contentType = contentType;
      return this;
    }

    /**
     * Sets the asset last modified date.
     * 
     * @param lastModified the asset last modified date
     * @return this builder
     */
    public Builder lastModified(final OffsetDateTime lastModified) {
      this.lastModified = lastModified;
      return this;
    }

    /**
     * Sets the asset last downloaded date.
     * 
     * @param lastDownloaded the asset last downloaded date
     * @return this builder
     */
    public Builder lastDownloaded(final OffsetDateTime lastDownloaded) {
      this.lastDownloaded = lastDownloaded;
      return this;
    }

    /**
     * Sets the asset blob created date.
     * 
     * @param blobCreated the asset blob created date
     * @return this builder
     */
    public Builder blobCreated(final OffsetDateTime blobCreated) {
      this.blobCreated = blobCreated;
      return this;
    }

    /**
     * Sets the asset file size.
     * 
     * @param fileSize the asset file size
     * @return this builder
     */
    public Builder fileSize(final Long fileSize) {
      this.fileSize = fileSize;
      return this;
    }

    /**
     * Sets the asset uploader.
     * 
     * @param uploader the asset uploader
     * @return this builder
     */
    public Builder uploader(final String uploader) {
      this.uploader = uploader;
      return this;
    }

    /**
     * Sets the asset uploader IP.
     * 
     * @param uploaderIp the asset uploader IP
     * @return this builder
     */
    public Builder uploaderIp(final String uploaderIp) {
      this.uploaderIp = uploaderIp;
      return this;
    }

    /**
     * Sets the asset attributes map.
     * 
     * @param attributes the asset attributes map
     * @return this builder
     */
    public Builder attributes(final Map<String, Object> attributes) {
      this.attributes = attributes != null ? new HashMap<>(attributes) : null;
      return this;
    }

    /**
     * For backward compatibility with code that uses Date objects.
     * 
     * @param lastModified the asset last modified date as a java.util.Date
     * @return this builder
     * @deprecated Use {@link #lastModified(OffsetDateTime)} instead
     */
    @Deprecated
    public Builder lastModifiedDate(final Date lastModified) {
      if (lastModified != null) {
        this.lastModified = OffsetDateTime.ofInstant(lastModified.toInstant(), 
                                                   java.time.ZoneId.systemDefault());
      }
      return this;
    }

    /**
     * For backward compatibility with code that uses Date objects.
     * 
     * @param lastDownloaded the asset last downloaded date as a java.util.Date
     * @return this builder
     * @deprecated Use {@link #lastDownloaded(OffsetDateTime)} instead
     */
    @Deprecated
    public Builder lastDownloadedDate(final Date lastDownloaded) {
      if (lastDownloaded != null) {
        this.lastDownloaded = OffsetDateTime.ofInstant(lastDownloaded.toInstant(), 
                                                     java.time.ZoneId.systemDefault());
      }
      return this;
    }

    /**
     * For backward compatibility with code that uses Date objects.
     * 
     * @param blobCreated the asset blob created date as a java.util.Date
     * @return this builder
     * @deprecated Use {@link #blobCreated(OffsetDateTime)} instead
     */
    @Deprecated
    public Builder blobCreatedDate(final Date blobCreated) {
      if (blobCreated != null) {
        this.blobCreated = OffsetDateTime.ofInstant(blobCreated.toInstant(), 
                                                  java.time.ZoneId.systemDefault());
      }
      return this;
    }

    /**
     * Builds a new AssetSearchResult instance.
     * 
     * @return a new AssetSearchResult instance
     */
    public AssetSearchResult build() {
      return new AssetSearchResult(this);
    }
  }
}
