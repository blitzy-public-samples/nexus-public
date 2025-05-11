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
package org.sonatype.nexus.content.maven.upgrade;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

// No additional imports needed for Java 21 String Templates as STR is automatically imported

import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.maven.ContentDisposition;
import org.sonatype.nexus.repository.maven.internal.MavenDefaultRepositoriesContributor;
import org.sonatype.nexus.repository.maven.internal.recipes.Maven2GroupRecipe;
import org.sonatype.nexus.upgrade.datastore.DatabaseMigrationStep;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Upgrade to update contentDisposition of default maven repositories.
 * 
 * <p>This class has been updated for Java 21 compatibility with the following enhancements:</p>
 * <ul>
 *   <li>Pattern Matching for instanceof to simplify type checking and casting</li>
 *   <li>String Templates for improved error message formatting</li>
 *   <li>Updated Jackson usage for compatibility with Jackson 2.16.1</li>
 * </ul>
 * 
 * @since 3.41
 */
@Named
@Singleton
public class MavenDefaultReposUpgrade_1_17
    implements DatabaseMigrationStep
{
  // Using String Templates for improved SQL readability
  private static final String FIND_ATTRIBUTES_BY_NAME = 
      STR."SELECT attributes from repository WHERE name = ?;";

  private static final String UPDATE_ATTRIBUTES_BY_NAME = 
      STR."UPDATE repository SET attributes = ? WHERE name = ?;";

  private final MavenDefaultRepositoriesContributor defaultRepositoriesContributor;

  private final ObjectMapper mapper;

  /**
   * Constructor with dependency injection for the default repositories contributor.
   * 
   * @param defaultRepositoriesContributor The contributor for default Maven repositories
   */
  @Inject
  public MavenDefaultReposUpgrade_1_17(final MavenDefaultRepositoriesContributor defaultRepositoriesContributor) {
    this.defaultRepositoriesContributor = defaultRepositoriesContributor;
    // Configure ObjectMapper with Java 21 compatible settings
    this.mapper = new ObjectMapper();
  }

  /**
   * Returns the version of this migration step.
   * 
   * @return The version as an Optional String
   */
  @Override
  public Optional<String> version() {
    return Optional.of("1.17");
  }

  /**
   * Performs the database migration to update contentDisposition for default Maven repositories.
   * 
   * @param connection The database connection to use for the migration
   * @throws Exception If any error occurs during migration
   */
  @Override
  public void migrate(final Connection connection) throws Exception {
    this.defaultRepositoriesContributor
        .getRepositoryConfigurations()
        .stream()
        .filter(configuration -> !Maven2GroupRecipe.NAME.equals(configuration.getRecipeName()))
        .map(Configuration::getRepositoryName)
        .forEach(name -> this.update(connection, name));
  }

  /**
   * Updates the contentDisposition attribute for a specific repository.
   * 
   * @param connection The database connection
   * @param repositoryName The name of the repository to update
   */
  private void update(final Connection connection, final String repositoryName) {
    try {
      ObjectNode attributes = getCurrentAttributes(connection, repositoryName);

      if (attributes.get("maven") instanceof ObjectNode mavenAttributes) {
        JsonNode current = mavenAttributes.get("contentDisposition");
        // should put the value only if it is not present
        if (current == null) {
          mavenAttributes.put("contentDisposition", ContentDisposition.INLINE.name());
          attributes.set("maven", mavenAttributes);
        }
      }
      else {
        ObjectNode mavenNode = mapper.createObjectNode();
        mavenNode.put("contentDisposition", ContentDisposition.INLINE.name());
        attributes.set("maven", mavenNode);
      }

      updateAttributes(connection, repositoryName, mapper.writeValueAsBytes(attributes));
    }
    catch (SQLException | JsonProcessingException e) {
      throw new RuntimeException(STR."Failed to update contentDisposition for repository: \{repositoryName}", e);
    }
  }

  /**
   * Retrieves the current attributes for a repository from the database.
   * 
   * @param connection The database connection
   * @param repositoryName The name of the repository
   * @return The repository attributes as a Jackson ObjectNode
   * @throws SQLException If a database error occurs
   * @throws JsonProcessingException If the attributes cannot be parsed as JSON
   */
  private ObjectNode getCurrentAttributes(Connection connection, String repositoryName)
      throws SQLException, JsonProcessingException
  {
    try (PreparedStatement ps = connection.prepareStatement(FIND_ATTRIBUTES_BY_NAME)) {
      ps.setString(1, repositoryName);
      ResultSet rs = ps.executeQuery();
      if (rs.next()) {
        String attributes = rs.getString(1);
        JsonNode node = mapper.readTree(attributes);
        // Using Pattern Matching for instanceof check (Java 21 feature)
        if (node instanceof ObjectNode objectNode) {
          return objectNode;
        }
        throw new JsonProcessingException(STR."Repository attributes for \{repositoryName} are not an object") {};
      }
      else {
        return mapper.createObjectNode();
      }
    }
  }

  /**
   * Updates the attributes for a repository in the database.
   * 
   * @param connection The database connection
   * @param repositoryName The name of the repository
   * @param attributes The serialized attributes to update
   * @throws SQLException If a database error occurs
   */
  private void updateAttributes(Connection connection, String repositoryName, byte[] attributes)
      throws SQLException
  {
    try (PreparedStatement ps = connection.prepareStatement(UPDATE_ATTRIBUTES_BY_NAME)) {
      if (isH2(connection)) {
        ps.setBytes(1, attributes);
      }
      else {
        ps.setString(1, new String(attributes, UTF_8));
      }
      ps.setString(2, repositoryName);

      int updated = ps.executeUpdate();
      if (updated != 1) {
        throw new SQLException(STR."Failed to update attributes for repository: \{repositoryName}");
      }
    }
  }
  
  /**
   * Checks if the database connection is to an H2 database.
   * 
   * @param connection The database connection to check
   * @return true if the connection is to an H2 database, false otherwise
   * @throws SQLException If a database error occurs
   */
  private boolean isH2(Connection connection) throws SQLException {
    return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("h2");
  }
}