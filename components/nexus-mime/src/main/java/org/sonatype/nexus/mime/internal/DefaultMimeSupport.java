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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.mime.MimeRule;
import org.sonatype.nexus.mime.MimeRulesSource;
import org.sonatype.nexus.mime.MimeSupport;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.detect.Detector;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MimeTypes;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Default {@link MimeSupport} implementation based on Apache Tika.
 *
 * @since 2.0
 */
@Named
@Singleton
public class DefaultMimeSupport
    implements MimeSupport
{
  private final TikaConfig tikaConfig;

  /**
   * "Low" level Tika detector, as {@link org.apache.tika.Tika} hides too much.
   */
  private final Detector detector;

  /**
   * A loading cache of extension to MIME type.
   */
  private final LoadingCache<String, List<String>> extensionToMimeTypeCache;
  
  /**
   * Thread factory for virtual threads to handle I/O-bound operations.
   */
  private final ThreadFactory virtualThreadFactory;
  
  /**
   * Executor service for parallelized MIME detection on large files.
   */
  private final ExecutorService executorService;

  @Inject
  public DefaultMimeSupport() {
    this(new NexusMimeTypes());
  }

  @VisibleForTesting
  public DefaultMimeSupport(final NexusMimeTypes nexusMimeTypes) {
    this.tikaConfig = TikaConfig.getDefaultConfig();
    this.detector = tikaConfig.getDetector();
    this.virtualThreadFactory = Thread.ofVirtual().factory();
    this.executorService = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    // create the cache
    extensionToMimeTypeCache =
        CacheBuilder.newBuilder().maximumSize(500).build(new CacheLoader<String, List<String>>()
        {
          @Override
          public List<String> load(final String key)
              throws Exception
          {
            // Use virtual thread to load cache entries
            return executorService.submit(() -> {
              final List<String> detected = Lists.newArrayList();
              final MimeRule mimeType = nexusMimeTypes.getMimeRuleForExtension(key);
              if (mimeType != null) {
                // add Nexus matches first
                detected.addAll(mimeType.getMimetypes());
                if (mimeType.isOverride()) {
                  return detected;
                }
              }
              // ask Tika too
              final Metadata metadata = new Metadata();
              metadata.set(Metadata.RESOURCE_NAME_KEY, STR."dummy.\{key}");
              MediaType mediaType = detector.detect(null, metadata);
              // unravel to least specific
              unravel(detected, mediaType);
              return detected;
            }).get();
          }
        });
  }

  @Nonnull
  @Override
  public String guessMimeTypeFromPath(final String path, final MimeRulesSource... mimeRulesSources) {
    return guessMimeTypesListFromPath(path, mimeRulesSources).get(0);
  }

  @Nonnull
  @Override
  public List<String> guessMimeTypesListFromPath(final String path, final MimeRulesSource... mimeRulesSources) {
    checkNotNull(path);
    List<String> mimeTypes = new ArrayList<>();
    for (MimeRulesSource mimeRulesSource : mimeRulesSources) {
      final MimeRule mimeRule = mimeRulesSource.getRuleForName(path);
      if (mimeRule != null) {
        mimeTypes.addAll(mimeRule.getMimetypes());
        if (mimeRule.isOverride()) {
          return mimeTypes;
        }
      }
    }
    mimeTypes.addAll(guessMimeTypesListFromPath(path));
    return mimeTypes;
  }

  @Nonnull
  @Override
  public String detectMimeType(final InputStream input, @Nullable final String fileName) throws IOException {
    return detectMimeTypes(input, fileName).get(0);
  }

  @Nonnull
  @Override
  public List<String> detectMimeTypes(final InputStream input, @Nullable final String fileName) throws IOException {
    checkNotNull(input);

    // Use virtual threads for I/O-bound operations
    try {
      return executorService.submit(() -> detectMimeTypesInternal(input, fileName)).get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(STR."MIME type detection interrupted: \{e.getMessage()}");
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException) {
        throw (IOException) cause;
      }
      throw new IOException(STR."Failed to detect MIME type: \{cause.getMessage()}", cause);
    }
  }

  /**
   * Internal method to detect MIME types, executed within a virtual thread.
   */
  private List<String> detectMimeTypesInternal(final InputStream input, @Nullable final String fileName) throws IOException {
    List<String> detected = Lists.newArrayList();
    Metadata metadata = new Metadata();
    if (fileName != null) {
      metadata.set(Metadata.RESOURCE_NAME_KEY, fileName);
    }

    MediaType mediaType;
    try (final TikaInputStream tis = TikaInputStream.get(input)) {
      mediaType = detector.detect(tis, metadata);
    }

    // unravel to least specific
    unravel(detected, mediaType);

    if (detected.isEmpty()) {
      detected.add(MimeTypes.OCTET_STREAM);
    }

    return detected;
  }

  /**
   * Uses extension to mime types cache backed by {@link NexusMimeTypes} and Tika registry.
   */
  @Nonnull
  private List<String> guessMimeTypesListFromPath(final String path) {
    checkNotNull(path);
    final String pathExtension = Files.getFileExtension(path);
    try {
      final List<String> mimeTypes = new ArrayList<>();
      List<String> extBasedTypes = extensionToMimeTypeCache.get(pathExtension);
      if (extBasedTypes != null && !extBasedTypes.isEmpty()) {
        mimeTypes.addAll(extBasedTypes);
      }
      if (mimeTypes.isEmpty()) {
        mimeTypes.add(MimeTypes.OCTET_STREAM);
      }
      return mimeTypes;
    }
    catch (ExecutionException e) {
      throw new RuntimeException(STR."Failed to get MIME types for extension '\{pathExtension}': \{e.getMessage()}", e);
    }
  }

  /**
   * Unravels media type by aliases and supertype recursively.
   */
  private void unravel(final List<String> detected, final MediaType mt) {
    detected.add(mt.getBaseType().toString());
    // add aliases, for example "application/xml" type has "text/xml" alias
    for (MediaType alias : tikaConfig.getMediaTypeRegistry().getAliases(mt)) {
      detected.add(alias.getBaseType().toString());
    }
    // add super types if augmented with "+suffix"
    // Note: Tika supports +xml and +zip only
    if (mt.getSubtype().endsWith("+xml") || mt.getSubtype().endsWith("+zip")) {
      detected.add(tikaConfig.getMediaTypeRegistry().getSupertype(mt).toString());
    }
  }
}