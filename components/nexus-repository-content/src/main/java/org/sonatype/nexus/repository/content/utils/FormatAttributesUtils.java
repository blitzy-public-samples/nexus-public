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
package org.sonatype.nexus.repository.content.utils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.RepositoryContent;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.content.fluent.FluentAttributes;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @since 3.29
 */
public class FormatAttributesUtils
{
  private static final Logger LOG = LoggerFactory.getLogger(FormatAttributesUtils.class);

  public static Map<String, Object> getFormatAttributes(final FluentAsset fluent) {
    return getFormatAttributes(fluent.repository().getFormat().getValue(), fluent);
  }

  public static Map<String, Object> getFormatAttributes(final Asset asset, final String formatName) {
    return getFormatAttributes(formatName, asset);
  }

  public static Map<String, Object> getFormatAttributes(final FluentComponent fluent) {
    return getFormatAttributes(fluent.repository().getFormat().getValue(), fluent);
  }

  public static void setFormatAttributes(final FluentAsset fluent, final Map<String, Object> values) {
    setFormatAttributes(fluent.repository().getFormat().getValue(), fluent, fluent, values);
  }

  public static void setFormatAttributes(final FluentAsset fluent, final Supplier<Map<String, Object>> supplier) {
    setFormatAttributes(fluent, supplier.get());
  }

  public static void setFormatAttributes(final FluentAsset fluent, final String key, final Object value) {
    setFormatAttributes(
        fluent,
        Collections.singletonMap(key, value)
    );
  }

  public static void setFormatAttributes(final FluentComponent fluent, final Map<String, Object> values) {
    setFormatAttributes(fluent.repository().getFormat().getValue(), fluent, fluent, values);
  }

  public static void removeFormatAttributes(final FluentComponent fluent, final Set<String> keys) {
    removeFormatAttributes(
        fluent.repository().getFormat().getValue(),
        fluent,
        fluent,
        keys
    );
  }

  public static void removeFormatAttributes(final FluentAsset fluent, final Set<String> keys) {
    removeFormatAttributes(
        fluent.repository().getFormat().getValue(),
        fluent,
        fluent,
        keys
    );
  }

  public static void removeFormatAttributes(final FluentComponent fluent, final String key) {
    removeFormatAttributes(
        fluent,
        Collections.singleton(key)
    );
  }

  public static void removeFormatAttributes(final FluentAsset fluent, final String key) {
    removeFormatAttributes(
        fluent,
        Collections.singleton(key)
    );
  }

  private static Map<String, Object> getFormatAttributes(
      final String formatName,
      final RepositoryContent repositoryContent)
  {
    Object attributesObj = repositoryContent.attributes().get(formatName);
    
    // Using Java 21 Pattern Matching for instanceof to check and cast in one step
    if (attributesObj instanceof Map<?, ?> attributesMap) {
      // Safe to cast since we've verified it's a Map
      @SuppressWarnings("unchecked")
      Map<String, Object> attributes = (Map<String, Object>) attributesMap;
      LOG.debug(STR."Retrieved format attributes for format \{formatName} with \{attributes.size()} entries");
      //  "Attributes" can be CollectionSingletonMap or ImmutableMap for example,
      //    that does not support methods such as put, putAll and so on.
      //  To support setFormatAttributes, removeFormatAttributes, here is necessary to repack it into "Map",
      //    that supports put, remove. HashMap - good candidate.
      return new HashMap<>(attributes);
    }
    
    LOG.debug(STR."No format attributes found for format \{formatName}, creating empty map");
    return new HashMap<>();
  }

  private static void setFormatAttributes(
      final String formatName,
      final RepositoryContent repositoryContent,
      final FluentAttributes attributes,
      final Map<String, Object> values)
  {
    Map<String, Object> formatAttributes = getFormatAttributes(formatName, repositoryContent);
    formatAttributes.putAll(values);
    LOG.debug(STR."Setting format attributes for format \{formatName} with \{values.size()} new values");
    attributes.withAttribute(formatName, formatAttributes);
  }

  private static void removeFormatAttributes(
      final String formatName,
      final RepositoryContent repositoryContent,
      final FluentAttributes attributes,
      final Set<String> keys)
  {
    Map<String, Object> formatAttributes = getFormatAttributes(formatName, repositoryContent);
    LOG.debug(STR."Removing \{keys.size()} format attributes for format \{formatName}");
    keys.forEach(key -> formatAttributes.remove(key));
    attributes.withAttribute(formatName, formatAttributes);
  }
}