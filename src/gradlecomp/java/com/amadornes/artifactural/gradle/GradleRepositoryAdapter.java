package com.amadornes.artifactural.gradle;

import com.amadornes.artifactural.api.artifact.Artifact;
import com.amadornes.artifactural.api.artifact.ArtifactIdentifier;
import com.amadornes.artifactural.api.artifact.MissingArtifactException;
import com.amadornes.artifactural.api.repository.Repository;
import com.amadornes.artifactural.base.artifact.SimpleArtifactIdentifier;
import com.amadornes.artifactural.base.cache.LocatedArtifactCache;

import org.gradle.api.artifacts.ComponentMetadataSupplierDetails;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.internal.artifacts.BaseRepositoryFactory;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ConfiguredModuleComponentRepository;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ModuleComponentRepositoryAccess;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvableArtifact;
import org.gradle.api.internal.artifacts.repositories.AbstractArtifactRepository;
import org.gradle.api.internal.artifacts.repositories.DefaultMavenLocalArtifactRepository;
import org.gradle.api.internal.artifacts.repositories.ResolutionAwareRepository;
import org.gradle.api.internal.artifacts.repositories.resolver.MavenResolver;
import org.gradle.api.internal.artifacts.repositories.resolver.MetadataFetchingCost;
import org.gradle.api.internal.attributes.AttributesSchemaInternal;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.api.internal.component.ArtifactType;
import org.gradle.internal.action.InstantiatingAction;
import org.gradle.internal.component.external.model.ComponentVariant;
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata;
import org.gradle.internal.component.external.model.ModuleComponentResolveMetadata;
import org.gradle.internal.component.external.model.ModuleDependencyMetadata;
import org.gradle.internal.component.external.model.MutableModuleComponentResolveMetadata;
import org.gradle.internal.component.model.ComponentArtifactMetadata;
import org.gradle.internal.component.model.ComponentOverrideMetadata;
import org.gradle.internal.component.model.ComponentResolveMetadata;
import org.gradle.internal.component.model.ConfigurationMetadata;
import org.gradle.internal.component.model.ModuleSource;
import org.gradle.internal.hash.HashValue;
import org.gradle.internal.impldep.com.google.common.base.Optional;
import org.gradle.internal.impldep.com.google.common.collect.ImmutableList;
import org.gradle.internal.nativeintegration.filesystem.FileSystem;
import org.gradle.internal.nativeintegration.services.FileSystems;
import org.gradle.internal.resolve.result.BuildableArtifactResolveResult;
import org.gradle.internal.resolve.result.BuildableArtifactSetResolveResult;
import org.gradle.internal.resolve.result.BuildableComponentArtifactsResolveResult;
import org.gradle.internal.resolve.result.BuildableModuleComponentMetaDataResolveResult;
import org.gradle.internal.resolve.result.BuildableModuleVersionListingResolveResult;
import org.gradle.internal.resource.ExternalResourceName;
import org.gradle.internal.resource.ExternalResourceRepository;
import org.gradle.internal.resource.LocalBinaryResource;
import org.gradle.internal.resource.local.FileResourceRepository;
import org.gradle.internal.resource.local.LocalFileStandInExternalResource;
import org.gradle.internal.resource.local.LocallyAvailableExternalResource;
import org.gradle.internal.resource.metadata.ExternalResourceMetaData;
import org.gradle.internal.resource.transfer.DefaultCacheAwareExternalResourceAccessor;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GradleRepositoryAdapter extends AbstractArtifactRepository implements ResolutionAwareRepository {

    private static final Pattern URL_PATTERN = Pattern.compile(
            "^(?<group>\\S+(?:/\\S+)*)/(?<name>\\S+)/(?<version>\\S+)/" +
                    "\\2-\\3(?:-(?<classifier>[^.\\s]+))?\\.(?<extension>\\S+)$");

    public static GradleRepositoryAdapter add(RepositoryHandler handler, String name, File local, Repository repository) {
        BaseRepositoryFactory factory = ReflectionUtils.get(handler, "repositoryFactory"); // We reflect here and create it manually so it DOESN'T get attached.
        DefaultMavenLocalArtifactRepository maven = (DefaultMavenLocalArtifactRepository)factory.createMavenLocalRepository(); // We use maven local because it bypasses the caching and coping to .m2
        maven.setUrl(local);
        maven.setName(name);

        GradleRepositoryAdapter repo = new GradleRepositoryAdapter(repository, maven);
        repo.setName(name);
        handler.add(repo);
        return repo;
    }

    private final Repository repository;
    private final DefaultMavenLocalArtifactRepository local;

    private GradleRepositoryAdapter(Repository repository, DefaultMavenLocalArtifactRepository local) {
        this.repository = repository;
        this.local = local;
    }

    @Override
    public String getDisplayName() {
        return local.getDisplayName();
    }

    @Override
    public ConfiguredModuleComponentRepository createResolver() {
        MavenResolver resolver = (MavenResolver)local.createResolver();

        GeneratingFileResourceRepository repo = new GeneratingFileResourceRepository();
        ReflectionUtils.alter(resolver, "repository", prev -> repo);  // ExternalResourceResolver.repository
        //ReflectionUtils.alter(resolver, "metadataSources", ); //ExternalResourceResolver.metadataSources We need to fix these from returning 'missing'
        // MavenResolver -> MavenMetadataLoader -> FileCacheAwareExternalResourceAccessor -> DefaultCacheAwareExternalResourceAccessor
        DefaultCacheAwareExternalResourceAccessor accessor = ReflectionUtils.get(resolver, "mavenMetaDataLoader.cacheAwareExternalResourceAccessor.delegate");
        ReflectionUtils.alter(accessor, "delegate", prev -> repo); // DefaultCacheAwareExternalResourceAccessor.delegate
        ReflectionUtils.alter(accessor, "fileResourceRepository", prev -> repo); // DefaultCacheAwareExternalResourceAccessor.fileResourceRepository
        //ReflectionUtils.alter(resolver, "localAccess", AccessWrapper<MavenLocalRepositoryAccess>::new);

        //return resolver;
        //return new Resolver(resolver);
        return new ConfiguredModuleComponentRepository() {
            private final ModuleComponentRepositoryAccess local = wrap(resolver.getLocalAccess());
            private final ModuleComponentRepositoryAccess remote = wrap(resolver.getRemoteAccess());
            @Override public String getId() { return resolver.getId(); }
            @Override public String getName() { return resolver.getName(); }
            @Override public ModuleComponentRepositoryAccess getLocalAccess() { return local; }
            @Override public ModuleComponentRepositoryAccess getRemoteAccess() { return remote; }
            @Override public Map<ComponentArtifactIdentifier, ResolvableArtifact> getArtifactCache() { return resolver.getArtifactCache(); }
            @Override public InstantiatingAction<ComponentMetadataSupplierDetails> getComponentMetadataSupplier() { return resolver.getComponentMetadataSupplier(); }
            @Override public boolean isDynamicResolveMode() { return resolver.isDynamicResolveMode(); }
            @Override public boolean isLocal() { return resolver.isLocal(); }

            private ModuleComponentRepositoryAccess wrap(ModuleComponentRepositoryAccess delegate) {
                return new ModuleComponentRepositoryAccess() {
                    @Override
                    public void resolveComponentMetaData(ModuleComponentIdentifier moduleComponentIdentifier, ComponentOverrideMetadata requestMetaData, BuildableModuleComponentMetaDataResolveResult result) {
                        delegate.resolveComponentMetaData(moduleComponentIdentifier, requestMetaData, result);
                        if (result.getState() == BuildableModuleComponentMetaDataResolveResult.State.Resolved) {
                            ModuleComponentResolveMetadata meta = result.getMetaData();
                            if (meta.isMissing()) {
                                MutableModuleComponentResolveMetadata mutable = meta.asMutable();
                                mutable.setChanging(true);
                                mutable.setMissing(false);
                                result.resolved(mutable.asImmutable());
                            }
                        }
                    }

                    @Override
                    public void listModuleVersions(ModuleDependencyMetadata dependency, BuildableModuleVersionListingResolveResult result) {
                        delegate.listModuleVersions(dependency, result);
                    }

                    @Override
                    public void resolveArtifacts(ComponentResolveMetadata component, BuildableComponentArtifactsResolveResult result) {
                        delegate.resolveArtifacts(component, result);
                    }

                    @Override
                    public void resolveArtifactsWithType(ComponentResolveMetadata component, ArtifactType artifactType, BuildableArtifactSetResolveResult result) {
                        delegate.resolveArtifactsWithType(component, artifactType, result);
                    }

                    @Override
                    public void resolveArtifact(ComponentArtifactMetadata artifact, ModuleSource moduleSource, BuildableArtifactResolveResult result) {
                        delegate.resolveArtifact(artifact, moduleSource, result);
                    }

                    @Override
                    public MetadataFetchingCost estimateMetadataFetchingCost(ModuleComponentIdentifier moduleComponentIdentifier) {
                        return delegate.estimateMetadataFetchingCost(moduleComponentIdentifier);
                    }
                };
            }
        };
    }

    private static String cleanRoot(URI uri) {
        String ret = uri.normalize().getPath().replace('\\', '/');
        if (!ret.endsWith("/")) ret += '/';
        return ret;
    }

    private class GeneratingFileResourceRepository implements FileResourceRepository {
        private final FileSystem fileSystem = FileSystems.getDefault();
        private final String root = cleanRoot(GradleRepositoryAdapter.this.local.getUrl());
        private final LocatedArtifactCache cache = new LocatedArtifactCache(new File(root));

        @Override
        public ExternalResourceRepository withProgressLogging() {
            return this;
        }

        @Override
        public LocalBinaryResource localResource(File file) {
            System.out.println("localResource: " + file);
            return null;
        }

        @Override
        public LocallyAvailableExternalResource resource(File file) {
            System.out.println("resource(File): " + file);
            return findArtifact(file.getAbsolutePath().replace('\\', '/'));
        }

        @Override
        public LocallyAvailableExternalResource resource(ExternalResourceName location) {
            return resource(location, false);
        }

        @Override
        public LocallyAvailableExternalResource resource(ExternalResourceName location, boolean revalidate) {
            System.out.println("resource(ExternalResourceName,boolean): " + location + ", " + revalidate);
            return findArtifact(location.getUri().getPath().replace('\\', '/'));
        }

        @Override
        public LocallyAvailableExternalResource resource(File file, URI originUri, ExternalResourceMetaData originMetadata) {
            System.out.println("resource(File,URI,ExternalResourceMetaData): " + file + ", " + originUri + ", " + originMetadata);
            return findArtifact(file.getAbsolutePath().replace('\\', '/'));
        }

        private LocallyAvailableExternalResource findArtifact(String path) {
            if (path.startsWith(root)) {
                String relative = path.substring(root.length());
                System.out.println("  Relative: " + relative);
                Matcher matcher = URL_PATTERN.matcher(relative);
                if (!matcher.matches()) {
                    System.out.println("  Matcher Failed: " + relative);
                } else {
                    ArtifactIdentifier identifier = new SimpleArtifactIdentifier(
                        matcher.group("group").replace('/', '.'),
                        matcher.group("name"),
                        matcher.group("version"),
                        matcher.group("classifier"),
                        matcher.group("extension"));
                    Artifact artifact = repository.getArtifact(identifier);
                    return wrap(artifact, identifier);
                }
            } else {
                System.out.println("Unknown root: " + path);
            }
            return new LocalFileStandInExternalResource(new File(path), fileSystem);
        }

        private LocallyAvailableExternalResource wrap(Artifact artifact, ArtifactIdentifier id) {
            if (!artifact.isPresent())
                return new LocalFileStandInExternalResource(cache.getPath(artifact), fileSystem);
            Artifact.Cached cached = artifact.optionallyCache(cache);
            try {
                return new LocalFileStandInExternalResource(cached.asFile(), fileSystem);
            } catch (MissingArtifactException | IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
