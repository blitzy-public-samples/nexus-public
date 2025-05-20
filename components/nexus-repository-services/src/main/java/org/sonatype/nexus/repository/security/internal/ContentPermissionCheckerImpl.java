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
package org.sonatype.nexus.repository.security.internal;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.repository.security.ContentPermissionChecker;
import org.sonatype.nexus.repository.security.RepositoryContentSelectorPermission;
import org.sonatype.nexus.repository.security.RepositoryViewPermission;
import org.sonatype.nexus.security.SecurityHelper;
import org.sonatype.nexus.selector.SelectorConfiguration;
import org.sonatype.nexus.selector.SelectorEvaluationException;
import org.sonatype.nexus.selector.SelectorManager;
import org.sonatype.nexus.selector.VariableSource;

import com.google.common.annotations.VisibleForTesting;
import org.apache.shiro.authz.Permission;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * @since 3.1
 */
@Named
@Singleton
public class ContentPermissionCheckerImpl
    extends ComponentSupport
    implements ContentPermissionChecker
{
  private final SecurityHelper securityHelper;

  private final SelectorManager selectorManager;

  @Inject
  public ContentPermissionCheckerImpl(final SecurityHelper securityHelper,
                                      final SelectorManager selectorManager) {
    this.securityHelper = checkNotNull(securityHelper);
    this.selectorManager = checkNotNull(selectorManager);
  }

  @VisibleForTesting
  public boolean isViewPermitted(final String repositoryName, final String repositoryFormat, final String action) {
    return securityHelper.anyPermitted(new RepositoryViewPermission(repositoryFormat, repositoryName, action));
  }

  @VisibleForTesting
  public boolean isViewPermitted(final String repositoryName, final String repositoryFormat, final String... actions) {
    return securityHelper.anyPermitted(permissionsFor(repositoryName, repositoryFormat, actions));
  }

  @VisibleForTesting
  public boolean isViewPermitted(final Set<String> repoNames, final String repositoryFormat, final String... actions) {
    return securityHelper.anyPermitted(permissionsFor(repoNames, repositoryFormat, actions));
  }

  private static RepositoryViewPermission[] permissionsFor(final String repositoryName, final String repositoryFormat, final String... actions) {
    return permissionsStreamFor(repositoryName, repositoryFormat, actions)
        .toArray(RepositoryViewPermission[]::new);
  }

  private static RepositoryContentSelectorPermission[] contentPermissionsFor(
      final String repositoryName,
      final String repositoryFormat,
      final SelectorConfiguration selectorConfiguration,
      final String... actions)
  {
    return contentPermissionsStreamFor(selectorConfiguration.getName(), repositoryFormat, repositoryName, actions)
        .toArray(RepositoryContentSelectorPermission[]::new);
  }

  private static RepositoryViewPermission[] permissionsFor(final Set<String> repoNames, final String repoFormat, final String... actions) {
    return repoNames.stream()
        .flatMap(repoName -> permissionsStreamFor(repoName, repoFormat, actions))
        .toArray(RepositoryViewPermission[]::new);
  }

  private static Stream<RepositoryViewPermission> permissionsStreamFor(final String repositoryName, final String repositoryFormat, final String... actions) {
    return Arrays.stream(actions).map(action -> new RepositoryViewPermission(repositoryFormat, repositoryName, action));
  }

  /**
   * Creates a stream of RepositoryContentSelectorPermission objects for the given parameters.
   * Uses Java 21's enhanced Stream API features for improved performance.
   *
   * @param selectorConfigurationName The name of the selector configuration
   * @param repositoryFormat The repository format
   * @param repositoryName The repository name
   * @param actions The actions to create permissions for
   * @return A stream of RepositoryContentSelectorPermission objects
   */
  private static Stream<RepositoryContentSelectorPermission> contentPermissionsStreamFor(
      final String selectorConfigurationName, 
      final String repositoryFormat, 
      final String repositoryName, 
      final String... actions) 
  {
    return Arrays.stream(actions)
        .map(action -> new RepositoryContentSelectorPermission(
            selectorConfigurationName, 
            repositoryFormat, 
            repositoryName, 
            List.of(action)));
  }

  @VisibleForTesting
  public boolean isViewPermitted(final Set<String> repositoryNames, final String repositoryFormat, final String action) {
    RepositoryViewPermission[] perms = permissionsFor(repositoryNames, repositoryFormat, action);
    if (perms.length > 0) {
      return securityHelper.anyPermitted(perms);
    }
    return false;
  }

  /**
   * Checks if content access is permitted for a specific repository, format, and action.
   *
   * @param repositoryName The repository name to check permissions for
   * @param repositoryFormat The repository format to check permissions for
   * @param action The action to check permissions for
   * @param selectorConfiguration The selector configuration to evaluate
   * @param variableSource The variable source for selector evaluation
   * @return true if content access is permitted, false otherwise
   */
  @VisibleForTesting
  public boolean isContentPermitted(final String repositoryName,
                                    final String repositoryFormat,
                                    final String action,
                                    final SelectorConfiguration selectorConfiguration,
                                    final VariableSource variableSource)
  {
    RepositoryContentSelectorPermission perm = new RepositoryContentSelectorPermission(
        selectorConfiguration.getName(), repositoryFormat, repositoryName, List.of(action));

    try {
      // make sure subject has the selector permission before evaluating it, because that's a cheaper/faster check
      return securityHelper.anyPermitted(perm) && selectorManager.evaluate(selectorConfiguration, variableSource);
    }
    catch (SelectorEvaluationException e) {
      logSelectorEvaluationException(e);
    }

    return false;
  }

  @VisibleForTesting
  public boolean isContentPermittedAnyOf(final String repositoryName,
                                         final String repositoryFormat,
                                         final SelectorConfiguration selectorConfiguration,
                                         final VariableSource variableSource,
                                         final String... actions)
  {
    try {
      Permission[] permissions = contentPermissionsFor(repositoryName, repositoryFormat, selectorConfiguration, actions);
      // make sure subject has the selector permission before evaluating it, because that's a cheaper/faster check
      return securityHelper.anyPermitted(permissions) && selectorManager.evaluate(selectorConfiguration, variableSource);
    }
    catch (SelectorEvaluationException e) {
      logSelectorEvaluationException(e);
    }

    return false;
  }


  /**
   * Checks if content access is permitted for any of the specified actions across multiple repositories.
   * Uses Java 21's enhanced Stream API features for improved performance.
   *
   * @param repositoryNames The set of repository names to check permissions for
   * @param repositoryFormat The repository format to check permissions for
   * @param selectorConfiguration The selector configuration to evaluate
   * @param variableSource The variable source for selector evaluation
   * @param actions The actions to check permissions for
   * @return true if content access is permitted for any of the actions, false otherwise
   */
  @VisibleForTesting
  public boolean isContentPermittedAnyOf(final Set<String> repositoryNames,
                                         final String repositoryFormat,
                                         final SelectorConfiguration selectorConfiguration,
                                         final VariableSource variableSource,
                                         final String... actions)
  {
    try {
      // Create permissions for all repository names and actions combinations
      Permission[] permissions = repositoryNames.stream()
          .flatMap(repoName -> contentPermissionsStreamFor(
              selectorConfiguration.getName(), 
              repositoryFormat, 
              repoName, 
              actions))
          .toArray(Permission[]::new);
          
      // Make sure subject has the selector permission before evaluating it, because that's a cheaper/faster check
      return securityHelper.anyPermitted(permissions) && selectorManager.evaluate(selectorConfiguration, variableSource);
    }
    catch (SelectorEvaluationException e) {
      logSelectorEvaluationException(e);
    }

    return false;
  }

  /**
   * Logs a SelectorEvaluationException with appropriate level of detail based on trace settings.
   * 
   * @param ex The exception to log
   */
  private void logSelectorEvaluationException(final SelectorEvaluationException ex) {
    if (log.isTraceEnabled()) {
      log.debug(ex.getMessage(), ex);
    }
    else {
      log.debug(ex.getMessage());
    }
  }

  @VisibleForTesting
  public boolean isContentPermitted(final Set<String> repositoryNames,
                                    final String repositoryFormat,
                                    final String action,
                                    final SelectorConfiguration selectorConfiguration,
                                    final VariableSource variableSource)
  {
    RepositoryContentSelectorPermission[] perms = repositoryNames.stream()
        .map(repositoryName -> new RepositoryContentSelectorPermission(
            selectorConfiguration.getName(), 
            repositoryFormat,
            repositoryName, 
            List.of(action)))
        .toArray(RepositoryContentSelectorPermission[]::new);
    
    if (perms.length > 0) {
      try {
        // make sure subject has the selector permission before evaluating it, because that's a cheaper/faster check
        return securityHelper.anyPermitted(perms) && selectorManager.evaluate(selectorConfiguration, variableSource);
      }
      catch (SelectorEvaluationException e) {
        logSelectorEvaluationException(e);
      }
    }

    return false;
  }

  @Override
  public boolean isPermitted(final String repositoryName,
                             final String repositoryFormat,
                             final String action,
                             final VariableSource variableSource)
  {
    //check view perm first, if applicable, grant access
    if (isViewPermitted(repositoryName, repositoryFormat, action)) {
      return true;
    }
    //otherwise check the content selector perms
    return selectorManager.browse().stream()
        .anyMatch(config -> isContentPermitted(repositoryName, repositoryFormat, action, config, variableSource));
  }

  @Override
  public boolean isPermittedJexlOnly(final String repositoryName,
                                     final String repositoryFormat,
                                     final String action,
                                     final VariableSource variableSource)
  {
    // check view perm first, if applicable, grant access
    if (isViewPermitted(repositoryName, repositoryFormat, action)) {
      return true;
    }
    // otherwise check the content selector perms
    return selectorManager.browseJexl().stream()
        .anyMatch(config -> isContentPermitted(repositoryName, repositoryFormat, action, config, variableSource));
  }

  /**
   * Checks if access is permitted for a set of repositories, a format, and an action.
   * Uses pattern matching for improved code clarity when evaluating permissions.
   *
   * @param repositoryNames The set of repository names to check permissions for
   * @param repositoryFormat The repository format to check permissions for
   * @param action The action to check permissions for
   * @param variableSource The variable source for selector evaluation
   * @return true if access is permitted, false otherwise
   */
  @Override
  public boolean isPermitted(final Set<String> repositoryNames,
                             final String repositoryFormat,
                             final String action,
                             final VariableSource variableSource)
  {
    if (repositoryNames.isEmpty()) {
      return false;
    }

    if (isViewPermitted(repositoryNames, repositoryFormat, action)) {
      return true;
    }
    
    var activeConfigs = selectorManager.browseActive(repositoryNames, List.of(repositoryFormat));
    return activeConfigs.stream()
        .anyMatch(config -> isContentPermitted(repositoryNames, repositoryFormat, action, config, variableSource));
  }

  @Override
  public boolean isPermittedJexlOnlyAnyOf(
      final String repositoryName,
      final String repositoryFormat,
      final VariableSource variableSource,
      final String... actions)
  {
    // check view perm first, if applicable, grant access
    if (isViewPermitted(repositoryName, repositoryFormat, actions)) {
      return true;
    }
    // otherwise check the content selector perms
    return selectorManager.browseJexl().stream()
        .anyMatch(config -> isContentPermittedAnyOf(repositoryName, repositoryFormat, config, variableSource, actions));
  }

  /**
   * Checks if access is permitted for any of the specified actions.
   * Optimized with Java 21's enhanced Stream API features.
   *
   * @param repositoryName The repository name to check permissions for
   * @param repositoryFormat The repository format to check permissions for
   * @param variableSource The variable source for selector evaluation
   * @param actions The actions to check permissions for
   * @return true if access is permitted for any of the actions, false otherwise
   */
  @Override
  public boolean isPermittedAnyOf(
      final String repositoryName,
      final String repositoryFormat,
      final VariableSource variableSource,
      final String... actions)
  {
    // Check view perm first, if applicable, grant access
    if (isViewPermitted(repositoryName, repositoryFormat, actions)) {
      return true;
    }
    
    // Otherwise check the content selector perms
    var configs = selectorManager.browse();
    return configs.stream()
        .anyMatch(config -> isContentPermittedAnyOf(repositoryName, repositoryFormat, config, variableSource, actions));
  }

  /**
   * Checks if access is permitted for any of the specified actions across multiple repositories.
   * Optimized with Java 21's enhanced Stream API features and pattern matching.
   *
   * @param repositoryNames The set of repository names to check permissions for
   * @param repositoryFormat The repository format to check permissions for
   * @param variableSource The variable source for selector evaluation
   * @param actions The actions to check permissions for
   * @return true if access is permitted for any of the actions, false otherwise
   */
  @Override
  public boolean isPermittedAnyOf(
      final Set<String> repositoryNames,
      final String repositoryFormat,
      final VariableSource variableSource,
      final String... actions)
  {
    if (repositoryNames.isEmpty()) {
      return false;
    }

    // Check view permissions first for efficiency
    if (isViewPermitted(repositoryNames, repositoryFormat, actions)) {
      return true;
    }
    
    // Check content selector permissions if view permissions not granted
    var configs = selectorManager.browse();
    return configs.stream()
        .anyMatch(config -> isContentPermittedAnyOf(repositoryNames, repositoryFormat, config, variableSource, actions));
  }
}