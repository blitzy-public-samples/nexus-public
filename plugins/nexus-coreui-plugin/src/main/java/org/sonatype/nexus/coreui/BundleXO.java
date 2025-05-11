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

import java.util.List;
import java.util.Map;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;

/**
 * OSGI bundle.
 *
 * @since 3.0
 */
public record BundleXO(
  @Min(0L) long id,
  @NotBlank String state,
  @NotBlank String name,
  @NotBlank String symbolicName,
  @NotBlank String location,
  @NotBlank String version,
  @Min(0L) int startLevel,
  boolean fragment,
  long lastModified,
  List<Long> fragments,
  List<Long> fragmentHosts,
  Map<String, String> headers
) {
  /**
   * Creates a new BundleXO with the specified id.
   *
   * @param id the bundle id
   * @return a new BundleXO with the specified id and default values for other fields
   */
  public static BundleXO withId(long id) {
    return new BundleXO(id, "", "", "", "", "", 0, false, 0L, null, null, null);
  }

  /**
   * Creates a new BundleXO with the specified state.
   *
   * @param state the bundle state
   * @return a new BundleXO with the specified state
   */
  public BundleXO withState(String state) {
    return new BundleXO(id, state, name, symbolicName, location, version, startLevel, fragment, lastModified, 
        fragments, fragmentHosts, headers);
  }

  /**
   * Creates a new BundleXO with the specified name.
   *
   * @param name the bundle name
   * @return a new BundleXO with the specified name
   */
  public BundleXO withName(String name) {
    return new BundleXO(id, state, name, symbolicName, location, version, startLevel, fragment, lastModified, 
        fragments, fragmentHosts, headers);
  }

  /**
   * Creates a new BundleXO with the specified symbolic name.
   *
   * @param symbolicName the bundle symbolic name
   * @return a new BundleXO with the specified symbolic name
   */
  public BundleXO withSymbolicName(String symbolicName) {
    return new BundleXO(id, state, name, symbolicName, location, version, startLevel, fragment, lastModified, 
        fragments, fragmentHosts, headers);
  }

  /**
   * Creates a new BundleXO with the specified location.
   *
   * @param location the bundle location
   * @return a new BundleXO with the specified location
   */
  public BundleXO withLocation(String location) {
    return new BundleXO(id, state, name, symbolicName, location, version, startLevel, fragment, lastModified, 
        fragments, fragmentHosts, headers);
  }

  /**
   * Creates a new BundleXO with the specified version.
   *
   * @param version the bundle version
   * @return a new BundleXO with the specified version
   */
  public BundleXO withVersion(String version) {
    return new BundleXO(id, state, name, symbolicName, location, version, startLevel, fragment, lastModified, 
        fragments, fragmentHosts, headers);
  }

  /**
   * Creates a new BundleXO with the specified start level.
   *
   * @param startLevel the bundle start level
   * @return a new BundleXO with the specified start level
   */
  public BundleXO withStartLevel(int startLevel) {
    return new BundleXO(id, state, name, symbolicName, location, version, startLevel, fragment, lastModified, 
        fragments, fragmentHosts, headers);
  }

  /**
   * Creates a new BundleXO with the specified fragment flag.
   *
   * @param fragment the bundle fragment flag
   * @return a new BundleXO with the specified fragment flag
   */
  public BundleXO withFragment(boolean fragment) {
    return new BundleXO(id, state, name, symbolicName, location, version, startLevel, fragment, lastModified, 
        fragments, fragmentHosts, headers);
  }

  /**
   * Creates a new BundleXO with the specified last modified timestamp.
   *
   * @param lastModified the bundle last modified timestamp
   * @return a new BundleXO with the specified last modified timestamp
   */
  public BundleXO withLastModified(long lastModified) {
    return new BundleXO(id, state, name, symbolicName, location, version, startLevel, fragment, lastModified, 
        fragments, fragmentHosts, headers);
  }

  /**
   * Creates a new BundleXO with the specified fragments.
   *
   * @param fragments the bundle fragments
   * @return a new BundleXO with the specified fragments
   */
  public BundleXO withFragments(List<Long> fragments) {
    return new BundleXO(id, state, name, symbolicName, location, version, startLevel, fragment, lastModified, 
        fragments, fragmentHosts, headers);
  }

  /**
   * Creates a new BundleXO with the specified fragment hosts.
   *
   * @param fragmentHosts the bundle fragment hosts
   * @return a new BundleXO with the specified fragment hosts
   */
  public BundleXO withFragmentHosts(List<Long> fragmentHosts) {
    return new BundleXO(id, state, name, symbolicName, location, version, startLevel, fragment, lastModified, 
        fragments, fragmentHosts, headers);
  }

  /**
   * Creates a new BundleXO with the specified headers.
   *
   * @param headers the bundle headers
   * @return a new BundleXO with the specified headers
   */
  public BundleXO withHeaders(Map<String, String> headers) {
    return new BundleXO(id, state, name, symbolicName, location, version, startLevel, fragment, lastModified, 
        fragments, fragmentHosts, headers);
  }
}