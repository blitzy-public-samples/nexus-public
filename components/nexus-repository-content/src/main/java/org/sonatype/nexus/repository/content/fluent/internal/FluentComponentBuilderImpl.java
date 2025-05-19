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
package org.sonatype.nexus.repository.content.fluent.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.facet.ContentFacetSupport;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.content.fluent.FluentComponentBuilder;
import org.sonatype.nexus.repository.content.store.ComponentData;
import org.sonatype.nexus.repository.content.store.ComponentStore;

import static java.lang.StringTemplate.STR;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * {@link FluentComponentBuilder} implementation.
 *
 * @since 3.24
 */
public class FluentComponentBuilderImpl
    implements FluentComponentBuilder
{
  private final ContentFacetSupport facet;

  private final ComponentStore<?> componentStore;

  private final String name;

  private String kind = "";

  private String namespace = "";

  private String version = "";

  private String normalizedVersion = "";

  private Map<String, Object> attributes;

  public FluentComponentBuilderImpl(
      final ContentFacetSupport facet,
      final ComponentStore<?> componentStore,
      final String name)
  {
    this.facet = checkNotNull(facet);
    this.componentStore = checkNotNull(componentStore);
    this.name = checkNotNull(name);
  }

  @Override
  public FluentComponentBuilder namespace(final String namespace) {
    this.namespace = checkNotNull(namespace);
    return this;
  }

  @Override
  public FluentComponentBuilder kind(final String kind) {
    this.kind = checkNotNull(kind);
    return this;
  }

  @Override
  public FluentComponentBuilder kind(final Optional<String> optionalKind) {
    // Using enhanced Java 21 Optional APIs with pattern matching
    switch (optionalKind) {
      case Optional<String> opt when opt.isPresent() -> this.kind = opt.get();
      case Optional<String> empty -> {
        // Optional is empty, keep default kind value
        // Could add logging here if needed
      }
    }
    return this;
  }

  @Override
  public FluentComponentBuilder version(final String version) {
    this.version = checkNotNull(version);
    return this;
  }

  @Override
  public FluentComponentBuilder normalizedVersion(final String normalizedVersion) {
    this.normalizedVersion = checkNotNull(normalizedVersion);
    return this;
  }

  @Override
  public FluentComponentBuilder attributes(final String key, final Object value) {
    checkNotNull(key, STR."Key cannot be null when setting attribute for component: \{name}");
    checkNotNull(value, STR."Value cannot be null for key: \{key} in component: \{name}");
    
    // Initialize attributes map if needed, using Java 21 enhanced collection API
    attributes = (attributes != null) ? attributes : new HashMap<>();
    attributes.put(key, value);
    return this;
  }

  @Override
  public FluentComponent getOrCreate() {
    // Using String Templates for logging if needed
    Component component = componentStore.getOrCreate(this::findComponent, this::createComponent);
    
    // Using pattern matching to handle different component types
    return switch (component) {
      case ComponentData data -> {
        // Log creation using String Templates if needed
        // logger.debug(STR."Created new component: {data.name()} in repository: {data.repositoryId()}");
        yield new FluentComponentImpl(facet, data);
      }
      default -> new FluentComponentImpl(facet, component);
    };
  }

  @Override
  public Optional<FluentComponent> find() {
    // Using enhanced Java 21 Optional APIs with pattern matching for more expressive code
    return findComponent()
        .map(component -> switch(component) {
          case Component c when c.namespace().equals(namespace) && c.name().equals(name) -> 
              new FluentComponentImpl(facet, c);
          default -> new FluentComponentImpl(facet, component);
        });
  }

  private Optional<Component> findComponent() {
    // Using enhanced Optional APIs to provide more context if debugging is needed
    return componentStore.readCoordinate(facet.contentRepositoryId(), namespace, name, version)
        .or(() -> {
          // This branch is taken when the component is not found
          // We return an empty Optional but could add logging here if needed
          return Optional.empty();
        });
  }

  private Component createComponent() {
    // Create ComponentData with all required fields
    ComponentData component = new ComponentData();
    component.setRepositoryId(facet.contentRepositoryId());
    component.setNamespace(namespace);
    component.setName(name);
    component.setKind(kind);
    component.setVersion(version);
    component.setNormalizedVersion(normalizedVersion);

    // Using pattern matching to handle component attributes with Java 21 collection APIs
    switch (attributes) {
      case Map<String, Object> attrs when !attrs.isEmpty() -> 
          component.attributes().backing().putAll(attrs);
      case null, Map<String, Object> emptyAttrs -> 
          // No attributes to add
          break;
    }

    // Create the component in the store
    componentStore.createComponent(component);

    // Using record pattern to access component data (if ComponentData were a record)
    // This is a demonstration of how record patterns would be used if ComponentData was a record
    // For now, we're just returning the component as is
    return component;
  }
}