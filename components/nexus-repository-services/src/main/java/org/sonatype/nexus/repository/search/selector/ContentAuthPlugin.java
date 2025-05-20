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

import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.search.query.SearchSubjectHelper;
import org.sonatype.nexus.repository.security.ContentPermissionChecker;
import org.sonatype.nexus.repository.security.VariableResolverAdapterManager;

import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.script.ScriptModule;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Elasticsearch plugin that exposes a content auth function for working with content selectors in search. Also holds
 * on to necessary instances as static variables that should be set by the matching {@link ContentAuthPluginLocator},
 * as this class is instantiated by ES, not by us.
 * <p>
 * Updated for Java 21 compatibility with improved thread-safety and module system compatibility.
 * Uses AtomicReference for dependency management to ensure visibility across threads and modules.
 *
 * @since 3.1
 */
public class ContentAuthPlugin
    extends Plugin
{
  // Using AtomicReference for better thread-safety and compatibility with Java 21's module system
  private static final AtomicReference<ContentPermissionChecker> contentPermissionChecker = new AtomicReference<>();
  private static final AtomicReference<VariableResolverAdapterManager> variableResolverAdapterManager = new AtomicReference<>();
  private static final AtomicReference<SearchSubjectHelper> searchSubjectHelper = new AtomicReference<>();
  private static final AtomicReference<RepositoryManager> repositoryManager = new AtomicReference<>();
  private static volatile boolean contentAuthSleep;

  /**
   * Constructor that verifies all required dependencies have been set.
   * Uses thread-safe access to dependencies via AtomicReference.
   */
  public ContentAuthPlugin() {
    checkNotNull(contentPermissionChecker.get(), "ContentPermissionChecker must be set before plugin initialization");
    checkNotNull(variableResolverAdapterManager.get(), "VariableResolverAdapterManager must be set before plugin initialization");
    checkNotNull(searchSubjectHelper.get(), "SearchSubjectHelper must be set before plugin initialization");
    checkNotNull(repositoryManager.get(), "RepositoryManager must be set before plugin initialization");
  }

  @Override
  public String name() {
    return "content-auth-plugin";
  }

  @Override
  public String description() {
    return "ES plugin for working with content selectors";
  }

  /**
   * Registers the content auth script with Elasticsearch.
   * This method is called by Elasticsearch when the plugin is loaded.
   *
   * @param module The ScriptModule to register with
   */
  public void onModule(final ScriptModule module) {
    module.registerScript(ContentAuthPluginScript.NAME, ContentAuthPluginScriptFactory.class);
  }

  /**
   * Sets the dependencies required by this plugin.
   * Uses AtomicReference for thread-safe dependency management compatible with Java 21's module system.
   *
   * @param contentPermissionChecker The content permission checker to use
   * @param variableResolverAdapterManager The variable resolver adapter manager to use
   * @param searchSubjectHelper The search subject helper to use
   * @param repositoryManager The repository manager to use
   * @param contentAuthSleep Whether to sleep during content auth operations
   */
  public static void setDependencies(final ContentPermissionChecker contentPermissionChecker,
                                     final VariableResolverAdapterManager variableResolverAdapterManager,
                                     final SearchSubjectHelper searchSubjectHelper,
                                     final RepositoryManager repositoryManager,
                                     final boolean contentAuthSleep)
  {
    ContentAuthPlugin.contentPermissionChecker.set(checkNotNull(contentPermissionChecker));
    ContentAuthPlugin.variableResolverAdapterManager.set(checkNotNull(variableResolverAdapterManager));
    ContentAuthPlugin.searchSubjectHelper.set(checkNotNull(searchSubjectHelper));
    ContentAuthPlugin.repositoryManager.set(checkNotNull(repositoryManager));
    ContentAuthPlugin.contentAuthSleep = contentAuthSleep;
  }

  /**
   * Gets the content permission checker.
   * Thread-safe access via AtomicReference.get().
   *
   * @return The content permission checker
   */
  public static ContentPermissionChecker getContentPermissionChecker() {
    return contentPermissionChecker.get();
  }

  /**
   * Gets the variable resolver adapter manager.
   * Thread-safe access via AtomicReference.get().
   *
   * @return The variable resolver adapter manager
   */
  public static VariableResolverAdapterManager getVariableResolverAdapterManager() {
    return variableResolverAdapterManager.get();
  }

  /**
   * Gets the search subject helper.
   * Thread-safe access via AtomicReference.get().
   *
   * @return The search subject helper
   */
  public static SearchSubjectHelper getSearchSubjectHelper() {
    return searchSubjectHelper.get();
  }

  /**
   * Gets the repository manager.
   * Thread-safe access via AtomicReference.get().
   *
   * @return The repository manager
   */
  public static RepositoryManager getRepositoryManager() {
    return repositoryManager.get();
  }

  /**
   * Gets whether to sleep during content auth operations.
   * Uses volatile for visibility across threads.
   *
   * @return Whether to sleep during content auth operations
   */
  public static boolean getContentAuthSleep() {
    return contentAuthSleep;
  }
}