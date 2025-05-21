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
package org.sonatype.nexus.internal.capability.secrets.migration;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.capability.CapabilityReference;
import org.sonatype.nexus.capability.CapabilityRegistry;
import org.sonatype.nexus.formfields.Encrypted;
import org.sonatype.nexus.scheduling.CancelableHelper;
import org.sonatype.nexus.security.secrets.SecretsMigrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Migrates existing records from the legacy secret format to the new secret format.
 */
@Named
public class CapabilitySecretsMigrator
    implements SecretsMigrator
{
  private static final Logger log = LoggerFactory.getLogger(CapabilitySecretsMigrator.class);
  private static final long MIGRATION_TIMEOUT_SECONDS = 60;
  
  private final CapabilityRegistry capabilityRegistry;

  @Inject
  public CapabilitySecretsMigrator(final CapabilityRegistry capabilityRegistry) {
    this.capabilityRegistry = checkNotNull(capabilityRegistry);
  }

  @Override
  public void migrate() {
    List<CapabilityReference> maybeEncrypted = capabilityRegistry.getAll()
        .stream()
        .filter(this::containsEncryptedField)
        .collect(Collectors.toList());

    log.info(STR."Starting migration of \{maybeEncrypted.size()} capabilities with encrypted fields");
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<CompletableFuture<Void>> futures = maybeEncrypted.stream()
          .map(ref -> CompletableFuture.runAsync(() -> {
            try {
              CancelableHelper.checkCancellation();
              log.debug(STR."Migrating secrets for capability: \{ref.context().id()}");
              capabilityRegistry.migrateSecrets(ref, this::isLegacyEncryptedString);
            } catch (Exception e) {
              log.error(STR."Error migrating secrets for capability: \{ref.context().id()}", e);
              throw e;
            }
          }, executor)
          .orTimeout(MIGRATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
          .exceptionally(ex -> {
            log.error(STR."Migration task failed or timed out: \{ex.getMessage()}");
            return null;
          }))
          .collect(Collectors.toList());
      
      // Wait for all tasks to complete
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
      log.info("Capability secrets migration completed");
    }
  }

  private boolean containsEncryptedField(CapabilityReference reference) {
    return reference.context()
        .descriptor()
        .formFields()
        .stream()
        .anyMatch(f -> f instanceof Encrypted encrypted);
  }
}