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
package org.sonatype.nexus.coreui;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response.Status;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.group.GroupFacet;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.rest.api.RoutingRulePreviewXO;
import org.sonatype.nexus.repository.routing.RoutingMode;
import org.sonatype.nexus.repository.routing.RoutingRule;
import org.sonatype.nexus.repository.routing.RoutingRuleHelper;
import org.sonatype.nexus.repository.routing.RoutingRuleStore;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.repository.security.RepositoryPermissionChecker;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.rest.Resource;
import org.sonatype.nexus.security.BreadActions;

import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Streams.stream;
import static java.util.Collections.emptyList;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static org.sonatype.nexus.common.entity.EntityHelper.id;

/**
 * REST resource for managing routing rules.
 *
 * @since 3.16
 */
@Named
@Singleton
@Consumes(APPLICATION_JSON)
@Produces(APPLICATION_JSON)
@Path(RoutingRulesResource.RESOURCE_PATH)
public class RoutingRulesResource
    extends ComponentSupport
    implements Resource
{
  static final String RESOURCE_PATH = "internal/ui/routing-rules";

  private static final String GROUPS = "groups";

  private static final String PROXIES = "proxies";

  private static final boolean ALLOWED = true;
  
  private final RoutingRuleStore routingRuleStore;

  private final RoutingRuleHelper routingRuleHelper;

  private final RepositoryPermissionChecker repositoryPermissionChecker;

  @Inject
  public RoutingRulesResource(final RoutingRuleStore routingRuleStore,
                              final RoutingRuleHelper routingRuleHelper,
                              final RepositoryPermissionChecker repositoryPermissionChecker) {
    this.routingRuleStore = checkNotNull(routingRuleStore);
    this.routingRuleHelper = checkNotNull(routingRuleHelper);
    this.repositoryPermissionChecker = checkNotNull(repositoryPermissionChecker);
  }

  @Inject
  private RepositoryManager repositoryManager;

  /**
   * Creates a new routing rule.
   *
   * @param routingRuleXO the routing rule to create
   */
  @POST
  @RequiresAuthentication
  @RequiresPermissions("nexus:*")
  public void createRoutingRule(RoutingRuleXO routingRuleXO)
  {
    routingRuleStore.create(fromXO(routingRuleXO));
  }

  /**
   * Tests if a path is allowed by the given routing rule configuration.
   *
   * @param routingRuleTestXO the routing rule test configuration
   * @return true if the path is allowed, false otherwise
   */
  @POST
  @Path("/test")
  @RequiresAuthentication
  @RequiresPermissions("nexus:*")
  public boolean isAllowed(RoutingRuleTestXO routingRuleTestXO)
  {
    String path = routingRuleTestXO.getPath();
    List<String> matchers = routingRuleTestXO.getMatchers();
    RoutingMode mode = routingRuleTestXO.getMode();
    return routingRuleHelper.isAllowed(mode, matchers, path);
  }

  /**
   * Retrieves all routing rules.
   *
   * @param includeRepositoryNames whether to include repository names in the response
   * @return a list of routing rules
   */
  @GET
  public List<RoutingRuleXO> getRoutingRules(@QueryParam("includeRepositoryNames") boolean includeRepositoryNames) {
    routingRuleHelper.ensureUserHasPermissionToRead();

    List<RoutingRuleXO> rules = routingRuleStore.list()
            .stream()
            .map(RoutingRulesResource::toXO)
            .collect(toList());

    if (includeRepositoryNames) {
      // Use virtual thread for this potentially I/O-bound operation
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        executor.submit(() -> setAssignedRepositories(rules)).join();
      }
    }

    return rules;
  }

  /**
   * Sets the assigned repositories for each routing rule.
   *
   * @param rules the routing rules to update
   */
  @RequiresAuthentication
  @RequiresPermissions("nexus:*")
  private void setAssignedRepositories(final List<RoutingRuleXO> rules) {
    Map<EntityId, List<Repository>> assignedRepositories = routingRuleHelper.calculateAssignedRepositories();
    for (RoutingRuleXO rule : rules) {
      List<Repository> repositories = assignedRepositories.computeIfAbsent(id(rule.getId()), id -> emptyList());
      List<String> repositoryNames = repositoryPermissionChecker
          .userHasRepositoryAdminPermission(repositories, BreadActions.READ).stream()
          .map(Repository::getName)
          .sorted(String.CASE_INSENSITIVE_ORDER)
          .collect(Collectors.toList());

      rule.setAssignedRepositoryCount(repositories.size());
      rule.setAssignedRepositoryNames(repositoryNames);
    }
  }

  /**
   * Retrieves a routing rule by name.
   *
   * @param name the name of the routing rule
   * @return the routing rule
   * @since 3.29
   */
  @GET
  @Path("/{name}")
  public RoutingRuleXO getRoutingRule(@PathParam("name") final String name) {
    routingRuleHelper.ensureUserHasPermissionToRead();
    RoutingRuleXO routingRule = RoutingRulesResource.toXO(routingRuleStore.getByName(name));
    
    // Use virtual thread for this potentially I/O-bound operation
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> {
        Map<EntityId, List<Repository>> assignedRepositories = routingRuleHelper.calculateAssignedRepositories();
        populateAssignedRepositoryNames(assignedRepositories, routingRule);
      }).join();
    }
    
    return routingRule;
  }

  /**
   * Updates a routing rule.
   *
   * @param name the name of the routing rule to update
   * @param routingRuleXO the updated routing rule
   */
  @PUT
  @Path("/{name}")
  @RequiresAuthentication
  @RequiresPermissions("nexus:*")
  public void updateRoutingRule(@PathParam("name") final String name, RoutingRuleXO routingRuleXO) {
    RoutingRule routingRule = routingRuleStore.getByName(name);
    if (routingRule == null) {
      throw new WebApplicationException(STR."Routing rule '\{name}' not found", Status.NOT_FOUND);
    }
    routingRule.name(routingRuleXO.getName());
    routingRule.description(routingRuleXO.getDescription());
    routingRule.mode(routingRuleXO.getMode());
    routingRule.matchers(routingRuleXO.getMatchers());
    routingRuleStore.update(routingRule);
  }

  /**
   * Deletes a routing rule.
   *
   * @param name the name of the routing rule to delete
   */
  @DELETE
  @Path("/{name}")
  @RequiresAuthentication
  @RequiresPermissions("nexus:*")
  public void deleteRoutingRule(@PathParam("name") final String name) {
    RoutingRule routingRule = routingRuleStore.getByName(name);
    if (routingRule == null) {
      throw new WebApplicationException(STR."Routing rule '\{name}' not found", Status.NOT_FOUND);
    }

    Map<EntityId, List<Repository>> assignedRepositories = routingRuleHelper.calculateAssignedRepositories();
    List<Repository> repositories = assignedRepositories.getOrDefault(routingRule.id(), emptyList());
    if (repositories.size() > 0) {
      throw new WebApplicationException(
          STR."Routing rule is still in use by \{repositories.size()} repositories.", 
          Status.BAD_REQUEST);
    }

    routingRuleStore.delete(routingRule);
  }

  /**
   * Previews routing rules for a given path.
   *
   * @param path the path to preview
   * @param filter the filter to apply ("groups", "proxies", or null for all)
   * @return a preview of routing rules for the path
   */
  @GET
  @Path("/preview")
  @RequiresPermissions("nexus:*")
  @RequiresAuthentication
  public RoutingRulePreviewXO getRoutingRulesPreview(@QueryParam("path") final String path,
                                                     @QueryParam("filter") final String filter)
  {
    // Use virtual thread for this potentially I/O-bound operation
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      return executor.submit(() -> generateRoutingRulesPreview(path, filter)).join();
    }
  }

  /**
   * Generates a preview of routing rules for a given path.
   *
   * @param path the path to preview
   * @param filter the filter to apply ("groups", "proxies", or null for all)
   * @return a preview of routing rules for the path
   */
  private RoutingRulePreviewXO generateRoutingRulesPreview(final String path, final String filter) {
    Map<Class<?>, List<Repository>> repositoriesByType = stream(repositoryManager.browse())
        .collect(groupingBy(r -> r.getType().getClass()));
    List<Repository> groupRepositories = repositoriesByType.get(GroupType.class);
    List<Repository> proxyRepositories = repositoriesByType.get(ProxyType.class);

    Map<RoutingRule, Boolean> routingRulePathMapping = routingRuleStore.list().stream()
        .collect(toMap(identity(), rule -> routingRuleHelper.isAllowed(rule, path)));

    final Stream<Repository> repositories = switch (filter) {
      case GROUPS -> groupRepositories.stream();
      case PROXIES -> proxyRepositories.stream();
      default -> Stream.of(groupRepositories, proxyRepositories)
                       .filter(list -> list != null)
                       .flatMap(Collection::stream);
    };

    List<RoutingRulePreviewXO> rootRepositories = repositories
        .map(repository -> {
          var children = repository.optionalFacet(GroupFacet.class)
              .map(GroupFacet::members)
              .orElse(null);
          return toPreviewXO(repository, children, routingRulePathMapping);
        })
        .collect(toList());

    return RoutingRulePreviewXO.builder()
        .children(rootRepositories)
        .expanded(!rootRepositories.isEmpty())
        .expandable(true)
        .build();
  }

  /**
   * Converts a repository to a preview XO.
   *
   * @param repository the repository
   * @param childRepositories the child repositories
   * @param routingRulePathMapping the routing rule path mapping
   * @return the preview XO
   */
  private RoutingRulePreviewXO toPreviewXO(final Repository repository,
                                           final List<Repository> childRepositories,
                                           Map<RoutingRule, Boolean> routingRulePathMapping)
  {
    Optional<RoutingRule> maybeRule = getRoutingRule(repository);

    boolean allowed = maybeRule.map(routingRulePathMapping::get).orElse(ALLOWED);
    String ruleName = maybeRule.map(RoutingRule::name).orElse(null);

    List<RoutingRulePreviewXO> children = childRepositories == null ? null : childRepositories.stream()
        .map(childRepository -> toPreviewXO(childRepository, null, routingRulePathMapping))
        .collect(toList());

    boolean hasChildren = children != null && !children.isEmpty();
    
    return RoutingRulePreviewXO.builder()
        .repository(repository.getName())
        .type(repository.getType().getValue())
        .format(repository.getFormat().getValue())
        .allowed(allowed)
        .rule(ruleName)
        .children(children)
        .expanded(hasChildren)
        .expandable(hasChildren)
        .build();
  }

  /**
   * Gets the routing rule for a repository.
   *
   * @param repository the repository
   * @return the routing rule, if any
   */
  private Optional<RoutingRule> getRoutingRule(final Repository repository) {
    return Optional.ofNullable(repository.getConfiguration().getRoutingRuleId())
        .map(EntityId::getValue)
        .map(routingRuleStore::getById);
  }

  /**
   * Converts a routing rule XO to a routing rule.
   *
   * @param routingRuleXO the routing rule XO
   * @return the routing rule
   */
  private RoutingRule fromXO(RoutingRuleXO routingRuleXO) {
    final RoutingRule routingRule = routingRuleStore.newRoutingRule();
    routingRule.name(routingRuleXO.getName());
    routingRule.description(routingRuleXO.getDescription());
    routingRule.mode(routingRuleXO.getMode());
    routingRule.matchers(routingRuleXO.getMatchers());
    return routingRule;
  }

  /**
   * Converts a routing rule to a routing rule XO.
   *
   * @param routingRule the routing rule
   * @return the routing rule XO
   */
  private static RoutingRuleXO toXO(RoutingRule routingRule) {
    RoutingRuleXO routingRuleXO = new RoutingRuleXO();
    routingRuleXO.setId(routingRule.id().getValue());
    routingRuleXO.setName(routingRule.name());
    routingRuleXO.setDescription(routingRule.description());
    routingRuleXO.setMode(routingRule.mode());
    routingRuleXO.setMatchers(routingRule.matchers());
    return routingRuleXO;
  }

  /**
   * Populates the assigned repository names for a routing rule.
   *
   * @param assignedRepositories the assigned repositories
   * @param routingRule the routing rule
   */
  private void populateAssignedRepositoryNames(Map<EntityId, List<Repository>> assignedRepositories, RoutingRuleXO routingRule) {
    List<Repository> repositories = assignedRepositories.computeIfAbsent(id(routingRule.getId()), id -> emptyList());
    List<String> repositoryNames = repositoryPermissionChecker
        .userHasRepositoryAdminPermission(repositories, BreadActions.READ).stream()
        .map(Repository::getName)
        .sorted(String.CASE_INSENSITIVE_ORDER)
        .collect(toList());

    routingRule.setAssignedRepositoryCount(repositoryNames.size());
    routingRule.setAssignedRepositoryNames(repositoryNames);
  }
}