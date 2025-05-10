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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
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
  
  // Virtual thread executor for concurrent permission checks
  private final ExecutorService virtualThreadExecutor;

  @Inject
  public ContentPermissionCheckerImpl(final SecurityHelper securityHelper,
                                      final SelectorManager selectorManager) {
    this.securityHelper = checkNotNull(securityHelper);
    this.selectorManager = checkNotNull(selectorManager);
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
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

  private static Stream<RepositoryContentSelectorPermission> contentPermissionsStreamFor(final String selectorConfigurationName, final String repositoryFormat, final String repositoryName, final String... actions) {
    return Arrays.stream(actions).map(action -> new RepositoryContentSelectorPermission(selectorConfigurationName, repositoryFormat, repositoryName, Arrays.asList(action)));
  }

  @VisibleForTesting
  public boolean isViewPermitted(final Set<String> repositoryNames, final String repositoryFormat, final String action) {
    RepositoryViewPermission[] perms = permissionsFor(repositoryNames, repositoryFormat, action);
    if (perms.length > 0) {
      return securityHelper.anyPermitted(perms);
    }
    return false;
  }

  @VisibleForTesting
  public boolean isContentPermitted(final String repositoryName,
                                    final String repositoryFormat,
                                    final String action,
                                    final SelectorConfiguration selectorConfiguration,
                                    final VariableSource variableSource)
  {
    RepositoryContentSelectorPermission perm = new RepositoryContentSelectorPermission(
        selectorConfiguration.getName(), repositoryFormat, repositoryName, Arrays.asList(action));

    try {
      // make sure subject has the selector permission before evaluating it, because that's a cheaper/faster check
      return securityHelper.anyPermitted(perm) && selectorManager.evaluate(selectorConfiguration, variableSource);
    }
    catch (SelectorEvaluationException e) {
      logMsgAndMaybeException(e);
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
      logMsgAndMaybeException(e);
    }

    return false;
  }


  @VisibleForTesting
  public boolean isContentPermittedAnyOf(final Set<String> repositoryNames,
                                         final String repositoryFormat,
                                         final SelectorConfiguration selectorConfiguration,
                                         final VariableSource variableSource,
                                         final String... actions)
  {
    try {
      Permission[] permissions = repositoryNames.stream()
          .flatMap(repoName -> contentPermissionsStreamFor(selectorConfiguration.getName(), repositoryFormat, repoName, actions))
          .toArray(Permission[]::new);
      // make sure subject has the selector permission before evaluating it, because that's a cheaper/faster check
      return securityHelper.anyPermitted(permissions) && selectorManager.evaluate(selectorConfiguration, variableSource);
    }
    catch (SelectorEvaluationException e) {
      logMsgAndMaybeException(e);
    }

    return false;
  }

  private void logMsgAndMaybeException(final SelectorEvaluationException ex) {
    if (log.isTraceEnabled()) {
      log.debug(STR."\{ex.getMessage()}", ex);
    }
    else {
      log.debug(STR."\{ex.getMessage()}");
    }
  }

  @VisibleForTesting
  public boolean isContentPermitted(final Set<String> repositoryNames,
                                    final String repositoryFormat,
                                    final String action,
                                    final SelectorConfiguration selectorConfiguration,
                                    final VariableSource variableSource)
  {
    RepositoryContentSelectorPermission[] perms = repositoryNames.stream().map(
        repositoryName -> new RepositoryContentSelectorPermission(selectorConfiguration.getName(), repositoryFormat,
            repositoryName, Arrays.asList(action))).toArray(RepositoryContentSelectorPermission[]::new);
    if (perms.length > 0) {
      try {
        // make sure subject has the selector permission before evaluating it, because that's a cheaper/faster check
        return securityHelper.anyPermitted(perms) && selectorManager.evaluate(selectorConfiguration, variableSource);
      }
      catch (SelectorEvaluationException e) {
        logMsgAndMaybeException(e);
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
    //otherwise check the content selector perms using virtual threads for concurrent evaluation
    List<SelectorConfiguration> configs = selectorManager.browse().collect(Collectors.toList());
    
    try {
      // Use CompletableFuture with virtual threads for concurrent permission checks
      List<CompletableFuture<Boolean>> futures = configs.stream()
          .map(config -> CompletableFuture.supplyAsync(() -> 
              isContentPermitted(repositoryName, repositoryFormat, action, config, variableSource),
              virtualThreadExecutor))
          .collect(Collectors.toList());
      
      // Wait for any future to complete with 'true' result
      return CompletableFuture.anyOf(futures.toArray(new CompletableFuture[0]))
          .thenApply(result -> (Boolean) result)
          .exceptionally(ex -> {
            log.error(STR."Error during concurrent permission check: \{ex.getMessage()}", ex);
            return false;
          })
          .join() || futures.stream().anyMatch(f -> {
            try {
              return f.getNow(false);
            } catch (Exception e) {
              return false;
            }
          });
    } catch (Exception e) {
      log.error(STR."Error setting up concurrent permission checks: \{e.getMessage()}", e);
      // Fallback to sequential evaluation if concurrent approach fails
      return configs.stream()
          .anyMatch(config -> isContentPermitted(repositoryName, repositoryFormat, action, config, variableSource));
    }
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
    // otherwise check the content selector perms using virtual threads for concurrent evaluation
    List<SelectorConfiguration> configs = selectorManager.browseJexl().collect(Collectors.toList());
    
    try {
      // Use CompletableFuture with virtual threads for concurrent permission checks
      List<CompletableFuture<Boolean>> futures = configs.stream()
          .map(config -> CompletableFuture.supplyAsync(() -> 
              isContentPermitted(repositoryName, repositoryFormat, action, config, variableSource),
              virtualThreadExecutor))
          .collect(Collectors.toList());
      
      // Wait for any future to complete with 'true' result
      return CompletableFuture.anyOf(futures.toArray(new CompletableFuture[0]))
          .thenApply(result -> (Boolean) result)
          .exceptionally(ex -> {
            log.error(STR."Error during concurrent JEXL permission check: \{ex.getMessage()}", ex);
            return false;
          })
          .join() || futures.stream().anyMatch(f -> {
            try {
              return f.getNow(false);
            } catch (Exception e) {
              return false;
            }
          });
    } catch (Exception e) {
      log.error(STR."Error setting up concurrent JEXL permission checks: \{e.getMessage()}", e);
      // Fallback to sequential evaluation if concurrent approach fails
      return configs.stream()
          .anyMatch(config -> isContentPermitted(repositoryName, repositoryFormat, action, config, variableSource));
    }
  }

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
    
    List<SelectorConfiguration> configs = selectorManager.browseActive(repositoryNames, 
        Collections.singletonList(repositoryFormat)).collect(Collectors.toList());
    
    try {
      // Use CompletableFuture with virtual threads for concurrent permission checks
      List<CompletableFuture<Boolean>> futures = configs.stream()
          .map(config -> CompletableFuture.supplyAsync(() -> 
              isContentPermitted(repositoryNames, repositoryFormat, action, config, variableSource),
              virtualThreadExecutor))
          .collect(Collectors.toList());
      
      // Wait for any future to complete with 'true' result
      return CompletableFuture.anyOf(futures.toArray(new CompletableFuture[0]))
          .thenApply(result -> (Boolean) result)
          .exceptionally(ex -> {
            log.error(STR."Error during concurrent multi-repository permission check: \{ex.getMessage()}", ex);
            return false;
          })
          .join() || futures.stream().anyMatch(f -> {
            try {
              return f.getNow(false);
            } catch (Exception e) {
              return false;
            }
          });
    } catch (Exception e) {
      log.error(STR."Error setting up concurrent multi-repository permission checks: \{e.getMessage()}", e);
      // Fallback to sequential evaluation if concurrent approach fails
      return configs.stream()
          .anyMatch(config -> isContentPermitted(repositoryNames, repositoryFormat, action, config, variableSource));
    }
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
    
    // otherwise check the content selector perms using virtual threads for concurrent evaluation
    List<SelectorConfiguration> configs = selectorManager.browseJexl().collect(Collectors.toList());
    
    try {
      // Use CompletableFuture with virtual threads for concurrent permission checks
      List<CompletableFuture<Boolean>> futures = configs.stream()
          .map(config -> CompletableFuture.supplyAsync(() -> 
              isContentPermittedAnyOf(repositoryName, repositoryFormat, config, variableSource, actions),
              virtualThreadExecutor))
          .collect(Collectors.toList());
      
      // Wait for any future to complete with 'true' result
      return CompletableFuture.anyOf(futures.toArray(new CompletableFuture[0]))
          .thenApply(result -> (Boolean) result)
          .exceptionally(ex -> {
            log.error(STR."Error during concurrent JEXL any-of permission check: \{ex.getMessage()}", ex);
            return false;
          })
          .join() || futures.stream().anyMatch(f -> {
            try {
              return f.getNow(false);
            } catch (Exception e) {
              return false;
            }
          });
    } catch (Exception e) {
      log.error(STR."Error setting up concurrent JEXL any-of permission checks: \{e.getMessage()}", e);
      // Fallback to sequential evaluation if concurrent approach fails
      return configs.stream()
          .anyMatch(config -> isContentPermittedAnyOf(repositoryName, repositoryFormat, config, variableSource, actions));
    }
  }

  @Override
  public boolean isPermittedAnyOf(
      final String repositoryName,
      final String repositoryFormat,
      final VariableSource variableSource,
      final String... actions)
  {
    //check view perm first, if applicable, grant access
    if (isViewPermitted(repositoryName, repositoryFormat, actions)) {
      return true;
    }
    
    //otherwise check the content selector perms using virtual threads for concurrent evaluation
    List<SelectorConfiguration> configs = selectorManager.browse().collect(Collectors.toList());
    
    try {
      // Use CompletableFuture with virtual threads for concurrent permission checks
      List<CompletableFuture<Boolean>> futures = configs.stream()
          .map(config -> CompletableFuture.supplyAsync(() -> 
              isContentPermittedAnyOf(repositoryName, repositoryFormat, config, variableSource, actions),
              virtualThreadExecutor))
          .collect(Collectors.toList());
      
      // Wait for any future to complete with 'true' result
      return CompletableFuture.anyOf(futures.toArray(new CompletableFuture[0]))
          .thenApply(result -> (Boolean) result)
          .exceptionally(ex -> {
            log.error(STR."Error during concurrent any-of permission check: \{ex.getMessage()}", ex);
            return false;
          })
          .join() || futures.stream().anyMatch(f -> {
            try {
              return f.getNow(false);
            } catch (Exception e) {
              return false;
            }
          });
    } catch (Exception e) {
      log.error(STR."Error setting up concurrent any-of permission checks: \{e.getMessage()}", e);
      // Fallback to sequential evaluation if concurrent approach fails
      return configs.stream()
          .anyMatch(config -> isContentPermittedAnyOf(repositoryName, repositoryFormat, config, variableSource, actions));
    }
  }

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

    if (isViewPermitted(repositoryNames, repositoryFormat, actions)) {
      return true;
    }
    
    List<SelectorConfiguration> configs = selectorManager.browse().collect(Collectors.toList());
    
    try {
      // Use CompletableFuture with virtual threads for concurrent permission checks
      List<CompletableFuture<Boolean>> futures = configs.stream()
          .map(config -> CompletableFuture.supplyAsync(() -> 
              isContentPermittedAnyOf(repositoryNames, repositoryFormat, config, variableSource, actions),
              virtualThreadExecutor))
          .collect(Collectors.toList());
      
      // Wait for any future to complete with 'true' result
      return CompletableFuture.anyOf(futures.toArray(new CompletableFuture[0]))
          .thenApply(result -> (Boolean) result)
          .exceptionally(ex -> {
            log.error(STR."Error during concurrent multi-repository any-of permission check: \{ex.getMessage()}", ex);
            return false;
          })
          .join() || futures.stream().anyMatch(f -> {
            try {
              return f.getNow(false);
            } catch (Exception e) {
              return false;
            }
          });
    } catch (Exception e) {
      log.error(STR."Error setting up concurrent multi-repository any-of permission checks: \{e.getMessage()}", e);
      // Fallback to sequential evaluation if concurrent approach fails
      return configs.stream()
          .anyMatch(config -> isContentPermittedAnyOf(repositoryNames, repositoryFormat, config, variableSource, actions));
    }
  }
}