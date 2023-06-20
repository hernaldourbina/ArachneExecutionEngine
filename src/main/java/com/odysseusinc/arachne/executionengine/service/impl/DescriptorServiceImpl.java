package com.odysseusinc.arachne.executionengine.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.odysseusinc.arachne.execution_engine_common.util.CommonFileUtils;
import com.odysseusinc.arachne.executionengine.config.runtimeservice.RIsolatedRuntimeProperties;
import com.odysseusinc.arachne.executionengine.model.descriptor.DefaultDescriptor;
import com.odysseusinc.arachne.executionengine.model.descriptor.Descriptor;
import com.odysseusinc.arachne.executionengine.model.descriptor.DescriptorBundle;
import com.odysseusinc.arachne.executionengine.model.descriptor.ExecutionRuntime;
import com.odysseusinc.arachne.executionengine.model.descriptor.ExecutionRuntimeHelper;
import com.odysseusinc.arachne.executionengine.service.DescriptorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class DescriptorServiceImpl implements DescriptorService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DescriptorServiceImpl.class);

    private static final String DESCRIPTOR_PREFIX = "descriptor";

    private RIsolatedRuntimeProperties rIsolatedRuntimeProps;

    private DescriptorBundle defaultDescriptorBundle;

    private ObjectMapper mapper = new ObjectMapper();

    public DescriptorServiceImpl(RIsolatedRuntimeProperties rIsolatedRuntimeProps) {
        this.rIsolatedRuntimeProps = rIsolatedRuntimeProps;
        this.defaultDescriptorBundle = new DescriptorBundle(rIsolatedRuntimeProps.getArchive(), new DefaultDescriptor());
    }

    @Override
    public List<Descriptor> getDescriptors() {
        List<Descriptor> descriptors = new ArrayList<>();
        if (rIsolatedRuntimeProps.getArchiveFolder() != null) {
            File archiveFolder = new File(rIsolatedRuntimeProps.getArchiveFolder());
            if (!archiveFolder.isDirectory()) {
                throw new RuntimeException(rIsolatedRuntimeProps.getArchiveFolder() + " is empty or cannot be accessed");
            }
            File[] files = archiveFolder.listFiles();
            if (files == null) {
                throw new RuntimeException(rIsolatedRuntimeProps.getArchiveFolder() + " is empty or cannot be accessed");
            }
            for (File file : archiveFolder.listFiles()) {
                if (file.getName().startsWith(DESCRIPTOR_PREFIX)) {
                    try {
                        if (file.isFile()) {
                            InputStream is = new FileInputStream(file);
                            Descriptor descriptor = mapper.readValue(is, Descriptor.class);
                            descriptors.add(descriptor);
                        }
                    } catch (Exception e) {
                        throw new RuntimeException("Error getting descriptor from file: " + file.getName(), e);
                    }
                }
            }
        }
        return descriptors;
    }

    @Override
    public DescriptorBundle getDescriptorBundle(File file, Long analysisId, String requestedDescriptorId) {
        Optional<DescriptorBundle> descriptorBundleOpt = Optional.empty();
        if (rIsolatedRuntimeProps.getArchiveFolder() != null) {
            List<Descriptor> availableDescriptors = getDescriptors();
            if (!availableDescriptors.isEmpty()) {
                if (!StringUtils.isEmpty(requestedDescriptorId)) {
                    descriptorBundleOpt = getRequestedDescriptorBundle(requestedDescriptorId, availableDescriptors);
                }
                if (!descriptorBundleOpt.isPresent()) {
                    descriptorBundleOpt = getDescriptorBundle(file, availableDescriptors);
                }
            }
        }
        return descriptorBundleOpt
                .map(descriptorBundle -> {
                    Descriptor descriptor = descriptorBundle.getDescriptor();
                    LOGGER.info("Found descriptor '{}' for analysis '{}' with bundle name '{}' and path '{}'",
                            descriptor.getLabel(), analysisId, descriptor.getBundleName(), descriptorBundle.getPath());
                    return descriptorBundle;
                })
                .orElseGet(() -> {
                    LOGGER.info("No descriptor found for analysis '{}'. Using default", analysisId);
                    return defaultDescriptorBundle;
                });
    }

    private Optional<DescriptorBundle> getRequestedDescriptorBundle(String requestedDescriptorId,
                                                                    List<Descriptor> availableDescriptors) {
        return availableDescriptors.stream()
                .filter(descriptor -> descriptor.getId().equals(requestedDescriptorId))
                .map(descriptor -> {
                    String descriptorPath = getDescriptorPath(descriptor);
                    return new DescriptorBundle(descriptorPath, descriptor);
                })
                .findFirst();
    }

    private Optional<DescriptorBundle> getDescriptorBundle(File file, List<Descriptor> availableDescriptors) {
        File temporaryDir = com.google.common.io.Files.createTempDir();
        try {
            extractFiles(file, temporaryDir);
            List<File> files = Arrays.asList(temporaryDir.listFiles());
            List<ExecutionRuntime> executionRuntimes = ExecutionRuntimeHelper.getRuntimes(files);

            return availableDescriptors.stream()
                    .filter(availableDescriptor ->
                            compareExecutionRuntimes(availableDescriptor.getExecutionRuntimes(), executionRuntimes))
                    .findFirst()
                    .map(descriptor -> {
                        String descriptorPath = getDescriptorPath(descriptor);
                        File bundle = new File(descriptorPath);
                        if (bundle.exists() && bundle.isFile()) {
                            return new DescriptorBundle(descriptorPath, descriptor);
                        }
                        return null;
                    });
        } finally {
            temporaryDir.delete();
        }
    }

    private boolean compareExecutionRuntimes(List<ExecutionRuntime> availableExecutionRuntimes,
                                             List<ExecutionRuntime> executionRuntimes) {
        boolean result = executionRuntimes.stream()
                .map(otherRuntime -> availableExecutionRuntimes.stream()
                        .filter(executionRuntime -> executionRuntime.getType().equals(otherRuntime.getType()))
                        .anyMatch(executionRuntime -> executionRuntime.matches(otherRuntime))
                )
                .reduce(true, (a, b) -> a && b);
        return result;
    }

    private String getDescriptorPath(Descriptor descriptor) {
        return rIsolatedRuntimeProps.getArchiveFolder() + descriptor.getBundleName();
    }

    private void extractFiles(File parentFolder, File tempFolder) {
        for (File file : parentFolder.listFiles()) {
            try {
                CommonFileUtils.unzipFiles(file, tempFolder);
            } catch (Exception e) {
                // ignore
            }
        }
    }
}
