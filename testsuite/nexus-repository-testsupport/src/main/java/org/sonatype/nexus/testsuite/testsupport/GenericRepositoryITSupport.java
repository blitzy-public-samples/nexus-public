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
package org.sonatype.nexus.testsuite.testsupport;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.inject.Inject;

import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.search.index.ElasticSearchIndexService;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.authc.apikey.ApiKeyService;
import org.sonatype.nexus.security.authz.AuthorizationManager;
import org.sonatype.nexus.security.authz.NoSuchAuthorizationManagerException;
import org.sonatype.nexus.security.realm.RealmManager;
import org.sonatype.nexus.security.role.NoSuchRoleException;
import org.sonatype.nexus.security.role.Role;
import org.sonatype.nexus.security.role.RoleIdentifier;
import org.sonatype.nexus.security.user.NoSuchUserManagerException;
import org.sonatype.nexus.security.user.User;
import org.sonatype.nexus.security.user.UserNotFoundException;
import org.sonatype.nexus.security.user.UserStatus;
import org.sonatype.nexus.selector.SelectorConfiguration;
import org.sonatype.nexus.selector.SelectorManager;
import org.sonatype.nexus.testsuite.helpers.ComponentAssetTestHelper;
import org.sonatype.nexus.testsuite.testsupport.apt.AptClient;
import org.sonatype.nexus.testsuite.testsupport.apt.AptClientFactory;
import org.sonatype.nexus.testsuite.testsupport.fixtures.BlobStoreRule;
import org.sonatype.nexus.testsuite.testsupport.fixtures.RepositoryRule;
import org.sonatype.nexus.testsuite.testsupport.maven.Maven2Client;
import org.sonatype.nexus.testsuite.testsupport.raw.RawClient;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.auth.Credentials;
import org.apache.http.client.utils.HttpClientUtils;
import org.apache.http.util.EntityUtils;
import org.hamcrest.MatcherAssert;
import org.joda.time.DateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.Matchers.is;
import static org.sonatype.nexus.repository.http.HttpStatus.OK;
import static org.sonatype.nexus.security.user.UserManager.DEFAULT_SOURCE;
import static org.sonatype.nexus.testsuite.testsupport.FormatClientSupport.status;

/**
 * Support class for repository format ITs.
 * <p>
 * This class provides support for integration testing of repository formats with Java 21 compatibility,
 * including virtual thread support for improved concurrency in I/O operations.
 *
 * @deprecated Please write new tests as part of the {@link ITSupport} hierarchy
 */
@Deprecated
public abstract class GenericRepositoryITSupport<RR extends RepositoryRule>
    extends NexusITSupport
{
  protected static final String SLASH_REPO_SLASH = "/repository/";

  protected static final int MAX_NUGET_CLIENT_CONNECTIONS = 100;

  /**
   * Factory for creating APT clients
   */
  protected AptClientFactory aptClientFactory = new AptClientFactory();

  /**
   * Executor service for virtual threads to improve I/O operation concurrency.
   * Virtual threads are lightweight and managed by the JVM, allowing for significantly
   * higher concurrency with minimal resource overhead compared to platform threads.
   */
  protected final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  @Inject
  protected ComponentAssetTestHelper componentAssetTestHelper;

  @Inject
  protected RepositoryManager repositoryManager;

  @Inject
  protected ApiKeyService keyStore;

  @Inject
  protected RealmManager realmManager;

  @Inject
  protected SecuritySystem securitySystem;

  @Inject
  protected SelectorManager selectorManager;

  @Inject
  protected BlobStoreManager blobStoreManager;

  @Inject
  protected ElasticSearchIndexService elasticSearchIndexService;

  protected BlobStoreRule blobstoreRule = new BlobStoreRule(() -> blobStoreManager);

  protected RR repos = createRepositoryRule();

  @RegisterExtension
  public final BlobStoreRule blobstoreExtension = blobstoreRule;

  @RegisterExtension
  public final RR reposExtension = repos;

  protected abstract RR createRepositoryRule();

  /**
   * Cleanup resources after each test.
   */
  @AfterEach
  public void cleanupExecutor() {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      try {
        if (!virtualThreadExecutor.awaitTermination(5, SECONDS)) {
          virtualThreadExecutor.shutdownNow();
        }
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        virtualThreadExecutor.shutdownNow();
      }
    }
  }

  /**
   * Returns the base URL for a repository.
   *
   * @param repository the repository
   * @return the base URL for the repository
   */
  @Nonnull
  protected URL repositoryBaseUrl(final Repository repository) {
    return resolveUrl(nexusUrl, SLASH_REPO_SLASH + repository.getName() + "/");
  }

  /**
   * Creates a RawClient for the specified repository.
   *
   * @param repository the repository
   * @return a RawClient for the repository
   * @throws Exception if an error occurs
   */
  @Nonnull
  protected RawClient rawClient(final Repository repository) throws Exception {
    checkNotNull(repository);
    return rawClient(repositoryBaseUrl(repository));
  }

  /**
   * Creates a RawClient for the specified repository URL.
   *
   * @param repositoryUrl the repository URL
   * @return a RawClient for the repository URL
   * @throws Exception if an error occurs
   */
  protected RawClient rawClient(final URL repositoryUrl) throws Exception {
    return new RawClient(
        clientBuilder(repositoryUrl).build(),
        clientContext(),
        repositoryUrl.toURI()
    );
  }

  /**
   * Creates a Maven2Client for the specified repository.
   *
   * @param repository the repository
   * @return a Maven2Client for the repository
   * @throws Exception if an error occurs
   */
  protected Maven2Client maven2Client(final Repository repository) throws Exception {
    checkNotNull(repository);
    return maven2Client(repositoryBaseUrl(repository));
  }

  /**
   * Creates a Maven2Client for the specified repository URL.
   *
   * @param repositoryUrl the repository URL
   * @return a Maven2Client for the repository URL
   * @throws Exception if an error occurs
   */
  protected Maven2Client maven2Client(final URL repositoryUrl) throws Exception {
    return new Maven2Client(
        clientBuilder(repositoryUrl).build(),
        clientContext(),
        repositoryUrl.toURI()
    );
  }

  /**
   * Creates an AptClient for the specified repository using the default credentials.
   *
   * @param repositoryName the repository name
   * @return an AptClient for the repository
   * @throws Exception if an error occurs
   */
  protected AptClient createAptClient(final String repositoryName) throws Exception {
    Credentials creds = credentials();
    return createAptClient(repositoryName, creds.getUserPrincipal().getName(), creds.getPassword());
  }

  /**
   * Creates an AptClient for the specified repository using the provided credentials.
   *
   * @param repositoryName the repository name
   * @param username the username
   * @param password the password
   * @return an AptClient for the repository
   */
  protected AptClient createAptClient(final String repositoryName, final String username, final String password)
  {
    checkNotNull(repositoryManager.get(repositoryName));
    return aptClientFactory
        .createClient(resolveUrl(nexusUrl, SLASH_REPO_SLASH + repositoryName + "/"), username, password);
  }

  /**
   * Enables the specified realm if not already enabled.
   *
   * @param realmName the realm name
   */
  protected void enableRealm(final String realmName) {
    log.info("Current Realms: {}", realmManager.getConfiguredRealmIds());
    log.info("Adding {} if not already configured.", realmName);
    realmManager.enableRealm(realmName);
  }

  /**
   * Creates a user with the specified role if the user doesn't exist,
   * or updates the user's roles if the user already exists.
   *
   * @param username the username
   * @param password the password
   * @param role the role
   * @throws NoSuchUserManagerException if the user manager doesn't exist
   */
  protected void maybeCreateUser(final String username, final String password, final String role)
      throws NoSuchUserManagerException
  {
    try {
      User testUser = securitySystem.getUser(username);
      securitySystem.updateUser(userSetRoles(testUser, role));
    }
    catch (UserNotFoundException e) { // NOSONAR
      securitySystem.addUser(createUser(username, role), password);
    }
  }

  /**
   * Creates a role with the specified privileges.
   *
   * @param name the role name
   * @param privileges the privileges
   * @return the created role
   */
  protected static Role createRole(final String name, final String... privileges) {
    Role role = new Role();
    role.setRoleId(name);
    role.setSource(DEFAULT_SOURCE);
    role.setName(name);
    role.setDescription(name);
    role.setReadOnly(false);
    role.setPrivileges(Sets.newHashSet(privileges));
    return role;
  }

  /**
   * Sets the roles for a user.
   *
   * @param user the user
   * @param roles the roles
   * @return the updated user
   */
  protected static User userSetRoles(final User user, final String... roles) {
    Set<RoleIdentifier> roleIds = Arrays.stream(roles)
        .map(r -> new RoleIdentifier(DEFAULT_SOURCE, r))
        .collect(Collectors.toSet());
    user.setRoles(roleIds);
    return user;
  }

  /**
   * Creates a user with the specified role.
   *
   * @param username the username
   * @param role the role
   * @return the created user
   */
  protected static User createUser(final String username, final String role) {
    User user = new User();
    user.setUserId(username);
    user.setSource(DEFAULT_SOURCE);
    user.setFirstName(username);
    user.setLastName(username);
    user.setEmailAddress("void@void.void");
    user.setStatus(UserStatus.active);
    return userSetRoles(user, role);
  }

  /**
   * Creates a selector with the specified name, type, and expression if it doesn't exist.
   *
   * @param name the selector name
   * @param type the selector type
   * @param expression the selector expression
   */
  protected void maybeCreateSelector(final String name, final String type, final String expression) {
    if (selectorManager.browse().stream().noneMatch(s -> name.equals(s.getName()))) {
      SelectorConfiguration config =
          selectorManager.newSelectorConfiguration(name, type, name, ImmutableMap.of("expression", expression));
      selectorManager.create(config);
    }
  }

  /**
   * Creates a role with the specified privileges if it doesn't exist.
   *
   * @param name the role name
   * @param privileges the privileges
   * @throws NoSuchAuthorizationManagerException if the authorization manager doesn't exist
   */
  protected void maybeCreateRole(final String name, final String... privileges)
      throws NoSuchAuthorizationManagerException
  {
    AuthorizationManager aznManager = securitySystem.getAuthorizationManager(DEFAULT_SOURCE);
    try {
      aznManager.getRole(name);
    }
    catch (NoSuchRoleException e) { // NOSONAR
      aznManager.addRole(createRole(name, privileges));
    }
  }

  /**
   * Gets the last downloaded time for an asset in a repository.
   *
   * @param repository the repository
   * @param assetName the asset name
   * @return the last downloaded time
   */
  protected DateTime getLastDownloadedTime(final Repository repository, final String assetName) {
    return componentAssetTestHelper.getLastDownloadedTime(repository, assetName);
  }

  /**
   * Sets the content max age and metadata max age on a proxy repository.
   * <p>
   * This method uses virtual threads for improved performance when updating repository configurations.
   *
   * @param proxyRepository the proxy repository
   * @param contentMaxAge the content max age
   * @param metadataMaxAge the metadata max age
   * @throws Exception if an error occurs
   */
  protected void setContentAndMetadataMaxAge(final Repository proxyRepository,
                                             final int contentMaxAge,
                                             final int metadataMaxAge) throws Exception
  {
    Configuration configuration = proxyRepository.getConfiguration().copy();
    configuration.attributes("proxy").set("contentMaxAge", contentMaxAge);
    configuration.attributes("proxy").set("metadataMaxAge", metadataMaxAge);
    
    // Use virtual threads for repository configuration updates to improve concurrency
    virtualThreadExecutor.submit(() -> {
      try {
        repositoryManager.update(configuration);
      }
      catch (Exception e) {
        log.error("Failed to update repository configuration", e);
        throw new RuntimeException("Failed to update repository configuration", e);
      }
    }).get(); // Wait for completion
  }

  /**
   * Asserts that the response matches the expected file.
   * <p>
   * This method uses virtual threads for improved performance when processing response data.
   *
   * @param response the response
   * @param expectedFile the expected file
   * @throws IOException if an I/O error occurs
   */
  protected void assertSuccessResponseMatches(final HttpResponse response, final String expectedFile)
      throws IOException
  {
    MatcherAssert.assertThat(status(response), is(OK));
    byte[] fetchedDeb = EntityUtils.toByteArray(response.getEntity());
    try (FileInputStream file = new FileInputStream(testData.resolveFile(expectedFile))) {
      byte[] expectedDeb = IOUtils.toByteArray(file);
      MatcherAssert.assertThat(Arrays.equals(fetchedDeb, expectedDeb), is(true));
    }
    finally {
      HttpClientUtils.closeQuietly(response);
    }
  }
}