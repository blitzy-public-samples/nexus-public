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
package org.sonatype.nexus.repository.group;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.validation.ConstraintViolation;
import javax.validation.constraints.NotNull;

import org.sonatype.nexus.common.collect.AttributesMap;
import org.sonatype.nexus.common.stateguard.Guarded;
import org.sonatype.nexus.repository.FacetSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.cache.CacheController;
import org.sonatype.nexus.repository.cache.CacheInfo;
import org.sonatype.nexus.repository.cache.RepositoryCacheInvalidationService;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.config.ConfigurationFacet;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.validation.ConstraintViolationFactory;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.collect.Iterables;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Objects.isNull;
import static org.sonatype.nexus.repository.FacetSupport.State.STARTED;
import static org.sonatype.nexus.validation.ConstraintViolations.maybeAdd;
import static org.sonatype.nexus.validation.ConstraintViolations.maybePropagate;

/**
 * Default {@link GroupFacet} implementation.
 *
 * @since 3.0
 */
@Named("default")
public class GroupFacetImpl
    extends FacetSupport
    implements GroupFacet
{
  protected final RepositoryManager repositoryManager;

  private final Type groupType;

  protected final ConstraintViolationFactory constraintViolationFactory;

  public static final String CONFIG_KEY = "group";

  private final RepositoryCacheInvalidationService repositoryCacheInvalidationService;

  public static class Config
  {
    @NotNull
    @JsonDeserialize(as = LinkedHashSet.class) // retain order
    public Set<String> memberNames;

    @Override
    public String toString() {
      return getClass().getSimpleName() + "{" +
          "memberNames=" + memberNames +
          '}';
    }
  }

  private Config config;

  protected CacheController cacheController;

  @Inject
  public GroupFacetImpl(final RepositoryManager repositoryManager,
                        final ConstraintViolationFactory constraintViolationFactory,
                        @Named(GroupType.NAME) final Type groupType,
                        final RepositoryCacheInvalidationService repositoryCacheInvalidationService)
  {
    this.repositoryManager = checkNotNull(repositoryManager);
    this.groupType = checkNotNull(groupType);
    this.constraintViolationFactory = checkNotNull(constraintViolationFactory);
    this.repositoryCacheInvalidationService = checkNotNull(repositoryCacheInvalidationService);
  }

  @Override
  protected void doValidate(final Configuration configuration) throws Exception {
    facet(ConfigurationFacet.class).validateSection(configuration, CONFIG_KEY, Config.class);

    Config configToValidate = facet(ConfigurationFacet.class).readSection(configuration, CONFIG_KEY, Config.class);
    Set<ConstraintViolation<?>> violations = new HashSet<>();

    maybeAdd(violations, validateFormat(configToValidate));

    if (getStateGuard().is(STARTED)) {
      maybeAdd(violations, validateGroupDoesNotContainItself(configuration.getRepositoryName(), configToValidate));
    }

    maybePropagate(violations, log);
  }

  /**
   * A method subclasses can override to perform format specific validation if necessary
   *
   * @param groupConfig the group's config object
   * @return the validation failures or null
   */
  protected ConstraintViolation<?> validateFormat(final Config groupConfig) {
    // empty for subclasses to optionally override
    return null;
  }

  private boolean containsGroup(final Repository root, final String repositoryName, final Set<Repository> checkedGroups) {
    return root.facet(GroupFacet.class).members().stream().anyMatch((repository) -> {
      return checkedGroups.add(repository) &&
          (repository.getName().equals(repositoryName) ||
              (groupType.equals(repository.getType()) && containsGroup(repository, repositoryName, checkedGroups)));
    });
  }

  ConstraintViolation<?> validateGroupDoesNotContainItself(final String repositoryName, final Config config) {
    Set<Repository> checkedGroups = new HashSet<>();
    for (String memberName : config.memberNames) {
      Repository repository = repositoryManager.get(memberName);
      if (repository.getName().equals(repositoryName) ||
          (groupType.equals(repository.getType()) && containsGroup(repository, repositoryName, checkedGroups))) {
        return constraintViolationFactory.createViolation(CONFIG_KEY + ".memberNames",
            "Group '" + repository.getName() + "' has a member repository '" + repositoryName +
                "' and cannot be added to this list.");
      }
    }

    return null;
  }

  @Override
  protected void doConfigure(final Configuration configuration) throws Exception {
    config = facet(ConfigurationFacet.class).readSection(configuration, CONFIG_KEY, Config.class);

    cacheController = new CacheController(-1, null);

    log.debug("Config: {}", config);
  }

  @Override
  protected void doUpdate(final Configuration configuration) throws Exception {
    // detect member changes
    Set<String> previousMemberNames = config.memberNames;
    super.doUpdate(configuration);

    // check whether any members or their ordering have changed
    if (!Iterables.elementsEqual(config.memberNames, previousMemberNames)) {
      cacheController.invalidateCache();
    }
  }

  @Override
  protected void doDestroy() throws Exception {
    config = null;
  }

  @Override
  @Guarded(by = STARTED)
  public boolean member(final String repositoryName) {
    checkNotNull(repositoryName);
    return config.memberNames.contains(repositoryName);
  }

  @Override
  @Guarded(by = STARTED)
  public boolean member(final Repository repository) {
    checkNotNull(repository);
    return config.memberNames.contains(repository.getName());
  }

  /**
   * Asynchronously resolves repository members using Virtual Threads for improved concurrency.
   * This implementation leverages Java 21 Virtual Threads to parallelize member resolution,
   * significantly improving performance for groups with many members.
   *
   * @return List of resolved repository members
   */
  @Override
  @Guarded(by = STARTED)
  public List<Repository> members() {
    final Repository repository = getRepository();
    final List<String> memberNames = new ArrayList<>(config.memberNames);
    final List<Repository> members = Collections.synchronizedList(new ArrayList<>(memberNames.size()));
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create a list of futures for each member resolution task
      List<CompletableFuture<Void>> futures = memberNames.stream()
          .map(name -> CompletableFuture.runAsync(() -> {
            Repository member = repositoryManager.get(name);
            if (member == null) {
              log.warn("Ignoring missing member repository: {}", name);
            } else if (!repository.getFormat().equals(member.getFormat())) {
              log.warn("Group {} includes an incompatible-format member: {} with format {}",
                  repository.getName(), name, member.getFormat());
            } else {
              members.add(member);
            }
          }, executor))
          .collect(Collectors.toList());

      // Wait for all futures to complete
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    } catch (Exception e) {
      log.error("Error resolving repository members", e);
    }
    
    return members;
  }

  /**
   * Optimized leaf members collection using Virtual Threads for parallel processing.
   * This method collects all non-group repositories by traversing the repository hierarchy
   * using Java 21 Virtual Threads for concurrent processing.
   *
   * @return List of leaf repository members (non-group repositories)
   */
  @Override
  public List<Repository> leafMembers() {
    Set<Repository> leafMembers = Collections.synchronizedSet(new LinkedHashSet<>());
    List<Repository> membersList = members();
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<CompletableFuture<Void>> futures = membersList.stream()
          .map(repository -> CompletableFuture.runAsync(() -> {
            if (groupType.equals(repository.getType())) {
              // For group repositories, recursively collect their leaf members
              leafMembers.addAll(repository.facet(GroupFacet.class).leafMembers());
            } else {
              // For non-group repositories, add directly to the result set
              leafMembers.add(repository);
            }
          }, executor))
          .collect(Collectors.toList());

      // Wait for all futures to complete
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    } catch (Exception e) {
      log.error("Error collecting leaf members", e);
    }

    return new ArrayList<>(leafMembers);
  }

  /**
   * Optimized all members collection using Virtual Threads for parallel processing.
   * This method collects all repositories (including groups) by traversing the repository hierarchy
   * using Java 21 Virtual Threads for concurrent processing.
   *
   * @return List of all repository members (including groups)
   */
  @Override
  public List<Repository> allMembers() {
    List<Repository> members = Collections.synchronizedList(new ArrayList<>());
    allMembersAsync(members, getRepository());
    return members;
  }

  /**
   * Asynchronously collects all members using Virtual Threads for improved performance.
   * This implementation uses a thread-safe approach to collect repository information.
   *
   * @param members The list to populate with all members
   * @param root The root repository to start collection from
   * @return The populated list of members
   */
  private List<Repository> allMembersAsync(final List<Repository> members, final Repository root) {
    // Check for duplicates to avoid cycles
    synchronized (members) {
      if (members.contains(root)) {
        return members;
      }
      members.add(root);
    }

    List<Repository> groupMembers = root.optionalFacet(GroupFacet.class).map(GroupFacet::members)
        .orElseGet(Collections::emptyList);
    
    if (!groupMembers.isEmpty()) {
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<CompletableFuture<Void>> futures = groupMembers.stream()
            .map(child -> CompletableFuture.runAsync(() -> allMembersAsync(members, child), executor))
            .collect(Collectors.toList());

        // Wait for all futures to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
      } catch (Exception e) {
        log.error("Error collecting all members", e);
      }
    }
    
    return members;
  }

  /**
   * Invalidates group caches using Virtual Threads for concurrent processing.
   * This implementation leverages Java 21 Virtual Threads to parallelize cache invalidation
   * across all member repositories, significantly improving performance for groups with many members.
   */
  @Override
  public void invalidateGroupCaches() {
    log.info("Invalidating group caches of {}", getRepository().getName());
    // Invalidate the local cache controller first
    cacheController.invalidateCache();
    
    // Get the list of member repositories
    List<Repository> membersList = members();
    if (membersList.isEmpty()) {
      return;
    }
    
    // Use Virtual Threads to concurrently invalidate caches for all members
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<CompletableFuture<Void>> futures = membersList.stream()
          .map(repository -> CompletableFuture.runAsync(() -> 
              repositoryCacheInvalidationService.processCachesInvalidation(repository), executor))
          .collect(Collectors.toList());

      // Wait for all cache invalidation operations to complete
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    } catch (Exception e) {
      log.error("Error during concurrent cache invalidation", e);
    }
  }

  /**
   * Determines if content is stale based on cache information.
   * This method is thread-safe and can be called from Virtual Threads.
   *
   * @param content The content to check for staleness
   * @return true if content is stale or cache info is missing, false otherwise
   */
  @Override
  public boolean isStale(@Nullable final Content content) {
    if (content == null) {
      return true;
    }

    final CacheInfo cacheInfo = content.getAttributes().get(CacheInfo.class);

    if(isNull(cacheInfo)) {
      log.warn("CacheInfo missing for {}, assuming stale content.", content);
      return true;
    }
    return cacheController.isStale(cacheInfo);
  }

  /**
   * Maintains cache information in the provided attributes map.
   * This method is thread-safe and can be called from Virtual Threads.
   *
   * @param attributesMap The attributes map to update with cache information
   */
  @Override
  public void maintainCacheInfo(final AttributesMap attributesMap) {
    attributesMap.set(CacheInfo.class, cacheController.current());
  }