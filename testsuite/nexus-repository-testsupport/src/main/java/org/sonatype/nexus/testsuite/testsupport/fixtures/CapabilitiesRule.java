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
package org.sonatype.nexus.testsuite.testsupport.fixtures;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.Executors;

import javax.inject.Provider;

import org.sonatype.nexus.capability.CapabilityContext;
import org.sonatype.nexus.capability.CapabilityIdentity;
import org.sonatype.nexus.capability.CapabilityReference;
import org.sonatype.nexus.capability.CapabilityRegistry;
import org.sonatype.nexus.capability.CapabilityType;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit Jupiter extension for managing capabilities during tests.
 * <p>
 * This extension provides methods to create, enable, disable, and remove capabilities,
 * and automatically cleans up after each test to ensure isolation.
 * <p>
 * Requires Java 21 or later.
 *
 * @since 3.60
 */
public class CapabilitiesRule
    implements BeforeEachCallback, AfterEachCallback
{
  private static final String OUTREACH = "OutreachManagementCapability";

  private final Provider<CapabilityRegistry> capabilityRegistryProvider;

  private final Collection<String> capabilitiesToDisable = new ArrayList<>();

  private final Collection<String> capabilitiesToRemove = new ArrayList<>();

  private final Map<String, Map<String, String>> originalProperties = new HashMap<>();

  /**
   * Constructor.
   *
   * @param capabilityRegistryProvider the provider for the capability registry
   */
  public CapabilitiesRule(final Provider<CapabilityRegistry> capabilityRegistryProvider) {
    this.capabilityRegistryProvider = capabilityRegistryProvider;
  }

  /**
   * Disables the Outreach capability.
   */
  public void disableOutreach() {
    disable(OUTREACH);
  }

  /**
   * Gets all capability references.
   *
   * @return all capability references
   */
  public Collection<CapabilityReference> getAll() {
    return capabilityRegistryProvider.get().getAll();
  }

  /**
   * Removes a capability by its identity.
   *
   * @param id the capability identity
   */
  public void removeById(final CapabilityIdentity id) {
    capabilityRegistryProvider.get().remove(id);
  }

  @Override
  public void beforeEach(ExtensionContext context) throws Exception {
    // No setup needed before each test
  }

  @Override
  public void afterEach(ExtensionContext context) throws Exception {
    // Use virtual threads for cleanup operations to improve performance with Java 21
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Remove capabilities that were created during the test
      capabilitiesToRemove.stream()
          .map(this::find)
          .filter(Optional::isPresent)
          .map(Optional::get)
          .map(CapabilityReference::context)
          .map(CapabilityContext::id)
          .forEach(id -> executor.submit(() -> capabilityRegistryProvider.get().remove(id)));

      // Disable capabilities that were enabled during the test
      capabilitiesToDisable.stream()
          .map(this::find)
          .filter(Optional::isPresent)
          .map(Optional::get)
          .map(CapabilityReference::context)
          .map(CapabilityContext::id)
          .forEach(id -> executor.submit(() -> capabilityRegistryProvider.get().disable(id)));

      // Restore original properties
      for (Entry<String, Map<String, String>> entry : originalProperties.entrySet()) {
        find(entry.getKey()).ifPresent(ref -> {
          executor.submit(() -> capabilityRegistryProvider.get().update(
              ref.context().id(), ref.context().isEnabled(), null, entry.getValue()));
        });
      }
    }

    // Clear state for next test
    capabilitiesToRemove.clear();
    capabilitiesToDisable.clear();
    originalProperties.clear();
  }

  /**
   * Creates a capability if not present, otherwise enable existing capability.
   *
   * @param capabilityType the capability type
   * @param properties the properties
   */
  protected void enableAndSetProperties(final String capabilityType, final Map<String, String> properties) {
    Optional<? extends CapabilityReference> capabilityReference = find(capabilityType);
    if (capabilityReference.isPresent()) {
      CapabilityContext context = capabilityReference.get().context();
      if (!context.isEnabled()) {
        capabilitiesToDisable.add(capabilityType);
      }
      originalProperties.put(capabilityType, context.properties());

      capabilityRegistryProvider.get().update(context.id(), true, null, properties);
    }
    else {
      createCapability(capabilityType, properties);
    }
  }

  /**
   * Create a capability.
   *
   * @param capabilityType the capability type
   * @param properties the properties
   */
  protected void createCapability(final String capabilityType, final Map<String, String> properties) {
    capabilityRegistryProvider.get().add(CapabilityType.capabilityType(capabilityType), true, null, properties);
    capabilitiesToRemove.add(capabilityType);
  }

  /**
   * Disables a capability, please note that original state will not be restored.
   *
   * @param capabilityType the capability type to disable
   */
  protected void disable(final String capabilityType) {
    // We don't handle missing capabilities here intentionally
    find(capabilityType)
        .ifPresent(capability ->
            capabilityRegistryProvider.get().disable(capability.context().id())
        );
  }

  /**
   * Removes a capability, please note that state will not be restored.
   *
   * @param capabilityType the capability type to remove
   */
  protected void remove(final String capabilityType) {
    find(capabilityType)
        .map(CapabilityReference::context)
        .map(CapabilityContext::id)
        .ifPresent(capabilityRegistryProvider.get()::remove);
  }

  /**
   * Checks if a capability is installed and enabled.
   *
   * @param capabilityType the capability type to check
   * @return true if the capability is installed and enabled, false otherwise
   */
  protected boolean isCapabilityInstalledAndEnabled(final String capabilityType) {
    return find(capabilityType)
        .map(CapabilityReference::context)
        .map(CapabilityContext::isEnabled)
        .orElse(false);
  }

  /**
   * Finds a capability reference by type.
   *
   * @param capabilityType the capability type to find
   * @return an optional containing the capability reference if found, empty otherwise
   */
  private Optional<? extends CapabilityReference> find(final String capabilityType) {
    CapabilityType type = CapabilityType.capabilityType(capabilityType);
    return capabilityRegistryProvider.get().getAll().stream()
        .filter(ref -> ref.context().type().equals(type))
        .findFirst();
  }
}