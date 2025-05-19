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
package org.sonatype.nexus.repository.content.security.internal;

import java.util.Map;
import java.util.logging.Logger;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.content.security.AssetVariableResolverSupport;
import org.sonatype.nexus.repository.search.AssetSearchResult;
import org.sonatype.nexus.repository.search.ComponentSearchResult;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.selector.VariableSourceBuilder;

import org.elasticsearch.search.lookup.SourceLookup;

/**
 * Simple implementation that will expose the path/format variable resolvers.
 * 
 * This implementation is compatible with Java 21 and utilizes String Templates (STR) 
 * for enhanced debug logging and improved code readability.
 *
 * @since 3.24
 */
@Named("simple")
@Singleton
public class SimpleVariableResolverAdapter
    extends AssetVariableResolverSupport
{
  private static final Logger log = Logger.getLogger(SimpleVariableResolverAdapter.class.getName());
  
  @Override
  protected void addFromRequest(final VariableSourceBuilder builder, final Request request) {
    log.fine(STR."Processing request: \{request != null ? request.getPath() : "null"}");
    // no-op the simple impl just allows for the path/format variable resolvers in the support class
  }

  @Override
  protected void addFromSourceLookup(final VariableSourceBuilder builder,
                                     final SourceLookup sourceLookup,
                                     final Map<String, Object> asset)
  {
    log.fine(STR."Processing source lookup with asset: \{asset != null ? asset.toString() : "null"}");
    // no-op the simple impl just allows for the path/format variable resolvers in the support class
  }

  @Override
  protected void addFromSearchResults(
      final VariableSourceBuilder builder,
      final ComponentSearchResult component,
      final AssetSearchResult asset)
  {
    log.fine(STR."Processing search results - component: \{component != null ? component.getRepositoryName() : "null"}, asset: \{asset != null ? asset.getPath() : "null"}");
    // no-op the simple impl just allows for the path/format variable resolvers in the support class
  }

  @Override
  protected void addFromAsset(final VariableSourceBuilder builder, final FluentAsset asset) {
    log.fine(STR."Processing asset: \{asset != null ? asset.path() : "null"}");
    // no-op the simple impl just allows for the path/format variable resolvers in the support class
  }
}