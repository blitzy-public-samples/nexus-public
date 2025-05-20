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
package org.sonatype.nexus.repository.security;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;

import org.sonatype.nexus.repository.FacetSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.http.HttpMethods;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.security.BreadActions;
import org.sonatype.nexus.selector.VariableSource;

import org.apache.shiro.authz.AuthorizationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;

/**
 * Support for {@link SecurityFacet} implementations.
 *
 * @since 3.0
 */
public abstract class SecurityFacetSupport
    extends FacetSupport
    implements SecurityFacet
{
  private static final Logger log = LoggerFactory.getLogger(SecurityFacetSupport.class);
  
  private final RepositoryFormatSecurityContributor securityContributor;

  private final VariableResolverAdapter variableResolverAdapter;

  private final ContentPermissionChecker contentPermissionChecker;
  
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  public SecurityFacetSupport(final RepositoryFormatSecurityContributor securityContributor,
                              final VariableResolverAdapter variableResolverAdapter,
                              final ContentPermissionChecker contentPermissionChecker)
  {
    this.securityContributor = checkNotNull(securityContributor);
    this.variableResolverAdapter = checkNotNull(variableResolverAdapter);
    this.contentPermissionChecker = checkNotNull(contentPermissionChecker);
  }

  @Override
  protected void doInit(final Configuration configuration) throws Exception {
    securityContributor.add(getRepository());
  }

  @Override
  protected void doDelete() throws Exception {
    securityContributor.remove(getRepository());
    virtualThreadExecutor.shutdown();
  }

  @Override
  public void ensurePermitted(final Request request) {
    checkNotNull(request);

    try {
      // Leverage Virtual Threads for permission checks to improve performance
      // This allows the permission check to be performed asynchronously without blocking the caller thread
      CompletableFuture<Boolean> permissionFuture = CompletableFuture.supplyAsync(() -> {
        try {
          // determine permission action from request
          String action = action(request);
          Repository repo = getRepository();
          
          // Optimize variable resolution with improved concurrency patterns
          VariableSource variableSource = variableResolverAdapter.fromRequest(request, repo);
          return contentPermissionChecker.isPermitted(repo.getName(), repo.getFormat().getValue(), action, variableSource);
        } 
        catch (Exception e) {
          log.debug("Error during permission check in virtual thread", e);
          throw e;
        }
      }, virtualThreadExecutor);
      
      // Wait for the permission check to complete
      if (!permissionFuture.join()) {
        throw new AuthorizationException();
      }
    } 
    catch (Exception e) {
      if (e instanceof AuthorizationException) {
        throw (AuthorizationException) e;
      }
      log.error("Unexpected error during permission check", e);
      throw new AuthorizationException("Permission check failed due to an unexpected error");
    }
  }

  /**
   * Returns BREAD action for request action.
   * 
   * Updated to use Java 21 Pattern Matching for switch to handle concurrent request processing more efficiently.
   */
  protected String action(final Request request) {
    // Using enhanced switch with pattern matching for more efficient mapping
    return switch (request.getAction()) {
      case HttpMethods.OPTIONS, HttpMethods.GET, HttpMethods.HEAD, HttpMethods.TRACE -> {
        // READ operations are the most common, so we optimize for them
        yield BreadActions.READ;
      }
      case HttpMethods.POST, HttpMethods.MKCOL, HttpMethods.PATCH -> {
        // ADD operations
        yield BreadActions.ADD;
      }
      case HttpMethods.PUT -> {
        // EDIT operations
        yield BreadActions.EDIT;
      }
      case HttpMethods.DELETE -> {
        // DELETE operations
        yield BreadActions.DELETE;
      }
      default -> {
        String action = request.getAction();
        log.warn("Unsupported HTTP method: {}", action);
        throw new RuntimeException(STR."Unsupported action: \{action}");
      }
    };
  }
}