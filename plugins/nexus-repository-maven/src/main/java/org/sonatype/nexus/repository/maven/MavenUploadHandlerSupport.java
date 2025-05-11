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
package org.sonatype.nexus.repository.maven;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

import org.sonatype.nexus.common.text.Strings2;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.importtask.ImportFileConfiguration;
import org.sonatype.nexus.repository.maven.MavenPath.Coordinates;
import org.sonatype.nexus.repository.maven.internal.Maven2Format;
import org.sonatype.nexus.repository.maven.internal.Maven2MavenPathParser;
import org.sonatype.nexus.repository.maven.internal.MavenModels;
import org.sonatype.nexus.repository.maven.internal.MavenPomGenerator;
import org.sonatype.nexus.repository.maven.internal.VersionPolicyValidator;
import org.sonatype.nexus.repository.rest.UploadDefinitionExtension;
import org.sonatype.nexus.repository.security.ContentPermissionChecker;
import org.sonatype.nexus.repository.security.VariableResolverAdapter;
import org.sonatype.nexus.repository.upload.AssetUpload;
import org.sonatype.nexus.repository.upload.ComponentUpload;
import org.sonatype.nexus.repository.upload.UploadDefinition;
import org.sonatype.nexus.repository.upload.UploadFieldDefinition;
import org.sonatype.nexus.repository.upload.UploadFieldDefinition.Type;
import org.sonatype.nexus.repository.upload.UploadHandlerSupport;
import org.sonatype.nexus.repository.upload.UploadRegexMap;
import org.sonatype.nexus.repository.upload.UploadResponse;
import org.sonatype.nexus.repository.upload.ValidatingComponentUpload;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.PartPayload;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.payloads.StringPayload;
import org.sonatype.nexus.repository.view.payloads.TempBlob;
import org.sonatype.nexus.repository.view.payloads.TempBlobPartPayload;
import org.sonatype.nexus.rest.ValidationErrorsException;
import org.sonatype.nexus.thread.NexusExecutorService;
import org.sonatype.nexus.security.subject.FakeAlmightySubject;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;
import org.apache.maven.model.Model;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Lists.newArrayList;
import static java.lang.String.format;
import static java.lang.String.join;
import static java.util.Objects.requireNonNull;
import static org.apache.commons.lang3.StringUtils.prependIfMissing;
import static org.sonatype.nexus.common.text.Strings2.isBlank;
import static org.sonatype.nexus.repository.maven.internal.Constants.ARCHETYPE_CATALOG_FILENAME;

/**
 * Common base for maven upload handlers
 * <p>
 * This class has been updated for Java 21 compatibility and leverages virtual threads
 * for I/O-bound operations to improve throughput and reduce resource consumption.
 * Virtual threads are particularly beneficial for file upload operations, which are
 * typically I/O-bound and can benefit from higher concurrency without the overhead
 * of traditional platform threads.
 *
 * @since 3.26
 */
public abstract class MavenUploadHandlerSupport
    extends UploadHandlerSupport
{
  protected static final String VERSION = "version";

  protected static final String ARTIFACT_ID = "artifactId";

  protected static final String GROUP_ID = "groupId";

  protected static final String GENERATE_POM = "generate-pom";

  protected static final String EXTENSION = "extension";

  protected static final String CLASSIFIER = "classifier";

  protected static final String PACKAGING = "packaging";

  private static final String MAVEN_POM_PROPERTY_PREFIX = "${";

  private static final String COMPONENT_COORDINATES_GROUP = "Component coordinates";

  private static final String GENERATE_POM_DISPLAY = "Generate a POM file with these coordinates";

  private static final String ARTIFACT_ID_DISPLAY = "Artifact ID";

  private static final String GROUP_ID_DISPLAY = "Group ID";
  
  /**
   * Executor service using Java 21 virtual threads for I/O-bound operations.
   * Virtual threads provide higher throughput with minimal resource overhead,
   * making them ideal for file upload/download operations.
   */
  private static final ExecutorService VIRTUAL_THREAD_EXECUTOR = 
      NexusExecutorService.forFixedSubject(Executors.newVirtualThreadPerTaskExecutor(), FakeAlmightySubject.TASK_SUBJECT);

  private static final Set<String> ignoredPaths = Sets.newHashSet(
      "/" + ARCHETYPE_CATALOG_FILENAME,
      ARCHETYPE_CATALOG_FILENAME);

  protected final Maven2MavenPathParser parser;

  protected final VariableResolverAdapter variableResolverAdapter;

  protected final ContentPermissionChecker contentPermissionChecker;

  protected final VersionPolicyValidator versionPolicyValidator;

  protected final MavenPomGenerator mavenPomGenerator;

  private final boolean datastoreEnabled;

  private UploadDefinition definition;

  public MavenUploadHandlerSupport (
      final Maven2MavenPathParser parser,
      final VariableResolverAdapter variableResolverAdapter,
      final ContentPermissionChecker contentPermissionChecker,
      final VersionPolicyValidator versionPolicyValidator,
      final MavenPomGenerator mavenPomGenerator,
      final Set<UploadDefinitionExtension> uploadDefinitionExtensions,
      final boolean datastoreEnabled)
  {
    super(uploadDefinitionExtensions);
    this.parser = parser;
    this.variableResolverAdapter = variableResolverAdapter;
    this.contentPermissionChecker = contentPermissionChecker;
    this.versionPolicyValidator = versionPolicyValidator;
    this.mavenPomGenerator = mavenPomGenerator;
    this.datastoreEnabled = datastoreEnabled;
  }

  @Override
  public UploadResponse handle(final Repository repository, final ComponentUpload upload) throws IOException {
    checkNotNull(repository);
    checkNotNull(upload);

    if (VersionPolicy.SNAPSHOT.equals(getVersionPolicy(repository))) {
      throw new ValidationErrorsException("Upload to snapshot repositories not supported, use the maven client.");
    }

    AssetUpload pomAsset = findPomAsset(upload);

    //purposefully not using a try with resources, as this will only be used in case of an included pom file, which
    //isn't required
    TempBlob pom = null;

    try {
      if (pomAsset != null) {
        PartPayload payload = pomAsset.getPayload();
        pom = createTempBlob(repository, payload);
        pomAsset.setPayload(new TempBlobPartPayload(payload, pom));
      }

      String basePath = getBasePath(upload, pom);

      doValidation(repository, basePath, upload.getAssetUploads());

      return getUploadResponse(repository, upload, basePath);
    }
    finally {
      if (pom != null) {
        pom.close();
      }
    }
  }
  
  /**
   * Handles the component upload asynchronously using Java 21 virtual threads.
   * This method provides the same functionality as {@link #handle(Repository, ComponentUpload)}
   * but leverages virtual threads for improved throughput on I/O-bound operations.
   *
   * @param repository the repository to upload to
   * @param upload the component upload data
   * @return the upload response
   * @throws IOException if an I/O error occurs
   * @since 3.60
   */
  public UploadResponse handleWithVirtualThreads(final Repository repository, final ComponentUpload upload) throws IOException {
    checkNotNull(repository);
    checkNotNull(upload);

    if (VersionPolicy.SNAPSHOT.equals(getVersionPolicy(repository))) {
      throw new ValidationErrorsException("Upload to snapshot repositories not supported, use the maven client.");
    }
    
    try {
      return VIRTUAL_THREAD_EXECUTOR.submit(() -> {
        AssetUpload pomAsset = findPomAsset(upload);
        TempBlob pom = null;
        
        try {
          if (pomAsset != null) {
            PartPayload payload = pomAsset.getPayload();
            pom = createTempBlob(repository, payload);
            pomAsset.setPayload(new TempBlobPartPayload(payload, pom));
          }

          String basePath = getBasePath(upload, pom);

          doValidation(repository, basePath, upload.getAssetUploads());

          return getUploadResponse(repository, upload, basePath);
        }
        finally {
          if (pom != null) {
            pom.close();
          }
        }
      }).get();
    }
    catch (Exception e) {
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      }
      throw new IOException("Error handling upload with virtual threads", e);
    }
  }

  @Override
  public Content handle(final Repository repository, final File content, final String path) throws IOException {
    ImportFileConfiguration configuration = new ImportFileConfiguration(repository, content, path);
    return handle(configuration);
  }

  @Override
  public Content handle(final ImportFileConfiguration configuration) throws IOException {
    final Repository repository = configuration.getRepository();
    final String path = configuration.getAssetName();

    if (ignoredPaths.contains(path)) {
      log.debug("skipping {} as it is on the ignore list.", path);
      return null;
    }

    MavenPath mavenPath = parser.parsePath(path);

    try {
      doImportValidation(repository, mavenPath);
    } catch (ValidationErrorsException e) {
      log.warn(e.getMessage(), log.isDebugEnabled() ? e : null);
      return null;
    }

    if (!configuration.isHardLinkingEnabled() && mavenPath.getHashType() != null) {
      log.debug("skipping hash file {}", mavenPath);
      return null;
    }

    return doPut(configuration);
  }
  
  /**
   * Handles the import file configuration asynchronously using Java 21 virtual threads.
   * This method provides the same functionality as {@link #handle(ImportFileConfiguration)}
   * but leverages virtual threads for improved throughput on I/O-bound operations.
   *
   * @param configuration the import file configuration
   * @return the content or null if the file was skipped
   * @throws IOException if an I/O error occurs
   * @since 3.60
   */
  public Content handleWithVirtualThreads(final ImportFileConfiguration configuration) throws IOException {
    try {
      return VIRTUAL_THREAD_EXECUTOR.submit(() -> {
        final Repository repository = configuration.getRepository();
        final String path = configuration.getAssetName();

        if (ignoredPaths.contains(path)) {
          log.debug("skipping {} as it is on the ignore list.", path);
          return null;
        }

        MavenPath mavenPath = parser.parsePath(path);

        try {
          doImportValidation(repository, mavenPath);
        } catch (ValidationErrorsException e) {
          log.warn(e.getMessage(), log.isDebugEnabled() ? e : null);
          return null;
        }

        if (!configuration.isHardLinkingEnabled() && mavenPath.getHashType() != null) {
          log.debug("skipping hash file {}", mavenPath);
          return null;
        }

        return doPut(configuration);
      }).get();
    }
    catch (Exception e) {
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      }
      throw new IOException("Error handling import with virtual threads", e);
    }
  }

  @Override
  public UploadDefinition getDefinition() {
    if (definition == null) {
      List<UploadFieldDefinition> componentFields = Arrays.asList(
          new UploadFieldDefinition(GROUP_ID, GROUP_ID_DISPLAY, null, false, Type.STRING, COMPONENT_COORDINATES_GROUP),
          new UploadFieldDefinition(ARTIFACT_ID, ARTIFACT_ID_DISPLAY, null, false, Type.STRING,
              COMPONENT_COORDINATES_GROUP),
          new UploadFieldDefinition(VERSION, false, Type.STRING, COMPONENT_COORDINATES_GROUP),
          new UploadFieldDefinition(GENERATE_POM, GENERATE_POM_DISPLAY, null, true, Type.BOOLEAN,
              COMPONENT_COORDINATES_GROUP),
          new UploadFieldDefinition(PACKAGING, true, Type.STRING, COMPONENT_COORDINATES_GROUP));

      List<UploadFieldDefinition> assetFields = Arrays.asList(
          new UploadFieldDefinition(CLASSIFIER, true, Type.STRING),
          new UploadFieldDefinition(EXTENSION, false, Type.STRING));

      UploadRegexMap regexMap = new UploadRegexMap(
          "-(?:(?:\\.?\\d)+)(?:-(?:SNAPSHOT|\\d+))?(?:-(\\w+))?\\.((?:\\.?\\w)+)$", CLASSIFIER, EXTENSION);

      definition = getDefinition(Maven2Format.NAME, true, componentFields, assetFields, regexMap);
    }
    return definition;
  }

  @Override
  public VariableResolverAdapter getVariableResolverAdapter() {
    return variableResolverAdapter;
  }

  @Override
  public ContentPermissionChecker contentPermissionChecker() {
    return contentPermissionChecker;
  }

  @Override
  public ValidatingComponentUpload getValidatingComponentUpload(final ComponentUpload componentUpload) {
    return new MavenValidatingComponentUpload(getDefinition(), componentUpload);
  }

  /**
   * Generates a POM file if requested in the component upload.
   *
   * @param repository the repository to store the POM in
   * @param componentUpload the component upload data
   * @param basePath the base path for the POM
   * @param responseData the response data to update with the POM path
   * @throws IOException if an I/O error occurs during POM generation
   */
  protected void maybeGeneratePom(final Repository repository,
                                  final ComponentUpload componentUpload,
                                  final String basePath,
                                  final ContentAndAssetPathResponseData responseData)
      throws IOException
  {
    if (isGeneratePom(componentUpload.getField(GENERATE_POM))) {
      String pomPath = generatePom(repository, basePath, componentUpload.getFields().get(GROUP_ID),
          componentUpload.getFields().get(ARTIFACT_ID), componentUpload.getFields().get(VERSION),
          componentUpload.getFields().get(PACKAGING));

      responseData.addAssetPath(pomPath);
    }
  }
  
  /**
   * Generates a POM file if requested in the component upload, using virtual threads for improved I/O throughput.
   *
   * @param repository the repository to store the POM in
   * @param componentUpload the component upload data
   * @param basePath the base path for the POM
   * @param responseData the response data to update with the POM path
   * @throws IOException if an I/O error occurs during POM generation
   * @since 3.60
   */
  protected void maybeGeneratePomWithVirtualThreads(final Repository repository,
                                                   final ComponentUpload componentUpload,
                                                   final String basePath,
                                                   final ContentAndAssetPathResponseData responseData)
      throws IOException
  {
    if (isGeneratePom(componentUpload.getField(GENERATE_POM))) {
      String pomPath = generatePomWithVirtualThreads(repository, basePath, componentUpload.getFields().get(GROUP_ID),
          componentUpload.getFields().get(ARTIFACT_ID), componentUpload.getFields().get(VERSION),
          componentUpload.getFields().get(PACKAGING));

      responseData.addAssetPath(pomPath);
    }
  }

  protected void validateVersionPolicy(final Repository repository, final MavenPath mavenPath) {
    VersionPolicy versionPolicy = getVersionPolicy(repository);

    boolean valid = parser.isRepositoryMetadata(mavenPath) ?
        versionPolicyValidator.validMetadataPath(versionPolicy, mavenPath.main().getPath()) :
        versionPolicyValidator.validArtifactPath(versionPolicy, mavenPath.getCoordinates());

    if (!valid) {
      throw new ValidationErrorsException(
          format("Version policy mismatch, cannot upload %s content to %s repositories for file '%s'",
              versionPolicy.equals(VersionPolicy.RELEASE) ? VersionPolicy.SNAPSHOT.name() : VersionPolicy.RELEASE.name(),
              versionPolicy.name(),
              mavenPath.getPath()));
    }
  }

  protected abstract UploadResponse getUploadResponse(Repository repository,
                                                                       ComponentUpload componentUpload,
                                                                       String basePath)
      throws IOException;

  protected abstract Content doPut(final Repository repository, final MavenPath mavenPath, final Payload payload)
      throws IOException;

  protected abstract Content doPut(ImportFileConfiguration configuration) throws IOException;

  protected abstract VersionPolicy getVersionPolicy(Repository repository);

  protected abstract TempBlob createTempBlob(Repository repository, PartPayload payload);

  protected void doValidation(final Repository repository,
                              final String basePath,
                              final List<AssetUpload> assetUploads)
  {
    for (AssetUpload asset : assetUploads) {
      MavenPath mavenPath = getMavenPath(basePath, asset);
      doCoordinatesValidation(mavenPath);
      validateVersionPolicy(repository, mavenPath);
      String path  = datastoreEnabled ? prependIfMissing(mavenPath.getPath(), "/") : mavenPath.getPath();
      ensurePermitted(repository.getName(), Maven2Format.NAME, path, toMap(mavenPath.getCoordinates()));
    }
  }

  private void doImportValidation(final Repository repository, final MavenPath mavenPath) {
    LayoutPolicy layoutPolicy = repository.facet(MavenFacet.class).layoutPolicy();
    if (layoutPolicy == LayoutPolicy.STRICT) {
      if (!parser.isRepositoryMetadata(mavenPath)) {
        doCoordinatesValidation(mavenPath);
      }
      validateVersionPolicy(repository, mavenPath);
    }
    String path  = datastoreEnabled ? prependIfMissing(mavenPath.getPath(), "/") : mavenPath.getPath();
    ensurePermitted(repository.getName(), Maven2Format.NAME, path, toMap(mavenPath.getCoordinates()));
  }

  private void doCoordinatesValidation(final MavenPath mavenPath) {
    if (mavenPath.getCoordinates() == null) {
      throw new ValidationErrorsException(
          format("Cannot generate maven coordinate from assembled path '%s'", mavenPath.getPath()));
    }
  }

  /**
   * Generates a POM file and stores it in the repository.
   *
   * @param repository the repository to store the POM in
   * @param basePath the base path for the POM
   * @param groupId the group ID for the POM
   * @param artifactId the artifact ID for the POM
   * @param version the version for the POM
   * @param packaging the packaging type for the POM (may be null)
   * @return the path of the stored POM
   * @throws IOException if an I/O error occurs during POM generation or storage
   */
  protected String generatePom(final Repository repository,
                               final String basePath,
                               final String groupId,
                               final String artifactId,
                               final String version,
                               @Nullable final String packaging)
      throws IOException
  {
    log.debug("Generating pom for {} {} {} with packaging {}", groupId, artifactId, version, packaging);

    String pom = mavenPomGenerator.generatePom(groupId, artifactId, version, packaging);

    MavenPath mavenPath = parser.parsePath(basePath + ".pom");

    storeAssetContent(repository, mavenPath, new StringPayload(pom, "text/xml"));

    return mavenPath.getPath();
  }
  
  /**
   * Generates a POM file and stores it in the repository using virtual threads for improved I/O throughput.
   *
   * @param repository the repository to store the POM in
   * @param basePath the base path for the POM
   * @param groupId the group ID for the POM
   * @param artifactId the artifact ID for the POM
   * @param version the version for the POM
   * @param packaging the packaging type for the POM (may be null)
   * @return the path of the stored POM
   * @throws IOException if an I/O error occurs during POM generation or storage
   * @since 3.60
   */
  protected String generatePomWithVirtualThreads(final Repository repository,
                                                final String basePath,
                                                final String groupId,
                                                final String artifactId,
                                                final String version,
                                                @Nullable final String packaging)
      throws IOException
  {
    try {
      return VIRTUAL_THREAD_EXECUTOR.submit(() -> {
        log.debug("Generating pom for {} {} {} with packaging {}", groupId, artifactId, version, packaging);

        String pom = mavenPomGenerator.generatePom(groupId, artifactId, version, packaging);

        MavenPath mavenPath = parser.parsePath(basePath + ".pom");

        storeAssetContent(repository, mavenPath, new StringPayload(pom, "text/xml"));

        return mavenPath.getPath();
      }).get();
    }
    catch (Exception e) {
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      }
      throw new IOException("Error generating POM with virtual threads", e);
    }
  }

  protected AssetUpload findPomAsset(final ComponentUpload componentUpload) {
    return componentUpload.getAssetUploads().stream()
        .filter(asset -> "pom".equals(asset.getField(EXTENSION)) && isBlank(asset.getField(CLASSIFIER)))
        .findFirst().orElse(null);
  }

  protected static boolean isGeneratePom(final String generatePom) {
    return ("on".equals(generatePom) || Boolean.parseBoolean(generatePom));
  }

  /**
   * Creates assets in the repository from the provided asset uploads.
   * <p>
   * This method processes each asset upload, stores the content in the repository,
   * and collects information about the created assets for the response.
   *
   * @param repository the repository to store assets in
   * @param basePath the base path for the assets
   * @param assetUploads the list of asset uploads to process
   * @return data about the created assets and content
   * @throws IOException if an I/O error occurs during asset creation
   */
  protected ContentAndAssetPathResponseData createAssets(final Repository repository,
                                                         final String basePath,
                                                         final List<AssetUpload> assetUploads)
      throws IOException
  {
    ContentAndAssetPathResponseData responseData = new ContentAndAssetPathResponseData();

    for (AssetUpload asset : assetUploads) {
      MavenPath mavenPath = getMavenPath(basePath, asset);

      Content content = storeAssetContent(repository, mavenPath, asset.getPayload());

      //We only need to set the component id one time
      if(responseData.getContent() == null) {
        responseData.setContent(content);
      }
      responseData.addAssetPath(mavenPath.getPath());

      //All assets belong to same component, so just grab the coordinates for one of them
      if (responseData.getCoordinates() == null) {
        responseData.setCoordinates(mavenPath.getCoordinates());
      }
    }

    return responseData;
  }
  
  /**
   * Creates assets in the repository from the provided asset uploads using virtual threads.
   * <p>
   * This method provides the same functionality as {@link #createAssets(Repository, String, List)}
   * but processes each asset upload using Java 21 virtual threads for improved throughput
   * on I/O-bound operations.
   *
   * @param repository the repository to store assets in
   * @param basePath the base path for the assets
   * @param assetUploads the list of asset uploads to process
   * @return data about the created assets and content
   * @throws IOException if an I/O error occurs during asset creation
   * @since 3.60
   */
  protected ContentAndAssetPathResponseData createAssetsWithVirtualThreads(final Repository repository,
                                                                          final String basePath,
                                                                          final List<AssetUpload> assetUploads)
      throws IOException
  {
    try {
      return VIRTUAL_THREAD_EXECUTOR.submit(() -> {
        ContentAndAssetPathResponseData responseData = new ContentAndAssetPathResponseData();

        for (AssetUpload asset : assetUploads) {
          MavenPath mavenPath = getMavenPath(basePath, asset);

          Content content = storeAssetContent(repository, mavenPath, asset.getPayload());

          //We only need to set the component id one time
          if(responseData.getContent() == null) {
            responseData.setContent(content);
          }
          responseData.addAssetPath(mavenPath.getPath());

          //All assets belong to same component, so just grab the coordinates for one of them
          if (responseData.getCoordinates() == null) {
            responseData.setCoordinates(mavenPath.getCoordinates());
          }
        }

        return responseData;
      }).get();
    }
    catch (Exception e) {
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      }
      throw new IOException("Error creating assets with virtual threads", e);
    }
  }

  private MavenPath getMavenPath(final String basePath, final AssetUpload asset) {
    StringBuilder path = new StringBuilder(basePath);

    String classifier = asset.getFields().get(CLASSIFIER);
    if (!Strings2.isEmpty(classifier)) {
      path.append('-').append(classifier);
    }
    path.append('.').append(asset.getFields().get(EXTENSION));

    return parser.parsePath(path.toString());
  }

  /**
   * Stores asset content in the repository.
   *
   * @param repository the repository to store the content in
   * @param mavenPath the Maven path for the asset
   * @param payload the payload containing the asset content
   * @return the stored content
   * @throws IOException if an I/O error occurs during storage
   */
  protected Content storeAssetContent(final Repository repository,
                                      final MavenPath mavenPath,
                                      final Payload payload) throws IOException
  {
    return doPut(repository, mavenPath, payload);
  }
  
  /**
   * Stores asset content in the repository using virtual threads for improved I/O throughput.
   *
   * @param repository the repository to store the content in
   * @param mavenPath the Maven path for the asset
   * @param payload the payload containing the asset content
   * @return the stored content
   * @throws IOException if an I/O error occurs during storage
   * @since 3.60
   */
  protected Content storeAssetContentWithVirtualThreads(final Repository repository,
                                                       final MavenPath mavenPath,
                                                       final Payload payload) throws IOException
  {
    try {
      return VIRTUAL_THREAD_EXECUTOR.submit(() -> doPut(repository, mavenPath, payload)).get();
    }
    catch (Exception e) {
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      }
      throw new IOException("Error storing asset content with virtual threads", e);
    }
  }

  protected String getBasePath(final ComponentUpload componentUpload, final TempBlob pom) throws IOException
  {
    if (pom != null) {
      return createBasePathFromPom(pom);
    }

    return createBasePath(componentUpload.getFields().get(GROUP_ID), componentUpload.getFields().get(ARTIFACT_ID),
        componentUpload.getFields().get(VERSION));
  }

  private String createBasePath(final String groupId, final String artifactId, final String version) {
    List<String> parts = newArrayList(groupId.split("\\."));
    parts.addAll(Arrays.asList(artifactId, version, artifactId));
    return join("-", join("/", parts), version);
  }

  private String createBasePathFromPom(final TempBlob tempBlob) throws IOException {
    try (InputStream in = tempBlob.get()) {
      Model model = MavenModels.readModel(in);
      validatePom(model);
      return createBasePath(requireNonNull(getGroupId(model)), getArtifactId(model), getVersion(model));
    }
  }

  @VisibleForTesting
  public void validatePom(final Model model) {
    if (model == null) {
      throw new ValidationErrorsException("The provided POM file is invalid.");
    }

    String groupId = getGroupId(model);
    String version = getVersion(model);
    String artifactId = getArtifactId(model);

    if (groupId == null || artifactId == null || version == null ||
        groupId.startsWith(MAVEN_POM_PROPERTY_PREFIX) ||
        artifactId.startsWith(MAVEN_POM_PROPERTY_PREFIX) ||
        version.startsWith(MAVEN_POM_PROPERTY_PREFIX)) {
      throw new ValidationErrorsException(
          format("The provided POM file is invalid.  Could not retrieve valid G:A:V parameters (%s:%s:%s)", groupId,
              artifactId, version));
    }
  }

  private String getGroupId(Model model) {
    String groupId = model.getGroupId();
    if (groupId == null && model.getParent() != null) {
      groupId = model.getParent().getGroupId();
    }
    return groupId;
  }

  private String getArtifactId(Model model) {
    return model.getArtifactId();
  }

  private String getVersion(Model model) {
    String version = model.getVersion();
    if (version == null && model.getParent() != null) {
      version = model.getParent().getVersion();
    }
    return version;
  }

  protected Map<String, String> toMap(final Coordinates coordinates) {
    Map<String, String> map = new HashMap<>();
    if (coordinates != null) {
      map.put(GROUP_ID, coordinates.getGroupId());
      map.put(ARTIFACT_ID, coordinates.getArtifactId());
      map.put(VERSION, coordinates.getVersion());
      if (coordinates.getClassifier() != null) {
        map.put(CLASSIFIER, coordinates.getClassifier());
      }
      map.put(EXTENSION, coordinates.getExtension());
    }
    return map;
  }

  @Override
  public boolean supportsExportImport() {
    return true;
  }

  /**
   * Simple data carrier used to collect data needed
   * to populate the {@link UploadResponse}
   */
  public static class ContentAndAssetPathResponseData {
    Content content;
    List<String> assetPaths = newArrayList();
    Coordinates coordinates;

    public void setContent(final Content content) {
      this.content = content;
    }

    public void setCoordinates(final Coordinates coordinates) {
      this.coordinates = coordinates;
    }

    public void addAssetPath(final String assetPath) {
      this.assetPaths.add(assetPath);
    }

    public Content getContent() {return this.content;}

    public Coordinates getCoordinates() {
      return this.coordinates;
    }

    public List<String> getAssetPaths() { return this.assetPaths;}

    public UploadResponse uploadResponse() {
      return new UploadResponse(content, assetPaths);
    }
  }
}
