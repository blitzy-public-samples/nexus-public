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
package org.sonatype.nexus.repository.search.selector;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.lang.Thread;

// Import for Java 21 String Templates
import static java.lang.StringTemplate.STR;

import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.security.ContentPermissionChecker;
import org.sonatype.nexus.repository.security.VariableResolverAdapter;
import org.sonatype.nexus.repository.security.VariableResolverAdapterManager;
import org.sonatype.nexus.selector.VariableSource;

import com.google.common.annotations.VisibleForTesting;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.subject.support.SubjectThreadState;
import org.apache.shiro.util.ThreadState;
import org.elasticsearch.script.AbstractSearchScript;
import org.elasticsearch.search.lookup.SourceLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.repository.search.index.SearchConstants.FORMAT;
import static org.sonatype.nexus.repository.search.index.SearchConstants.REPOSITORY_NAME;
import static org.sonatype.nexus.security.BreadActions.BROWSE;

/**
 * Native script to work with content selectors from within ES queries.
 *
 * @since 3.1
 */
public class ContentAuthPluginScript
    extends AbstractSearchScript
{
  public static final String NAME = "content_auth";

  private static final Logger log = LoggerFactory.getLogger(ContentAuthPluginScript.class);

  private final Subject subject;

  private final VariableResolverAdapterManager variableResolverAdapterManager;

  private final ContentPermissionChecker contentPermissionChecker;

  private final RepositoryManager repositoryManager;

  private final boolean contentAuthSleep;

  public ContentAuthPluginScript(
      final Subject subject,
      final ContentPermissionChecker contentPermissionChecker,
      final VariableResolverAdapterManager variableResolverAdapterManager,
      final RepositoryManager repositoryManager,
      final boolean contentAuthSleep)
  {
    this.subject = checkNotNull(subject);
    this.contentPermissionChecker = checkNotNull(contentPermissionChecker);
    this.variableResolverAdapterManager = checkNotNull(variableResolverAdapterManager);
    this.repositoryManager = checkNotNull(repositoryManager);
    this.contentAuthSleep = contentAuthSleep;
  }

  @Override
  public Object run() {
    ThreadState threadState = new SubjectThreadState(subject);
    threadState.bind();
    try {
      SourceLookup sourceLookup = getSourceLookup();
      
      // Using pattern matching for type checking and casting
      if (sourceLookup.get(FORMAT) instanceof String format && 
          sourceLookup.get(REPOSITORY_NAME) instanceof String repositoryName) {
        
        VariableResolverAdapter variableResolverAdapter = variableResolverAdapterManager.get(format);
        
        // Using pattern matching for assets list extraction
        Object assetsObj = sourceLookup.getOrDefault("assets", Collections.emptyList());
        if (assetsObj instanceof List<?> assetsList && !assetsList.isEmpty()) {
          // Using record pattern for asset data extraction (first element)
          if (assetsList.get(0) instanceof Map<?, ?> asset) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typedAsset = (Map<String, Object>) asset;
            
            VariableSource variableSource = variableResolverAdapter.fromSourceLookup(sourceLookup, typedAsset);
            Set<String> repoNames = new HashSet<>();
            repoNames.add(repositoryName);
            repoNames.addAll(repositoryManager.findContainingGroups(repositoryName));
            return contentPermissionChecker.isPermitted(repoNames, format, BROWSE, variableSource);
          }
        }
      }
      return false;
    }
    finally {
      threadState.clear();
      if (contentAuthSleep) {
        try {
          // Optimized for Virtual Threads - direct Thread.sleep call
          Thread.sleep(1);
        }
        catch (InterruptedException e) { // NOSONAR: pooled ES thread
          // Using String Template for improved logging
          log.error(STR."Thread.sleep interrupted in ContentAuthPluginScript: \{e.getMessage()}", e);
        }
      }
    }
  }

  /**
   * Delegates to {@link #source()}, only here to aid in unit testing.
   */
  @VisibleForTesting
  protected SourceLookup getSourceLookup() {
    return source();
  }
}