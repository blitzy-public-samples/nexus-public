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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * OSGI bundle.
 *
 * @since 3.0
 */
public record BundleXO(
  @Min(0L)
  long id,

  @NotBlank
  String state,

  @NotBlank
  String name,

  @NotBlank
  String symbolicName,

  @NotBlank
  String location,

  @NotBlank
  String version,

  @Min(0L)
  int startLevel,

  boolean fragment,

  long lastModified,

  /**
   * Fragment bundle ids.
   */
  List<Long> fragments,

  /**
   * Fragment-host bundle ids.
   */
  List<Long> fragmentHosts,

  Map<String, String> headers
)
{
  /**
   * Returns a new BundleXO with the specified id.
   *
   * @param id the id to set
   * @return a new BundleXO with the updated id
   */
  public BundleXO withId(long id) {
    return new BundleXO(id, state, name, symbolicName, location, version, startLevel, fragment, lastModified, 
        fragments, fragmentHosts, headers);
  }

  /**
   * Returns a new BundleXO with the specified state.
   *
   * @param state the state to set
   * @return a new BundleXO with the updated state
   */
  public BundleXO withState(String state) {
    return new BundleXO(id, state, name, symbolicName, location, version, startLevel, fragment, lastModified, 
        fragments, fragmentHosts, headers);
  }

  /**
   * Returns a new BundleXO with the specified name.
   *
   * @param name the name to set
   * @return a new BundleXO with the updated name
   */
  public BundleXO withName(String name) {
    return new BundleXO(id, state, name, symbolicName, location, version, startLevel, fragment, lastModified, 
        fragments, fragmentHosts, headers);
  }

  /**
   * Returns a new BundleXO with the specified symbolic name.
   *
   * @param symbolicName the symbolic name to set
   * @return a new BundleXO with the updated symbolic name
   */
  public BundleXO withSymbolicName(String symbolicName) {
    return new BundleXO(id, state, name, symbolicName, location, version, startLevel, fragment, lastModified, 
        fragments, fragmentHosts, headers);
  }

  /**
   * Returns a new BundleXO with the specified location.
   *
   * @param location the location to set
   * @return a new BundleXO with the updated location
   */
  public BundleXO withLocation(String location) {
    return new BundleXO(id, state, name, symbolicName, location, version, startLevel, fragment, lastModified, 
        fragments, fragmentHosts, headers);
  }

  /**
   * Returns a new BundleXO with the specified version.
   *
   * @param version the version to set
   * @return a new BundleXO with the updated version
   */
  public BundleXO withVersion(String version) {
    return new BundleXO(id, state, name, symbolicName, location, version, startLevel, fragment, lastModified, 
        fragments, fragmentHosts, headers);
  }

  /**
   * Returns a new BundleXO with the specified start level.
   *
   * @param startLevel the start level to set
   * @return a new BundleXO with the updated start level
   */
  public BundleXO withStartLevel(int startLevel) {
    return new BundleXO(id, state, name, symbolicName, location, version, startLevel, fragment, lastModified, 
        fragments, fragmentHosts, headers);
  }

  /**
   * Returns a new BundleXO with the specified fragment flag.
   *
   * @param fragment the fragment flag to set
   * @return a new BundleXO with the updated fragment flag
   */
  public BundleXO withFragment(boolean fragment) {
    return new BundleXO(id, state, name, symbolicName, location, version, startLevel, fragment, lastModified, 
        fragments, fragmentHosts, headers);
  }

  /**
   * Returns a new BundleXO with the specified last modified timestamp.
   *
   * @param lastModified the last modified timestamp to set
   * @return a new BundleXO with the updated last modified timestamp
   */
  public BundleXO withLastModified(long lastModified) {
    return new BundleXO(id, state, name, symbolicName, location, version, startLevel, fragment, lastModified, 
        fragments, fragmentHosts, headers);
  }

  /**
   * Returns a new BundleXO with the specified fragments.
   *
   * @param fragments the fragments to set
   * @return a new BundleXO with the updated fragments
   */
  public BundleXO withFragments(List<Long> fragments) {
    return new BundleXO(id, state, name, symbolicName, location, version, startLevel, fragment, lastModified, 
        fragments, fragmentHosts, headers);
  }

  /**
   * Returns a new BundleXO with the specified fragment hosts.
   *
   * @param fragmentHosts the fragment hosts to set
   * @return a new BundleXO with the updated fragment hosts
   */
  public BundleXO withFragmentHosts(List<Long> fragmentHosts) {
    return new BundleXO(id, state, name, symbolicName, location, version, startLevel, fragment, lastModified, 
        fragments, fragmentHosts, headers);
  }

  /**
   * Returns a new BundleXO with the specified headers.
   *
   * @param headers the headers to set
   * @return a new BundleXO with the updated headers
   */
  public BundleXO withHeaders(Map<String, String> headers) {
    return new BundleXO(id, state, name, symbolicName, location, version, startLevel, fragment, lastModified, 
        fragments, fragmentHosts, headers);
  }
  
  public static Builder builder() {
      return new Builder();
  }

  public static class Builder {
      private long id = 0L;
      private String state = "UNKNOWN";
      private String name = "Unnamed Bundle";
      private String symbolicName = "com.example.unnamed";
      private String location = "";
      private String version = "0.0.0";
      private int startLevel = 0;
      private boolean fragment = false;
      private long lastModified = System.currentTimeMillis();
      private List<Long> fragments = new ArrayList<>();
      private List<Long> fragmentHosts = new ArrayList<>();
      private Map<String, String> headers = new HashMap<>();

      public Builder id(long id) { this.id = id; return this; }
      public Builder state(String state) { this.state = state; return this; }
      public Builder name(String name) { this.name = name; return this; }
      public Builder symbolicName(String symbolicName) { this.symbolicName = symbolicName; return this; }
      public Builder location(String location) { this.location = location; return this; }
      public Builder version(String version) { this.version = version; return this; }
      public Builder startLevel(int startLevel) { this.startLevel = startLevel; return this; }
      public Builder fragment(boolean fragment) { this.fragment = fragment; return this; }
      public Builder lastModified(long lastModified) { this.lastModified = lastModified; return this; }
      public Builder fragments(List<Long> fragments) { this.fragments = new ArrayList<>(fragments); return this; } // Defensive copy
      public Builder fragmentHosts(List<Long> fragmentHosts) { this.fragmentHosts = new ArrayList<>(fragmentHosts); return this; } // Defensive copy
      public Builder headers(Map<String, String> headers) { this.headers = new HashMap<>(headers); return this; } // Defensive copy

      public BundleXO build() {
          return new BundleXO(
              id, state, name, symbolicName, location, version,
              startLevel, fragment, lastModified,
              Collections.unmodifiableList(fragments), // Make lists unmodifiable for true immutability
              Collections.unmodifiableList(fragmentHosts),
              Collections.unmodifiableMap(headers)
          );
      }
  }
}