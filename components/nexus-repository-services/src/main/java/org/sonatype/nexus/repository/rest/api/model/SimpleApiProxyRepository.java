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

import java.util.Optional;
import javax.validation.constraints.NotNull;

import org.sonatype.nexus.repository.types.ProxyType;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModelProperty;

/**
 * API Proxy Repository for simple formats which do not have custom attributes for proxies.
 * Updated for Java 21 compatibility with Record-based attribute classes.
 *
 * @since 3.20
 */
@JsonIgnoreProperties(value = {"format", "type", "url"}, allowGetters = true)
public class SimpleApiProxyRepository
    extends AbstractApiRepository
{
  @NotNull
  protected final StorageAttributes storage;

  protected final CleanupPolicyAttributes cleanup;

  @NotNull
  protected final ProxyAttributes proxy;

  @NotNull
  protected final NegativeCacheAttributes negativeCache;

  @NotNull
  protected final HttpClientAttributes httpClient;

  @ApiModelProperty(value = "The name of the routing rule assigned to this repository")
  protected final String routingRuleName;

  protected final ReplicationAttributes replication;

  @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
  public SimpleApiProxyRepository(
      @JsonProperty("name") final String name,
      @JsonProperty("format") final String format,
      @JsonProperty("url") final String url,
      @JsonProperty("online") final Boolean online,
      @JsonProperty("storage") final StorageAttributes storage,
      @JsonProperty("cleanup") final CleanupPolicyAttributes cleanup,
      @JsonProperty("proxy") final ProxyAttributes proxy,
      @JsonProperty("negativeCache") final NegativeCacheAttributes negativeCache,
      @JsonProperty("httpClient") final HttpClientAttributes httpClient,
      @JsonProperty("routingRuleName") final String routingRuleName,
      @JsonProperty("replication") @JsonInclude(value= Include.NON_EMPTY, content=Include.NON_NULL)
      final ReplicationAttributes replication)
  {
    super(name, format, ProxyType.NAME, url, online);
    this.storage = storage;
    this.cleanup = cleanup;
    this.proxy = proxy;
    this.negativeCache = negativeCache;
    this.httpClient = httpClient;
    this.routingRuleName = routingRuleName;
    this.replication = replication;
 }

  /**
   * Returns the storage attributes for this repository.
   * 
   * @return the storage attributes record
   */
  public StorageAttributes getStorage() {
    return storage;
  }

  /**
   * Returns the cleanup policy attributes for this repository.
   * 
   * @return the cleanup policy attributes record, may be null
   */
  public CleanupPolicyAttributes getCleanup() {
    return cleanup;
  }

  /**
   * Returns the proxy attributes for this repository.
   * 
   * @return the proxy attributes record
   */
  public ProxyAttributes getProxy() {
    return proxy;
  }

  /**
   * Returns the negative cache attributes for this repository.
   * 
   * @return the negative cache attributes record
   */
  public NegativeCacheAttributes getNegativeCache() {
    return negativeCache;
  }

  /**
   * Returns the HTTP client attributes for this repository.
   * 
   * @return the HTTP client attributes record
   */
  public HttpClientAttributes getHttpClient() {
    return httpClient;
  }

  /**
   * Returns the routing rule name for this repository.
   * 
   * @return the routing rule name, may be null
   */
  public String getRoutingRuleName() {
    return routingRuleName;
  }

  /**
   * Returns the replication attributes for this repository.
   * 
   * @return the replication attributes record, may be null
   */
  public ReplicationAttributes getReplication() { 
    return replication; 
  }
  
  /**
   * Utility method that demonstrates the use of Record Patterns with Java 21.
   * Extracts the blob store name from the storage attributes using Record Pattern matching.
   *
   * @return the blob store name
   */
  public String getBlobStoreName() {
    // Using Record Pattern to extract the blobStoreName component directly
    if (storage instanceof StorageAttributes(var blobStoreName, var strictContentTypeValidation)) {
      return blobStoreName;
    }
    return null;
  }
  
  /**
   * Utility method that demonstrates the use of Record Patterns with Java 21.
   * Extracts the remote URL from the proxy attributes using Record Pattern matching.
   *
   * @return the remote URL
   */
  public String getRemoteUrl() {
    // Using Record Pattern to extract the remoteUrl component directly
    if (proxy instanceof ProxyAttributes(var remoteUrl, var contentMaxAge, var metadataMaxAge)) {
      return remoteUrl;
    }
    return null;
  }
  
  /**
   * Utility method that demonstrates the use of Record Patterns with Java 21.
   * Creates a configuration summary string using Record Patterns to extract multiple components.
   *
   * @return a configuration summary string
   */
  public String getConfigurationSummary() {
    StringBuilder summary = new StringBuilder();
    
    // Using Record Patterns to extract components from multiple records
    if (storage instanceof StorageAttributes(var blobStoreName, var strictContentTypeValidation)) {
      summary.append("Storage: ").append(blobStoreName)
             .append(" (strict validation: ").append(strictContentTypeValidation).append(")\n");
    }
    
    if (proxy instanceof ProxyAttributes(var remoteUrl, var contentMaxAge, var metadataMaxAge)) {
      summary.append("Proxy: ").append(remoteUrl)
             .append(" (content cache: ").append(contentMaxAge)
             .append(" min, metadata cache: ").append(metadataMaxAge).append(" min)\n");
    }
    
    if (httpClient instanceof HttpClientAttributes(var blocked, var autoBlock, var connection)) {
      summary.append("HTTP Client: blocked=").append(blocked)
             .append(", autoBlock=").append(autoBlock).append("\n");
    }
    
    return summary.toString();
  }
  
  /**
   * Utility method that demonstrates the use of Optional with Record Patterns in Java 21.
   * Safely extracts the content max age from proxy attributes.
   *
   * @return an Optional containing the content max age if available
   */
  public Optional<Integer> getContentMaxAge() {
    // Using Record Pattern with Optional for safe access
    return Optional.ofNullable(proxy)
        .map(p -> {
          if (p instanceof ProxyAttributes(var remoteUrl, var contentMaxAge, var metadataMaxAge)) {
            return contentMaxAge;
          }
          return null;
        });
  }
}