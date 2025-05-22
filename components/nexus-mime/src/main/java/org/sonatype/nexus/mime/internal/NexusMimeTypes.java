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
package org.sonatype.nexus.mime.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.annotation.Nullable;

import org.sonatype.nexus.mime.MimeRule;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.StringTemplate.STR;

/**
 * Parse mime type extensions and overrides from classpath.
 *
 * This class will look up '/builtin-mimetypes.properties' and '/nexus.mimetypes' from classpath.
 * These property files must have the following format:
 * <ul>
 * <li><em>additional mimetypes:</em>
 * A mapping from file name extension to a comma separated list of mime types (e.g. "bz2:
 * application/x-bzip2,application/x-bzip").
 * No whitespace is allowed in the list of mime types.</li>
 *
 * <li><em>overriding mimetypes:</em>
 * The format is the same as adding mime types, but prefixed with 'override.' (e.g. "override.html:
 * text/xhtml,application/xml").
 * These mime type definitions will override the builtin mime types.
 * The first listed mimetype is the 'primary' mime type and will be used by Nexus as the downstream content type.
 * </li>
 * </ul>
 *
 * @since 2.3
 */
public class NexusMimeTypes
{

  private static final Logger log = LoggerFactory.getLogger(NexusMimeTypes.class);

  public static final String BUILTIN_MIMETYPES_FILENAME = "builtin-mimetypes.properties";

  public static final String MIMETYPES_FILENAME = "nexus.mimetypes";

  private final Map<String, MimeRule> extensions = new ConcurrentHashMap<>();
  
  private final ReadWriteLock lock = new ReentrantReadWriteLock();

  public NexusMimeTypes() {
    loadMimeTypes();
  }
  
  private void loadMimeTypes() {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Load both property files concurrently using virtual threads
      Future<Properties> builtinFuture = executor.submit(() -> loadProperties(BUILTIN_MIMETYPES_FILENAME));
      Future<Properties> nexusFuture = executor.submit(() -> loadProperties(MIMETYPES_FILENAME));
      
      // Get the results and initialize mime types
      Properties builtinProperties = builtinFuture.get();
      if (builtinProperties != null) {
        initMimeTypes(builtinProperties);
      }
      
      Properties nexusProperties = nexusFuture.get();
      if (nexusProperties != null) {
        initMimeTypes(nexusProperties);
      }
    } catch (Exception e) {
      log.error(STR."Failed to load mime type definitions: \{e.getMessage()}", e);
    }
  }

  private Properties loadProperties(final String filename) {
    Properties properties = new Properties();
    String resourcePath = STR."/\{filename}";
    
    try (InputStream stream = this.getClass().getResourceAsStream(resourcePath)) {
      if (stream != null) {
        properties.load(stream);
        log.debug(STR."Successfully loaded mime type definitions from \{filename}");
        return properties;
      } else {
        log.debug(STR."No mime type definitions found at \{resourcePath}");
        return null;
      }
    } catch (IOException e) {
      if (log.isDebugEnabled()) {
        log.warn(STR."Could not load \{filename}: \{e}", e);
      } else {
        log.warn(STR."Could not load \{filename}: \{e.getMessage()}");
      }
      return null;
    }
  }

  @VisibleForTesting
  void initMimeTypes(final Properties properties) {
    try {
      lock.writeLock().lock();
      final Set<String> keys = properties.stringPropertyNames();
      final Map<String, List<String>> overrides = Maps.newHashMap();
      final Map<String, List<String>> additional = Maps.newHashMap();

      for (String key : keys) {
        if (key.startsWith("override.")) {
          overrides.put(key.substring("override.".length()), types(properties.getProperty(key, null)));
        }
        else {
          additional.put(key, types(properties.getProperty(key, null)));
        }
      }

      for (String extension : overrides.keySet()) {
        final List<String> mimetypes = overrides.get(extension);

        if (additional.containsKey(extension)) {
          mimetypes.addAll(additional.get(extension));
          additional.remove(extension);
        }
        this.extensions.put(extension, new MimeRule(true, mimetypes));
      }

      for (String extension : additional.keySet()) {
        final List<String> mimetypes = additional.get(extension);
        this.extensions.put(extension, new MimeRule(false, mimetypes));
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  private List<String> types(final String value) {
    if (value == null) {
      return Collections.emptyList();
    }
    else {
      return Lists.newArrayList(Splitter.on(",").split(value));
    }
  }

  /**
   * Method returning {@link MimeRule} for given extension (it must not contain leading dot!). If no rule exists, {@code
   * null} is returned.
   */
  @Nullable
  public MimeRule getMimeRuleForExtension(String extension) {
    try {
      lock.readLock().lock();
      while (!extension.isEmpty()) {
        if (extensions.containsKey(extension)) {
          return extensions.get(extension);
        }
        extension = Files.getFileExtension(extension);
      }
      return null;
    } finally {
      lock.readLock().unlock();
    }
  }
}