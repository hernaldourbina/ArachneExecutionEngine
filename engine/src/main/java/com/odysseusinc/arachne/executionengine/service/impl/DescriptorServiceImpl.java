package com.odysseusinc.arachne.executionengine.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.odysseusinc.arachne.executionengine.config.runtimeservice.RIsolatedRuntimeProperties;
import com.odysseusinc.arachne.executionengine.model.descriptor.DefaultDescriptor;
import com.odysseusinc.arachne.executionengine.model.descriptor.Descriptor;
import com.odysseusinc.arachne.executionengine.model.descriptor.DescriptorBundle;
import com.odysseusinc.arachne.executionengine.model.descriptor.ExecutionRuntime;
import com.odysseusinc.arachne.executionengine.model.descriptor.ParseStrategy;
import com.odysseusinc.arachne.executionengine.model.descriptor.r.rEnv.REnvParseStrategy;
import com.odysseusinc.arachne.executionengine.service.DescriptorService;
import com.odysseusinc.arachne.executionengine.util.ZipInputSubStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DescriptorServiceImpl implements DescriptorService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DescriptorServiceImpl.class);
    private static final List<ParseStrategy> STRATEGIES = Arrays.asList(
            new REnvParseStrategy()
    );
    private static final String DESCRIPTOR_PREFIX = "descriptor";

    private final ObjectMapper mapper = new ObjectMapper();

    private final DescriptorBundle defaultDescriptorBundle;
    private final Optional<Path> archiveFolder;
    private final boolean dependencyMatching;

    @Autowired
    public DescriptorServiceImpl(RIsolatedRuntimeProperties rIsolatedRuntimeProps) {
        this(
                new DescriptorBundle(rIsolatedRuntimeProps.getArchive(), new DefaultDescriptor()),
                Optional.ofNullable(rIsolatedRuntimeProps.getArchiveFolder()).map(name -> new File(name).toPath()),
                rIsolatedRuntimeProps.isApplyRuntimeDependenciesComparisonLogic()
        );
    }

    public DescriptorServiceImpl(DescriptorBundle defaultDescriptorBundle, Optional<Path> archiveFolder, boolean dependencyMatching) {
        this.defaultDescriptorBundle = defaultDescriptorBundle;
        this.archiveFolder = archiveFolder;
        this.dependencyMatching = dependencyMatching;
    }

    @Override
    public Optional<List<Descriptor>> getDescriptors() {
        return archiveFolder.map(dir -> {
            try (Stream<Path> files = Files.list(dir)) {
                return files.map(Path::toFile).filter(file ->
                        file.getName().startsWith(DESCRIPTOR_PREFIX) && file.isFile()
                ).map(file -> {
                    try (InputStream is = new FileInputStream(file)) {
                        return mapper.readValue(is, Descriptor.class);
                    } catch (IOException e) {
                        throw new RuntimeException("Error getting descriptor from file: " + file.getName(), e);
                    }
                }).collect(Collectors.toList());
            } catch (IOException e) {
                LOGGER.info("Error traversing [{}]: {}", dir, e.getMessage());
                throw new RuntimeException("Error traversing [" + dir + "]: " + e.getMessage());
            }
        });
    }

    @Override
    public List<Descriptor> getDescriptors(String id) {
        return getDescriptors().map(Collection::stream).orElseGet(Stream::of)
                .filter(descriptor -> Objects.equals(descriptor.getId(), id))
                .collect(Collectors.toList());
    }

    @Override
    public DescriptorBundle getDescriptorBundle(File dir, Long analysisId, String requestedDescriptorId) {
        return getDescriptors().map(available ->
                Optional.ofNullable(StringUtils.defaultIfEmpty(requestedDescriptorId, null)).map(id ->
                        findRequestedDescriptor(analysisId, available, id)
                ).orElseGet(() -> {
                    if (dependencyMatching) {
                        LOGGER.info("For analysis [{}] no descriptor is requested explicitly, fall back to dependency matching among {} present descriptors", analysisId, available.size());
                        return findMatchingDescriptor(dir, analysisId, available);
                    } else {
                        LOGGER.info("For analysis [{}] no descriptor is requested explicitly, dependency matching is disabled. Using default", analysisId);
                        return defaultDescriptorBundle;
                    }
                })
        ).orElseGet(() -> {
            LOGGER.info("For analysis [{}] using default descriptor (no descriptors configured). Requested [{}]", analysisId, requestedDescriptorId);
            return defaultDescriptorBundle;
        });
    }

    private DescriptorBundle findRequestedDescriptor(Long analysisId, List<Descriptor> available, String id) {
        return available.stream().filter(descriptor ->
                descriptor.getId().equals(id)
        ).reduce((a, b) -> {
            LOGGER.error("For analysis [{}], multiple descriptors found for requested id [{}]: [{}] and [{}]",
                    analysisId, id, a.getBundleName(), b.getBundleName());
            throw new RuntimeException("For analysis [" + analysisId + "], multiple descriptors found for requested id [" + id + "]");
        }).map(descriptor -> {
            LOGGER.info("For analysis [{}]], using requested descriptor [{}] found under [{}]", analysisId, id, descriptor.getBundleName());
            return toBundle(descriptor);
        }).orElseGet(() -> {
            LOGGER.warn("For analysis [{}]], requested descriptor [{}] not found", analysisId, id);
            return null;
        });
    }

    private DescriptorBundle findMatchingDescriptor(File dir, Long analysisId, List<Descriptor> available) {
        return getRuntime(dir).flatMap(runtime -> {
            Map<Boolean, List<Map.Entry<Descriptor, String>>> results = available.stream().flatMap(descriptor ->
                    descriptor.getExecutionRuntimes().stream().<Map.Entry<Descriptor, String>>map(runtime1 ->
                            new AbstractMap.SimpleEntry<>(descriptor, runtime1.getMismatches(runtime))
                    )
            ).collect(Collectors.partitioningBy(entry -> entry.getValue() == null));
            List<Map.Entry<Descriptor, String>> matched = results.get(true);
            if (matched.isEmpty()) {
                List<Map.Entry<Descriptor, String>> notMatched = results.get(false);
                LOGGER.warn("For analysis [{}] of total [{}] descriptors none matched to requested. Fall back to default", analysisId, available.size());

                notMatched.forEach(mismatch -> {
                    LOGGER.info("Descriptor [{}] not matched: {}", mismatch.getKey().getLabel(), mismatch.getValue());
                });
                return Optional.empty();
            } else {
                return matched.stream().reduce((a, b) -> {
                    LOGGER.info("For analysis [{}] multiple descriptors matched. Discarded extra [{}]", analysisId, b.getKey().getBundleName());
                    return a;
                }).map(match -> {
                    LOGGER.info("For analysis [{}] using matched descriptor [{}]", analysisId, match.getKey().getBundleName());
                    return match;
                });
            }
        }).map(Map.Entry::getKey).map(this::toBundle).orElse(defaultDescriptorBundle);
    }

    private DescriptorBundle toBundle(Descriptor descriptor) {
        String bundleName = descriptor.getBundleName();
        String path = archiveFolder.map(folder -> folder.resolve(bundleName).toFile().getPath()).orElse(bundleName);
        File bundle = new File(path);
        if (bundle.exists() && bundle.isFile()) {
            return new DescriptorBundle(path, descriptor);
        } else {
            LOGGER.info("Descriptor [{}] matched, but bundle [{}] not found", descriptor.getLabel(), path);
            return null;
        }
    }

    private Optional<ExecutionRuntime> getRuntime(File dir) {
        try (Stream<Path> paths = Files.walk(dir.toPath())) {
            return paths.map(Path::toFile).filter(file -> !file.isDirectory()).flatMap(file -> {
                String name = file.getName();
                try (FileInputStream is = new FileInputStream(file)) {
                    return name.endsWith(".zip") ? extractRuntimes(name, is) : getRuntime(file.getPath(), is);
                } catch (IOException e) {
                    throw new RuntimeException("Error reading file [" + name + "]", e);
                }
            }).reduce((a, b) -> {
                LOGGER.error("Multiple descriptors found: [{}] and [{}], aborting", a, b);
                throw new RuntimeException("Multiple descriptors found: [" + a + "] and [" + b + "]");
            });
        } catch (IOException e) {
            LOGGER.error("Error traversing directory [{}]:", dir.getPath(), e);
            throw new RuntimeException("Error traversing directory [" + dir.getPath() + "]", e);
        }
    }

    private Stream<ExecutionRuntime> extractRuntimes(String zipName, FileInputStream fis) throws IOException {
        Stream.Builder<ExecutionRuntime> sb = Stream.builder();
        try (ZipInputStream zis = new ZipInputStream(fis)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                ZipInputSubStream ziss = new ZipInputSubStream(zis);
                getRuntime(zipName + ":/" + entry.getName(), ziss).forEach(sb);
            }
        }
        return sb.build();
    }

    public static Stream<ExecutionRuntime> getRuntime(String name, InputStream is) {
        // findFirst() here is important to ensure not attempting to read again from the same stream
        return stream(
                STRATEGIES.stream().map(strategy ->
                        strategy.apply(name, is)
                ).filter(Objects::nonNull).findFirst().flatMap(o -> o)
        ).peek(runtime -> {
            LOGGER.info("Detected runtime descriptor [{}] in [{}]", runtime, name);
        });
    }

    private static <T> Stream<T> stream(Optional<T> optional) {
        return optional.map(Stream::of).orElseGet(Stream::of);
    }

}
