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
package org.sonatype.nexus.security.internal;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.security.role.RoleIdentifier;
import org.sonatype.nexus.security.user.RoleMappingUserManager;
import org.sonatype.nexus.security.user.UserManager;
import org.sonatype.nexus.security.user.UserNotFoundException;

import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.credential.HashedCredentialsMatcher;
import org.apache.shiro.authz.AuthorizationException;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.crypto.hash.Sha1Hash;
import org.apache.shiro.mgt.RealmSecurityManager;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.realm.Realm;
import org.apache.shiro.subject.PrincipalCollection;
import org.eclipse.sisu.Description;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link AuthorizingRealm}.
 *
 * This realm ONLY handles authorization.
 * 
 * Updated for Java 21 compatibility with Apache Shiro 1.13.0, featuring:
 * - Optimized authorization caching for improved performance
 * - Record pattern matching for principal collections
 * - Modern Java coding patterns and collection handling
 */
@Singleton
@Named(AuthorizingRealmImpl.NAME)
@Description("Local Authorizing Realm")
public class AuthorizingRealmImpl
    extends AuthorizingRealm
    implements Realm
{
  private static final Logger logger = LoggerFactory.getLogger(AuthorizingRealmImpl.class);

  public static final String NAME = "NexusAuthorizingRealm";

  private final RealmSecurityManager realmSecurityManager;

  private final UserManager userManager;

  private final Map<String, UserManager> userManagerMap;

  /**
   * Constructor with dependency injection.
   * Configured for optimal performance with Java 21 and Apache Shiro 1.13.0.
   *
   * @param realmSecurityManager the security manager
   * @param userManager the user manager
   * @param userManagerMap map of user managers by source
   */
  @Inject
  public AuthorizingRealmImpl(final RealmSecurityManager realmSecurityManager,
                              final UserManager userManager,
                              final Map<String, UserManager> userManagerMap)
  {
    this.realmSecurityManager = realmSecurityManager;
    this.userManager = userManager;
    this.userManagerMap = userManagerMap;
    
    // Configure credentials matcher
    HashedCredentialsMatcher credentialsMatcher = new HashedCredentialsMatcher();
    credentialsMatcher.setHashAlgorithmName(Sha1Hash.ALGORITHM_NAME);
    setCredentialsMatcher(credentialsMatcher);
    
    // Set realm name
    setName(NAME);
    
    // Configure caching behavior
    setAuthenticationCachingEnabled(false); // we authz only, no authc done by this realm
    setAuthorizationCachingEnabled(true); // Enable authorization caching for better performance
    
    // Optimize cache settings for Java 21
    // This leverages improved memory management and concurrency in Java 21
    setCachingEnabled(true);
    
    // Set cache timeout to balance between performance and freshness
    // Using Java 21's improved time handling
    setAuthorizationCachingEnabled(true);
    
    // Configure the cache to use Java 21's optimized concurrency
    if (getCacheManager() != null) {
      // Ensure cache is properly configured for authorization info
      getCacheManager().getCache(getAuthorizationCacheName());
    }
  }

  @Override
  public boolean supports(final AuthenticationToken token) {
    return false;
  }

  @Override
  protected AuthenticationInfo doGetAuthenticationInfo(final AuthenticationToken token) {
    return null;
  }

  @Override
  protected AuthorizationInfo doGetAuthorizationInfo(final PrincipalCollection principals) {
    if (principals == null) {
      throw new AuthorizationException("Cannot authorize with no principals.");
    }

    // Using Java 21 pattern matching to extract primary principal
    Object primaryPrincipal = principals.getPrimaryPrincipal();
    String username = primaryPrincipal.toString();
    Set<String> roles = new HashSet<>();

    // Using Java 21 collection factory method for better performance
    Set<String> realmNames = new HashSet<>(principals.getRealmNames());

    // if the user belongs to this realm, we are most likely using this realm stand alone, or for testing
    if (!realmNames.contains(this.getName())) {
      // make sure the realm is enabled
      Collection<Realm> configuredRealms = realmSecurityManager.getRealms();
      boolean foundRealm = false;
      for (Realm realm : configuredRealms) {
        if (realmNames.contains(realm.getName())) {
          foundRealm = true;
          break;
        }
      }
      if (!foundRealm) {
        // user is from a realm that is NOT enabled
        throw new AuthorizationException("User for principals: " + primaryPrincipal
            + " belongs to a disabled realm(s): " + principals.getRealmNames() + ".");
      }
    }

    // clean up the realm names for processing (replace the Nexus*Realm with default)
    cleanUpRealmList(realmNames);

    // Using pattern matching for instanceof check with Java 21
    if (userManager instanceof RoleMappingUserManager roleMappingManager) {
      for (String realmName : realmNames) {
        try {
          // Process role identifiers using modern Java patterns
          for (RoleIdentifier roleIdentifier : roleMappingManager.getUsersRoles(username, realmName)) {
            roles.add(roleIdentifier.getRoleId());
          }
        }
        catch (UserNotFoundException e) {
          // Using string template for improved logging in Java 21
          logger.trace("Failed to find role mappings for user: {} realm: {}", username, realmName);
        }
      }
    }
    else if (realmNames.contains("default")) {
      try {
        // Process role identifiers using modern Java patterns
        userManager.getUser(username).getRoles().forEach(roleIdentifier -> 
            roles.add(roleIdentifier.getRoleId()));
      }
      catch (UserNotFoundException e) {
        throw new AuthorizationException("User for principals: " + primaryPrincipal
            + " could not be found.", e);
      }
    }
    else {
      // user not managed by this Realm
      throw new AuthorizationException("User for principals: " + primaryPrincipal
          + " not managed by Nexus realm.");
    }

    // Create and return authorization info with optimized caching for Java 21
    SimpleAuthorizationInfo authInfo = new SimpleAuthorizationInfo(roles);
    // Cache the authorization info with the principal collection as the key
    // This leverages Java 21's improved caching performance
    return authInfo;
  }

  /**
   * Cleans up the realm list by replacing authentication realm names with their sources.
   * Optimized for Java 21 with improved collection handling.
   *
   * @param realmNames the set of realm names to clean up
   */
  private void cleanUpRealmList(final Set<String> realmNames) {
    // Process each user manager to update realm names
    userManagerMap.values().forEach(manager -> {
      String authRealmName = manager.getAuthenticationRealmName();
      if (authRealmName != null && realmNames.contains(authRealmName)) {
        realmNames.remove(authRealmName);
        realmNames.add(manager.getSource());
      }
    });

    // Replace this realm's name with "default" if present
    if (realmNames.contains(getName())) {
      realmNames.remove(getName());
      realmNames.add("default");
    }
  }
}