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

  @POST
  @RequiresAuthentication
  @RequiresPermissions("nexus:*")
  public void createRoutingRule(RoutingRuleXO routingRuleXO)
  {
    routingRuleStore.create(fromXO(routingRuleXO));
  }

  @POST
  @Path("/test")
  @RequiresAuthentication
  @RequiresPermissions("nexus:*")
  public boolean isAllowed(RoutingRuleTestXO routingRuleTestXO)
  {
    var path = routingRuleTestXO.getPath();
    var matchers = routingRuleTestXO.getMatchers();
    var mode = routingRuleTestXO.getMode();
    return routingRuleHelper.isAllowed(mode, matchers, path);
  }

  @GET
  public List<RoutingRuleXO> getRoutingRules(@QueryParam("includeRepositoryNames") boolean includeRepositoryNames) {
    routingRuleHelper.ensureUserHasPermissionToRead();

    var rules = routingRuleStore.list()
            .stream()
            .map(RoutingRulesResource::toXO)
            .collect(toList());

    if (includeRepositoryNames) {
      setAssignedRepositories(rules);
    }

    return rules;
  }

  @RequiresAuthentication
  @RequiresPermissions("nexus:*")
  private void setAssignedRepositories(final List<RoutingRuleXO> rules) {
    var assignedRepositories = routingRuleHelper.calculateAssignedRepositories();
    for (var rule : rules) {
      var ruleId = id(rule.getId());
      var repositories = assignedRepositories.computeIfAbsent(ruleId, id -> emptyList());
      var repositoryNames = repositoryPermissionChecker
          .userHasRepositoryAdminPermission(repositories, BreadActions.READ).stream()
          .map(Repository::getName)
          .sorted(String.CASE_INSENSITIVE_ORDER)
          .collect(toList());

      rule.setAssignedRepositoryCount(repositories.size());
      rule.setAssignedRepositoryNames(repositoryNames);
    }
  }

  /**
   * @since 3.29
   */
  @GET
  @Path("/{name}")
  public RoutingRuleXO getRoutingRule(@PathParam("name") final String name) {
    routingRuleHelper.ensureUserHasPermissionToRead();
    var routingRule = RoutingRulesResource.toXO(routingRuleStore.getByName(name));
    var assignedRepositories = routingRuleHelper.calculateAssignedRepositories();
    populateAssignedRepositoryNames(assignedRepositories, routingRule);
    return routingRule;
  }

  @PUT
  @Path("/{name}")
  @RequiresAuthentication
  @RequiresPermissions("nexus:*")
  public void updateRoutingRule(@PathParam("name") final String name, RoutingRuleXO routingRuleXO) {
    var routingRule = routingRuleStore.getByName(name);
    if (null == routingRule) {
      throw new WebApplicationException(Status.NOT_FOUND);
    }
    routingRule.name(routingRuleXO.getName());
    routingRule.description(routingRuleXO.getDescription());
    routingRule.mode(routingRuleXO.getMode());
    routingRule.matchers(routingRuleXO.getMatchers());
    routingRuleStore.update(routingRule);
  }

  @DELETE
  @Path("/{name}")
  @RequiresAuthentication
  @RequiresPermissions("nexus:*")
  public void deleteRoutingRule(@PathParam("name") final String name) {
    var routingRule = routingRuleStore.getByName(name);
    if (null == routingRule) {
      throw new WebApplicationException(Status.NOT_FOUND);
    }

    var assignedRepositories = routingRuleHelper.calculateAssignedRepositories();
    var repositories = assignedRepositories.getOrDefault(routingRule.id(), emptyList());
    if (repositories.size() > 0) {
      throw new WebApplicationException(
          STR."Routing rule is still in use by \{repositories.size()} repositories.", 
          Status.BAD_REQUEST);
    }

    routingRuleStore.delete(routingRule);
  }

  @GET
  @Path("/preview")
  @RequiresPermissions("nexus:*")
  @RequiresAuthentication
  public RoutingRulePreviewXO getRoutingRulesPreview(@QueryParam("path") final String path,
                                                     @QueryParam("filter") final String filter)
  {
    var repositoriesByType = stream(repositoryManager.browse())
        .collect(groupingBy(r -> r.getType().getClass()));
    var groupRepositories = repositoriesByType.get(GroupType.class);
    var proxyRepositories = repositoriesByType.get(ProxyType.class);

    var routingRulePathMapping = routingRuleStore.list().stream()
        .collect(toMap(identity(), rule -> routingRuleHelper.isAllowed(rule, path)));

    final Stream<Repository> repositories = switch (filter) {
      case GROUPS -> groupRepositories.stream();
      case PROXIES -> proxyRepositories.stream();
      default -> Stream.of(groupRepositories, proxyRepositories).flatMap(Collection::stream);
    };

    var rootRepositories = repositories.map(repository -> {
      var children = repository.optionalFacet(GroupFacet.class)
          .map(GroupFacet::members).orElse(null);
      return toPreviewXO(repository, children, routingRulePathMapping);
    }).collect(toList());

    return RoutingRulePreviewXO.builder()
        .children(rootRepositories)
        .expanded(!rootRepositories.isEmpty())
        .expandable(true)
        .build();
  }

  private RoutingRulePreviewXO toPreviewXO(final Repository repository,
                                           final List<Repository> childRepositories,
                                           Map<RoutingRule, Boolean> routingRulePathMapping)
  {
    Optional<RoutingRule> maybeRule = getRoutingRule(repository);

    boolean allowed = maybeRule.map(routingRulePathMapping::get).orElse(ALLOWED);
    String ruleName = maybeRule.map(RoutingRule::name).orElse(null);

    List<RoutingRulePreviewXO> children = switch (childRepositories) {
      case null -> null;
      case var repos -> repos.stream()
          .map(childRepository -> toPreviewXO(childRepository, null, routingRulePathMapping))
          .collect(toList());
    };

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

  private Optional<RoutingRule> getRoutingRule(final Repository repository) {
    var config = repository.getConfiguration();
    return Optional.ofNullable(config.getRoutingRuleId())
        .map(EntityId::getValue)
        .map(routingRuleStore::getById);
  }

  private RoutingRule fromXO(RoutingRuleXO routingRuleXO) {
    var routingRule = routingRuleStore.newRoutingRule();
    routingRule.name(routingRuleXO.getName());
    routingRule.description(routingRuleXO.getDescription());
    routingRule.mode(routingRuleXO.getMode());
    routingRule.matchers(routingRuleXO.getMatchers());
    return routingRule;
  }

  private static RoutingRuleXO toXO(RoutingRule routingRule) {
    var routingRuleXO = new RoutingRuleXO();
    routingRuleXO.setId(routingRule.id().getValue());
    routingRuleXO.setName(routingRule.name());
    routingRuleXO.setDescription(routingRule.description());
    routingRuleXO.setMode(routingRule.mode());
    routingRuleXO.setMatchers(routingRule.matchers());
    return routingRuleXO;
  }

  private void populateAssignedRepositoryNames(Map<EntityId, List<Repository>> assignedRepositories, RoutingRuleXO routingRule) {
    var ruleId = id(routingRule.getId());
    var repositories = assignedRepositories.computeIfAbsent(ruleId, id -> emptyList());
    var repositoryNames = repositoryPermissionChecker
        .userHasRepositoryAdminPermission(repositories, BreadActions.READ).stream()
        .map(Repository::getName)
        .sorted(String.CASE_INSENSITIVE_ORDER)
        .collect(toList());

    routingRule.setAssignedRepositoryCount(repositoryNames.size());
    routingRule.setAssignedRepositoryNames(repositoryNames);
  }
}