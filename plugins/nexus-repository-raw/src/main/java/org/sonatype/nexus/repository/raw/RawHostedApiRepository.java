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
package org.sonatype.nexus.repository.raw;

import javax.validation.constraints.NotNull;

import org.sonatype.nexus.repository.raw.internal.RawFormat;
import org.sonatype.nexus.repository.rest.api.model.CleanupPolicyAttributes;
import org.sonatype.nexus.repository.rest.api.model.ComponentAttributes;
import org.sonatype.nexus.repository.rest.api.model.HostedStorageAttributes;
import org.sonatype.nexus.repository.rest.api.model.SimpleApiHostedRepository;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Raw Hosted Repository API model.
 * <p>
 * This class is compatible with Java 21 and can be used with Record Patterns
 * when processing instances of this class in consumer code.
 * <p>
 * Example using Record Patterns with instanceof (Java 21+):
 * <pre>
 * if (repo instanceof RawHostedApiRepository(var name, var url, var online, var storage, var cleanup, var component, var raw)) {
 *     // Access components directly without getter methods
 *     processRawAttributes(raw);
 * }
 * </pre>
 *
 * @since 3.41
 */
public class RawHostedApiRepository
    extends SimpleApiHostedRepository
{
  @NotNull
  private final RawAttributes raw;

  /**
   * Creates a new RawHostedApiRepository instance.
   *
   * @param name     the repository name
   * @param url      the repository URL
   * @param online   whether the repository is online
   * @param storage  the storage attributes
   * @param cleanup  the cleanup policy attributes
   * @param component the component attributes
   * @param raw      the raw-specific attributes
   */
  @JsonCreator
  public RawHostedApiRepository(
      @JsonProperty("name") final String name,
      @JsonProperty("url") final String url,
      @JsonProperty("online") final Boolean online,
      @JsonProperty("storage") final HostedStorageAttributes storage,
      @JsonProperty("cleanup") final CleanupPolicyAttributes cleanup,
      @JsonProperty("component") final ComponentAttributes component,
      @JsonProperty("raw") final RawAttributes raw)
  {
    super(name, RawFormat.NAME, url, online, storage, cleanup, component);
    this.raw = raw;
  }

  /**
   * Returns the raw-specific attributes.
   *
   * @return the raw attributes
   */
  public RawAttributes getRaw() {
    return raw;
  }
}