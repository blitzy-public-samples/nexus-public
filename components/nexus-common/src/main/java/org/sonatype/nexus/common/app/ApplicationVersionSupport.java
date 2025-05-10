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
package org.sonatype.nexus.common.app;

import java.io.InputStream;
import java.net.URL;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Objects;

import org.sonatype.goodies.common.ComponentSupport;

import com.google.common.annotations.VisibleForTesting;

/**
 * Support for {@link ApplicationVersion} implementations.
 *
 * @since 3.0
 */
public abstract class ApplicationVersionSupport
    extends ComponentSupport
    implements ApplicationVersion
{
  public static final String UNKNOWN = "UNKNOWN";

  @VisibleForTesting
  static final String VERSION = "version";

  @VisibleForTesting
  static final String BUILD_REVISION = "build.revision";

  @VisibleForTesting
  static final String BUILD_TIMESTAMP = "build.timestamp";

  @VisibleForTesting
  static final String NEXUS2_VERSION = "nexus2.version";

  /**
   * Resource name of properties-file which contains version information.
   */
  private static final String RESOURCE_NAME = "version.properties";

  /**
   * Thread-safe lazy initialization of properties.
   */
  private final AtomicReference<Properties> propertiesRef = new AtomicReference<>();

  /**
   * Load or return cached properties.
   * Thread-safe implementation using AtomicReference for lazy initialization.
   * 
   * This implementation ensures that:
   * 1. Properties are loaded only once (lazy initialization)
   * 2. Thread safety is maintained during initialization
   * 3. All threads see the same instance after initialization
   */
  @VisibleForTesting
  Properties getProperties() {
    // Fast path - check if properties are already loaded
    Properties properties = propertiesRef.get();
    if (properties != null) {
      return properties;
    }

    // Slow path - load properties (may happen concurrently in multiple threads)
    Properties newProps = new Properties();
    URL url = ApplicationVersionSupport.class.getResource(RESOURCE_NAME);
    if (url != null) {
      log.debug("Loading properties from: {}", url);

      try (InputStream input = url.openStream()) {
        newProps.load(input);
        log.trace("Loaded properties: {}", newProps);
      }
      catch (Exception e) {
        log.error("Failed to load properties from: {}", url, e);
      }
    }
    else {
      log.error("Missing required resource: {}", RESOURCE_NAME);
    }

    // Use compareAndSet to ensure thread safety during initialization
    if (!propertiesRef.compareAndSet(null, newProps)) {
      // Another thread initialized the properties first, use that instance
      return propertiesRef.get();
    }
    return newProps;
  }

  /**
   * Retrieves a property value by key, returning UNKNOWN if not found.
   * 
   * @param key the property key to look up
   * @return the property value or UNKNOWN if not found
   */
  private String property(final String key) {
    Objects.requireNonNull(key, "Property key cannot be null");
    return getProperties().getProperty(key, UNKNOWN);
  }

  @Override
  public String getVersion() {
    return property(VERSION);
  }

  @Override
  public String getBrandedEditionAndVersion() {
    String edition = getEdition();
    String version = getVersion();
    return ("OSS".equals(edition) ? edition + " " : "") + version;
  }

  @Override
  public String getBuildRevision() {
    return property(BUILD_REVISION);
  }

  @Override
  public String getBuildTimestamp() {
    return property(BUILD_TIMESTAMP);
  }

  @Override
  public String getNexus2CompatibleVersion() {
    return property(NEXUS2_VERSION);
  }
}