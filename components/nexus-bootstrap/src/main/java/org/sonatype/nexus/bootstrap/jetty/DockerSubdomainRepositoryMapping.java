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
package org.sonatype.nexus.bootstrap.jetty;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Maps Docker subdomains to repository names for Docker registry request routing.
 * <p>
 * This class provides thread-safe operations for managing Docker subdomain to repository mappings
 * using Java 21 concurrent features and pattern matching for improved performance and reliability.
 */
public class DockerSubdomainRepositoryMapping
{
  private static final Logger log = Logger.getLogger(DockerSubdomainRepositoryMapping.class.getName());
  private static final Map<String, String> map = new ConcurrentHashMap<>();

  private DockerSubdomainRepositoryMapping() {
    // empty
  }

  /**
   * Retrieves the repository name associated with the subdomain in the given host header.
   * <p>
   * Uses pattern matching to extract the subdomain from the host header.
   *
   * @param hostHeader the host header from the HTTP request
   * @return the repository name or null if no mapping exists
   */
  public static String get(final String hostHeader) {
    if (hostHeader == null) {
      log.fine(STR."No host header provided for Docker subdomain lookup");
      return null;
    }
    
    // Use pattern matching to extract the subdomain
    return switch (hostHeader) {
      case String h when h.indexOf(".") > 0 -> {
        String subdomain = h.substring(0, h.indexOf("."));
        String repositoryName = map.get(subdomain);
        if (repositoryName != null) {
          log.fine(STR."Found repository mapping: \{subdomain} -> \{repositoryName}");
        } else {
          log.fine(STR."No repository mapping found for subdomain: \{subdomain}");
        }
        yield repositoryName;
      }
      default -> {
        log.fine(STR."Invalid host header format: \{hostHeader}");
        yield null;
      }
    };
  }

  /**
   * Associates a subdomain with a repository name.
   * <p>
   * Uses ConcurrentHashMap's thread-safe operations for reliable concurrent access.
   *
   * @param subdomain the Docker subdomain
   * @param repositoryName the repository name
   * @return the previous repository name associated with the subdomain, or null if there was no mapping
   */
  public static String put(final String subdomain, final String repositoryName) {
    if (subdomain == null || repositoryName == null) {
      log.warning(STR."Cannot create mapping with null values: subdomain=\{subdomain}, repositoryName=\{repositoryName}");
      return null;
    }
    
    String previous = map.put(subdomain, repositoryName);
    if (previous != null) {
      log.fine(STR."Updated repository mapping: \{subdomain} -> \{repositoryName} (was: \{previous})");
    } else {
      log.fine(STR."Created new repository mapping: \{subdomain} -> \{repositoryName}");
    }
    return previous;
  }

  /**
   * Removes the mapping for a subdomain.
   * <p>
   * Uses ConcurrentHashMap's thread-safe operations for reliable concurrent access.
   *
   * @param subdomain the Docker subdomain
   * @return the repository name that was associated with the subdomain, or null if there was no mapping
   */
  public static String remove(final String subdomain) {
    if (subdomain == null) {
      log.warning(STR."Cannot remove mapping for null subdomain");
      return null;
    }
    
    String removed = map.remove(subdomain);
    if (removed != null) {
      log.fine(STR."Removed repository mapping: \{subdomain} -> \{removed}");
    } else {
      log.fine(STR."No repository mapping found to remove for subdomain: \{subdomain}");
    }
    return removed;
  }
  
  /**
   * Checks if a mapping exists for the given subdomain.
   * <p>
   * Provides a thread-safe way to check for existence without retrieving the value.
   *
   * @param subdomain the Docker subdomain
   * @return true if a mapping exists, false otherwise
   */
  public static boolean containsSubdomain(final String subdomain) {
    boolean exists = subdomain != null && map.containsKey(subdomain);
    log.fine(STR."Checking if subdomain exists: \{subdomain} -> \{exists}");
    return exists;
  }
  
  /**
   * Returns the number of subdomain mappings currently stored.
   *
   * @return the number of mappings
   */
  public static int size() {
    return map.size();
  }
}