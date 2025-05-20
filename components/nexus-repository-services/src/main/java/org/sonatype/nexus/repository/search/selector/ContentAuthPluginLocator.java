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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.elasticsearch.PluginLocator;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.search.query.SearchSubjectHelper;
import org.sonatype.nexus.repository.security.ContentPermissionChecker;
import org.sonatype.nexus.repository.security.VariableResolverAdapterManager;

import org.elasticsearch.plugins.Plugin;

/**
 * {@link PluginLocator} for {@link ContentAuthPlugin}. Also responsible for setting some required objects into static
 * fields on {@link ContentAuthPlugin} as the instantiation of the latter occurs outside of our purview within ES.
 *
 * @since 3.1
 */
@Named
@Singleton
public class ContentAuthPluginLocator
    implements PluginLocator
{
  /**
   * Creates a new ContentAuthPluginLocator instance with the required dependencies.
   * 
   * This constructor is compatible with Java 21's module system for proper plugin loading.
   * It explicitly validates all parameters to ensure proper initialization in the module context.
   *
   * @param contentPermissionChecker The content permission checker to use
   * @param variableResolverAdapterManager The variable resolver adapter manager to use
   * @param searchSubjectHelper The search subject helper to use
   * @param repositoryManager The repository manager to use
   * @param contentAuthSleep Whether to sleep during content auth (for testing)
   */
  @Inject
  public ContentAuthPluginLocator(final ContentPermissionChecker contentPermissionChecker,
                                  final VariableResolverAdapterManager variableResolverAdapterManager,
                                  final SearchSubjectHelper searchSubjectHelper,
                                  final RepositoryManager repositoryManager,
                                  @Named("${nexus.elasticsearch.contentAuthSleep:-false}") final boolean contentAuthSleep)
  {
    // Validate parameters explicitly for Java 21 module system compatibility
    if (contentPermissionChecker == null) {
      throw new IllegalArgumentException("contentPermissionChecker cannot be null");
    }
    if (variableResolverAdapterManager == null) {
      throw new IllegalArgumentException("variableResolverAdapterManager cannot be null");
    }
    if (searchSubjectHelper == null) {
      throw new IllegalArgumentException("searchSubjectHelper cannot be null");
    }
    if (repositoryManager == null) {
      throw new IllegalArgumentException("repositoryManager cannot be null");
    }
    
    // Set dependencies on the ContentAuthPlugin
    ContentAuthPlugin.setDependencies(contentPermissionChecker, variableResolverAdapterManager,
        searchSubjectHelper, repositoryManager, contentAuthSleep);
  }

  /**
   * Returns the plugin class that this locator is responsible for.
   * This method is compatible with Java 21's module system for proper class loading.
   *
   * @return The ContentAuthPlugin class
   */
  @Override
  public Class<? extends Plugin> pluginClass() {
    try {
      return ContentAuthPlugin.class;
    }
    catch (Exception e) {
      throw new RuntimeException("Failed to load ContentAuthPlugin class in Java 21 module context", e);
    }
  }
}