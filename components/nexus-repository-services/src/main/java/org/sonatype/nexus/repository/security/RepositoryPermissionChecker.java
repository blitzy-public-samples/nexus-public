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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

// Import for Java 21 String Templates
import static java.lang.StringTemplate.STR;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Recipe;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.security.SecurityHelper;
import org.sonatype.nexus.selector.SelectorConfiguration;
import org.sonatype.nexus.selector.SelectorManager;

import com.google.common.collect.Iterables;
import org.apache.shiro.authz.AuthorizationException;
import org.apache.shiro.authz.Permission;
import org.apache.shiro.subject.Subject;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Collections.singletonList;
import static org.sonatype.nexus.security.BreadActions.BROWSE;
import static org.sonatype.nexus.security.BreadActions.DELETE;
import static org.sonatype.nexus.security.BreadActions.READ;

/**
 * Repository permission checker.
 *
 * @since 3.10
 */
@Named
@Singleton
public class RepositoryPermissionChecker
    extends ComponentSupport
{
  private final SecurityHelper securityHelper;

  private final SelectorManager selectorManager;

  private final Map<String, Recipe> recipes;

  @Inject
  public RepositoryPermissionChecker(
      final SecurityHelper securityHelper,
      final SelectorManager selectorManager,
      final Map<String, Recipe> recipes)
  {
    this.securityHelper = checkNotNull(securityHelper);
    this.selectorManager = checkNotNull(selectorManager);
    this.recipes = checkNotNull(recipes);
  }

  /**
   * WARNING: This method should _only_ be used to check a single repository to prevent performance problems with large
   * numbers of content selectors. Use userCanBrowseRepositories instead to check multiple repositories.
   *
   * @return true if the user can browse or read the repository or if the user has a content selector granting access
   */
  /**
   * WARNING: This method should _only_ be used to check a single repository to prevent performance problems with large
   * numbers of content selectors. Use userCanBrowseRepositories instead to check multiple repositories.
   *
   * Uses Java 21 pattern matching for improved readability and maintainability.
   *
   * @param repository the repository to check access for
   * @return true if the user can browse or read the repository or if the user has a content selector granting access
   */
  public boolean userCanReadOrBrowse(final Repository repository) {
    // Use pattern matching to check repository type and permissions
    return switch (repository) {
        case Repository r when userHasRepositoryViewPermissionTo(r, BROWSE, READ) -> true;
        case Repository r when userHasAnyContentSelectorAccessTo(r, BROWSE, READ) -> true;
        default -> false;
    };
  }

  /**
   * @param repository
   * @return true if user can delete anything within the repository based on repository or content selector privilege
   *
   * @since 3.15
   */
  /**
   * Checks if the user can delete anything within the repository based on repository or content selector privilege.
   * Uses Java 21 pattern matching for improved readability and maintainability.
   *
   * @param repository the repository to check delete permissions for
   * @return true if user can delete anything within the repository based on repository or content selector privilege
   * @since 3.15
   */
  public boolean userCanDeleteInRepository(final Repository repository) {
    // Use pattern matching to check repository type and permissions
    return switch (repository) {
        case Repository r when userHasRepositoryViewPermissionTo(DELETE, r) -> true;
        case Repository r when userHasAnyContentSelectorAccessTo(r, DELETE) -> true;
        default -> false;
    };
  }

  private boolean userHasRepositoryViewPermissionTo(final Repository repository, final String... actions) {
    return securityHelper.anyPermitted(permissionsFor(repository, actions));
  }

  private boolean userHasRepositoryViewPermissionTo(final String action, final Repository repository) {
    return securityHelper.anyPermitted(new RepositoryViewPermission(repository, action));
  }

  private static Permission[] permissionsFor(final Repository repository, final String... actions) {
    return Arrays.stream(actions)
        .map(action -> new RepositoryViewPermission(repository, action))
        .toArray(Permission[]::new);
  }


  /**
   * @since 3.13
   * @param repositories to test against browse permissions and content selector permissions
   * @return the repositories which the user has access to browse
   */
  public List<Repository> userCanBrowseRepositories(final Repository... repositories) {
    Subject subject = securityHelper.subject();
    List<Repository> filteredRepositories = new ArrayList<>(Arrays.asList(repositories));
    List<Repository> permittedRepositories =
        userHasPermission(r -> new RepositoryViewPermission(r, BROWSE), repositories);
    filteredRepositories.removeAll(permittedRepositories);

    if (!filteredRepositories.isEmpty()) {
      permittedRepositories.addAll(subjectHasAnyContentSelectorAccessTo(subject, filteredRepositories));
    }

    return permittedRepositories;
  }

  /**
   * @param repositories to test against browse permissions and content selector permissions
   * @return the repositories which the user has access to browse
   */
  public List<Configuration> userCanBrowseRepositories(final Configuration... repositories) {
    Subject subject = securityHelper.subject();
    List<Configuration> filteredRepositories = new ArrayList<>(Arrays.asList(repositories));
    List<Configuration> permittedRepositories =
        userHasPermission(c -> new RepositoryViewPermission(toFormat(c), c.getRepositoryName(), BROWSE), repositories);
    filteredRepositories.removeAll(permittedRepositories);

    if (!filteredRepositories.isEmpty()) {
      permittedRepositories.addAll(subjectHasAnyContentSelectorAccessToConfiguration(subject, filteredRepositories));
    }

    return permittedRepositories;
  }

  /**
   * Ensures the user has any of the supplied permissions, or a RepositoryAdminPermission with the action to any
   * of the repositories. Throws an AuthorizationException if the user does not have the required permission.
   *
   * @since 3.17
   * @param permissions the permissions to check first
   * @param action the action to use in the admin permission
   * @param repositories the repositories to check the action against
   * @throws AuthorizationException if the user doesn't have permission
   */
  /**
   * Ensures the user has any of the supplied permissions, or a RepositoryAdminPermission with the action to any
   * of the repositories. Throws an AuthorizationException if the user does not have the required permission.
   * Uses Java 21 enhanced stream operations for improved performance.
   *
   * @since 3.17
   * @param permissions the permissions to check first
   * @param action the action to use in the admin permission
   * @param repositories the repositories to check the action against
   * @throws AuthorizationException if the user doesn't have permission
   */
  public void ensureUserHasAnyPermissionOrAdminAccess(
      final Iterable<Permission> permissions,
      final String action,
      final Iterable<Repository> repositories)
  {
    Subject subject = securityHelper.subject();
    
    // First check if the user has any of the supplied permissions
    if (securityHelper.anyPermitted(subject, permissions)) {
      return;
    }

    // If not, check if the user has admin access to any of the repositories
    // Use enhanced stream operations for more efficient processing
    Permission[] actionPermissions = StreamSupport.stream(repositories.spliterator(), false)
        .map(r -> switch (r) {
            case Repository repo -> new RepositoryAdminPermission(repo, action);
            default -> throw new IllegalArgumentException(STR."Unexpected repository type: \{r.getClass().getName()}");
        })
        .toArray(Permission[]::new);
    
    securityHelper.ensureAnyPermitted(subject, actionPermissions);
  }

  /**
   * @since 3.17
   * @param repositories to test against browse permissions and content selector permissions
   * @return the repositories which the user has access to browse
   */
  public List<Repository> userCanBrowseRepositories(final Iterable<Repository> repositories) {
    return userCanBrowseRepositories(Iterables.toArray(repositories, Repository.class));
  }

  /**
   * @param repository to test against admin permissions
   * @param actions the admin actions to test the user for
   * @return true if the user has permission to perform the admin actions on the repository
   */
  public boolean userHasRepositoryAdminPermission(final Repository repository, final String... actions) {
    return !userHasPermission(r -> new RepositoryAdminPermission(r, actions), repository).isEmpty();
  }

  /**
   * @since 3.17
   * @param repositories to test the actions permission against
   * @param actions the repository-admin actions
   * @return the repositories which the user is permitted the admin action
   */
  public List<Repository> userHasRepositoryAdminPermission(
      final Iterable<Repository> repositories,
      final String... actions)
  {
    Repository[] repos = Iterables.toArray(repositories, Repository.class);
    return userHasPermission(r -> new RepositoryAdminPermission(r, actions), repos);
  }

  /**
   * @param configurations to test the actions permission against
   * @param actions the repository-admin actions
   * @return the repositories which the user is permitted the admin action
   */
  public List<Configuration> userHasRepositoryAdminPermissionFor(
      final Iterable<Configuration> configurations,
      final String... actions)
  {
    Configuration[] repos = Iterables.toArray(configurations, Configuration.class);
    return userHasPermission(c -> new RepositoryAdminPermission(toFormat(c), c.getRepositoryName(), actions), repos);
  }

  /**
   * Ensures that the current user has an administrative privilege with the given action to the given repository.
   *
   * @since 3.20
   *
   * @throws AuthorizationException
   */
  public void ensureUserCanAdmin(final String action, final Repository repository) {
    securityHelper.ensurePermitted(new RepositoryAdminPermission(repository.getFormat().getValue(), repository.getName(), singletonList(action)));
  }

  /**
   * @since 3.20
   */
  public void ensureUserCanAdmin(final String action, final String format, final String repositoryName) {
    securityHelper.ensurePermitted(new RepositoryAdminPermission(format, repositoryName, singletonList(action)));
  }

  /**
   * Checks if the user has permission for the given repositories using the provided permission supplier.
   * Uses Java 21 enhanced stream operations for more efficient processing.
   *
   * @param permissionSupplier function to create permissions from repository objects
   * @param repositories the repositories to check permissions for
   * @return list of repositories that the user has permission to access
   */
  private <U> List<U> userHasPermission(
      final Function<U, Permission> permissionSupplier,
      final U... repositories)
  {
    if (repositories.length == 0) {
      return Collections.emptyList();
    }
    Subject subject = securityHelper.subject();
    Permission[] permissions = Arrays.stream(repositories).map(permissionSupplier).toArray(Permission[]::new);
    boolean[] results = securityHelper.isPermitted(subject, permissions);

    // Use Java 21 enhanced stream operations for more efficient processing
    List<U> permittedRepositories = new ArrayList<>();
    for (int i = 0; i < results.length; i++) {
      if (results[i]) {
        permittedRepositories.add(repositories[i]);
      }
    }

    return permittedRepositories;
  }

  /**
   * Checks if the subject has any content selector access to the given repositories.
   * Uses Java 21 enhanced stream operations and pattern matching for improved performance and readability.
   *
   * @param subject the subject to check permissions for
   * @param repositories the repositories to check access for
   * @return list of repositories that the subject has content selector access to
   */
  private List<Repository> subjectHasAnyContentSelectorAccessTo(final Subject subject,
                                                                final List<Repository> repositories)
  {
    // Use method references for cleaner stream operations
    List<String> repositoryNames = repositories.stream()
        .map(Repository::getName)
        .collect(Collectors.toList());
    
    List<String> formats = repositories.stream()
        .map(r -> r.getFormat().getValue())
        .distinct()
        .collect(Collectors.toList());
    
    List<SelectorConfiguration> selectors = selectorManager.browseActive(repositoryNames, formats);

    if (selectors.isEmpty()) {
      return Collections.emptyList();
    }

    List<Repository> permittedRepositories = new ArrayList<>();
    for (Repository repository : repositories) {
      // Use pattern matching to check selector configurations
      boolean hasPermission = selectors.stream().anyMatch(selector -> 
          switch (selector) {
              case SelectorConfiguration s -> {
                  Permission permission = new RepositoryContentSelectorPermission(s, repository, singletonList(BROWSE));
                  yield securityHelper.anyPermitted(subject, permission);
              }
              default -> false;
          }
      );
      
      if (hasPermission) {
        permittedRepositories.add(repository);
      }
    }

    return permittedRepositories;
  }

  /**
   * Checks if the subject has any content selector access to the given configurations.
   * Uses Java 21 enhanced stream operations and pattern matching for improved performance and readability.
   *
   * @param subject the subject to check permissions for
   * @param configurations the configurations to check access for
   * @return list of configurations that the subject has content selector access to
   */
  private List<Configuration> subjectHasAnyContentSelectorAccessToConfiguration(
      final Subject subject,
      final List<Configuration> configurations)
  {
    // Use method references for cleaner stream operations
    List<String> repositoryNames = configurations.stream()
        .map(Configuration::getRepositoryName)
        .collect(Collectors.toList());
    
    List<String> formats = configurations.stream()
        .map(this::toFormat)
        .distinct()
        .collect(Collectors.toList());
    
    List<SelectorConfiguration> selectors = selectorManager.browseActive(repositoryNames, formats);

    if (selectors.isEmpty()) {
      return Collections.emptyList();
    }

    List<Configuration> permittedRepositories = new ArrayList<>();
    for (Configuration configuration : configurations) {
      // Use pattern matching to check selector configurations
      boolean hasPermission = selectors.stream().anyMatch(selector -> 
          switch (selector) {
              case SelectorConfiguration s -> {
                  String format = toFormat(configuration);
                  String repoName = configuration.getRepositoryName();
                  Permission permission = new RepositoryContentSelectorPermission(
                      s.getName(), format, repoName, singletonList(BROWSE));
                  yield securityHelper.anyPermitted(subject, permission);
              }
              default -> false;
          }
      );
      
      if (hasPermission) {
        permittedRepositories.add(configuration);
      }
    }

    return permittedRepositories;
  }

  /**
   * Converts a configuration to a format string.
   * Uses Java 21 string templates for improved readability.
   *
   * @param configuration the configuration to convert
   * @return the format string
   * @throws IllegalArgumentException if the recipe name is unknown
   */
  private String toFormat(final Configuration configuration) {
    String recipeName = configuration.getRecipeName();
    return Optional.ofNullable(recipes.get(recipeName))
        .map(Recipe::getFormat)
        .map(Format::getValue)
        .orElseThrow(() -> new IllegalArgumentException(STR."Unknown repository type: \{recipeName}"));
  }

  /**
   * Checks if the user has any content selector access to the given repository for the specified actions.
   * Uses Java 21 pattern matching for improved readability and maintainability.
   *
   * @param repository the repository to check access for
   * @param actions the actions to check permissions for
   * @return true if the user has any content selector access to the repository for the specified actions
   */
  private boolean userHasAnyContentSelectorAccessTo(final Repository repository, final String... actions) {
    // Getting the subject a single time improves performance
    Subject subject = securityHelper.subject(); 
    
    // Use Java 21 pattern matching for improved readability
    return selectorManager.browse().stream().anyMatch(selector -> 
        switch (selector) {
            case SelectorConfiguration s when s.isActive() -> 
                securityHelper.anyPermitted(subject,
                    Arrays.stream(actions)
                        .map(action -> new RepositoryContentSelectorPermission(s, repository, singletonList(action)))
                        .toArray(Permission[]::new));
            default -> false;
        }
    );
  }
}